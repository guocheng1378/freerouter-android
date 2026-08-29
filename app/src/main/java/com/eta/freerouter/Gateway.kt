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
import fi.iki.elonen.NanoHTTPD
import okhttp3.Response as OkResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

class LocalGateway(private val port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        return when {
            uri.endsWith("/health/liveliness") || uri.endsWith("/health/live") ->
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
            uri.endsWith("/v1/models") && method == NanoHTTPD.Method.GET -> handleModels()
            uri.endsWith("/v1/chat/completions") && method == NanoHTTPD.Method.POST -> handleChat(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", "{\"error\":\"not found\"}")
        }
    }

    private fun handleModels(): NanoHTTPD.Response {
        val arr = JSONArray()
        for (p in Catalog.providers) {
            val key = p.credentialEnv?.let { Keys.get(it) }
            if (key.isNullOrEmpty()) continue
            arr.put(JSONObject().put("id", "${p.id}-free").put("object", "model"))
            for (m in Router.candidateModels(p)) {
                arr.put(JSONObject().put("id", "fr/${p.id}/$m").put("object", "model"))
            }
        }
        arr.put(JSONObject().put("id", "free-router").put("object", "model"))
        val root = JSONObject().put("object", "list").put("data", arr)
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", root.toString())
    }

    private fun handleChat(session: IHTTPSession): NanoHTTPD.Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (len > 0) {
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = session.inputStream.read(buf, off, len - off)
                if (r < 0) break
                off += r
            }
            buf
        } else ByteArray(0)
        val reqModel = try {
            JSONObject(String(bodyBytes, Charsets.UTF_8)).optString("model", "free-router")
        } catch (_: Exception) {
            "free-router"
        }
        val targets = Router.resolve(reqModel)
        if (targets.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, "application/json",
                JSONObject().put("error", "no provider configured for model '$reqModel'. Please add API keys in FreeRouter.").toString()
            )
        }
        var lastErr: String? = null
        for (t in targets) {
            try {
                val resp: OkResponse = Router.forward(t, bodyBytes)
                if (resp.isSuccessful) {
                    Router.markHealth(t.provider.id, t.model, true)
                    val mime = resp.header("Content-Type") ?: "application/json"
                    val upstream: InputStream? = resp.body?.byteStream()
                    val r = if (upstream != null) {
                        NanoHTTPD.newChunkedResponse(statusFromCode(resp.code), mime, upstream)
                    } else {
                        NanoHTTPD.newFixedLengthResponse(statusFromCode(resp.code), mime, resp.body?.string() ?: "")
                    }
                    return r
                } else {
                    val msg = resp.body?.string() ?: ""
                    Router.markHealth(t.provider.id, t.model, false)
                    lastErr = "provider ${t.provider.id} model ${t.model}: HTTP ${resp.code} $msg"
                    Log.w("FreeRouter", lastErr)
                }
            } catch (e: Exception) {
                Router.markHealth(t.provider.id, t.model, false)
                lastErr = "provider ${t.provider.id} model ${t.model}: ${e.message}"
                Log.w("FreeRouter", lastErr, e)
            }
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json",
            JSONObject().put("error", "all candidates failed. last: $lastErr").toString()
        )
    }
}

fun statusFromCode(code: Int): NanoHTTPD.Response.Status = when (code) {
    200 -> NanoHTTPD.Response.Status.OK
    400 -> NanoHTTPD.Response.Status.BAD_REQUEST
    401 -> NanoHTTPD.Response.Status.UNAUTHORIZED
    403 -> NanoHTTPD.Response.Status.FORBIDDEN
    404 -> NanoHTTPD.Response.Status.NOT_FOUND
    429 -> NanoHTTPD.Response.Status.BAD_REQUEST
    in 500..599 -> NanoHTTPD.Response.Status.INTERNAL_ERROR
    else -> NanoHTTPD.Response.Status.OK
}

class RouterService : Service() {
    private var gateway: LocalGateway? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification("FreeRouter 运行中 · 127.0.0.1:4000"))
        try {
            gateway = LocalGateway(PORT).apply { start() }
        } catch (e: Exception) {
            Log.e("FreeRouter", "start gateway failed", e)
        }
    }

    override fun onDestroy() {
        gateway?.stop()
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

    companion object {
        const val PORT = 4000
    }
}
