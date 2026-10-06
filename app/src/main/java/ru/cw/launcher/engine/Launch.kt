package ru.cw.launcher.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import ru.cw.launcher.ui.GameActivity
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class GameLaunch(
    private val context: Context,
    private val cfg: CwSettings,
    private val installer: Installer
) {
    @Volatile var process: Process? = null
    @Volatile var booted: Boolean = false
    @Volatile var stoppedByUser: Boolean = false

    fun running(): Boolean {
        val stage = PhoneVm.stage()
        if (stage != PhoneVm.Stage.STARTING && stage != PhoneVm.Stage.RUNNING) return false
        val beat = PhoneVm.beat()
        return beat <= 0L || System.currentTimeMillis() - beat < 15_000
    }

    fun stop() {
        stoppedByUser = true
        booted = false
        val pid = PhoneVm.pid()
        if (pid > 0) {
            try {
                android.os.Process.killProcess(pid)
            } catch (_: Exception) {
            }
        }
        PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
        process?.destroy()
        process = null
    }

    fun javaBinary(): File? {
        val custom = cfg.javaPath.trim()
        if (custom.isNotEmpty()) {
            val file = File(custom)
            if (file.isFile) return file
        }
        return BundledJava.launcher(context)
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
        val availMb = (mem.availMem / (1024 * 1024)).toInt().coerceAtLeast(768)
        val cap = minOf(2048, (availMb - 256).coerceAtLeast(768))
        val ram = minOf(cfg.gameRamMb(totalMb), cap).coerceAtLeast(512)
        val xms = minOf(512, ram)
        val cmd = mutableListOf<String>()
        cmd.add(java.absolutePath)
        cmd.add("-Xms${xms}M")
        cmd.add("-Xmx${ram}M")
        cmd.add("-XX:MaxDirectMemorySize=256M")
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
            if (r.startsWith("-Djava.library.path=")) {
                cmd.add("-Djava.library.path=${jreLibraryPath()}")
                continue
            }
            if (hostileJvm(r)) continue
            if (r.contains("\${") || r.isBlank()) continue
            cmd.add(r)
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        cmd.add("-Dorg.lwjgl.librarypath=$nativeDir")
        cmd.add("-Dcw.nativedir=$nativeDir")
        cmd.add("-Dcw.main=${profile.mainClass}")
        cmd.add("-Dcw.jvm.log=${File(AppPaths.logs, "jvm.log").absolutePath}")
        cmd.add("-Dcw.game.log=${File(gameDir, "logs/latest.log").absolutePath}")
        cmd.add("-Dfabric.noGui=true")
        cmd.add("-Dloader.disable_forked_guis=true")
        cmd.add("-Dfml.earlyprogresswindow=false")
        cmd.add("-Dfabric.log.file=${File(AppPaths.logs, "fabric.log").absolutePath}")
        cmd.add("-Dfabric.gameVersion=$version")
        val clientJar = installer.clientJar(version)
        if (clientJar.isFile) cmd.add("-Dfabric.gameJarPath.client=${clientJar.absolutePath}")
        cmd.add("-Dorg.lwjgl.opengl.libname=libng_gl4es.so")
        cmd.add("-Dorg.lwjgl.freetype.libname=$nativeDir/libfreetype.so")
        cmd.add("-Djava.home=${BundledJava.home().absolutePath}")
        cmd.add("-Duser.dir=${gameDir.absolutePath}")
        cmd.add("-Djava.awt.headless=true")
        cmd.add("-Dos.name=Linux")
        cmd.add("-Dos.version=Android-${android.os.Build.VERSION.RELEASE}")
        cmd.add("-Dglfwstub.initEgl=false")
        cmd.add("-Dglfwstub.windowWidth=${cfg.screenWidth}")
        cmd.add("-Dglfwstub.windowHeight=${cfg.screenHeight}")
        cmd.add("-Djdk.lang.Process.launchMechanism=FORK")
        cmd.add("-Dlog4j2.formatMsgNoLookups=true")
        cmd.add("-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors().coerceAtLeast(1)}")
        cmd.add("-Dorg.lwjgl.system.allocator=system")
        cmd.add("-Dio.netty.transport.noNative=true")
        cmd.add("-Dio.netty.handler.ssl.noOpenSsl=true")
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
            if (arg.startsWith("-Xm") || arg == "-cp" || arg == "-classpath" || hostileJvm(arg)) return@forEach
            if (arg.startsWith("-D") || arg.startsWith("-XX") || arg.startsWith("--add-")) cmd.add(arg)
        }
        cmd.add("-cp")
        cmd.add(cp)
        cmd.add("ru.cw.boot.CwMain")
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
        addJoin(game, version)
        cmd.addAll(game)
        return cmd
    }

    fun outputLogs(): List<File> = listOf(
        AppPaths.gameLog,
        File(AppPaths.game(cfg.minecraftVersion), "logs/latest.log"),
        File(AppPaths.logs, "jvm.log"),
        File(AppPaths.logs, "fabric.log")
    )

    fun windowReady(): Boolean {
        if (booted) return true
        val files = outputLogs()
        for (file in files) {
            val text = tail(file)
            if (text.contains("Sound engine started") || text.contains("LWJGL Version:")) {
                booted = true
                return true
            }
        }
        return false
    }

    fun freshGameLines(cursors: MutableMap<String, Long>): List<String> {
        val files = outputLogs()
        val lines = mutableListOf<String>()
        for (file in files) {
            if (!file.isFile) continue
            val key = file.absolutePath
            val seen = cursors[key] ?: 0L
            val len = file.length()
            if (len <= seen) continue
            val text = try {
                file.inputStream().use { input ->
                    if (seen > 0) input.skip(seen)
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                continue
            }
            cursors[key] = len
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        }
        return lines
    }

    private fun tail(file: File, max: Int = 64_000): String {
        if (!file.isFile) return ""
        return try {
            file.inputStream().use { input ->
                val len = file.length()
                if (len > max) input.skip(len - max)
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun start(account: Account, profileId: String) {
        stoppedByUser = false
        booted = false
        val cmd = command(account, profileId)
        AppPaths.gameLog.parentFile?.mkdirs()
        if (AppPaths.gameLog.exists()) AppPaths.gameLog.delete()
        File(AppPaths.logs, "jvm.log").delete()
        File(AppPaths.logs, "fabric.log").delete()
        File(AppPaths.game(cfg.minecraftVersion), "logs/latest.log").delete()
        File(AppPaths.logs, "hs_err.log").delete()
        val heap = cmd.firstOrNull { it.startsWith("-Xmx") }.orEmpty()
        PhoneVm.prepare(cmd.drop(1), AppPaths.game(cfg.minecraftVersion), cfg.vsync)
        CwLog.info("Сборка ${CwLog.VERSION}")
        CwLog.info("Запуск на экране телефона: $profileId $heap")
        val intent = Intent(context, GameActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun classpath(version: String, profile: VersionProfile): String {
        val files = linkedSetOf<String>()
        bridgeJars().forEach { files.add(it.absolutePath) }
        for (lib in profile.libraries) {
            if (!lib.download || lib.isNative() || crashesJvm(lib.name) || lib.name.startsWith("org.lwjgl:")) continue
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
            if (lib.name.contains("jemalloc", true) || lib.name.contains("tinyfd", true)) continue
            val jar = File(AppPaths.libs(version), lib.path)
            if (!jar.isFile) continue
            ZipFile(jar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.startsWith("META-INF")) continue
                    val simple = entry.name.substringAfterLast('/')
                    if (!simple.endsWith(".so") || simple.endsWith(".so.git")) continue
                    val out = File(natives, simple)
                    if (!out.canonicalPath.startsWith(natives.canonicalPath)) continue
                    val part = File(natives, "$simple.part")
                    zip.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                    if (!elfMatchesDevice(part) || linksLinuxGlibc(part)) {
                        part.delete()
                        CwLog.warn("Пропущена библиотека, она роняет Java: $simple")
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
        if (count == 0) CwLog.info("Библиотеки Linux из Minecraft пропущены. Экран телефона рисуют свои графические библиотеки.")
        return natives
    }

    private fun crashesJvm(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n.contains("native-epoll") || n.contains("native-kqueue") || n.contains("native-io_uring")
            || n.contains("tcnative") || n.contains("jemalloc") || n.contains("tinyfd")
    }

    private fun jreLibraryPath(): String {
        val home = BundledJava.home()
        return listOf(
            context.applicationInfo.nativeLibraryDir,
            File(home, "lib/server").absolutePath,
            File(home, "lib").absolutePath
        ).joinToString(":")
    }

    private fun bridgeJars(): List<File> {
        val dir = File(AppPaths.root, "bridge")
        dir.mkdirs()
        val names = listOf(
            "cw-boot.jar",
            "cw-glfw.jar",
            "lwjgl-3.3.3.jar",
            "lwjgl-merged.jar",
            "lwjgl-stb.jar",
            "lwjgl-openal.jar",
            "lwjgl-freetype.jar"
        )
        return names.map { name ->
            val out = File(dir, name)
            if (name == "cw-boot.jar" || name == "cw-glfw.jar" || !out.isFile || out.length() < 1000) {
                context.assets.open("bridge/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            out
        }
    }

    private fun addJoin(game: MutableList<String>, version: String) {
        val raw = cfg.serverAddress.trim().ifBlank { cfg.serverIp.trim() }
        if (raw.isBlank()) return
        if (game.any { it == "--quickPlayMultiplayer" || it == "--server" }) return
        val host = if (raw.contains(":")) raw.substringBeforeLast(":") else raw
        val port = if (raw.contains(":")) raw.substringAfterLast(":") else "25565"
        val minor = version.substringAfter(".").substringBefore(".").substringBefore("-").toIntOrNull() ?: 0
        val major = version.substringBefore(".").toIntOrNull() ?: 1
        if (major > 1 || minor >= 20) {
            game.add("--quickPlayMultiplayer")
            game.add(if (raw.contains(":")) raw else "$host:$port")
        } else {
            game.add("--server")
            game.add(host)
            game.add("--port")
            game.add(port)
        }
    }

    private fun linksLinuxGlibc(file: File): Boolean {
        val data = file.readBytes()
        val marker = "libc.so.6".toByteArray(Charsets.US_ASCII)
        if (data.size < marker.size) return false
        for (i in 0..data.size - marker.size) {
            var same = true
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) {
                    same = false
                    break
                }
            }
            if (same) return true
        }
        return false
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

    private fun hostileJvm(arg: String): Boolean {
        return arg.contains("G1") || arg.contains("UseStringDeduplication") || arg.contains("MaxGCPauseMillis")
            || arg.contains("UnlockExperimentalVMOptions") || arg.contains("AlwaysPreTouch")
            || arg.contains("UseLargePages") || arg.contains("UseNUMA") || arg.contains("UseTransparentHugePages")
            || arg.contains("UseParallelGC") || arg.contains("UseZGC") || arg.contains("UseShenandoah")
            || arg.contains("UseCompressedOops") || arg.contains("UseLSE") || arg.contains("UseSVE")
            || arg.contains("UseAES") || arg.contains("UseSHA") || arg.contains("UseFMA")
            || arg.contains("UseCRC32") || arg.contains("TieredStopAtLevel") || arg.contains("UseContainerSupport")
    }

    private fun memoryFlags(): List<String> = listOf(
        "-XX:+UseSerialGC",
        "-XX:+DisableExplicitGC",
        "-XX:+PerfDisableSharedMem",
        "-XX:ErrorFile=${File(AppPaths.logs, "hs_err.log").absolutePath}"
    )

    private fun putFlag(args: MutableList<String>, flag: String, value: String?) {
        if (args.any { it == flag }) return
        args.add(flag)
        if (value != null) args.add(value)
    }
}
