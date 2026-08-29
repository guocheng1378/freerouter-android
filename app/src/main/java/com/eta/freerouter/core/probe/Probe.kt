package com.eta.freerouter.core.probe

import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.transport.Response
import com.eta.freerouter.core.transport.request

object Probe {
    private val PAID_HINTS = listOf(
        "access_denied", "forbidden", "insufficient", "quota", "balance",
        "credit", "deposit", "unlock", "premium", "payment", "expired", "not available"
    )

    fun probeOne(
        provider: Provider,
        modelId: String,
        secrets: Map<String, String>,
        timeoutMs: Int = 40000,
    ): HealthStatus {
        val base = provider.apiBase?.removeSuffix("/") ?: return HealthStatus.UNKNOWN
        val url = "$base/chat/completions"
        val key = secrets[provider.credential]?.trim() ?: ""
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val body = """{"model":"$modelId","messages":[{"role":"user","content":"ping"}],"max_tokens":16,"stream":false}"""
        val resp = request(url, method = "POST", headers = headers, body = body, timeoutMs = timeoutMs)
        return classify(resp.status, resp.text)
    }

    fun classify(code: Int, body: String): HealthStatus {
        val b = body.lowercase()
        val paid = PAID_HINTS.any { it in b }
        return when {
            200 <= code && code < 300 -> HealthStatus.HEALTHY
            401 == code || 403 == code -> if (paid) HealthStatus.QUARANTINED else HealthStatus.UNKNOWN
            402 == code -> HealthStatus.QUARANTINED
            429 == code -> if (paid) HealthStatus.QUARANTINED else HealthStatus.UNKNOWN
            code in 400..499 -> if (paid) HealthStatus.QUARANTINED else HealthStatus.UNKNOWN
            else -> HealthStatus.UNKNOWN
        }
    }
}
