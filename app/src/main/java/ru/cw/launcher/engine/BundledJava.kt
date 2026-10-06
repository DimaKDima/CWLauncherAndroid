package ru.cw.launcher.engine

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object BundledJava {
    private const val ASSET = "jre17-arm64.zip"
    private const val STAMP = "jre17-17.0.20d"
    private const val ABI = "arm64-v8a"

    fun home(): File = File(AppPaths.root, "jre/17")

    fun supported(): Boolean = Build.SUPPORTED_ABIS.contains(ABI)

    fun launcher(context: Context): File? {
        if (!supported()) return null
        val bin = File(context.applicationInfo.nativeLibraryDir, "libcwjava.so")
        return bin.takeIf { it.isFile }
    }

    fun ensure(context: Context, onProgress: ((String) -> Unit)? = null): File {
        if (!supported()) {
            throw java.io.IOException(
                "Встроенная Java 17 собрана для arm64. Этот процессор: ${Build.SUPPORTED_ABIS.joinToString()}"
            )
        }
        val dir = home()
        val marker = File(dir, ".cw-jre")
        val modules = File(dir, "lib/modules")
        val ready = marker.isFile && marker.readText() == STAMP && modules.isFile && modules.length() > 1_000_000
        if (!ready) {
            onProgress?.invoke("Распаковка Java 17")
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            var count = 0
            context.assets.open(ASSET).use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (name.isBlank() || name.startsWith("/") || name.contains("..") || name.contains(':')) {
                            zip.closeEntry()
                            continue
                        }
                        val out = File(dir, name)
                        val rootPath = dir.canonicalPath + File.separator
                        if (!out.canonicalPath.startsWith(rootPath)) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory || name.endsWith("/")) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                            if (name.startsWith("bin/") || name.endsWith(".so") || name.endsWith("jspawnhelper")) {
                                out.setExecutable(true, false)
                            }
                        }
                        count++
                        if (count % 40 == 0) onProgress?.invoke("Распаковка Java 17 · $count")
                        zip.closeEntry()
                    }
                }
            }
            if (!modules.isFile) throw java.io.IOException("В архиве Java нет lib/modules")
            marker.writeText(STAMP)
            CwLog.info("Встроенная Java 17 распакована в ${dir.absolutePath}")
        }
        linkExecutables(context, dir)
        prepareLibraries(context, dir)
        hidePackagedShmem(context)
        return dir
    }

    fun libraryPath(context: Context, home: File): String {
        return listOf(
            File(home, "lib/server").absolutePath,
            File(home, "lib").absolutePath
        ).joinToString(":")
    }

    private fun prepareLibraries(context: Context, home: File) {
        val marker = File(home, ".cw-libs")
        val cxx = File(home, "lib/libc++_shared.so")
        if (marker.isFile && marker.readText() == "4" && cxx.isFile && cxx.length() > 100_000) return
        val oldName = "libandroid-shmem.so".toByteArray(Charsets.US_ASCII)
        val newName = "libcwshmem-arm64.so".toByteArray(Charsets.US_ASCII)
        check(oldName.size == newName.size)
        home.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { file ->
            val data = file.readBytes()
            val replaced = replaceBytes(data, oldName, newName) ?: return@forEach
            file.writeBytes(replaced)
        }
        for (rel in listOf("lib", "lib/server")) {
            val dir = File(home, rel)
            renameIfExists(File(dir, "libandroid-shmem.so"), File(dir, "libcwshmem-arm64.so"))
            restoreZlib(dir)
        }
        cxx.parentFile?.mkdirs()
        context.assets.open("libc++_shared.so").use { input ->
            cxx.outputStream().use { input.copyTo(it) }
        }
        cxx.setExecutable(true, false)
        copyAsset(context, "jre-extra/fontconfig.bfc", File(home, "lib/fontconfig.bfc"))
        copyAsset(context, "jre-extra/fontconfig.properties", File(home, "lib/fontconfig.properties"))
        val extraLibs = listOf(
            "libjpeg.so.8",
            "libspeexdsp.so",
            "libpulse.so",
            "libpulsecommon-17.0.so",
            "libdbus-1.so",
            "libandroid-execinfo.so",
            "libsndfile.so",
            "libFLAC.so",
            "libogg.so",
            "libopus.so",
            "libvorbis.so",
            "libvorbisenc.so",
            "libmp3lame.so",
            "libmpg123.so"
        )
        var libsReady = true
        for (name in extraLibs) {
            val out = File(home, "lib/$name")
            copyAsset(context, "jre-extra/$name", out)
            if (!out.isFile || out.length() < 1_000) {
                libsReady = false
                CwLog.warn("Не скопировалась библиотека $name")
            } else {
                out.setExecutable(true, false)
            }
        }
        if (!libsReady) return
        marker.writeText("4")
        CwLog.info("Библиотеки Java подготовлены для переводчика ПК")
    }

    private fun hidePackagedShmem(context: Context) {
        val leaked = File(context.applicationInfo.nativeLibraryDir, "libandroid-shmem.so")
        if (leaked.isFile && !leaked.delete()) {
            CwLog.warn("Системный каталог всё ещё содержит libandroid-shmem.so")
        }
    }

    private fun restoreZlib(dir: File) {
        val zlib = File(dir, "libz.so")
        val renamed = File(dir, "libcwzlib.so")
        if (!zlib.isFile && renamed.isFile) {
            if (!renamed.renameTo(zlib)) renamed.copyTo(zlib, overwrite = true)
        }
        if (!zlib.isFile) return
        val soname = File(dir, "libz.so.1")
        if (!soname.isFile || soname.length() != zlib.length()) {
            zlib.copyTo(soname, overwrite = true)
            soname.setExecutable(true, false)
        }
    }

    private fun copyAsset(context: Context, asset: String, out: File) {
        out.parentFile?.mkdirs()
        try {
            context.assets.open(asset).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun renameIfExists(from: File, to: File) {
        if (!from.isFile) return
        if (to.exists() && !to.delete()) return
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun replaceBytes(data: ByteArray, old: ByteArray, new: ByteArray): ByteArray? {
        var changed = false
        val out = data.copyOf()
        var i = 0
        while (i <= out.size - old.size) {
            var same = true
            for (j in old.indices) {
                if (out[i + j] != old[j]) {
                    same = false
                    break
                }
            }
            if (same) {
                new.copyInto(out, i)
                changed = true
                i += old.size
            } else {
                i++
            }
        }
        return if (changed) out else null
    }

    private fun linkExecutables(context: Context, dir: File) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        linkOver(File(nativeDir, "libcwjava.so"), File(dir, "bin/java"))
        linkOver(File(nativeDir, "libjspawnhelper.so"), File(dir, "lib/jspawnhelper"))
    }

    private fun linkOver(target: File, link: File) {
        if (!target.isFile) return
        val backup = File(link.parentFile, link.name + ".real")
        try {
            if (link.exists() && !isSymlink(link)) {
                if (backup.exists()) backup.delete()
                if (!link.renameTo(backup)) return
            } else if (link.exists()) {
                link.delete()
            }
            link.parentFile?.mkdirs()
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: Exception) {
            CwLog.warn("Ссылка ${link.name}: ${e.message}")
            if (!link.exists() && backup.exists()) backup.renameTo(link)
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            Os.readlink(file.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }
}
