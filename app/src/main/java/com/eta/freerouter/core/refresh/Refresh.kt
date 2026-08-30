package com.eta.freerouter.core.refresh

import com.eta.freerouter.core.discovery.discover
import com.eta.freerouter.core.engine.FreeRouterEngine
import com.eta.freerouter.core.planning.manualFreeMatch
import com.eta.freerouter.core.probe.ProbeOutcome
import com.eta.freerouter.core.probe.probeOne
import com.eta.freerouter.core.registry.credentialsFor
import com.eta.freerouter.core.state.HealthStatus
import com.eta.freerouter.core.state.HealthEvent
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

fun runCycle(engine: FreeRouterEngine, recheckAfterMs: Long) {
    val now = System.currentTimeMillis()
    val changes = mutableListOf<String>()
    for (provider in engine.providers) {
        val sec = credentialsFor(provider, engine.secrets)
        if (sec.isEmpty()) { changes.add(provider.id + ": skipped (missing credentials)"); continue }
        val result = discover(provider, sec, engine.manualFree)
        if (!result.usable) { changes.add(provider.id + ": discovery failed (" + (result.error ?: result.skipped ?: "unknown") + ")"); continue }
        val prev = engine.discovered[provider.id] ?: emptyList()
        if (prev != result.models) {
            val added = result.models - prev
            val removed = prev - result.models
            for (m in added) engine.changelog.add("ADDED", provider.id + ": " + m)
            for (m in removed) {
                engine.changelog.add("REMOVED", provider.id + ": " + m)
                engine.health.prune { p, mdl -> !(p == provider.id && mdl == m) }
            }
            engine.discovered[provider.id] = result.models
            changes.add(provider.id + ": discovered " + result.models.size + " models")
        }
        if (provider.probe.enabled) {
            val toProbe = mutableListOf<String>()
            for (m in result.models) {
                val h = engine.health.get(provider.id, m)
                val status = h?.status ?: HealthStatus.UNKNOWN
                val alias = "fr/" + provider.id + "/" + m
                val isFreed = manualFreeMatch(provider.id, m, engine.manualFree)
                if (engine.traffic.lastOkWithin(alias)) {
                    if (status != HealthStatus.HEALTHY) {
                        engine.health.getOrCreate(provider.id, m).status = HealthStatus.HEALTHY
                        engine.changelog.add("TRAFFIC_OK", provider.id + ": " + m)
                    }
                    continue
                }
                if (status == HealthStatus.HEALTHY) {
                    val lastOk = h?.lastOk ?: 0L
                    if (now - lastOk < recheckAfterMs) continue
                }
                if (h != null && h.retryAfter > now) continue
                if (provider.probe.deny.any { glob(it, m) }) {
                    val hh = engine.health.getOrCreate(provider.id, m)
                    if (hh.status != HealthStatus.QUARANTINED) {
                        hh.status = HealthStatus.QUARANTINED
                        hh.detail = "probe deny"
                        engine.changelog.add("QUARANTINED", provider.id + ": " + m + " (deny)")
                    }
                    continue
                }
                if (provider.probe.allow.isNotEmpty() && !provider.probe.allow.any { glob(it, m) }) continue
                toProbe.add(m)
            }
            val capped = toProbe.take(provider.probe.maxPerCycle)
            for (m in capped) {
                val r = probeOne(provider, m, sec)
                // 白名单模型：探测失败不隔离（用户手动确认免费），保留在池中，仅记录状态。
                if (manualFreeMatch(provider.id, m, engine.manualFree) && r.outcome != ProbeOutcome.OK) {
                    val h = engine.health.getOrCreate(provider.id, m)
                    h.lastProbe = now
                    h.lastSeen = now
                    h.lastOutcome = r.outcome
                    h.lastError = r.body.take(200)
                    if (h.status == HealthStatus.QUARANTINED) {
                        h.status = HealthStatus.UNKNOWN
                        h.retryAfter = 0L
                    }
                } else {
                    engine.health.recordProbe(provider.id, m, r.outcome, r.body)
                }
                Thread.sleep(1500)
            }
            if (capped.isNotEmpty()) changes.add(provider.id + ": probed " + capped.size + " models")
        }
        provider.offer.endsOn?.let { ends ->
            val remain = parseDaysUntil(ends)
            if (remain != null && remain <= 7L) {
                val kind = if (remain <= 0) "OFFER_EXPIRED" else "OFFER_EXPIRING"
                engine.changelog.add(kind, provider.id + " 活动" + (if (remain <= 0) "已到期" else "将在 " + remain + " 天后到期") + " (" + ends + ")")
            }
        }
    }
    val events = engine.health.drainEvents()
    for (e in events) {
        val line = when (e) {
            is HealthEvent.Quarantined -> e.provider + ": " + e.model + " 隔离 (" + e.reason + ")"
            is HealthEvent.Revived -> e.provider + ": " + e.model + " 恢复"
            is HealthEvent.Added -> e.provider + ": " + e.model + " 新增"
            is HealthEvent.Removed -> e.provider + ": " + e.model + " 移除"
        }
        engine.changelog.add(e::class.simpleName ?: "EVENT", line)
        changes.add(line)
    }
    if (changes.isNotEmpty()) engine.notifier.notify("refresh", changes.joinToString("\n"))
}

private fun glob(pattern: String, text: String): Boolean {
    return try { Regex(pattern.replace("*", ".*").replace("?", ".")).matches(text) } catch (_: Exception) { false }
}

private fun parseDaysUntil(dateStr: String): Long? {
    return try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr) ?: return null
        TimeUnit.MILLISECONDS.toDays(d.time - System.currentTimeMillis())
    } catch (_: Exception) { null }
}