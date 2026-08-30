package com.eta.freerouter.core.engine

import com.eta.freerouter.core.notify.Changelog
import com.eta.freerouter.core.notify.LogNotifier
import com.eta.freerouter.core.notify.Notifier
import com.eta.freerouter.core.planning.*
import com.eta.freerouter.core.refresh.runCycle
import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.registry.loadProviders
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.state.HealthState
import com.eta.freerouter.core.traffic.CallRecord
import com.eta.freerouter.core.traffic.TrafficRecorder
import com.eta.freerouter.core.transport.Response
import com.eta.freerouter.core.watch.WatchScheduler
import android.util.Log
import org.json.JSONObject

class FreeRouterEngine(
    catalogJson: String,
    val secrets: MutableMap<String, String> = mutableMapOf(),
    val notifier: Notifier = LogNotifier(),
) {
    val providers: List<Provider> = loadProviders(catalogJson)
    val health = HealthState()
    val traffic = TrafficRecorder()
    val discovered = mutableMapOf<String, List<String>>()
    val changelog = Changelog()
    private val watch = WatchScheduler(10 * 60 * 1000L) { runCycle(this, 30 * 60 * 1000L) }
    var defaultModel: String = POOL_ALIAS
    var freeRouterEnabled: Boolean = true
    val modelEnabled = mutableMapOf<String, Boolean>()

    fun listModels(): List<String> {
        val out = mutableListOf<String>()
        for (p in providers) {
            if (!p.routable) continue
            out.add(p.groupAlias)
            discovered[p.id]?.forEach { m ->
                val alias = directAlias(p.id, m)
                if (modelEnabled[alias] != false && health.get(p.id, m)?.status != HealthStatus.QUARANTINED) out.add(alias)
            }
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
            val t0 = System.currentTimeMillis()
            val r = forwardChat(p, parts[2], bodyJson, secrets, timeoutMs)
            val dt = System.currentTimeMillis() - t0
            val (pt, ct) = extractUsage(r.text)
            traffic.record(p.id, target, r.ok, pt, ct, pt + ct)
            traffic.recordCall(CallRecord(ok = r.ok, alias = target, realModel = parts[2], durationMs = dt, promptTokens = pt, completionTokens = ct))
            return r
        }
        val group = providers.firstOrNull { it.groupAlias == target }
        if (group != null) {
            for (m in discovered[group.id] ?: emptyList()) {
                val alias = directAlias(group.id, m)
                if (modelEnabled[alias] == false) continue
                val t0 = System.currentTimeMillis()
                val r = forwardChat(group, m, bodyJson, secrets, timeoutMs)
                val dt = System.currentTimeMillis() - t0
                val (pt, ct) = extractUsage(r.text)
                traffic.record(group.id, alias, r.ok, pt, ct, pt + ct)
                traffic.recordCall(CallRecord(ok = r.ok, alias = target, realModel = m, durationMs = dt, promptTokens = pt, completionTokens = ct))
                if (r.ok) return r
            }
            return Response(502, """{"error":"provider ${group.id} all models failed"}""".toByteArray())
        }
        return Response(404, """{"error":"model not found: $target"}""".toByteArray())
    }

    // 上游 cli `recheck` 等价：清掉隔离与退避计时，然后立即跑一轮刷新（后台线程）。
    fun recheck(providerId: String? = null) {
        health.clearQuarantine(providerId)
        refreshNow()
    }

    fun refreshNow() {
        Thread {
            try {
                runCycle(this, 30 * 60 * 1000L)
            } catch (e: Exception) {
                Log.e("FreeRouter", "manual refresh failed", e)
            }
        }.start()
    }

    fun start() {
        Thread {
            try {
                runCycle(this, 30 * 60 * 1000L)
            } catch (e: Exception) {
                Log.e("FreeRouter", "initial discovery failed", e)
            }
        }.start()
        watch.start()
    }

    fun stop() {
        watch.stop()
    }
}

// 从 OpenAI 风格响应的 usage 字段抽取 token 计数；解析失败返回 0。
fun extractUsage(text: String): Pair<Long, Long> {
    return try {
        val o = JSONObject(text)
        val u = o.optJSONObject("usage")
        if (u != null) u.optLong("prompt_tokens", 0L) to u.optLong("completion_tokens", 0L) else 0L to 0L
    } catch (_: Exception) {
        0L to 0L
    }
}
