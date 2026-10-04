package ru.cw.launcher

import android.app.Application
import ru.cw.launcher.engine.Engine

class CwApplication : Application() {
    lateinit var engine: Engine
        private set

    override fun onCreate() {
        super.onCreate()
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
