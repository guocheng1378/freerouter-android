package com.eta.freerouter.core.refresh

import com.eta.freerouter.core.discovery.discover
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.engine.forwardChat
import com.eta.freerouter.core.probe.*
import com.eta.freerouter.core.registry.credentialsFor
import com.eta.freerouter.core.state.HealthStatus

data class CycleReport(
    val discovered: Map<String, List<String>>,
    val changes: List<String>,
    val probed: Int,
)

fun runCycle(engine: FreeRouterEngine, recheckAfterMs: Long): CycleReport {
    val now = System.currentTimeMillis()
    val newDiscovered = mutableMapOf<String, List<String>>()
    val changes = mutableListOf<String>()
    var probed = 0
    for (provider in engine.providers) {
        if (!provider.routable) continue
        val secrets = credentialsFor(provider, engine.secrets)
        val result = discover(provider, secrets)
        if (!result.usable) {
            changes.add("${provider.id}: discovery ${result.skipped ?: result.error}")
            continue
        }
        val old = engine.discovered[provider.id] ?: emptyList()
        if (old != result.models) {
            changes.add("${provider.id}: ${old.size} -> ${result.models.size} models")
        }
        newDiscovered[provider.id] = result.models
        for (modelId in result.models) engine.health.getOrCreate(provider.id, modelId)
        val targets = probeTargets(engine.health, provider, now, recheckAfterMs)
        for (modelId in targets) {
            val outcome = probeOne({ alias, prompt, maxTokens ->
                val body = """{"model":"$alias","messages":[{"role":"user","content":"$prompt"}],"max_tokens":$maxTokens,"stream":false}"""
                engine.forwardProbe(alias, body)
            }, provider, modelId)
            probed++
            val h = engine.health.getOrCreate(provider.id, modelId)
            h.lastOutcome = outcome.first
            h.detail = outcome.second
            when (outcome.first) {
                ProbeOutcome.OK -> { h.status = HealthStatus.HEALTHY; h.lastOk = now; h.retryAfter = null }
                ProbeOutcome.TRANSIENT -> {}
                else -> { h.status = HealthStatus.QUARANTINED; h.retryAfter = now + 10 * 60 * 1000 }
            }
        }
    }
    engine.discovered.putAll(newDiscovered)
    if (changes.isNotEmpty()) engine.notifier.notify("FreeRouter 发现变更", changes.joinToString("; "))
    return CycleReport(newDiscovered, changes, probed)
}
