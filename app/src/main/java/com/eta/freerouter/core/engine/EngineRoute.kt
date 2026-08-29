package com.eta.freerouter.core.engine

import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.planning.directAlias
import com.eta.freerouter.core.transport.Response

fun routePool(engine: FreeRouterEngine, bodyJson: String, timeoutMs: Int): Response {
    val candidates = mutableListOf<Pair<Provider, String>>()
    for (p in engine.providers) {
        if (!p.routable) continue
        for (m in engine.discovered[p.id] ?: emptyList()) candidates.add(p to m)
    }
    candidates.sortBy { (p, m) ->
        when (engine.health.get(p.id, m)?.status) {
            HealthStatus.HEALTHY -> 0
            HealthStatus.UNKNOWN -> 1
            else -> 2
        }
    }
    val active = candidates.filter { (p, m) -> engine.health.get(p.id, m)?.status != HealthStatus.QUARANTINED }
    for ((p, m) in active) {
        val r = forwardChat(p, m, bodyJson, engine.secrets, timeoutMs)
        engine.traffic.record(p.id, directAlias(p.id, m), r.ok)
        if (r.ok) return r
    }
    return Response(502, """{"error":"all free models failed"}""".toByteArray())
}
