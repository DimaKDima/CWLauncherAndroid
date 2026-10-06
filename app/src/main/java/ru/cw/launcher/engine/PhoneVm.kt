package ru.cw.launcher.engine

import android.app.Activity
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import java.util.concurrent.CountDownLatch
import com.oracle.dalvik.VMLauncher
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge
import org.json.JSONArray
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Runs Minecraft in the game process and draws it on the phone screen.
 * The status files are shared with the launcher process.
 */
object PhoneVm {
    enum class Stage { IDLE, STARTING, RUNNING, EXITED }

    @Volatile var exitCode: Int = 0
    @Volatile var error: String? = null

    private var args: List<String> = emptyList()
    private var gameDir: File = File(".")
    private var forceVsync: Boolean = false

    fun prepare(jvmArgs: List<String>, dir: File, vsync: Boolean) {
        args = jvmArgs
        gameDir = dir
        forceVsync = vsync
        exitCode = 0
        error = null
        val saved = JSONArray()
        jvmArgs.forEach { saved.put(it) }
        file("args.json").writeText(saved.toString())
        file("gamedir").writeText(dir.absolutePath)
        file("vsync").writeText(if (vsync) "1" else "0")
        write("stage", Stage.STARTING.name)
        write("code", "0")
        write("error", "")
        write("pid", "0")
        write("beat", System.currentTimeMillis().toString())
    }

    fun stage(): Stage {
        val raw = read("stage")
        return try {
            if (raw.isBlank()) Stage.IDLE else Stage.valueOf(raw)
        } catch (_: Exception) {
            Stage.IDLE
        }
    }

    fun pid(): Int = read("pid").toIntOrNull() ?: 0

    fun beat(): Long = read("beat").toLongOrNull() ?: 0L

    fun storedCode(): Int = read("code").toIntOrNull() ?: 0

    fun storedError(): String = read("error")

    fun beatNow() {
        write("beat", System.currentTimeMillis().toString())
    }

    fun mark(stage: Stage, code: Int, message: String?) {
        write("code", code.toString())
        write("error", message.orEmpty().replace('\n', ' '))
        write("stage", stage.name)
        write("beat", System.currentTimeMillis().toString())
    }

    fun launch(context: Context, surface: Surface, width: Int, height: Int): Int {
        if (!surface.isValid) throw IOException("Поверхность окна игры не готова")
        AppPaths.init(context)
        loadSaved()
        thread(name = "cw-beat", isDaemon = true) {
            while (true) {
                try {
                    beatNow()
                } catch (_: Exception) {
                }
                Thread.sleep(1000)
            }
        }
        val home = BundledJava.ensure(context)
        val jvm = File(home, "lib/server/libjvm.so")
        if (!jvm.isFile) throw IOException("В Java 17 нет lib/server/libjvm.so")
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val jreLib = File(home, "lib").absolutePath
        val jvmLib = jvm.parentFile?.absolutePath ?: throw IOException("В Java 17 нет папки lib/server")
        val systemLibs = if (translatedPc()) {
            listOf("/system/lib64/arm64", "/system/lib64/houdini/arm64", "/system/lib64")
        } else {
            listOf("/system/lib64")
        }
        val ld = listOf(jvmLib, jreLib, nativeDir)
            .plus(systemLibs)
            .plus(listOf("/vendor/lib64", "/vendor/lib64/hw"))
            .joinToString(":")
        set("JAVA_HOME", home.absolutePath)
        set("HOME", AppPaths.root.absolutePath)
        set("TMPDIR", AppPaths.tmp.absolutePath)
        set("POJAV_NATIVEDIR", nativeDir)
        set("AMETHYST_RENDERER", "opengles3")
        set("POJAV_RENDERER", "opengles3")
        set("LIBGL_ES", "3")
        set("LIBGL_MIPMAP", "3")
        set("LIBGL_NOERROR", "1")
        set("LIBGL_NORMALIZE", "1")
        set("LIBGL_NOINTOVLHACK", "1")
        set("LIBGL_NOPSA", "1")
        set("LIBGL_GLXRECYCLE", "0")
        set("FORCE_VSYNC", if (forceVsync) "true" else "false")
        set("MESA_GLSL_CACHE_DIR", AppPaths.cache.absolutePath)
        set("LD_LIBRARY_PATH", ld)
        set("PATH", File(home, "bin").absolutePath + ":" + (Os.getenv("PATH") ?: ""))
        // The screen bridge must be loaded first. Its native methods are registered
        // only after that, and setLdLibraryPath is one of them.
        CallbackBridge.setContext(context)
        CallbackBridge.prepare()
        CwLog.info("Графический мост открыт")
        JREUtils.setLdLibraryPath("$jvmLib:$ld")
        prepareGraphics()
        openGraphics()
        open(File(home, "lib/libjli.so").absolutePath, true)
        openJvm(jvm, jvmLib)
        val gl4es = File(nativeDir, "libng_gl4es.so")
        if (!gl4es.isFile) throw IOException("Не открылась графическая библиотека libng_gl4es.so")
        open(File(nativeDir, "libopenal.so").absolutePath, false)
        enterGameDir()
        setupWindow(context, surface, width, height)
        patchOptions(width, height)
        enterGameDir()
        write("pid", android.os.Process.myPid().toString())
        mark(Stage.RUNNING, 0, null)
        CwLog.info("Окно игры ${width}x${height}, Java внутри приложения")
        redirectJvmOutput()
        return VMLauncher.launchJVM(sized(width, height))
    }

    private fun enterGameDir() {
        if (!gameDir.isDirectory && !gameDir.mkdirs()) {
            throw IOException("Нет папки игры: ${gameDir.absolutePath}")
        }
        if (JREUtils.chdir(gameDir.absolutePath) != 0) {
            throw IOException("Не удалось открыть папку игры: ${gameDir.absolutePath}")
        }
        set("PWD", gameDir.absolutePath)
        CwLog.info("Папка игры: ${gameDir.absolutePath}")
    }

    private fun setupWindow(context: Context, surface: Surface, width: Int, height: Int) {
        val failure = arrayOfNulls<Throwable>(1)
        val task = Runnable {
            try {
                JREUtils.setupBridgeWindow(surface)
                CallbackBridge.sendScreenSize(width, height)
            } catch (t: Throwable) {
                failure[0] = t
            }
        }
        if (context is Activity) {
            val latch = CountDownLatch(1)
            context.runOnUiThread {
                try {
                    task.run()
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
        } else {
            task.run()
        }
        val error = failure[0]
        if (error != null) throw error
    }

    private fun redirectJvmOutput() {
        try {
            val log = File(AppPaths.logs, "jvm.log")
            log.parentFile?.mkdirs()
            if (log.exists()) log.delete()
            val flags = OsConstants.O_CREAT or OsConstants.O_WRONLY or OsConstants.O_TRUNC
            val fd = Os.open(log.absolutePath, flags, 420)
            Os.dup2(fd, 1)
            Os.dup2(fd, 2)
            Os.close(fd)
        } catch (e: Exception) {
            CwLog.warn("Журнал Java не открылся: ${e.message}")
        }
    }

    private fun sized(width: Int, height: Int): Array<String> {
        val out = args.toMutableList()
        fun prop(prefix: String, value: String) {
            val line = prefix + value
            val index = out.indexOfFirst { it.startsWith(prefix) }
            if (index >= 0) out[index] = line else out.add(line)
        }
        prop("-Dglfwstub.windowWidth=", width.toString())
        prop("-Dglfwstub.windowHeight=", height.toString())
        prop("-Dglfwstub.initEgl=", "false")
        fun flag(name: String, value: String) {
            val index = out.indexOf(name)
            if (index >= 0 && index + 1 < out.size) out[index + 1] = value
            else {
                out.add(name)
                out.add(value)
            }
        }
        flag("--width", width.toString())
        flag("--height", height.toString())
        out.removeAll { it == "--fullscreen" }
        out.add(0, "java")
        return out.toTypedArray()
    }

    private fun patchOptions(width: Int, height: Int) {
        val options = File(gameDir, "options.txt")
        val values = linkedMapOf<String, String>()
        if (options.isFile) {
            options.readLines().forEach { line ->
                val cut = line.indexOf(':')
                if (cut > 0) values[line.substring(0, cut)] = line.substring(cut + 1)
            }
        }
        values["fullscreen"] = "false"
        values["overrideWidth"] = width.toString()
        values["overrideHeight"] = height.toString()
        options.writeText(values.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
    }

    private var eglPath: String? = null
    private var glesPath: String? = null

    private fun prepareGraphics() {
        eglPath = chooseGraphics("LIBGL_EGL", "libEGL.so")
        glesPath = chooseGraphics("LIBGL_GLES", "libGLESv3.so") ?: chooseGraphics("LIBGL_GLES", "libGLESv2.so")
        if (eglPath == null || glesPath == null) {
            set("LIBGL_NOTEST", "1")
            CwLog.warn("Системная графика не найдена, проверка видеокарты пропущена")
        }
    }

    private fun openGraphics() {
        listOf(eglPath, glesPath).forEach { path ->
            if (path.isNullOrBlank()) return@forEach
            val ok = try {
                JREUtils.dlopen(path)
            } catch (t: Throwable) {
                CwLog.warn("dlopen $path: ${t.message}")
                false
            }
            CwLog.info("Открыта графика $path: ${if (ok) "да" else "нет"}")
            if (!ok) set("LIBGL_NOTEST", "1")
        }
    }

    private fun chooseGraphics(env: String, name: String): String? {
        val path = graphicsCandidates(name).firstOrNull()
        if (path == null) {
            CwLog.warn("Нет файла $name")
            return null
        }
        CwLog.info("Графика $env=$path")
        set(env, path)
        return path
    }

    private fun graphicsCandidates(name: String): List<String> {
        val paths = ArrayList<String>()
        if (translatedPc()) {
            paths.add("/system/lib64/arm64/$name")
            paths.add("/system/lib64/houdini/arm64/$name")
        } else {
            paths.add("/system/lib64/$name")
            paths.add("/system/lib/$name")
            paths.add(name)
        }
        return paths.distinct().filter { path -> path == name || File(path).isFile }
    }

    private fun translatedPc(): Boolean {
        return File("/system/lib64/libhoudini.so").isFile || File("/system/lib/libhoudini.so").isFile
    }

    private fun set(key: String, value: String) {
        try {
            Os.setenv(key, value, true)
        } catch (e: ErrnoException) {
            CwLog.warn("Среда $key не записалась: ${e.message}")
        }
    }

    private fun openJvm(jvm: File, jvmLib: String) {
        val names = listOf("libcwshmem-arm64.so", "libandroid-shmem.so")
        for (name in names) {
            loadFile(File(jvmLib, name))
        }
        try {
            System.loadLibrary("cwshmem-arm64")
            CwLog.info("Память Java из приложения: да")
        } catch (e: UnsatisfiedLinkError) {
            CwLog.warn("Память Java из приложения: ${e.message}")
        }
        try {
            System.load(jvm.absolutePath)
            CwLog.info("Библиотека libjvm.so: да")
            return
        } catch (e: UnsatisfiedLinkError) {
            val text = e.message.orEmpty()
            if (text.contains("already", true)) {
                CwLog.info("Библиотека libjvm.so: уже открыта")
                return
            }
            CwLog.warn("System.load libjvm: $text")
        }
        if (!tryOpen(jvm.absolutePath)) {
            throw IOException("Не открылась библиотека libjvm.so")
        }
    }

    private fun loadFile(file: File) {
        if (!file.isFile) return
        try {
            System.load(file.absolutePath)
            CwLog.info("Библиотека ${file.name}: да")
        } catch (e: UnsatisfiedLinkError) {
            val text = e.message.orEmpty()
            if (text.contains("already", true)) {
                CwLog.info("Библиотека ${file.name}: уже открыта")
            } else {
                CwLog.warn("Библиотека ${file.name}: $text")
                tryOpen(file.absolutePath)
            }
        }
    }

    private fun tryOpen(path: String): Boolean {
        return try {
            val ok = JREUtils.dlopen(path)
            CwLog.info("Библиотека ${File(path).name}: ${if (ok) "да" else "нет"}")
            ok
        } catch (e: Throwable) {
            CwLog.warn("dlopen ${File(path).name}: ${e.message}")
            false
        }
    }

    private fun open(path: String, required: Boolean) {
        if (!tryOpen(path) && required) throw IOException("Не открылась библиотека ${File(path).name}")
    }

    private fun loadSaved() {
        val text = file("args.json").readText()
        val saved = JSONArray(text)
        args = (0 until saved.length()).map { saved.getString(it) }
        gameDir = File(file("gamedir").readText().trim())
        forceVsync = file("vsync").readText().trim() == "1"
        if (args.isEmpty()) throw IOException("Команда запуска игры пустая")
    }

    private fun file(name: String): File {
        val dir = File(AppPaths.root, "phone")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, name)
    }

    private fun write(name: String, value: String) {
        val target = file(name)
        val part = File(target.parentFile, "$name.part")
        part.writeText(value)
        if (!part.renameTo(target)) {
            target.writeText(value)
            part.delete()
        }
    }

    private fun read(name: String): String {
        val target = file(name)
        return if (target.isFile) target.readText().trim() else ""
    }
}
