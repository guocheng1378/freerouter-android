package com.eta.freerouter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders

class RouterService : Service() {
    private var engine: FreeRouterEngine? = null
    private var gateway: LocalGateway? = null
    companion object {
        var engineRef: FreeRouterEngine? = null
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "freerouter_gateway"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
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
            val sp = getSharedPreferences("fr_state", MODE_PRIVATE)
            engine!!.defaultModel = sp.getString("default_model", "free-router") ?: "free-router"
            engine!!.freeRouterEnabled = sp.getBoolean("fr_enabled", true)
            sp.all.forEach { (k, vv) -> if (k.startsWith("m:")) engine!!.modelEnabled[k.removePrefix("m:")] = (vv as? Boolean) ?: true }
            sp.all.forEach { (k, vv) -> if (k.startsWith("wl:")) (vv as? String)?.let { engine!!.manualFree.add(it) } }
            engine!!.start()
            gateway = LocalGateway(LocalGateway.PORT, engine!!).apply { start() }
            engineRef = engine
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundSafely() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL_ID, "FreeRouter 网关", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            }
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FreeRouter 网关运行中")
                .setContentText("监听 http://127.0.0.1:4000/v1")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        gateway?.stop()
        engine?.stop()
        engineRef = null
        engine = null
        super.onDestroy()
    }
}
