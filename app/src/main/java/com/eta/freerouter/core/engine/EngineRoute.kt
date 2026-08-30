package com.eta.freerouter.core.engine

import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.planning.directAlias
import com.eta.freerouter.core.transport.Response

// free-router 池路由：收集候选后按健康度排序、跳过隔离与退避中模型，顺序 failover。
fun routePool(engine: FreeRouterEngine, bodyJson: String, timeoutMs: Int): Response {
    val now = System.currentTimeMillis()
    val candidates = mutableListOf<Pair<Provider, String>>()
    for (p in engine.providers) {
        if (!p.routable) continue
        for (m in engine.discovered[p.id] ?: emptyList()) {
            val alias = directAlias(p.id, m)
            if (engine.modelEnabled[alias] == false) continue
            val h = engine.health.get(p.id, m)
            val status = h?.status ?: HealthStatus.UNKNOWN
            if (status == HealthStatus.QUARANTINED) continue
            if (h != null && h.retryAfter > now) continue
            candidates.add(p to m)
        }
    }
    // 优先级：HEALTHY > 近期成功过 > 未探测(UNKNOWN) > 失败中(AUTH/EXHAUSTED/THROTTLED 尚未隔离)
    candidates.sortBy { (p, m) ->
        val h = engine.health.get(p.id, m)
        when {
            h?.status == HealthStatus.HEALTHY -> 0
            h?.lastOk != null -> 1
            h?.status == HealthStatus.UNKNOWN -> 2
            else -> 3
        }
    }
    for ((p, m) in candidates) {
        val r = forwardChat(p, m, bodyJson, engine.secrets, timeoutMs)
        engine.traffic.record(p.id, directAlias(p.id, m), r.ok)
        if (r.ok) return r
    }
    return Response(502, """{"error":"all free models failed"}""".toByteArray())
}
