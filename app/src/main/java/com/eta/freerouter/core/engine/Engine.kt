package com.eta.freerouter.core.engine

import com.eta.freerouter.core.notify.LogNotifier
import com.eta.freerouter.core.notify.Notifier
import com.eta.freerouter.core.planning.*
import com.eta.freerouter.core.refresh.runCycle
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders
import com.eta.freerouter.core.state.HealthState
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.traffic.TrafficRecorder
import com.eta.freerouter.core.transport.Response
import com.eta.freerouter.core.watch.WatchScheduler
import android.util.Log

class FreeRouterEngine(
    catalogJson: String,
    val secrets: MutableMap<String, String> = mutableMapOf(),
    val notifier: Notifier = LogNotifier(),
) {
    val providers: List<Provider> = loadProviders(catalogJson)
    val health = HealthState()
    val traffic = TrafficRecorder()
    val discovered = mutableMapOf<String, List<String>>()
    private val watch = WatchScheduler(10 * 60 * 1000L) { runCycle(this, 30 * 60 * 1000L) }
    var defaultModel: String = POOL_ALIAS
    var freeRouterEnabled: Boolean = true
    val modelEnabled = mutableMapOf<String, Boolean>()

    fun listModels(): List<String> {
        val out = mutableListOf<String>()
        for (p in providers) {
            if (!p.routable) continue
            out.add(p.groupAlias)
            discovered[p.id]?.forEach { if (modelEnabled[directAlias(p.id, it)] != false) out.add(directAlias(p.id, it)) }
        }
        if (freeRouterEnabled) out.add(POOL_ALIAS)
        return out
    }

    fun forwardProbe(alias: String, body: String): Response {
        val parts = alias.split("/", limit = 3)
        if (parts.size < 3) return Response(0, "bad alias".toByteArray())
        val provider = providers.firstOrNull { it.id == parts[1] } ?: return Response(0, "unknown provider".toByteArray())
        return forwardChat(provider, parts[2], body, secrets)
    }

    fun routeChat(model: String, bodyJson: String, timeoutMs: Int = 90000): Response {
        val target = model.ifBlank { defaultModel }
        if (target == POOL_ALIAS) {
            if (!freeRouterEnabled) return Response(400, """{"error":"free-router disabled"}""".toByteArray())
            return routePool(this, bodyJson, timeoutMs)
        }
        if (target.startsWith("$DIRECT_NAMESPACE/")) {
            val parts = target.split("/", limit = 3)
            if (parts.size < 3) return Response(400, """{"error":"bad model"}""".toByteArray())
            val p = providers.firstOrNull { it.id == parts[1] } ?: return Response(404, """{"error":"unknown provider"}""".toByteArray())
            val r = forwardChat(p, parts[2], bodyJson, secrets, timeoutMs)
            traffic.record(p.id, target, r.ok)
            return r
        }
        val group = providers.firstOrNull { it.groupAlias == target }
        if (group != null) {
            for (m in discovered[group.id] ?: emptyList()) {
                val r = forwardChat(group, m, bodyJson, secrets, timeoutMs)
                traffic.record(group.id, directAlias(group.id, m), r.ok)
                if (r.ok) return r
            }
            return Response(502, """{"error":"provider ${group.id} all models failed"}""".toByteArray())
        }
        return Response(404, """{"error":"model not found: $target"}""".toByteArray())
    }

    fun start() {
        Thread { try { runCycle(this, 30 * 60 * 1000L) } catch (e: Exception) { Log.e("FreeRouter", "initial discovery failed", e) } }.start()
        watch.start()
    }
    fun stop() { watch.stop() }
}
