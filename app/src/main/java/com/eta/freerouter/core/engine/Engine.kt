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
import com.eta.freerouter.core.transport.isErrorEvent
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
    val manualFree = mutableSetOf<String>()

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
        for ((pid, m) in manualCandidates()) {
            val alias = directAlias(pid.id, m)
            if (modelEnabled[alias] != false && health.get(pid.id, m)?.status != HealthStatus.QUARANTINED) out.add(alias)
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

    internal fun manualCandidates(): List<Pair<Provider, String>> {
        val out = mutableListOf<Pair<Provider, String>>()
        for (entry in manualFree) {
            val parts = entry.split("/", limit = 2)
            if (parts.size != 2) continue
            val p = providers.firstOrNull { it.id == parts[0] } ?: continue
            if (!p.routable) continue
            if (parts[1] == "*") {
                for (m in discovered[p.id] ?: emptyList()) out.add(p to m)
            } else {
                out.add(p to parts[1])
            }
        }
        return out
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

    // 流式路由：free-router 池在"写出首个有效事件之前"做 failover（首字节前自动切换模型）；
    // 一旦锁定某个模型的首个有效事件，后续不再切换。指定别名/group 失败时回退到 pool。
    fun routeChatStreaming(model: String, bodyJson: String, timeoutMs: Int = 90000, finalChunk: (String) -> Unit) {
        val target = model.ifBlank { defaultModel }
        if (target == POOL_ALIAS) {
            if (!freeRouterEnabled) { finalChunk(streamError("free-router disabled")); return }
            streamThrough(poolCandidates(this), bodyJson, finalChunk, timeoutMs)
            return
        }
        if (target.startsWith("$DIRECT_NAMESPACE/")) {
            val parts = target.split("/", limit = 3)
            if (parts.size < 3) { finalChunk(streamError("bad model")); return }
            val p = providers.firstOrNull { it.id == parts[1] }
            if (p == null) { finalChunk(streamError("unknown provider ${parts[1]}")); return }
            streamThrough(listOf(p to parts[2]), bodyJson, finalChunk, timeoutMs, fallbackPool = true)
            return
        }
        val group = providers.firstOrNull { it.groupAlias == target }
        if (group != null) {
            val cands = discovered[group.id]?.map { group to it } ?: emptyList()
            streamThrough(cands, bodyJson, finalChunk, timeoutMs, fallbackPool = true)
            return
        }
        finalChunk(streamError("model not found: $target"))
    }

    // 依次尝试候选；首个写出有效事件前若遇错误则切换下一个；全部失败回退 free-router 池（若允许）。
    private fun streamThrough(
        cands: List<Pair<Provider, String>>,
        bodyJson: String,
        finalChunk: (String) -> Unit,
        timeoutMs: Int,
        fallbackPool: Boolean = false,
    ) {
        if (cands.isEmpty()) {
            if (fallbackPool && freeRouterEnabled) { streamThrough(poolCandidates(this), bodyJson, finalChunk, timeoutMs); return }
            finalChunk(streamError("no candidate models available")); return
        }
        val PRECHECK_MS = 3000L
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
        var probe = 0
        for ((p, m) in cands) {
            val myProbe = ++probe
            done.set(false); emitted.set(false)
            val t0 = System.currentTimeMillis()
            val th = Thread {
                try {
                    forwardChatStreaming(p, m, bodyJson, secrets, { ev ->
                        if (myProbe != probe) return@forwardChatStreaming
                        if (!emitted.get()) {
                            if (isErrorEvent(ev)) { return@forwardChatStreaming }
                            emitted.set(true)
                        }
                        finalChunk(ev)
                    }, timeoutMs)
                } catch (_: Throwable) {
                    // 防御：单个候选异常不应拖垮整个网关进程
                } finally {
                    done.set(true)
                }
            }
            th.start()
            while (System.currentTimeMillis() - t0 < PRECHECK_MS && !emitted.get() && !done.get()) Thread.sleep(50)
            if (emitted.get()) {
                while (th.isAlive && System.currentTimeMillis() - t0 < timeoutMs) Thread.sleep(100)
                return
            }
            th.interrupt()
        }
        if (fallbackPool && freeRouterEnabled) {
            streamThrough(poolCandidates(this), bodyJson, finalChunk, timeoutMs)
        } else {
            finalChunk(streamError("all candidate models failed"))
        }
    }


    fun addManualFree(entry: String) {
        if (manualFree.add(entry.trim())) refreshNow()
    }

    fun removeManualFree(entry: String) {
        if (manualFree.remove(entry.trim())) refreshNow()
    }

    fun recheck(providerId: String? = null) {
        health.clearQuarantine(providerId)
        refreshNow()
    }

    fun refreshNow() {
        Thread {
            try { runCycle(this, 30 * 60 * 1000L) } catch (e: Exception) { Log.e("FreeRouter", "manual refresh failed", e) }
        }.start()
    }

    fun start() {
        Thread {
            try { runCycle(this, 30 * 60 * 1000L) } catch (e: Exception) { Log.e("FreeRouter", "initial discovery failed", e) }
        }.start()
        watch.start()
    }

    fun stop() {
        watch.stop()
    }
}

// 收集 free-router 候选（按健康度排序），流式与非流式共用
internal fun poolCandidates(engine: FreeRouterEngine): List<Pair<Provider, String>> {
    val now = System.currentTimeMillis()
    val out = mutableListOf<Pair<Provider, String>>()
    fun add(p: Provider, m: String) {
        val alias = directAlias(p.id, m)
        if (engine.modelEnabled[alias] == false) return
        val h = engine.health.get(p.id, m)
        val status = h?.status ?: HealthStatus.UNKNOWN
        if (status == HealthStatus.QUARANTINED) return
        if (h != null && h.retryAfter > now) return
        out.add(p to m)
    }
    for (p in engine.providers) {
        if (!p.routable) continue
        for (m in engine.discovered[p.id] ?: emptyList()) add(p, m)
    }
    for ((p, m) in engine.manualCandidates()) add(p, m)
    out.sortWith(compareBy<Pair<Provider, String>> { (p, m) ->
        if (engine.traffic.lastOkWithin(directAlias(p.id, m))) 0 else 1
    }.thenBy { (p, m) ->
        val h = engine.health.get(p.id, m)
        when {
            h?.status == HealthStatus.HEALTHY -> 0
            h?.lastOk != null -> 1
            h?.status == HealthStatus.UNKNOWN -> 2
            else -> 3
        }
    })
    return out
}

// 非流式路由：沿用候选池 + 错误体检测做 failover
fun routePool(engine: FreeRouterEngine, bodyJson: String, timeoutMs: Int): Response {
    val cands = poolCandidates(engine)
    for ((p, m) in cands) {
        val alias = directAlias(p.id, m)
        val t0 = System.currentTimeMillis()
        val r = forwardChat(p, m, bodyJson, engine.secrets, timeoutMs)
        val dt = System.currentTimeMillis() - t0
        val (pt, ct) = extractUsage(r.text)
        engine.traffic.record(p.id, alias, r.ok, pt, ct, pt + ct)
        engine.traffic.recordCall(CallRecord(ok = r.ok, alias = alias, realModel = m, durationMs = dt, promptTokens = pt, completionTokens = ct))
        if (r.ok && !isErrorResponse(r.text)) return r
    }
    return Response(502, """{"error":"all free models failed"}""".toByteArray())
}

fun streamError(msg: String): String =
    "data: " + JSONObject().put("error", msg).toString() + "\n\n"

fun isErrorResponse(text: String): Boolean = isErrorEvent(text)

fun extractUsage(text: String): Pair<Long, Long> {
    return try {
        val o = JSONObject(text)
        val u = o.optJSONObject("usage")
        if (u != null) u.optLong("prompt_tokens", 0L) to u.optLong("completion_tokens", 0L) else 0L to 0L
    } catch (_: Exception) {
        0L to 0L
    }
}
