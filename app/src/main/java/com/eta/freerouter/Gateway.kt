package com.eta.freerouter

import android.util.Log
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.transport.Response as EngineResponse
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class LocalGateway(private val port: Int, private val engine: FreeRouterEngine) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method
        return when {
            uri.endsWith("/health/liveliness") || uri.endsWith("/health/live") ->
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", """{"status":"ok"}""")
            uri.endsWith("/v1/models") && method == NanoHTTPD.Method.GET -> handleModels()
            uri.endsWith("/v1/chat/completions") && method == NanoHTTPD.Method.POST -> handleChat(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
        }
    }

    private fun handleModels(): NanoHTTPD.Response {
        val arr = JSONArray()
        for (id in engine.listModels()) arr.put(JSONObject().put("id", id).put("object", "model"))
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", JSONObject().put("object", "list").put("data", arr).toString())
    }

    private fun handleChat(session: IHTTPSession): NanoHTTPD.Response {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = ByteArray(len); var off = 0
            while (off < len) { val r = session.inputStream.read(buf, off, len - off); if (r < 0) break; off += r }
            buf
        } else ByteArray(0)
        val reqModel = try { JSONObject(String(body, Charsets.UTF_8)).optString("model", "free-router") } catch (_: Exception) { "free-router" }
        val resp: EngineResponse = engine.routeChat(reqModel, String(body, Charsets.UTF_8))
        return NanoHTTPD.newFixedLengthResponse(statusFromCode(resp.status), "application/json", resp.text)
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
