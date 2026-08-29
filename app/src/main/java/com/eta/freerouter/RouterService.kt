package com.eta.freerouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.registry.loadProviders

class RouterService : Service() {
    private var gateway: LocalGateway? = null
    private var engine: FreeRouterEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification("FreeRouter 运行中 · 127.0.0.1:4000"))
        try {
            val catalogJson = assets.open("catalog.json").bufferedReader().use { it.readText() }
            val secrets = mutableMapOf<String, String>()
            for (p in loadProviders(catalogJson)) {
                p.credential?.let { env -> Keys.get(env)?.let { k -> if (k.isNotBlank()) secrets[env] = k } }
            }
            engine = FreeRouterEngine(catalogJson, secrets)
            engine!!.start()
            gateway = LocalGateway(PORT, engine!!).apply { start() }
        } catch (e: Exception) {
            Log.e("FreeRouter", "start gateway failed", e)
        }
    }

    override fun onDestroy() {
        engine?.stop()
        gateway?.stop()
        engine = null
        gateway = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val ch = "freerouter_chan"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(NotificationChannel(ch, "FreeRouter", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("FreeRouter")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    companion object { const val PORT = 4000 }
}
