package ru.cw.launcher.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Lib(
    val name: String,
    val path: String,
    val url: String,
    val sha1: String?,
    val size: Long,
    val download: Boolean,
    val nativeClassifier: String?
) {
    fun isNative(): Boolean = nativeClassifier != null
}

data class VersionProfile(
    val id: String,
    val inheritsFrom: String?,
    val mainClass: String,
    val assets: String,
    val assetIndexUrl: String?,
    val assetIndexSha1: String?,
    val assetIndexSize: Long,
    val clientUrl: String?,
    val clientSha1: String?,
    val clientSize: Long,
    val libraries: List<Lib>,
    val gameArgs: List<String>,
    val jvmArgs: List<String>
)

data class LoadProgress(
    val title: String,
    val fraction: Float,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val extra: String = ""
)

class Installer(
    private val net: Net,
    private val cfg: CwSettings,
    private val cancel: AtomicBoolean,
    private val onProgress: (LoadProgress) -> Unit
) {
    private val threads: Int get() = if (cfg.internetLeader) 4 else 3
    private val meter = SpeedMeter()
    private val lastUi = AtomicLong(0)
    @Volatile private var lastDownloadError = ""

    fun present(version: String): Boolean {
        if (!AppPaths.versionJson(version, version).isFile) return false
        if (!clientJar(version).isFile) return false
        return try {
            val profile = resolved(version)
            File(AppPaths.assets(version), "indexes/${profile.assets}.json").isFile
        } catch (_: Exception) {
            false
        }
    }

    fun versionReady(version: String): Boolean {
        return try {
            if (!AppPaths.versionJson(version, version).isFile) return false
            if (!clientJar(version).isFile) return false
            val v = resolved(version)
            if (v.mainClass.isBlank()) return false
            for (lib in v.libraries) {
                if (lib.download && !File(AppPaths.libs(version), lib.path).isFile) return false
            }
            if (!File(AppPaths.assets(version), "indexes/${v.assets}.json").isFile) return false
            missingAssets(version, v) == 0
        } catch (_: Exception) {
            false
        }
    }

    fun clientJar(version: String, depth: Int = 0): File {
        val own = AppPaths.versionJar(version, version)
        if (own.isFile || depth > 4) return own
        val json = AppPaths.versionJson(version, version)
        if (!json.isFile) return own
        val parent = JSONObject(json.readText()).optString("inheritsFrom", "")
        if (parent.isNotBlank() && parent != version) return clientJar(parent, depth + 1)
        return own
    }

    fun load(version: String, id: String): VersionProfile = parse(JSONObject(AppPaths.versionJson(version, id).readText()))

    fun resolved(version: String, id: String = version): VersionProfile {
        val child = load(version, id)
        val parentId = child.inheritsFrom
        if (parentId.isNullOrBlank() || parentId == id) return child
        val parent = resolved(version, parentId)
        val libs = child.libraries.toMutableList()
        for (p in parent.libraries) {
            if (libs.none { it.name == p.name && it.nativeClassifier == p.nativeClassifier }) libs.add(p)
        }
        return child.copy(
            mainClass = child.mainClass.ifBlank { parent.mainClass },
            assets = if (child.assetIndexUrl != null) child.assets else parent.assets,
            assetIndexUrl = child.assetIndexUrl ?: parent.assetIndexUrl,
            assetIndexSha1 = child.assetIndexSha1 ?: parent.assetIndexSha1,
            assetIndexSize = if (child.assetIndexSize > 0) child.assetIndexSize else parent.assetIndexSize,
            clientUrl = child.clientUrl ?: parent.clientUrl,
            clientSha1 = child.clientSha1 ?: parent.clientSha1,
            clientSize = if (child.clientSize > 0) child.clientSize else parent.clientSize,
            libraries = libs,
            gameArgs = child.gameArgs.ifEmpty { parent.gameArgs },
            jvmArgs = mergeArgs(parent.jvmArgs, child.jvmArgs)
        )
    }

    fun missingAssets(version: String, profile: VersionProfile? = null): Int {
        val v = profile ?: resolved(version)
        val index = File(AppPaths.assets(version), "indexes/${v.assets}.json")
        if (!index.isFile) return 1
        val objects = JSONObject(index.readText()).optJSONObject("objects") ?: return 1
        var missing = 0
        val keys = objects.keys()
        while (keys.hasNext()) {
            val obj = objects.optJSONObject(keys.next()) ?: continue
            val hash = obj.optString("hash")
            val size = obj.optLong("size")
            if (hash.length < 5) continue
            val target = File(AppPaths.assets(version), "objects/${hash.take(2)}/$hash")
            if (!target.isFile || (size > 0 && target.length() != size)) missing++
        }
        return missing
    }

    fun remoteJsonDiffers(version: String): Boolean {
        val local = AppPaths.versionJson(version, version)
        if (!local.isFile) return false
        return try {
            val body = net.getText(MANIFEST)
            val versions = JSONObject(body).optJSONArray("versions") ?: return false
            var url: String? = null
            for (i in 0 until versions.length()) {
                val item = versions.getJSONObject(i)
                if (item.optString("id") == version) {
                    url = item.optString("url")
                    break
                }
            }
            if (url.isNullOrBlank()) return false
            val remote = net.getText(url)
            normalizeVersionJson(remote) != normalizeVersionJson(local.readText())
        } catch (e: Exception) {
            CwLog.warn("Сверка Minecraft: ${e.message}")
            false
        }
    }

    fun releases(): List<String> {
        val cache = File(AppPaths.cache, "mojang-manifest.json")
        val body = try {
            val text = net.getText(MANIFEST)
            cache.writeText(text)
            text
        } catch (e: Exception) {
            if (cache.isFile) cache.readText() else throw e
        }
        val out = mutableListOf<String>()
        val versions = JSONObject(body).optJSONArray("versions") ?: return out
        for (i in 0 until versions.length()) {
            val item = versions.getJSONObject(i)
            if (item.optString("type") == "release") out.add(item.optString("id"))
        }
        return out
    }

    fun installMinecraft(version: String) {
        checkCancel()
        step("Проверка Minecraft")
        val url = versionUrl(version)
        val body = net.getText(url)
        val json = AppPaths.versionJson(version, version)
        json.parentFile?.mkdirs()
        json.writeText(body)
        val parsed = parse(JSONObject(body))
        if (parsed.mainClass.isBlank() && parsed.inheritsFrom.isNullOrBlank()) {
            throw IOException("Профиль версии $version повреждён")
        }
        val profile = resolved(version)
        if (!profile.assetIndexUrl.isNullOrBlank()) {
            val index = File(AppPaths.assets(version), "indexes/${profile.assets}.json")
            if (!sameFile(index, profile.assetIndexSize, profile.assetIndexSha1)) {
                step("Проверка ресурсов")
                try {
                    net.download(profile.assetIndexUrl, index, profile.assetIndexSha1, profile.assetIndexSize, cancel, null)
                } catch (e: Exception) {
                    throw IOException(downloadError(e.message ?: "индекс ресурсов не скачан", "${profile.assets}.json"))
                }
            }
        }
        val items = mutableListOf<Item>()
        var have = 0L
        val client = AppPaths.versionJar(version, version)
        if (profile.clientUrl.isNullOrBlank()) throw IOException("В профиле нет client.jar")
        if (sameFile(client, profile.clientSize, profile.clientSha1) && looksLikeZip(client)) {
            have += client.length()
        } else {
            items.add(Item(profile.clientUrl, client, profile.clientSha1, profile.clientSize, "client.jar"))
        }
        for (lib in profile.libraries) {
            if (!lib.download) continue
            val file = File(AppPaths.libs(version), lib.path)
            if (acceptLocal(file, lib)) have += file.length()
            else items.add(lib.item(version))
        }
        val index = File(AppPaths.assets(version), "indexes/${profile.assets}.json")
        if (index.isFile) {
            val objects = JSONObject(index.readText()).optJSONObject("objects")
                ?: throw IOException("Индекс ресурсов пуст")
            val keys = objects.keys()
            while (keys.hasNext()) {
                val obj = objects.optJSONObject(keys.next()) ?: continue
                val hash = obj.optString("hash")
                val size = obj.optLong("size")
                if (hash.length < 5) continue
                val target = File(AppPaths.assets(version), "objects/${hash.take(2)}/$hash")
                if (target.isFile && (size <= 0 || target.length() == size)) {
                    have += target.length()
                    continue
                }
                items.add(Item("https://resources.download.minecraft.net/${hash.take(2)}/$hash", target, hash, size, hash))
            }
        }
        if (items.isEmpty()) {
            step("Minecraft уже установлен", 1f)
            return
        }
        val need = items.sumOf { it.size.coerceAtLeast(0) }
        CwLog.info("Minecraft: уже ${formatBytes(have)}, нужно скачать ${formatBytes(need)} (${items.size} файлов)")
        fetchAll(items, "Загрузка Minecraft", "Уже установлено: ${formatBytes(have)} · нужно скачать: ${formatBytes(need)}")
        val left = missingAssets(version, profile)
        if (left > 0) throw IOException(downloadError("После загрузки не хватает $left файлов ресурсов", "assets"))
        step("Файлы проверены", 1f)
    }

    fun installedLoader(version: String): String? {
        val dir = AppPaths.versions(version)
        val prefix = "fabric-loader-"
        val suffix = "-$version"
        dir.listFiles()?.forEach { child ->
            val name = child.name
            if (name.startsWith(prefix) && name.endsWith(suffix) && File(child, "$name.json").isFile) {
                return name.removePrefix(prefix).removeSuffix(suffix)
            }
        }
        return null
    }

    fun latestLoader(version: String): String? {
        return try {
            val body = net.getText("https://meta.fabricmc.net/v2/versions/loader/$version")
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getJSONObject("loader").optString("version").ifBlank { null }
        } catch (e: Exception) {
            CwLog.warn("Fabric meta недоступен: ${e.message}")
            null
        }
    }

    fun fabricReady(version: String, loader: String?): Boolean {
        if (loader.isNullOrBlank()) return false
        val id = "fabric-loader-$loader-$version"
        if (!AppPaths.versionJson(version, id).isFile) return false
        return try {
            val profile = resolved(version, id)
            profile.mainClass.contains("Knot") &&
                profile.libraries.filter { it.download }.all { fileLooksValid(File(AppPaths.libs(version), it.path), it, false) } &&
                asmOnDisk(version, profile)
        } catch (_: Exception) {
            false
        }
    }

    fun ensureRuntime(version: String, id: String) {
        if (id.startsWith("fabric-loader-")) {
            val loader = id.removePrefix("fabric-loader-").removeSuffix("-$version")
            if (loader.isBlank()) throw IOException(downloadError("Не указана версия Fabric", "fabric-loader"))
            if (!fabricReady(version, loader)) installFabric(version, loader)
            if (!fabricReady(version, loader)) {
                throw IOException(downloadError("Класс org.objectweb.asm.ClassReader не найден после загрузки", "org.ow2.asm:asm"))
            }
        }
        val profile = resolved(version, id)
        val bad = profile.libraries.filter { it.download && !acceptLocal(File(AppPaths.libs(version), it.path), it) }
        if (bad.isNotEmpty()) {
            fetchAll(bad.map { it.item(version) }, "Загрузка библиотек", "")
        }
        if (profile.mainClass.contains("Knot") && !asmOnDisk(version, profile)) {
            throw IOException(downloadError("Fabric Loader не видит org/objectweb/asm/ClassReader", "org.ow2.asm:asm"))
        }
    }

    fun installFabric(version: String, loader: String) {
        checkCancel()
        step("Проверка Fabric")
        val id = "fabric-loader-$loader-$version"
        val body = try {
            net.getText("https://meta.fabricmc.net/v2/versions/loader/$version/$loader/profile/json")
        } catch (e: Exception) {
            throw IOException(downloadError(e.message ?: "профиль Fabric недоступен", "fabric-loader-$loader"))
        }
        val raw = JSONObject(body)
        if (raw.optString("mainClass").isBlank()) throw IOException("Fabric вернул неполный профиль для $version")
        val json = AppPaths.versionJson(version, id)
        json.parentFile?.mkdirs()
        json.writeText(body)
        val profile = resolved(version, id)
        val items = mutableListOf<Item>()
        var have = 0L
        for (lib in profile.libraries) {
            if (!lib.download) continue
            val file = File(AppPaths.libs(version), lib.path)
            if (acceptLocal(file, lib)) have += file.length()
            else items.add(lib.item(version))
        }
        if (items.isNotEmpty()) {
            val need = items.sumOf { it.size.coerceAtLeast(0) }
            CwLog.info("Fabric $loader: уже ${formatBytes(have)}, нужно скачать ${formatBytes(need)} (${items.size} файлов)")
            fetchAll(items, "Загрузка Fabric", "Уже установлено: ${formatBytes(have)} · нужно скачать: ${formatBytes(need)}")
        }
        if (!asmOnDisk(version, profile)) {
            throw IOException(downloadError("В скачанной библиотеке нет класса org.objectweb.asm.ClassReader", "org.ow2.asm:asm"))
        }
        step("Fabric проверен", 1f)
    }

    fun modsInstalled(version: String): Boolean {
        if (!AppPaths.buildState(version).isFile) return false
        return AppPaths.mods(version).listFiles()?.any { it.isFile && it.name.endsWith(".jar") } == true
    }

    fun installedBuild(version: String): String? {
        val state = AppPaths.buildState(version)
        if (state.isFile) {
            val v = JSONObject(state.readText()).optString("version")
            if (v.isNotBlank()) return v
        }
        return readModsVersion(AppPaths.mods(version))
    }

    fun installMods(remote: String?) {
        checkCancel()
        val version = cfg.minecraftVersion
        step("Проверка модов")
        val zip = File(AppPaths.tmp, "modsCWL.zip")
        val cached = File(AppPaths.cache, "modsCWL.zip")
        var reuse = false
        if (looksLikeZip(cached)) {
            val cachedVer = readModsVersionZip(cached)
            if (cachedVer != null && (remote.isNullOrBlank() || compareVersions(cachedVer, remote) == 0)) {
                cached.copyTo(zip, overwrite = true)
                reuse = looksLikeZip(zip)
                CwLog.info("Моды $cachedVer уже скачаны, повторная загрузка не нужна")
            }
        }
        if (!reuse) {
            meter.reset()
            try {
                Drive.download(net, cfg.modpackUrl, zip, true, cancel) { done, total ->
                    reportBytes("Обновление модов", done, total.coerceAtLeast(done), "")
                }
            } catch (e: Exception) {
                throw IOException(downloadError(e.message ?: "архив модов не скачан", "modsCWL.zip"))
            }
        }
        if (!looksLikeZip(zip)) throw IOException("Не удалось загрузить сборку модов. Проверьте интернет и повторите попытку.")
        val attempts = cfg.maxAttempts.coerceAtLeast(1)
        var accepted: String? = null
        var last: Exception? = null
        val stage = File(AppPaths.tmp, "staging")
        for (attempt in 1..attempts) {
            checkCancel()
            clearDir(stage)
            val extracted = extractZip(zip, stage)
            if (extracted == 0) throw IOException("Архив пуст или повреждён")
            val zipVersion = readModsVersion(stage)
            if (zipVersion == null) {
                last = IOException("В архиве нет version.txt с версией модов")
                CwLog.warn("${last.message}, попытка $attempt")
                zip.delete()
                if (attempt < attempts) {
                    Drive.download(net, cfg.modpackUrl, zip, true, cancel) { done, total ->
                        reportBytes("Обновление модов", done, total.coerceAtLeast(done), "")
                    }
                }
            } else {
                if (!remote.isNullOrBlank() && compareVersions(zipVersion, remote) != 0) {
                    CwLog.warn("cwVersion.txt = $remote, в архиве модов $zipVersion. Ставлю версию из архива.")
                } else {
                    CwLog.info("Версия модов в архиве $zipVersion")
                }
                accepted = zipVersion
                break
            }
        }
        if (accepted == null) throw last ?: IOException("Не удалось проверить версию модов")
        step("Установка модов")
        val mods = AppPaths.mods(version)
        mods.mkdirs()
        val old = mods.listFiles()?.filter { it.isFile }.orEmpty()
        val backup = File(AppPaths.tmp, "mods-backup-${System.currentTimeMillis()}")
        if (old.isNotEmpty()) {
            backup.mkdirs()
            old.forEach { it.copyTo(File(backup, it.name), overwrite = true); it.delete() }
        }
        val moved = placePack(stage, AppPaths.game(version), mods)
        File(mods, "Version.txt").writeText(accepted)
        val state = JSONObject()
            .put("version", accepted)
            .put("installedAt", System.currentTimeMillis())
            .put("modCount", moved)
            .put("zipName", "modsCWL.zip")
        AppPaths.buildState(version).writeText(state.toString(2))
        writeManifest(version, mods)
        if (old.isNotEmpty() && !cfg.modBackups) deleteTree(backup)
        else pruneBackups()
        zip.delete()
        CwLog.info("Установлено файлов модов: $moved")
        step("Сборка модов установлена", 1f)
    }

    fun verifyMods(version: String): List<String> {
        val problems = mutableListOf<String>()
        val manifest = AppPaths.buildManifest(version)
        if (!manifest.isFile) return problems
        val files = JSONObject(manifest.readText()).optJSONArray("files") ?: return problems
        val mods = AppPaths.mods(version)
        val missing = mutableListOf<String>()
        val corrupted = mutableListOf<String>()
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val name = item.optString("name")
            val file = File(mods, name)
            if (!file.isFile) {
                missing.add(name)
                continue
            }
            if (name.endsWith(".jar") && !looksLikeZip(file)) corrupted.add(name)
        }
        if (missing.isNotEmpty()) problems.add("Отсутствуют моды сборки: ${missing.take(6).joinToString(", ")}")
        if (corrupted.isNotEmpty()) problems.add("Повреждённые файлы: ${corrupted.take(6).joinToString(", ")}")
        val free = AppPaths.freeMb(AppPaths.root)
        if (cfg.checkDiskSpace && free in 0..1023) problems.add("Мало свободного места: $free МБ")
        return problems
    }

    fun readRemoteModsVersion(): String? {
        val zip = File(AppPaths.cache, "modsCWL.zip")
        Drive.download(net, cfg.modpackUrl, zip, true, cancel) { done, total ->
            reportBytes("Проверка модов", done, total.coerceAtLeast(done), "")
        }
        return readModsVersionZip(zip)
    }

    private fun writeManifest(version: String, mods: File) {
        val arr = JSONArray()
        mods.listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.forEach { file ->
            arr.put(JSONObject().put("name", file.name).put("size", file.length()))
        }
        AppPaths.buildManifest(version).writeText(JSONObject().put("files", arr).toString())
    }

    private fun placePack(stage: File, gameDir: File, modsDir: File): Int {
        var jars = 0
        stage.walkTopDown().filter { it.isFile }.forEach { file ->
            if (isVersionName(file.name)) return@forEach
            val rel = stage.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val lower = rel.lowercase(Locale.ROOT)
            val folder = packFolder(lower)
            if (folder != null) {
                val tail = tailAfter(rel, folder)
                if (!tail.isNullOrBlank()) {
                    val dest = File(File(gameDir, folder), tail)
                    dest.parentFile?.mkdirs()
                    file.copyTo(dest, overwrite = true)
                }
                return@forEach
            }
            if (!lower.endsWith(".jar")) return@forEach
            if (lower.contains("/libraries/") || lower.contains("/versions/") || lower.contains("/.fabric/")) return@forEach
            file.copyTo(File(modsDir, file.name), overwrite = true)
            jars++
        }
        return jars
    }

    private fun pruneBackups() {
        val dirs = AppPaths.tmp.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("mods-backup-") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        dirs.drop(cfg.modBackupCount.coerceAtLeast(1)).forEach { deleteTree(it) }
    }

    private fun fetchAll(items: List<Item>, title: String, extra: String) {
        if (items.isEmpty()) return
        meter.reset()
        val planned = items.sumOf { it.size.coerceAtLeast(0) }
        val totalBytes = AtomicLong(planned)
        val fileDone = ConcurrentHashMap<String, Long>()
        val grown = ConcurrentHashMap<String, Long>()
        val finished = AtomicInteger(0)
        val files = items.size
        fun sumDone() = fileDone.values.sum()
        reportBytes(title, 0, planned.coerceAtLeast(1), filesNote(extra, 0, files), true)
        var pending = items
        var round = 0
        while (pending.isNotEmpty() && round < 5) {
            checkCancel()
            val failed = java.util.Collections.synchronizedList(mutableListOf<Item>())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(if (round == 0) threads else 1)
            try {
                val tasks = pending.map { item ->
                    pool.submit {
                        val key = item.dest.absolutePath
                        try {
                            checkCancel()
                            item.dest.parentFile?.mkdirs()
                            fileDone[key] = 0L
                            net.download(item.url, item.dest, item.sha1, item.size, cancel) { done, total ->
                                if (item.size <= 0 && total > 0) {
                                    val prev = grown.put(key, total) ?: 0L
                                    if (prev != total) totalBytes.addAndGet(total - prev)
                                }
                            fileDone[key] = done
                            val note = filesNote(extra, finished.get(), files)
                            reportBytes(title, sumDone(), totalBytes.get().coerceAtLeast(1), note)
                            }
                            verifyDownloaded(item)
                            fileDone[key] = item.dest.length().coerceAtLeast(item.size)
                            finished.incrementAndGet()
                        } catch (e: Throwable) {
                            if (cancel.get()) throw e
                            fileDone[key] = 0L
                            lastDownloadError = e.message ?: e.javaClass.simpleName
                            failed.add(item)
                            CwLog.warn("$title: ${item.label}: $lastDownloadError")
                        }
                    }
                }
                tasks.forEach { task ->
                    try {
                        task.get()
                    } catch (e: Exception) {
                        if (cancel.get()) throw IOException("Отменено")
                        CwLog.warn("$title: ${e.message}")
                    }
                }
            } finally {
                pool.shutdown()
            }
            if (cancel.get()) throw IOException("Отменено")
            pending = failed.toList()
            round++
            if (pending.isNotEmpty() && round < 5) {
                CwLog.warn("$title: повтор ${pending.size} файлов")
                Thread.sleep(500)
            }
        }
        if (pending.isNotEmpty()) {
            val item = pending.first()
            val reason = lastDownloadError.ifBlank { "сервер не отдал файл, осталось ${pending.size}" }
            throw IOException(downloadError(reason, item.label))
        }
        val done = fileDone.values.sum().coerceAtLeast(planned)
        reportBytes(title, done, done.coerceAtLeast(1), filesNote(extra, files, files), true)
    }

    private fun verifyDownloaded(item: Item) {
        if (item.dest.name.endsWith(".jar", true) && !looksLikeZip(item.dest)) {
            item.dest.delete()
            throw IOException("Файл повреждён и не является библиотекой")
        }
        if (item.asmCore && !jarHasEntry(item.dest, ASM_CLASS)) {
            item.dest.delete()
            throw IOException("В библиотеке нет org/objectweb/asm/ClassReader")
        }
    }

    private fun step(title: String, fraction: Float = 0f) {
        onProgress(LoadProgress(title, fraction))
    }

    private fun reportBytes(title: String, done: Long, total: Long, extra: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            val prev = lastUi.get()
            if (now - prev < 200) return
            if (!lastUi.compareAndSet(prev, now)) return
        } else {
            lastUi.set(now)
        }
        val speed = meter.note(done)
        val frac = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
        onProgress(LoadProgress(title, frac, done, total, speed, extra))
    }

    private fun acceptLocal(file: File, lib: Lib): Boolean = fileLooksValid(file, lib, true)

    private fun fileLooksValid(file: File, lib: Lib, hash: Boolean): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        if (lib.size > 0 && file.length() != lib.size) return false
        if (file.name.endsWith(".jar", true) && !looksLikeZip(file)) return false
        if (isAsmCore(lib.name) && !jarHasEntry(file, ASM_CLASS)) return false
        if (hash && !lib.sha1.isNullOrBlank() && !sha1(file).equals(lib.sha1, ignoreCase = true)) return false
        return true
    }

    private fun filesNote(extra: String, done: Int, total: Int): String {
        val count = "Файлы: $done / $total"
        return if (extra.isBlank()) count else "$extra · $count"
    }

    private fun asmOnDisk(version: String, profile: VersionProfile): Boolean {
        val cores = profile.libraries.filter { it.download && isAsmCore(it.name) }
        if (cores.isNotEmpty()) {
            return cores.any { jarHasEntry(File(AppPaths.libs(version), it.path), ASM_CLASS) }
        }
        return profile.libraries.filter { it.download }.any {
            jarHasEntry(File(AppPaths.libs(version), it.path), ASM_CLASS)
        }
    }

    private fun Lib.item(version: String): Item =
        Item(url, File(AppPaths.libs(version), path), sha1, size, name, isAsmCore(name))

    private fun versionUrl(version: String): String {
        val body = net.getText(MANIFEST)
        File(AppPaths.cache, "mojang-manifest.json").writeText(body)
        val versions = JSONObject(body).getJSONArray("versions")
        for (i in 0 until versions.length()) {
            val item = versions.getJSONObject(i)
            if (item.optString("id") == version) return item.getString("url")
        }
        throw IOException("Версия $version не найдена у Mojang")
    }

    private fun checkCancel() {
        if (cancel.get()) throw IOException("Отменено")
    }

    private data class Item(
        val url: String,
        val dest: File,
        val sha1: String?,
        val size: Long,
        val label: String,
        val asmCore: Boolean = false
    )

    companion object {
        const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        fun parse(root: JSONObject): VersionProfile {
            val id = root.optString("id")
            val inherits = root.optString("inheritsFrom").ifBlank { null }
            val main = root.optString("mainClass")
            val assets = root.optString("assets", "legacy")
            val index = root.optJSONObject("assetIndex")
            val downloads = root.optJSONObject("downloads")
            val client = downloads?.optJSONObject("client")
            val libs = mutableListOf<Lib>()
            val arr = root.optJSONArray("libraries")
            if (arr != null) {
                for (i in 0 until arr.length()) libs.addAll(parseLibs(arr.getJSONObject(i)))
            }
            val game = mutableListOf<String>()
            val jvm = mutableListOf<String>()
            val args = root.optJSONObject("arguments")
            if (args != null) {
                readArgs(args.optJSONArray("game"), game)
                readArgs(args.optJSONArray("jvm"), jvm)
            } else {
                root.optString("minecraftArguments").split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { game.add(it) }
            }
            return VersionProfile(
                id = id,
                inheritsFrom = inherits,
                mainClass = main,
                assets = assets,
                assetIndexUrl = index?.optString("url")?.ifBlank { null },
                assetIndexSha1 = index?.optString("sha1")?.ifBlank { null },
                assetIndexSize = index?.optLong("size") ?: 0,
                clientUrl = client?.optString("url")?.ifBlank { null },
                clientSha1 = client?.optString("sha1")?.ifBlank { null },
                clientSize = client?.optLong("size") ?: 0,
                libraries = libs,
                gameArgs = game,
                jvmArgs = jvm
            )
        }

        private fun parseLibs(m: JSONObject): List<Lib> {
            val name = m.optString("name").ifBlank { return emptyList() }
            var allow = rulesAllow(m.optJSONArray("rules")) && m.optBoolean("client", true)
            val dl = m.optJSONObject("downloads")
            val art = dl?.optJSONObject("artifact")
            var nativeKey: String? = null
            var nat: JSONObject? = null
            val natives = m.optJSONObject("natives")
            if (natives != null && natives.has(currentOs())) {
                nativeKey = natives.optString(currentOs()).replace("\${arch}", if (is64()) "64" else "32")
                if (!nativeClassifierAllowed(nativeKey)) {
                    val alt = armNativeClassifier(nativeKey)
                    val altJson = if (alt == null) null else dl?.optJSONObject("classifiers")?.optJSONObject(alt)
                    if (alt != null && altJson != null) {
                        nativeKey = alt
                        nat = altJson
                    }
                }
                if (nat == null) nat = dl?.optJSONObject("classifiers")?.optJSONObject(nativeKey)
            }
            var namedNative = name.split(":").getOrNull(3)?.takeIf { it.startsWith("natives-") }
            var artifact = art
            var baseUrl = m.optString("url").ifBlank { "https://libraries.minecraft.net/" }
            val armClassifier = armNativeClassifier(namedNative)
            if (armClassifier != null) {
                namedNative = armClassifier
                artifact = null
                baseUrl = "https://repo1.maven.org/maven2/"
            }
            val armKey = armNativeClassifier(nativeKey)
            if (armKey != null) {
                nativeKey = armKey
                nat = null
                baseUrl = "https://repo1.maven.org/maven2/"
            }
            if (namedNative != null && !nativeClassifierAllowed(namedNative)) allow = false
            if (nativeKey != null && !nativeClassifierAllowed(nativeKey)) allow = false
            val out = mutableListOf<Lib>()
            val topSha = m.optString("sha1").ifBlank { null }
            val topSize = m.optLong("size", 0L)
            if (artifact != null || nativeKey == null) {
                out.add(libOf(name, baseUrl, allow, namedNative, artifact, topSha, topSize))
            }
            if (nativeKey != null) out.add(libOf(name, baseUrl, allow, nativeKey, nat, null, 0))
            return out
        }

        private fun libOf(
            name: String,
            baseUrl: String,
            allow: Boolean,
            classifier: String?,
            art: JSONObject?,
            fallbackSha: String?,
            fallbackSize: Long
        ): Lib {
            val path = art?.optString("path")?.ifBlank { null } ?: mavenPath(name, classifier) ?: name
            val artSha = art?.optString("sha1")?.ifBlank { null }
            val sha = artSha ?: if (classifier == null) fallbackSha else null
            val artSize = art?.optLong("size", -1) ?: -1
            val size = when {
                artSize > 0 -> artSize
                classifier == null && fallbackSize > 0 -> fallbackSize
                else -> 0L
            }
            val direct = art?.optString("url")?.takeIf { it.startsWith("http") }
            val encoded = path.replace("+", "%2B")
            val url = direct ?: (baseUrl.trimEnd('/') + "/" + encoded.removePrefix("/"))
            return Lib(name, path, url, sha, size, allow, classifier)
        }

        private fun mergeArgs(parent: List<String>, child: List<String>): List<String> {
            if (child.isEmpty()) return parent
            if (parent.isEmpty()) return child
            val out = parent.toMutableList()
            child.forEach { if (it !in out) out.add(it) }
            return out
        }

        fun mavenPath(coords: String?, classifier: String? = null): String? {
            if (coords.isNullOrBlank()) return null
            val p = coords.split(":")
            if (p.size < 3) return null
            val c = if (classifier.isNullOrEmpty()) "" else "-$classifier"
            val extra = if (p.size > 3 && classifier == null) "-" + p[3] else c
            return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + extra + ".jar"
        }

        private fun rulesAllow(rules: JSONArray?): Boolean {
            if (rules == null || rules.length() == 0) return true
            var allow = false
            for (i in 0 until rules.length()) {
                val rule = rules.optJSONObject(i) ?: continue
                if (osMatches(rule.optJSONObject("os"), rule.optJSONObject("features"))) {
                    allow = rule.optString("action", "allow") == "allow"
                }
            }
            return allow
        }

        private fun osMatches(os: JSONObject?, features: JSONObject?): Boolean {
            if (features != null && features.length() > 0) return false
            if (os == null || os.length() == 0) return true
            val name = os.optString("name")
            if (name.isNotBlank() && !name.equals(currentOs(), true)) return false
            val arch = os.optString("arch")
            if (arch.isNotBlank() && !archMatches(arch)) return false
            return true
        }

        private fun archMatches(rule: String): Boolean {
            val have = deviceArch()
            return when (rule.lowercase(Locale.ROOT)) {
                "arm64", "aarch64" -> have == "arm64"
                "arm32", "arm" -> have == "arm32"
                "x64", "x86_64", "amd64" -> have == "x64"
                "x86", "i386" -> have == "x86"
                else -> false
            }
        }

        private fun armNativeClassifier(classifier: String?): String? {
            if (classifier != "natives-linux") return null
            return when (deviceArch()) {
                "arm64" -> "natives-linux-arm64"
                "arm32" -> "natives-linux-arm32"
                else -> null
            }
        }

        private fun nativeClassifierAllowed(classifier: String?): Boolean {
            if (classifier.isNullOrBlank() || !classifier.startsWith("natives-")) return true
            if (classifier.contains("windows") || classifier.contains("macos") || classifier.contains("osx")) return false
            val arm64 = classifier.contains("arm64") || classifier.contains("aarch64")
            val arm32 = !arm64 && (classifier.contains("arm32") || classifier.contains("armv7"))
            return when (deviceArch()) {
                "arm64" -> arm64
                "arm32" -> arm32
                "x86" -> classifier == "natives-linux" || classifier.endsWith("-x86")
                else -> classifier == "natives-linux" || (classifier.contains("linux") && !arm64 && !arm32)
            }
        }

        private fun readArgs(list: JSONArray?, out: MutableList<String>) {
            if (list == null) return
            for (i in 0 until list.length()) {
                val value = list.opt(i)
                if (value is String) out.add(value)
                else if (value is JSONObject) {
                    if (!rulesAllow(value.optJSONArray("rules"))) continue
                    when (val v = value.opt("value")) {
                        is String -> out.add(v)
                        is JSONArray -> for (j in 0 until v.length()) v.optString(j).takeIf { it.isNotBlank() }?.let(out::add)
                    }
                }
            }
        }

        fun readModsVersion(root: File): String? {
            val names = listOf("Version.txt", "version.txt", "modsVersion.txt", "cwVersion.txt", "modsVersion.txt.txt", "Version.txt.txt")
            val files = root.walkTopDown().maxDepth(3).filter { it.isFile && isVersionName(it.name) }.toList()
            for (name in names) {
                files.firstOrNull { it.name.equals(name, true) }?.let { return readVersionToken(it.readText()) }
            }
            return files.firstOrNull()?.let { readVersionToken(it.readText()) }
        }

        fun readModsVersionZip(zip: File): String? {
            if (!zip.isFile) return null
            java.util.zip.ZipFile(zip).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val name = e.name.substringAfterLast('/')
                    if (!isVersionName(name)) continue
                    val text = z.getInputStream(e).bufferedReader().readText()
                    return readVersionToken(text)
                }
            }
            return null
        }

        fun isVersionName(name: String): Boolean {
            val n = name.lowercase(Locale.ROOT)
            return n == "version.txt" || n == "modsversion.txt" || n == "cwversion.txt" ||
                n == "version.txt.txt" || n == "modsversion.txt.txt"
        }

        private fun packFolder(lower: String): String? = when {
            lower.startsWith("config/") || lower.contains("/config/") -> "config"
            lower.startsWith("resourcepacks/") || lower.contains("/resourcepacks/") -> "resourcepacks"
            lower.startsWith("shaderpacks/") || lower.contains("/shaderpacks/") -> "shaderpacks"
            lower.startsWith("datapacks/") || lower.contains("/datapacks/") -> "datapacks"
            lower.startsWith("defaultconfigs/") || lower.contains("/defaultconfigs/") -> "defaultconfigs"
            else -> null
        }

        private fun tailAfter(rel: String, folder: String): String? {
            val norm = rel.replace('\\', '/')
            val mark = "$folder/"
            val i = norm.lowercase(Locale.ROOT).indexOf(mark)
            if (i < 0) return null
            return norm.substring(i + mark.length)
        }

        private fun normalizeVersionJson(text: String): String =
            text.replace(Regex("\\s+"), "")
    }
}
