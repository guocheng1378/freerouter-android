package com.eta.freerouter.core.probe

import com.eta.freerouter.core.planning.directAlias
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.state.*
import com.eta.freerouter.core.transport.*

const val MAX_DETAIL = 200

private val STATUS_OUTCOMES = mapOf(
    401 to ProbeOutcome.AUTH,
    402 to ProbeOutcome.EXHAUSTED,
    403 to ProbeOutcome.AUTH,
    404 to ProbeOutcome.MISSING,
    429 to ProbeOutcome.THROTTLED,
)

private val KEYWORD_OUTCOMES = listOf(
    ProbeOutcome.MISSING to listOf("model_not_found", "not found", "does not exist", "no such model", "unknown model", "decommissioned", "has been deprecated", "no longer available", "已下线", "不存在"),
    ProbeOutcome.EXHAUSTED to listOf("insufficient", "quota", "out of credit", "balance", "exceeded your current", "余额", "额度"),
    ProbeOutcome.THROTTLED to listOf("rate limit", "rate_limit", "too many requests", "请求过于频繁", "限流"),
    ProbeOutcome.AUTH to listOf("invalid api key", "unauthorized", "authentication", "invalid token", "鉴权"),
)

private fun keywordOutcome(text: String): ProbeOutcome? {
    val lower = text.lowercase()
    for ((outcome, needles) in KEYWORD_OUTCOMES) {
        if (needles.any { it in lower }) return outcome
    }
    return null
}

private val ERROR_PATHS = listOf("error.message", "message", "detail", "msg")

private fun detailOf(response: Response): String {
    val payload = response.json()
    for (path in ERROR_PATHS) {
        val message = asText(dig(payload, path))
        if (message != null) return message.split(Regex("\\s+")).joinToString(" ").take(MAX_DETAIL)
    }
    return response.text.split(Regex("\\s+")).joinToString(" ").take(MAX_DETAIL)
}

fun outcomeFor(status: Int, detail: String): ProbeOutcome {
    return STATUS_OUTCOMES[status] ?: keywordOutcome(detail) ?: ProbeOutcome.TRANSIENT
}

fun classify(response: Response): Pair<ProbeOutcome, String> {
    val detail = detailOf(response)
    if (response.ok) {
        val choices = asItems(dig(response.json(), "choices"))
        if (choices.isNotEmpty()) return ProbeOutcome.OK to "ok"
        return ProbeOutcome.TRANSIENT to "200 but no choices: $detail"
    }
    return outcomeFor(response.status, detail) to "HTTP ${response.status}: $detail"
}

fun probeOne(
    chatRequest: (alias: String, prompt: String, maxTokens: Int) -> Response,
    provider: Provider,
    modelId: String,
): Pair<ProbeOutcome, String> {
    val alias = directAlias(provider.id, modelId)
    return classify(chatRequest(alias, provider.probe.prompt, provider.probe.maxTokens))
}

fun probeTargets(
    state: HealthState,
    provider: Provider,
    now: Long,
    recheckAfterMs: Long,
    verifiedByTraffic: Collection<String> = emptyList(),
): List<String> {
    if (!provider.probe.enabled) return emptyList()
    val unknown = mutableListOf<String>()
    val due = mutableListOf<String>()
    val stale = mutableListOf<String>()
    for (health in state.all()) {
        if (health.provider != provider.id || health.modelId in verifiedByTraffic) continue
        when (health.status) {
            HealthStatus.UNKNOWN -> unknown.add(health.modelId)
            HealthStatus.QUARANTINED -> if (health.retryAfter == null || health.retryAfter!! <= now) due.add(health.modelId)
            HealthStatus.HEALTHY -> if (health.lastOk == null || now - health.lastOk!! >= recheckAfterMs) stale.add(health.modelId)
        }
    }
    return (unknown.sorted() + due.sorted() + stale.sorted()).take(provider.probe.maxPerCycle)
}
