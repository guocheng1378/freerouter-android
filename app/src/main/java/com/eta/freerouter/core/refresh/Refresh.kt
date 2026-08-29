package com.eta.freerouter.core.refresh

import com.eta.freerouter.core.discovery.discover
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.probe.Probe
import com.eta.freerouter.core.registry.credentialsFor
import com.eta.freerouter.core.state.HealthStatus

fun runCycle(engine: FreeRouterEngine, recheckAfterMs: Long) {
    val changes = mutableListOf<String>()
    for (provider in engine.providers) {
        val sec = credentialsFor(provider, engine.secrets)
        if (sec.isEmpty()) { changes.add(provider.id + ": skipped (missing credentials)"); continue }
        val result = discover(provider, sec)
        if (!result.usable) { changes.add(provider.id + ": discovery failed (" + (result.error ?: result.skipped ?: "unknown") + ")"); continue }
        val prev = engine.discovered[provider.id] ?: emptyList()
        if (prev != result.models) {
            engine.discovered[provider.id] = result.models
            changes.add(provider.id + ": discovered " + result.models.size + " models")
        }
        if (provider.probe.enabled) {
            val needsProbe = result.models.filter {
                (engine.health.get(provider.id, it)?.status ?: HealthStatus.UNKNOWN) != HealthStatus.HEALTHY
            }
            val capped = needsProbe.take(provider.probe.maxPerCycle)
            for (m in capped) {
                val h = Probe.probeOne(provider, m, sec)
                engine.health.getOrCreate(provider.id, m).status = h
                Thread.sleep(1500)
            }
            if (capped.isNotEmpty()) changes.add(provider.id + ": probed " + capped.size + " models")
        }
    }
    if (changes.isNotEmpty()) engine.notifier.notify("refresh", changes.joinToString("\n"))
}
