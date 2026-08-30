package com.eta.freerouter

import android.util.Log
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.transport.Response as EngineResponse
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

class LocalGateway(private val port: Int, private val engine: FreeRouterEngine) : NanoHTTPD(port) {
    companion object { const val PORT = 4000 }

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        return when {
            uri.endsWith("/health/liveliness") || uri.endsWith("/health/live") ->
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"status":"ok"}""")
            uri.endsWith("/v1/models") && method == NanoHTTPD.Method.GET -> handleModels()
            uri.endsWith("/spend/logs") && method == NanoHTTPD.Method.GET -> handleSpendLogs()
            uri.endsWith("/v1/chat/completions") && method == NanoHTTPD.Method.POST -> handleChat(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }

    private fun handleModels(): NanoHTTPD.Response {
        val arr = JSONArray()
        for (id in engine.listModels()) arr.put(JSONObject().put("id", id).put("object", "model"))
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", JSONObject().put("object", "list").put("data", arr).toString())
    }

    private fun handleSpendLogs(): NanoHTTPD.Response {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val arr = JSONArray()
        for (c in engine.traffic.recentCalls(200)) {
            arr.put(JSONObject().apply {
                put("startTime", fmt.format(java.util.Date(c.time)))
                put("status", if (c.ok) "success" else "failed")
                put("model_group", c.alias)
                put("model", c.realModel)
                put("request_duration_ms", c.durationMs)
                put("prompt_tokens", c.promptTokens)
                put("completion_tokens", c.completionTokens)
                put("is_probe", c.isProbe)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", arr.toString())
    }

    private fun handleChat(session: IHTTPSession): NanoHTTPD.Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len); var off = 0
            while (off < len) { val r = session.inputStream.read(buf, off, len - off); if (r < 0) break; off += r }
            buf
        } else ByteArray(0)
        val bodyStr = String(body, StandardCharsets.UTF_8)
        val reqModel = try { JSONObject(bodyStr).optString("model", "free-router") } catch (_: Exception) { "free-router" }
        val stream = try { JSONObject(bodyStr).optBoolean("stream", false) } catch (_: Exception) { false }
        if (!stream) {
            val resp: EngineResponse = engine.routeChat(reqModel, bodyStr)
            return NanoHTTPD.newFixedLengthResponse(statusFromCode(resp.status), "application/json", resp.text)
        }
        val pipedIn = PipedInputStream(16384)
        val pipedOut = PipedOutputStream(pipedIn)
        val resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", pipedIn)
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")
        Thread {
            try {
                engine.routeChatStreaming(reqModel, bodyStr) { chunk ->
                    try { pipedOut.write(chunk.toByteArray(StandardCharsets.UTF_8)); pipedOut.flush() } catch (_: Exception) {}
                }
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }.start()
        return resp
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
