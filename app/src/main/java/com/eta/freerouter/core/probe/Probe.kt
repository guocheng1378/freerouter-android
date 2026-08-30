package com.eta.freerouter.core.probe

import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.state.ProbeOutcome
import com.eta.freerouter.core.transport.*
import org.json.JSONArray
import org.json.JSONObject

private val STATUS_OUTCOMES = mapOf(
    401 to ProbeOutcome.AUTH,
    402 to ProbeOutcome.EXHAUSTED,
    403 to ProbeOutcome.AUTH,
    404 to ProbeOutcome.MISSING,
    429 to ProbeOutcome.THROTTLED,
)

private val KEYWORD_OUTCOMES = listOf(
    "model_not_found" to ProbeOutcome.MISSING,
    "not found" to ProbeOutcome.MISSING,
    "does not exist" to ProbeOutcome.MISSING,
    "已下线" to ProbeOutcome.MISSING,
    "不存在" to ProbeOutcome.MISSING,
    "access_denied" to ProbeOutcome.AUTH,
    "forbidden" to ProbeOutcome.AUTH,
    "鉴权" to ProbeOutcome.AUTH,
    "insufficient" to ProbeOutcome.EXHAUSTED,
    "quota" to ProbeOutcome.EXHAUSTED,
    "balance" to ProbeOutcome.EXHAUSTED,
    "credit" to ProbeOutcome.EXHAUSTED,
    "余额" to ProbeOutcome.EXHAUSTED,
    "额度" to ProbeOutcome.EXHAUSTED,
    "deposit" to ProbeOutcome.EXHAUSTED,
    "unlock" to ProbeOutcome.EXHAUSTED,
    "premium" to ProbeOutcome.EXHAUSTED,
    "payment" to ProbeOutcome.EXHAUSTED,
    "expired" to ProbeOutcome.EXHAUSTED,
    "not available" to ProbeOutcome.EXHAUSTED,
    "rate" to ProbeOutcome.THROTTLED,
    "limit" to ProbeOutcome.THROTTLED,
    "too many" to ProbeOutcome.THROTTLED,
    "限流" to ProbeOutcome.THROTTLED,
    "请求过于频繁" to ProbeOutcome.THROTTLED,
    "timeout" to ProbeOutcome.TRANSIENT,
    "transient" to ProbeOutcome.TRANSIENT,
)

fun outcomeFor(status: Int, body: String): ProbeOutcome {
    STATUS_OUTCOMES[status]?.let { return it }
    val low = body.lowercase()
    for ((kw, oc) in KEYWORD_OUTCOMES) if (low.contains(kw)) return oc
    return ProbeOutcome.TRANSIENT
}

fun classify(status: Int, body: String): ProbeOutcome {
    if (status in 200..299) {
        return if (body.contains("\"choices\"")) ProbeOutcome.OK else ProbeOutcome.TRANSIENT
    }
    return outcomeFor(status, body)
}

data class ProbeResult(val outcome: ProbeOutcome, val body: String)

fun probeOne(provider: Provider, modelId: String, secrets: Map<String, String>): ProbeResult {
    val base = provider.apiBase?.removeSuffix("/") ?: return ProbeResult(ProbeOutcome.TRANSIENT, "missing apiBase")
    val url = "$base/chat/completions"
    val key = secrets[provider.credential]?.trim() ?: ""
    val headers = mutableMapOf("Content-Type" to "application/json")
    if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
    val bodyText = try {
        JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "user"); put("content", provider.probe.prompt) })
            })
            put("max_tokens", provider.probe.maxTokens)
        }.toString()
    } catch (_: Exception) {
        "{\"model\":\"$modelId\",\"messages\":[{\"role\":\"user\",\"content\":\"${provider.probe.prompt}\"}]}"
    }
    val resp = request(url, method = "POST", headers = headers, body = bodyText, timeoutMs = 15000)
    return ProbeResult(classify(resp.status, resp.text), resp.text)
}
