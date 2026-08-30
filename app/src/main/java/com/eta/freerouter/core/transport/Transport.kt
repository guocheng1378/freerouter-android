package com.eta.freerouter.core.transport

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

typealias JsonValue = Any?

data class Response(val status: Int, val bodyBytes: ByteArray) {
    val ok: Boolean get() = status in 200..299
    val text: String get() = String(bodyBytes, StandardCharsets.UTF_8)
    fun json(): JsonValue = try { parseJson(text) } catch (_: Exception) { null }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Response) return false
        return status == other.status && bodyBytes.contentEquals(other.bodyBytes)
    }
    override fun hashCode(): Int = 31 * status + bodyBytes.contentHashCode()
}

fun request(
    url: String,
    method: String = "GET",
    headers: Map<String, String>? = null,
    body: String? = null,
    timeoutMs: Int = 30000,
): Response {
    val conn = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "FreeRouter/0.2")
            setRequestProperty("Accept", "application/json")
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    } catch (_: Exception) {
        return Response(0, ByteArray(0))
    }
    return try {
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = try { conn.responseCode } catch (_: Exception) { 0 }
        val bytes = try {
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (_: Exception) { ByteArray(0) }
        Response(status, bytes)
    } catch (_: Exception) {
        Response(0, ByteArray(0))
    }
}

// 流式请求：逐事件（以空行分隔的 SSE 块）回调 onEvent。返回最终 HTTP 状态码。
// 非 2xx 或连接失败均构造 data:{"error":...} 事件回调，便于外层做 failover 判断。
fun requestStreaming(
    url: String,
    method: String = "POST",
    headers: Map<String, String>? = null,
    body: String? = null,
    timeoutMs: Int = 90000,
    onEvent: (String) -> Unit,
): Int {
    val conn = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", "FreeRouter/0.2")
            setRequestProperty("Accept", "text/event-stream, application/json")
            headers?.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    } catch (_: Exception) {
        emitStreamError(onEvent, "connect failed: $url")
        return 0
    }
    return try {
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val status = try { conn.responseCode } catch (_: Exception) { 0 }
        if (status !in 200..299) {
            val err = try { conn.errorStream?.use { it.readBytes() }?.toString(StandardCharsets.UTF_8) } catch (_: Exception) { null }
            val msg = if (!err.isNullOrBlank()) err else "http $status"
            emitStreamError(onEvent, msg)
            return status
        }
        try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val l = line ?: ""
                    if (l.isEmpty()) {
                        if (sb.isNotEmpty()) { onEvent(sb.toString() + "\n"); sb.setLength(0) }
                    } else {
                        sb.append(l).append('\n')
                    }
                }
                if (sb.isNotEmpty()) onEvent(sb.toString() + "\n")
            }
        } catch (_: Exception) { /* 连接中途断开等，忽略 */ }
        status
    } catch (_: Exception) {
        emitStreamError(onEvent, "connect failed: $url")
        0
    }
}
private fun emitStreamError(onEvent: (String) -> Unit, msg: String) {
    try { onEvent("data: " + JSONObject().put("error", msg).toString() + "\n\n") } catch (_: Exception) {}
}

// 判断一段 SSE data 是否为 OpenAI 错误体（应触发 failover）
fun isErrorEvent(text: String): Boolean {
    return try {
        val t = text.trim()
        val json = if (t.startsWith("data:")) t.removePrefix("data:").trim() else t
        val o = JSONObject(json)
        o.has("error") && !o.has("choices")
    } catch (_: Exception) { false }
}

fun parseJson(text: String): JsonValue {
    val t = text.trim()
    return when {
        t.isEmpty() -> null
        t.startsWith("{") -> JSONObject(t)
        t.startsWith("[") -> JSONArray(t)
        else -> null
    }
}

fun dig(value: JsonValue, path: String): JsonValue {
    var cur: Any? = value
    for (key in path.split(".")) {
        cur = when (cur) {
            is JSONObject -> if (cur.has(key)) cur.get(key) else null
            else -> null
        }
    }
    return cur
}

fun asItems(v: JsonValue): List<JsonValue> =
    if (v is JSONArray) (0 until v.length()).map { v.get(it) } else emptyList()
fun asText(v: JsonValue): String? = if (v is String) v else null
fun asStrings(v: JsonValue): List<String> = asItems(v).mapNotNull { if (it is String) it else null }
fun asPrice(v: JsonValue): Double? = when (v) {
    is Boolean, null -> null
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}
