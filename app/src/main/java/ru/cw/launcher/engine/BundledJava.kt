package ru.cw.launcher.engine

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object BundledJava {
    private const val ASSET = "jre17-arm64.zip"
    private const val STAMP = "jre17-ec28559"
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
        return dir
    }

    fun libraryPath(context: Context, home: File): String {
        return listOf(
            File(home, "lib/server").absolutePath,
            File(home, "lib").absolutePath,
            context.applicationInfo.nativeLibraryDir
        ).joinToString(":")
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
