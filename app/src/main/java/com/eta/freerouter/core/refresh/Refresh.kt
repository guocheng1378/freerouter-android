package com.eta.freerouter.core.refresh

import com.eta.freerouter.core.discovery.Discovery
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.engine.secretsFor
import com.eta.freerouter.core.notify.Notify
import com.eta.freerouter.core.probe.Probe
import com.eta.freerouter.core.state.HealthStatus

fun runCycle(engine: FreeRouterEngine, recheckAfterMs: Long) {
    val changes = mutableListOf<String>()
    for (provider in engine.providers) {
        val sec = engine.secretsFor(provider)
        if (sec == null) { changes.add(provider.id + ": skipped (missing credentials)"); continue }
        val result = Discovery.discover(provider, sec)
        if (!result.usable) { changes.add(provider.id + ": discovery failed (" + result.reason + ")"); continue }
        val prev = engine.discovered[provider.id] ?: emptyList()
        if (prev != result.ids) {
            engine.discovered[provider.id] = result.ids.toMutableList()
            changes.add(provider.id + ": discovered " + result.ids.size + " models")
        }
        if (provider.probe.enabled) {
            val needsProbe = result.ids.filter {
                (engine.health.get(provider.id, it)?.status ?: HealthStatus.UNKNOWN) != HealthStatus.HEALTHY
            }
            val capped = needsProbe.take(provider.probe.maxPerCycle)
            for (m in capped) {
                val h = Probe.probeOne(provider, m, sec)
                engine.health.record(provider.id, m, h)
                Thread.sleep(1500)
            }
            if (capped.isNotEmpty()) changes.add(provider.id + ": probed " + capped.size + " models")
        }
    }
    if (changes.isNotEmpty()) Notify.push(changes.joinToString("\n"))
}
