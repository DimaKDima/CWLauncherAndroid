package ru.cw.launcher

import android.app.Application
import android.os.Build
import ru.cw.launcher.engine.AppPaths
import ru.cw.launcher.engine.Engine
import java.io.File

class CwApplication : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
        val process = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
            } catch (_: Exception) {
                ""
            }
        }
        if (process.endsWith(":game")) {
            AppPaths.init(this)
            return
        }
        System.setProperty("http.keepAlive", "false")
        System.setProperty("http.maxConnections", "4")
        instance = this
        engine = Engine(this)
        engine.start()
    }

    companion object {
        lateinit var instance: CwApplication
            private set
    }
}
