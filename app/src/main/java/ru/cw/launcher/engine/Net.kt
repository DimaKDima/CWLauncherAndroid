package ru.cw.launcher.engine

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Net {
    private val deadHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun getStatus(url: String): Pair<Int, String> = request(url, "GET", null, emptyMap())

    fun getTextAuth(url: String, access: String): Pair<Int, String> =
        request(url, "GET", null, mapOf("Authorization" to "Bearer $access", "Accept" to "application/json"))

    fun getText(url: String): String {
        var last: Exception? = null
        for (candidate in routes(url)) {
            var attempt = 0
            while (attempt < 2) {
                try {
                    val (code, body) = request(candidate, "GET", null, emptyMap())
                    if (code in 200..299 && body.isNotBlank()) return body
                    throw IOException("HTTP $code ${shorten(candidate)}")
                } catch (e: Exception) {
                    last = e
                    if (connectFailed(e)) {
                        deadHosts.add(hostOf(candidate))
                        break
                    }
                    attempt++
                    if (attempt < 2) Thread.sleep(600)
                }
            }
        }
        val cached = cachedText(url)
        if (cached != null) {
            CwLog.warn("Сеть недоступна, взят сохранённый ответ: ${shorten(url)}")
            return cached
        }
        throw last ?: IOException("Не удалось связаться с сервером Minecraft")
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        return request(url, "POST", json, headers)
    }

    fun download(
        url: String,
        dest: File,
        sha1: String? = null,
        size: Long = 0,
        cancel: AtomicBoolean? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        dest.parentFile?.mkdirs()
        var last: Exception? = null
        for (candidate in routes(url)) {
            var attempt = 0
            while (attempt < 4) {
                if (cancel?.get() == true) throw IOException("Отменено")
                try {
                    transfer(candidate, dest, sha1, size, cancel, onProgress)
                    return
                } catch (e: Exception) {
                    if (cancel?.get() == true) throw e
                    last = e
                    val reset = isReset(e)
                    if (connectFailed(e) && !reset) {
                        val host = hostOf(candidate)
                        if (deadHosts.add(host)) {
                            CwLog.warn("Сервер $host недоступен, дальше используется зеркало")
                        }
                        break
                    }
                    CwLog.warn("Загрузка ${shorten(candidate)}: ${e.message}")
                    attempt++
                    if (attempt < 4) Thread.sleep(600L * attempt)
                }
            }
        }
        throw last ?: IOException("Не удалось скачать файл")
    }

    private fun transfer(
        url: String,
        dest: File,
        sha1: String?,
        size: Long,
        cancel: AtomicBoolean?,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        val part = File(dest.parentFile, dest.name + ".part")
        var start = if (part.isFile) part.length() else 0L
        if (size > 0 && start >= size) start = 0L
        val headers = if (start > 0) mapOf("Range" to "bytes=$start-") else emptyMap()
        val connection = openGet(url, headers)
        try {
            val code = connection.responseCode
            val append = code == 206 && start > 0
            if (code == 200) start = 0L
            if (code !in 200..299) throw IOException("HTTP $code ${shorten(url)}")
            val total = if (size > 0) size else start + connection.contentLengthLong.coerceAtLeast(0)
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = if (append) start else 0L
                    while (true) {
                        if (cancel?.get() == true) throw IOException("Отменено")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (size > 0 && part.length() != size) {
            part.delete()
            throw IOException("Размер не совпал: ${dest.name}")
        }
        if (!sha1.isNullOrBlank() && !sha1(part).equals(sha1, ignoreCase = true)) {
            part.delete()
            throw IOException("Контрольная сумма не совпала: ${dest.name}")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    private fun routes(url: String): List<String> {
        val rewritten = rewriteHost(url)
        val out = LinkedHashSet<String>()
        if (hostOf(rewritten) !in deadHosts) out.add(rewritten)
        mirrorOf(rewritten)?.let { if (hostOf(it) !in deadHosts) out.add(it) }
        centralOf(rewritten)?.let { if (hostOf(it) !in deadHosts) out.add(it) }
        mcimOf(rewritten)?.let { if (hostOf(it) !in deadHosts) out.add(it) }
        if (out.isEmpty()) out.add(rewritten)
        return out.toList()
    }

    private fun rewriteHost(url: String): String {
        val host = hostOf(url)
        if (host != "bmclapi1.sakura.sld.tw" && host != "bmclapi.bangbang93.com") return url
        return url.replace("://$host", "://bmclapi2.bangbang93.com")
    }

    private fun mirrorOf(url: String): String? {
        val mapped = when {
            url.contains("://piston-meta.mojang.com") -> url.replace("://piston-meta.mojang.com", "://bmclapi2.bangbang93.com")
            url.contains("://piston-data.mojang.com") -> url.replace("://piston-data.mojang.com", "://bmclapi2.bangbang93.com")
            url.contains("://launchermeta.mojang.com") -> url.replace("://launchermeta.mojang.com", "://bmclapi2.bangbang93.com")
            url.contains("://launcher.mojang.com") -> url.replace("://launcher.mojang.com", "://bmclapi2.bangbang93.com")
            url.contains("://libraries.minecraft.net/") -> url.replace("://libraries.minecraft.net/", "://bmclapi2.bangbang93.com/maven/")
            url.contains("://resources.download.minecraft.net/") -> url.replace("://resources.download.minecraft.net/", "://bmclapi2.bangbang93.com/assets/")
            url.contains("://meta.fabricmc.net/") -> url.replace("://meta.fabricmc.net/", "://bmclapi2.bangbang93.com/fabric-meta/")
            url.contains("://maven.fabricmc.net/") -> url.replace("://maven.fabricmc.net/", "://bmclapi2.bangbang93.com/maven/")
            url.contains("://repo1.maven.org/maven2/") -> url.replace("://repo1.maven.org/maven2/", "://bmclapi2.bangbang93.com/maven/")
            else -> url
        }
        return mapped.takeIf { it != url }
    }

    private fun centralOf(url: String): String? {
        val marker = "://maven.fabricmc.net/"
        val at = url.indexOf(marker)
        if (at < 0) return null
        val path = url.substring(at + marker.length)
        if (!path.startsWith("org/ow2/asm/")) return null
        return "https://repo1.maven.org/maven2/$path"
    }

    private fun mcimOf(url: String): String? {
        val mapped = when {
            url.contains("://piston-meta.mojang.com") -> url.replace("://piston-meta.mojang.com", "://piston-meta.mcimirror.top")
            url.contains("://piston-data.mojang.com") -> url.replace("://piston-data.mojang.com", "://piston-data.mcimirror.top")
            url.contains("://launchermeta.mojang.com") -> url.replace("://launchermeta.mojang.com", "://launchermeta.mcimirror.top")
            url.contains("://launcher.mojang.com") -> url.replace("://launcher.mojang.com", "://launcher.mcimirror.top")
            url.contains("://libraries.minecraft.net/") -> url.replace("://libraries.minecraft.net/", "://libraries.mcimirror.top/")
            url.contains("://resources.download.minecraft.net/") -> url.replace("://resources.download.minecraft.net/", "://resources.download.mcimirror.top/")
            url.contains("://meta.fabricmc.net/") -> url.replace("://meta.fabricmc.net/", "://meta.fabricmc.net/")
            url.contains("://bmclapi2.bangbang93.com/assets/") -> url.replace("://bmclapi2.bangbang93.com/assets/", "://resources.download.mcimirror.top/")
            url.contains("://bmclapi2.bangbang93.com/maven/") -> url.replace("://bmclapi2.bangbang93.com/maven/", "://libraries.mcimirror.top/")
            url.contains("://bmclapi2.bangbang93.com/fabric-meta/") -> url.replace("://bmclapi2.bangbang93.com/fabric-meta/", "://meta.fabricmc.net/")
            url.contains("://bmclapi2.bangbang93.com/v1/objects/") -> url.replace("://bmclapi2.bangbang93.com", "://piston-data.mcimirror.top")
            url.contains("://bmclapi2.bangbang93.com/") -> url.replace("://bmclapi2.bangbang93.com", "://piston-meta.mcimirror.top")
            else -> url
        }
        return mapped.takeIf { it != url }
    }

    private fun openGet(url: String, headers: Map<String, String>): HttpURLConnection {
        var current = rewriteHost(url)
        val seen = HashSet<String>()
        repeat(5) {
            if (!seen.add(current)) return@repeat
            val connection = connect(current, "GET")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Пустая переадресация ${shorten(current)}")
            val next = absolute(current, location)
            val rewritten = rewriteHost(next)
            val jumped = if (hostOf(rewritten) == hostOf(current) || hostOf(next) == "bmclapi1.sakura.sld.tw") {
                mcimOf(current) ?: mcimOf(rewritten) ?: rewritten
            } else {
                rewritten
            }
            CwLog.warn("Переадресация ${shorten(current)} → ${shorten(jumped)}")
            current = jumped
        }
        throw IOException("Слишком много переадресаций ${shorten(url)}")
    }

    private fun absolute(base: String, location: String): String {
        return if (location.startsWith("http://") || location.startsWith("https://")) location
        else URL(URL(base), location).toString()
    }

    private fun hostOf(url: String): String = try {
        URL(url).host.lowercase(Locale.ROOT)
    } catch (_: Exception) {
        ""
    }

    private fun isReset(e: Exception): Boolean {
        val text = (e.message ?: "").lowercase(Locale.ROOT)
        return text.contains("connection reset") || text.contains("connection abort") || text.contains("broken pipe")
    }

    private fun connectFailed(e: Exception): Boolean {
        val text = (e.message ?: "").lowercase(Locale.ROOT)
        return text.contains("failed to connect") || text.contains("timed out") || text.contains("timeout")
            || text.contains("unreachable") || text.contains("unable to resolve") || text.contains("no address associated")
            || text.contains("connection reset") || text.contains("connection refused")
            || text.contains("software caused connection abort") || text.contains("sakura.sld.tw")
    }

    private fun cachedText(url: String): String? {
        if (!url.contains("version_manifest")) return null
        val file = File(AppPaths.cache, "mojang-manifest.json")
        return if (file.isFile && file.length() > 100) file.readText() else null
    }

    private fun request(url: String, method: String, json: String?, headers: Map<String, String>): Pair<Int, String> {
        if (json == null && method == "GET") {
            val connection = openGet(url, headers)
            return try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to body
            } finally {
                connection.disconnect()
            }
        }
        val connection = connect(url, method)
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        if (json != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 120_000
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "CWLauncher/${CwLog.VERSION}")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "close")
        return connection
    }
}

object Drive {
    private val idInUrl = Regex("(?:/d/|id=)([-_A-Za-z0-9]{10,60})")

    fun fileId(link: String?): String? {
        val s = link?.trim().orEmpty()
        if (s.isEmpty()) return null
        idInUrl.find(s)?.let { return it.groupValues[1] }
        if (s.matches(Regex("[-_A-Za-z0-9]{10,60}"))) return s
        return null
    }

    fun downloadUrls(id: String): List<String> = listOf(
        "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
        "https://drive.google.com/uc?export=download&id=$id&confirm=t",
        "https://drive.google.com/uc?export=download&id=$id"
    )

    fun download(
        net: Net,
        link: String,
        dest: File,
        requireZip: Boolean,
        cancel: AtomicBoolean? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val id = fileId(link) ?: throw IOException("Не удалось определить идентификатор файла Google Drive")
        dest.parentFile?.mkdirs()
        var last: Exception? = null
        for (url in downloadUrls(id)) {
            val tmp = File(dest.parentFile, dest.name + ".drv")
            try {
                net.download(url, tmp, null, 0, cancel, onProgress)
                val head = tmp.inputStream().use { input ->
                    val buf = ByteArray(256)
                    val n = input.read(buf)
                    if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8).lowercase(Locale.ROOT)
                }
                if (head.contains("<html") || head.contains("<!doctype") || head.contains("virus-scan")) {
                    tmp.delete()
                    throw IOException("Google Drive вернул HTML-страницу вместо файла")
                }
                if (requireZip && !looksLikeZip(tmp)) {
                    tmp.delete()
                    throw IOException("Скачанный файл не является ZIP")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                CwLog.info("Google Drive: файл получен (${dest.length()} байт)")
                return
            } catch (e: Exception) {
                tmp.delete()
                last = e
                CwLog.warn("Google Drive: ${e.message}")
                if (cancel?.get() == true) throw e
            }
        }
        throw last ?: IOException("Не удалось скачать файл из Google Drive")
    }

    fun readText(net: Net, link: String): String {
        val tmp = File.createTempFile("cw-drive", ".txt", AppPaths.tmp)
        try {
            download(net, link, tmp, false, null, null)
            val text = tmp.readText()
            val low = text.lowercase(Locale.ROOT)
            if (low.contains("<html") || low.contains("<!doctype")) {
                throw IOException("Google Drive вернул страницу вместо текста")
            }
            return text
        } finally {
            tmp.delete()
        }
    }
}

fun applyPatch(net: Net, cfg: CwSettings) {
    val text = try {
        if (CwSettings.PATCH_URL.contains("drive.google.com")) Drive.readText(net, CwSettings.PATCH_URL)
        else net.getText(CwSettings.PATCH_URL)
    } catch (e: Exception) {
        CwLog.warn("patchCWL.txt: ${e.message}")
        return
    }
    val links = linkedMapOf<String, String>()
    val body = if (text.startsWith("\uFEFF")) text.substring(1) else text
    for (raw in body.split(Regex("\r?\n"))) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val bar = line.indexOf('|')
        if (bar <= 0 || bar >= line.length - 1) continue
        val name = line.substring(0, bar).trim()
        val url = line.substring(bar + 1).trim()
        if (name.isEmpty() || !url.startsWith("https://")) continue
        links[name.lowercase(Locale.ROOT)] = url
        CwLog.info("patchCWL.txt: $name")
    }
    fun url(vararg names: String): String? {
        for (name in names) {
            links[name.lowercase(Locale.ROOT)]?.let { return it }
        }
        return null
    }
    url("cwVersion", "cwVersion.txt")?.let { cfg.cwVersionUrl = it }
    url("News.txt")?.let { cfg.newsUrl = it }
    url("UpdateInfo.zip")?.let { cfg.updateInfoUrl = it }
    url("CWLauncher.zip")?.let { cfg.launcherDownloadUrl = it }
    url("modsCWL.zip")?.let { cfg.modpackUrl = it }
    cfg.save()
}

fun readRemoteText(net: Net, url: String): String {
    return if (url.contains("drive.google.com")) Drive.readText(net, url) else net.getText(url)
}

fun shorten(s: String, n: Int = 80): String = if (s.length <= n) s else s.take(n - 1) + "…"
