package ru.cw.launcher.engine

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class GameLaunch(
    private val context: Context,
    private val cfg: CwSettings,
    private val installer: Installer
) {
    @Volatile var process: Process? = null

    fun running(): Boolean = process?.isAlive == true

    fun stop() {
        process?.destroy()
        process = null
    }

    fun javaBinary(): File? {
        val custom = cfg.javaPath.trim()
        if (custom.isNotEmpty()) {
            val file = File(custom)
            if (file.isFile) return file
        }
        BundledJava.launcher(context)?.let { return it }
        val candidates = listOf(
            File(AppPaths.javaDir, "bin/java"),
            File(BundledJava.home(), "bin/java"),
            File(AppPaths.root, "runtime/bin/java")
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun command(account: Account, profileId: String): List<String> {
        val version = cfg.minecraftVersion
        val profile = installer.resolved(version, profileId)
        if (profile.mainClass.isBlank()) throw java.io.IOException("В профиле $profileId не указан mainClass")
        val java = javaBinary() ?: throw java.io.IOException(
            if (BundledJava.supported()) {
                "Не найдена Java 17 для Minecraft $version. Встроенная Java должна лежать в самом лаунчере."
            } else {
                "Встроенная Java 17 есть для arm64. Этот процессор: ${android.os.Build.SUPPORTED_ABIS.joinToString()}."
            }
        )
        java.setExecutable(true)
        val gameDir = AppPaths.game(version)
        gameDir.mkdirs()
        applyOptions(gameDir)
        val natives = extractNatives(version, profile, gameDir)
        val cp = classpath(version, profile)
        if (profile.mainClass.contains("Knot") && !classpathHasAsm(version, profile)) {
            throw IOException(downloadError("Fabric Loader не видит org/objectweb/asm/ClassReader", "org.ow2.asm:asm"))
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalMb = (mem.totalMem / (1024 * 1024)).toInt().coerceAtLeast(1024)
        val ram = cfg.gameRamMb(totalMb)
        val xms = minOf(512, ram)
        val cmd = mutableListOf<String>()
        cmd.add(java.absolutePath)
        cmd.add("-Xms${xms}M")
        cmd.add("-Xmx${ram}M")
        cmd.add("-XX:MaxDirectMemorySize=${if (ram >= 6144) 2048 else 1024}M")
        cmd.addAll(memoryFlags())
        val player = account.username.ifBlank { "Player" }
        val jvm = profile.jvmArgs.ifEmpty {
            listOf(
                "-Djava.library.path=\${natives_directory}",
                "-Dminecraft.launcher.brand=\${launcher_name}",
                "-Dminecraft.launcher.version=\${launcher_version}"
            )
        }
        var jvmIndex = 0
        while (jvmIndex < jvm.size) {
            val raw = jvm[jvmIndex]
            var r = raw
                .replace("\${classpath}", "")
                .replace("\${natives_directory}", natives.absolutePath)
                .replace("\${launcher_name}", "CWLauncher")
                .replace("\${launcher_version}", CwLog.VERSION)
                .replace("\${assets_root}", AppPaths.assets(version).absolutePath)
                .replace("\${game_dir}", gameDir.absolutePath)
                .replace("\${auth_player_name}", player)
                .replace("\${user_properties}", "{}")
                .replace("\${clientid}", "")
                .replace("\${remoteserver}", "")
                .replace("\${version_name}", profileId)
            if (r == "-cp" || r == "-classpath") {
                jvmIndex += 2
                continue
            }
            jvmIndex++
            if (r.startsWith("-Xmx") || r.startsWith("-Xms") || r.startsWith("-XX:MaxDirectMemorySize")) continue
            if (r.contains("\${") || r.isBlank()) continue
            cmd.add(r)
        }
        cmd.add("-Dorg.lwjgl.librarypath=${natives.absolutePath}")
        cmd.add("-Dorg.lwjgl.system.allocator=system")
        cmd.add("-Dfile.encoding=UTF-8")
        cmd.add("-Djava.io.tmpdir=${AppPaths.tmp.absolutePath}")
        cmd.add("-Duser.home=${AppPaths.root.absolutePath}")
        cmd.add("-Dorg.lwjgl.util.NoChecks=true")
        if (account.type == AccountType.ELY) {
            try {
                cmd.add(agentArgument())
            } catch (e: Exception) {
                CwLog.warn("authlib-injector недоступен: ${e.message}")
            }
        }
        cfg.extraJvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { arg ->
            if (arg.startsWith("-Xm") || arg == "-cp" || arg == "-classpath") return@forEach
            if (arg.startsWith("-D") || arg.startsWith("-XX") || arg.startsWith("--add-")) cmd.add(arg)
        }
        cmd.add("-cp")
        cmd.add(cp)
        cmd.add(profile.mainClass)
        val token = if (account.type == AccountType.OFFLINE) "0" else Ely.token(account)
        val vars = mapOf(
            "\${auth_player_name}" to player,
            "\${version_name}" to profileId,
            "\${game_directory}" to gameDir.absolutePath,
            "\${assets_root}" to AppPaths.assets(version).absolutePath,
            "\${game_assets}" to AppPaths.assets(version).absolutePath,
            "\${assets_index_name}" to profile.assets,
            "\${auth_session}" to token,
            "\${auth_uuid}" to account.shortUuid(),
            "\${auth_access_token}" to token,
            "\${user_type}" to if (account.type == AccountType.OFFLINE) "legacy" else "mojang",
            "\${version_type}" to if (profileId.startsWith("fabric-loader")) "Fabric" else "release",
            "\${launcher_name}" to "CWLauncher",
            "\${launcher_version}" to CwLog.VERSION,
            "\${natives_directory}" to natives.absolutePath,
            "\${user_properties}" to "{}",
            "\${resolution_width}" to cfg.screenWidth.toString(),
            "\${resolution_height}" to cfg.screenHeight.toString(),
            "\${classpath}" to "",
            "\${clientid}" to "",
            "\${remoteserver}" to "",
            "\${quickPlayPath}" to "",
            "\${primaryServer}" to cfg.serverAddress
        )
        val game = mutableListOf<String>()
        for (raw in profile.gameArgs) {
            var r = raw
            vars.forEach { (k, v) -> r = r.replace(k, v) }
            if (r.contains("\${")) continue
            game.add(r)
        }
        putFlag(game, "--gameDir", gameDir.absolutePath)
        putFlag(game, "--assetsDir", AppPaths.assets(version).absolutePath)
        if (profile.assets.isNotBlank()) putFlag(game, "--assetIndex", profile.assets)
        if (cfg.screenWidth > 0) putFlag(game, "--width", cfg.screenWidth.toString())
        if (cfg.screenHeight > 0) putFlag(game, "--height", cfg.screenHeight.toString())
        if (cfg.screenFullscreen) putFlag(game, "--fullscreen", null)
        cmd.addAll(game)
        return cmd
    }

    fun start(account: Account, profileId: String) {
        val cmd = command(account, profileId)
        val log = AppPaths.gameLog
        log.parentFile?.mkdirs()
        if (log.exists()) log.delete()
        val pb = ProcessBuilder(cmd)
        pb.directory(AppPaths.game(cfg.minecraftVersion))
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        val env = pb.environment()
        val bundled = BundledJava.launcher(context)
        val usingBundled = bundled != null && cmd.firstOrNull() == bundled.absolutePath
        val home = if (usingBundled) BundledJava.ensure(context) else javaBinary()?.parentFile?.parentFile
        val nativesPath = cmd.firstOrNull { it.startsWith("-Djava.library.path=") }?.substringAfter("=").orEmpty()
        env["JAVA_HOME"] = home?.absolutePath ?: ""
        env["TMPDIR"] = AppPaths.tmp.absolutePath
        env["HOME"] = AppPaths.root.absolutePath
        env["LD_LIBRARY_PATH"] = if (usingBundled && home != null) {
            listOf(BundledJava.libraryPath(context, home), nativesPath).filter { it.isNotBlank() }.joinToString(":")
        } else {
            nativesPath.ifBlank { File(AppPaths.game(cfg.minecraftVersion), "natives").absolutePath }
        }
        if (home != null) {
            val bin = File(home, "bin").absolutePath
            env["PATH"] = bin + ":" + (env["PATH"] ?: "")
        }
        val main = cmd.getOrNull(cmd.indexOf("-cp") + 2).orEmpty()
        CwLog.info("Запуск: ${cmd.first()} $profileId $main")
        process = pb.start()
    }

    private fun classpath(version: String, profile: VersionProfile): String {
        val files = linkedSetOf<String>()
        for (lib in profile.libraries) {
            if (!lib.download) continue
            val file = File(AppPaths.libs(version), lib.path)
            if (!file.isFile) throw java.io.IOException(downloadError("Файл не найден перед запуском", lib.name))
            if (file.name.endsWith(".jar", true) && !looksLikeZip(file)) {
                throw java.io.IOException(downloadError("Файл повреждён", lib.name))
            }
            files.add(file.absolutePath)
        }
        val client = installer.clientJar(version)
        if (!client.isFile) throw java.io.IOException(downloadError("Нет client.jar", client.name))
        files.add(client.absolutePath)
        CwLog.info("Classpath: ${files.size} файлов")
        return files.joinToString(File.pathSeparator)
    }

    private fun classpathHasAsm(version: String, profile: VersionProfile): Boolean {
        val cores = profile.libraries.filter { it.download && isAsmCore(it.name) }
        val jars = if (cores.isNotEmpty()) cores else profile.libraries.filter { it.download }
        return jars.any { jarHasEntry(File(AppPaths.libs(version), it.path), ASM_CLASS) }
    }

    private fun extractNatives(version: String, profile: VersionProfile, gameDir: File): File {
        val natives = File(gameDir, "natives/${System.nanoTime()}")
        natives.mkdirs()
        var count = 0
        for (lib in profile.libraries) {
            if (!lib.download || !lib.isNative()) continue
            val jar = File(AppPaths.libs(version), lib.path)
            if (!jar.isFile) continue
            ZipFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF")) continue
                    val simple = entry.name.substringAfterLast('/')
                    if (simple.endsWith(".dll") || simple.endsWith(".dylib")) continue
                    val out = File(natives, simple)
                    if (!out.canonicalPath.startsWith(natives.canonicalPath)) continue
                    val part = File(natives, "$simple.part")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (simple.endsWith(".so") && !elfMatchesDevice(part)) {
                        part.delete()
                        CwLog.warn("Пропущена библиотека другой архитектуры: $simple")
                        continue
                    }
                    if (out.exists()) out.delete()
                    if (!part.renameTo(out)) {
                        part.copyTo(out, overwrite = true)
                        part.delete()
                    }
                    out.setExecutable(true)
                    count++
                }
            }
        }
        CwLog.info("Извлечено natives-файлов: $count (${deviceArch()})")
        if (count == 0) {
            throw IOException("Нет библиотек LWJGL для ${deviceArch()}. Нажмите «Повторить», чтобы скачать их заново.")
        }
        return natives
    }

    private fun elfMatchesDevice(file: File): Boolean {
        val head = ByteArray(20)
        val n = file.inputStream().use { it.read(head) }
        if (n < 20 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte()) return true
        val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
        return when (deviceArch()) {
            "arm64" -> machine == 183
            "arm32" -> machine == 40
            "x64" -> machine == 62
            "x86" -> machine == 3
            else -> true
        }
    }

    fun applyOptions(gameDir: File = AppPaths.game(cfg.minecraftVersion)) {
        if (!gameDir.isDirectory && !gameDir.mkdirs()) return
        val options = File(gameDir, "options.txt")
        val values = linkedMapOf<String, String>()
        if (options.isFile) {
            options.readLines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) values[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        values["lang"] = cfg.resolvedLanguage()
        values["fullscreen"] = cfg.screenFullscreen.toString()
        values["enableVsync"] = cfg.vsync.toString()
        values["maxFps"] = if (cfg.framerateLimit <= 0) "260" else cfg.framerateLimit.coerceIn(10, 260).toString()
        if (cfg.screenWidth in 640..7680) values["overrideWidth"] = cfg.screenWidth.toString()
        if (cfg.screenHeight in 360..4320) values["overrideHeight"] = cfg.screenHeight.toString()
        options.writeText(values.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
        CwLog.info("Настройки игры записаны: язык ${values["lang"]}, ${cfg.screenWidth}x${cfg.screenHeight}, fps ${values["maxFps"]}")
    }

    private fun agentArgument(): String {
        val jar = File(AppPaths.injector, "authlib-injector.jar")
        if (!jar.isFile || jar.length() < 10_000 || !looksLikeZip(jar)) {
            val metas = listOf(
                "https://authlib-injector.yushi.moe/artifact/latest.json",
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/latest.json"
            )
            var url: String? = null
            val net = Net()
            for (meta in metas) {
                try {
                    val found = JSONObject(net.getText(meta)).optString("download_url")
                    if (found.startsWith("https://")) {
                        url = found
                        break
                    }
                } catch (_: Exception) {
                }
            }
            val targets = listOfNotNull(
                url?.replace("bmclapi1.sakura.sld.tw", "bmclapi2.bangbang93.com"),
                "https://authlib-injector.yushi.moe/artifact/56/authlib-injector-1.2.8.jar",
                "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/56/authlib-injector-1.2.8.jar"
            )
            var last: Exception? = null
            for (target in targets) {
                try {
                    net.download(target, jar, null, 0, null, null)
                    if (looksLikeZip(jar)) break
                    jar.delete()
                    last = java.io.IOException("Загружен не JAR")
                } catch (e: Exception) {
                    last = e
                    jar.delete()
                }
            }
            if (!jar.isFile) throw last ?: java.io.IOException("Не удалось получить authlib-injector")
        }
        return "-javaagent:${jar.absolutePath}=${Ely.INJECTOR_API}"
    }

    private fun memoryFlags(): List<String> = listOf(
        "-XX:+UseG1GC",
        "-XX:+ParallelRefProcEnabled",
        "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+DisableExplicitGC",
        "-XX:G1NewSizePercent=30",
        "-XX:G1MaxNewSizePercent=40",
        "-XX:G1HeapRegionSize=8M",
        "-XX:G1ReservePercent=20",
        "-XX:G1HeapWastePercent=5",
        "-XX:G1MixedGCCountTarget=4",
        "-XX:InitiatingHeapOccupancyPercent=15",
        "-XX:G1MixedGCLiveThresholdPercent=90",
        "-XX:G1RSetUpdatingPauseTimePercent=5",
        "-XX:SurvivorRatio=32",
        "-XX:+PerfDisableSharedMem",
        "-XX:MaxTenuringThreshold=1",
        "-XX:+UseStringDeduplication"
    )

    private fun putFlag(args: MutableList<String>, flag: String, value: String?) {
        if (args.any { it == flag }) return
        args.add(flag)
        if (value != null) args.add(value)
    }
}
