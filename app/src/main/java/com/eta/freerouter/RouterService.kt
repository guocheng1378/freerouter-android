package com.eta.freerouter

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders

class RouterService : Service() {
    private var engine: FreeRouterEngine? = null
    private var gateway: LocalGateway? = null
    companion object { var engineRef: FreeRouterEngine? = null }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val catalogJson = assets.open("catalog.json").bufferedReader().use { it.readText() }
            val secrets = mutableMapOf<String, String>()
            for (p in loadProviders(catalogJson)) {
                p.credential?.let { env ->
                    val k = Keys.get(env)
                    if (!k.isNullOrBlank()) secrets[env] = k
                }
            }
            engine = FreeRouterEngine(catalogJson, secrets)
            engine!!.start()
            gateway = LocalGateway(LocalGateway.PORT, engine!!).apply { start() }
            engineRef = engine
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        gateway?.stop()
        engine?.stop()
        engineRef = null
        engine = null
        super.onDestroy()
    }
}
