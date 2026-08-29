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
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", "FreeRouter/0.2")
        setRequestProperty("Accept", "application/json")
        headers?.forEach { (k, v) -> setRequestProperty(k, v) }
    }
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
    return Response(status, bytes)
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
