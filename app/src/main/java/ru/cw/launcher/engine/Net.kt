package ru.cw.launcher.engine

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class Net {
    private val deadHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val dohAddress = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dohNoted = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val openPipes = java.util.Collections.synchronizedSet(mutableSetOf<SSLSocket>())
    private val pipeLocal = ThreadLocal<Pipe?>()

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
                    val previous = last
                    last = if (previous == null || !dnsFailed(e) || dnsFailed(previous)) e else previous
                    if (dnsFailed(e)) {
                        val ip = resolveDoh(hostOf(candidate))
                        if (!ip.isNullOrBlank()) {
                            try {
                                noteDns(candidate, ip)
                                val body = textDirect(candidate, ip)
                                if (body.isNotBlank()) return body
                            } catch (e2: Exception) {
                                val previous = last
                                last = if (previous == null || !dnsFailed(e2) || dnsFailed(previous)) e2 else previous
                            }
                        }
                        markDead(candidate)
                        break
                    }
                    if (connectFailed(e)) break
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
            if (hostOf(candidate) in deadHosts) {
                val known = dohAddress[hostOf(candidate)] ?: resolveDoh(hostOf(candidate))
                if (!known.isNullOrBlank()) {
                    try {
                        transferDirect(candidate, known, dest, sha1, size, startOf(dest), cancel, onProgress)
                        return
                    } catch (e: Exception) {
                        if (cancel?.get() == true) throw e
                        last = e
                        dropPipe()
                    }
                }
            }
            var attempt = 0
            while (attempt < 4) {
                if (cancel?.get() == true) throw IOException("Отменено")
                try {
                    transfer(candidate, dest, sha1, size, cancel, onProgress)
                    return
                } catch (e: Exception) {
                    if (cancel?.get() == true) throw e
                    val previous = last
                    last = if (previous == null || !dnsFailed(e) || dnsFailed(previous)) e else previous
                    if (dnsFailed(e)) {
                        markDead(candidate)
                        val ip = resolveDoh(hostOf(candidate))
                        if (!ip.isNullOrBlank()) {
                            try {
                                noteDns(candidate, ip)
                                transferDirect(candidate, ip, dest, sha1, size, startOf(dest), cancel, onProgress)
                                return
                            } catch (e2: Exception) {
                                if (cancel?.get() == true) throw e2
                                val previous = last
                                last = if (previous == null || !dnsFailed(e2) || dnsFailed(previous)) e2 else previous
                            }
                        }
                        markDead(candidate)
                        break
                    }
                    if (connectFailed(e) || isReset(e)) {
                        attempt++
                        if (attempt < 2) {
                            Thread.sleep(400L * attempt)
                            continue
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
        commitDownload(part, dest, sha1, size)
    }

    private fun commitDownload(part: File, dest: File, sha1: String?, size: Long) {
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
        val alts = alternatives(rewriteHost(url))
        val out = LinkedHashSet<String>()
        for (candidate in alts) {
            if (hostOf(candidate) !in deadHosts) out.add(candidate)
        }
        if (out.isEmpty()) {
            alts.filter { hostOf(it) != "resources.download.minecraft.net" }.forEach { out.add(it) }
        }
        if (out.isEmpty() && alts.isNotEmpty()) out.add(alts.first())
        return out.toList()
    }

    private fun alternatives(url: String): List<String> {
        val out = LinkedHashSet<String>()
        val assets = assetObjectPath(url)
        if (assets != null) {
            out.add("https://resources.download.minecraft.net/$assets")
            out.add("https://bmclapi2.bangbang93.com/assets/$assets")
            return out.toList()
        }
        val host = hostOf(url)
        val officialLast = host == "resources.download.minecraft.net" || host in deadHosts
        if (!officialLast) out.add(url)
        mirrorOf(url)?.let { out.add(it) }
        centralOf(url)?.let { out.add(it) }
        mcimOf(url)?.let { out.add(it) }
        if (officialLast) out.add(url)
        if (out.isEmpty()) out.add(url)
        return out.toList()
    }

    private fun assetObjectPath(url: String): String? {
        val markers = listOf(
            "://resources.download.minecraft.net/",
            "://resources.fastmcmirror.org/",
            "://bmclapi2.bangbang93.com/assets/",
            "://resources.download.mcimirror.top/"
        )
        for (marker in markers) {
            val at = url.indexOf(marker)
            if (at < 0) continue
            val path = url.substring(at + marker.length)
            if (path.length > 5 && path[2] == '/') return path
        }
        return null
    }

    private fun markDead(url: String) {
        val host = hostOf(url)
        if (host.isNotBlank() && deadHosts.add(host)) {
            CwLog.warn("Сервер $host недоступен, дальше используется зеркало")
        }
    }

    private fun rewriteHost(url: String): String {
        val host = hostOf(url)
        if (host.contains("sakura.sld.tw") || host == "bmclapi.bangbang93.com") {
            return url.replace("://$host", "://bmclapi2.bangbang93.com")
        }
        return url
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
            val connection = try {
                handshake(current, "GET", headers)
            } catch (e: Exception) {
                throw e
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Пустая переадресация ${shorten(current)}")
            val next = absolute(current, location)
            current = rewriteHost(next)
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

    private fun dnsFailed(e: Exception): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is java.net.UnknownHostException) return true
            val text = (current.message ?: "").lowercase(Locale.ROOT)
            if (text.contains("unable to resolve") || text.contains("no address associated")
                || text.contains("unknownhost") || text.contains("nodename nor servname")
            ) return true
            current = current.cause
        }
        return false
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
        val connection = handshake(url, method, headers) { outgoing ->
            if (json != null) {
                outgoing.doOutput = true
                outgoing.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outgoing.setRequestProperty("Accept", "application/json")
                outgoing.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
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

    private fun handshake(
        url: String,
        method: String,
        headers: Map<String, String>,
        prepare: (HttpURLConnection) -> Unit = {}
    ): HttpURLConnection {
        fun boot(connection: HttpURLConnection): HttpURLConnection {
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            prepare(connection)
            connection.responseCode
            return connection
        }
        val first = plain(url, method)
        return try {
            boot(first)
        } catch (e: Exception) {
            first.disconnect()
            throw e
        }
    }

    private fun plain(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        prepare(connection, method)
        return connection
    }

    private fun noteDns(url: String, ip: String) {
        val host = hostOf(url)
        if (host.isNotBlank() && dohNoted.add(host)) CwLog.info("Адрес $host получен через DNS: $ip")
    }

    private fun startOf(dest: File): Long {
        val part = File(dest.parentFile, dest.name + ".part")
        return if (part.isFile) part.length() else 0L
    }

    private fun textDirect(url: String, ip: String): String {
        AppPaths.tmp.mkdirs()
        val tmp = File.createTempFile("cw-dns", ".txt", AppPaths.tmp)
        return try {
            transferDirect(url, ip, tmp, null, 0, 0, null, null)
            tmp.readText()
        } finally {
            tmp.delete()
        }
    }

    private fun transferDirect(
        url: String,
        ip: String,
        dest: File,
        sha1: String?,
        size: Long,
        resume: Long,
        cancel: AtomicBoolean?,
        onProgress: ((Long, Long) -> Unit)?
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var start = resume
        if (size > 0 && start >= size) start = 0L
        var currentUrl = url
        var currentIp = ip
        repeat(5) {
            if (cancel?.get() == true) throw IOException("Отменено")
            val src = URL(currentUrl)
            val path = src.file.ifBlank { "/" }
            val pipe = borrowPipe(src.host, currentIp)
            var reuse = false
            try {
                val range = if (start > 0) "Range: bytes=$start-\r\n" else ""
                val request = "GET $path HTTP/1.1\r\nHost: ${src.host}\r\nUser-Agent: CWLauncher/${CwLog.VERSION}\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: keep-alive\r\n$range\r\n"
                pipe.socket.outputStream.write(request.toByteArray(Charsets.ISO_8859_1))
                pipe.socket.outputStream.flush()
                val input = pipe.input
                val status = socketLine(input)
                val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Плохой ответ ${shorten(currentUrl)}")
                val responseHeaders = linkedMapOf<String, String>()
                while (true) {
                    val line = socketLine(input)
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) responseHeaders[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
                }
                if (code in 300..399) {
                    discardBody(input, responseHeaders)
                    val location = responseHeaders["location"] ?: throw IOException("Пустая переадресация ${shorten(currentUrl)}")
                    currentUrl = absolute(currentUrl, location)
                    val nextHost = hostOf(currentUrl)
                    if (nextHost != src.host) dropPipe()
                    currentIp = dohAddress[nextHost] ?: resolveDoh(nextHost) ?: throw IOException("Не удалось открыть ${shorten(currentUrl)}")
                    reuse = nextHost == src.host && responseHeaders["connection"]?.contains("close", true) != true
                    return@repeat
                }
                if (code !in 200..299) {
                    discardBody(input, responseHeaders)
                    throw IOException("HTTP $code ${shorten(currentUrl)}")
                }
                val append = code == 206 && start > 0
                if (code == 200) start = 0L
                val length = responseHeaders["content-length"]?.toLongOrNull() ?: -1L
                val total = if (size > 0) size else if (length >= 0) start + length else 0L
                java.io.FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var done = if (append) start else 0L
                    var left = if (length >= 0) length else Long.MAX_VALUE
                    while (left > 0) {
                        if (cancel?.get() == true) throw IOException("Отменено")
                        val want = minOf(buf.size.toLong(), left).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        left -= n
                        if (total > 0) onProgress?.invoke(done, total)
                    }
                }
                reuse = length >= 0 && responseHeaders["connection"]?.contains("close", true) != true
                commitDownload(part, dest, sha1, size)
                return
            } catch (e: Exception) {
                dropPipe()
                reuse = false
                throw e
            } finally {
                if (!reuse) dropPipe()
            }
        }
        throw IOException("Слишком много переадресаций ${shorten(url)}")
    }

    private class Pipe(val host: String, val ip: String, val socket: SSLSocket, val input: java.io.BufferedInputStream)

    private fun borrowPipe(host: String, ip: String): Pipe {
        val current = pipeLocal.get()
        if (current != null && current.host == host && current.ip == ip && current.socket.isConnected && !current.socket.isClosed) {
            return current
        }
        if (current != null) dropPipe()
        val socket = openTls(host, ip)
        openPipes.add(socket)
        val created = Pipe(host, ip, socket, java.io.BufferedInputStream(socket.inputStream, 256 * 1024))
        pipeLocal.set(created)
        return created
    }

    private fun dropPipe() {
        val current = pipeLocal.get() ?: return
        pipeLocal.remove()
        openPipes.remove(current.socket)
        try {
            current.socket.close()
        } catch (_: Exception) {
        }
    }

    private fun discardBody(input: java.io.InputStream, headers: Map<String, String>) {
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        if (length < 0) {
            dropPipe()
            return
        }
        var left = length
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun openTls(host: String, ip: String): SSLSocket {
        val tcp = Socket()
        tcp.connect(java.net.InetSocketAddress(ip, 443), 12_000)
        tcp.soTimeout = 120_000
        val ssl = SSLContextHolder.factory.createSocket(tcp, host, 443, true) as SSLSocket
        val params = ssl.sslParameters
        params.serverNames = listOf(SNIHostName(host))
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    private fun socketLine(input: java.io.InputStream): String {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun prepare(connection: HttpURLConnection, method: String) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 120_000
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "CWLauncher/${CwLog.VERSION}")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")
    }

    fun releasePipes() {
        pipeLocal.remove()
        val copy = openPipes.toList()
        openPipes.clear()
        copy.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun looksLikeIp(host: String): Boolean {
        return host.all { it.isDigit() || it == '.' }
    }

    private fun resolveDoh(host: String): String? {
        dohAddress[host]?.let { return it }
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$host&type=A",
            "https://8.8.8.8/resolve?name=$host&type=A"
        )
        for (endpoint in endpoints) {
            try {
                val body = rawText(endpoint)
                val answers = JSONObject(body).optJSONArray("Answer") ?: continue
                for (i in 0 until answers.length()) {
                    val item = answers.optJSONObject(i) ?: continue
                    if (item.optInt("type") != 1) continue
                    val ip = item.optString("data").trim().trimEnd('.')
                    if (ip.isBlank() || !looksLikeIp(ip)) continue
                    dohAddress[host] = ip
                    return ip
                }
            } catch (e: Exception) {
                CwLog.warn("DNS $host: ${e.message}")
            }
        }
        return null
    }

    private fun rawText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.setRequestProperty("User-Agent", "CWLauncher/${CwLog.VERSION}")
        return connection.inputStream.use { it.bufferedReader().readText() }.also { connection.disconnect() }
    }

    private object SSLContextHolder {
        val factory: SSLSocketFactory = javax.net.ssl.SSLContext.getDefault().socketFactory
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
