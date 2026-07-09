package com.nekobot.app

import android.app.Application
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.remote.NetworkClient
import com.nekobot.app.data.remote.SocketManager
import com.nekobot.app.data.repository.NekobotRepository

/**
 * 全局依赖容器：单例持有 Prefs / Network / Repository / Gson。
 */
object ServiceContainer {
    lateinit var prefs: PrefsManager
        private set
    lateinit var network: NetworkClient
        private set
    lateinit var repository: NekobotRepository
        private set
    lateinit var socket: SocketManager
        private set
    val gson: Gson = GsonBuilder().setLenient().disableHtmlEscaping().create()

    fun init(app: Application) {
        prefs = PrefsManager(app)
        network = NetworkClient(prefs)
        repository = NekobotRepository(network, prefs)
        socket = SocketManager(prefs)
    }

    fun rebuildNetwork() {
        network.rebuild()
    }
}

class NekobotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceContainer.init(this)
    }
}
