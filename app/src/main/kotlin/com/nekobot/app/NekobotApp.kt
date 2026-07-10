package com.nekobot.app

import android.app.Application
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.ai.LocalAiClient
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.data.local.LocalRepository
import com.nekobot.app.data.remote.NetworkClient
import com.nekobot.app.data.remote.SocketManager
import com.nekobot.app.data.repository.NekobotRepository
import com.nekobot.app.data.repository.UnifiedRepository

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
    lateinit var unified: UnifiedRepository
        private set
    lateinit var localRepository: LocalRepository
        private set
    lateinit var socket: SocketManager
        private set
    val gson: Gson = GsonBuilder().setLenient().disableHtmlEscaping().create()

    fun init(app: Application) {
        prefs = PrefsManager(app)
        network = NetworkClient(prefs)
        repository = NekobotRepository(network, prefs)
        val db = NekobotDatabase.get(app)
        localRepository = LocalRepository(db, LocalAiClient(), app)
        unified = UnifiedRepository(prefs, repository, localRepository, app)
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
