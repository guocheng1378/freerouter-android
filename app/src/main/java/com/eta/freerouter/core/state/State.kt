package com.eta.freerouter.core.state

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class HealthStatus { UNKNOWN, HEALTHY, QUARANTINED }

enum class ProbeOutcome { OK, AUTH, MISSING, EXHAUSTED, THROTTLED, TRANSIENT }

data class ModelHealth(
    val provider: String,
    val model: String,
    var status: HealthStatus = HealthStatus.UNKNOWN,
    var consecutiveFailures: Int = 0,
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var lastOk: Long? = null,
    var lastProbe: Long? = null,
    var lastOutcome: ProbeOutcome? = null,
    var lastError: String? = null,
    var retryAfter: Long = 0L,
    var detail: String? = null,
)

sealed class HealthEvent {
    data class Added(val provider: String, val model: String) : HealthEvent()
    data class Removed(val provider: String, val model: String) : HealthEvent()
    data class Quarantined(val provider: String, val model: String, val reason: String) : HealthEvent()
    data class Revived(val provider: String, val model: String) : HealthEvent()
}

class HealthState {
    private val byKey = ConcurrentHashMap<String, ModelHealth>()
    private val events = mutableListOf<HealthEvent>()

    private fun key(provider: String, model: String) = "$provider\u0000$model"

    fun get(provider: String, model: String): ModelHealth? = byKey[key(provider, model)]

    fun getOrCreate(provider: String, model: String): ModelHealth =
        byKey.getOrPut(key(provider, model)) { ModelHealth(provider, model) }

    fun all(): List<ModelHealth> = byKey.values.toList()

    fun drainEvents(): List<HealthEvent> = synchronized(events) {
        val copy = events.toList()
        events.clear()
        copy
    }

    fun prune(isStillPresent: (String, String) -> Boolean) {
        val it = byKey.entries.iterator()
        while (it.hasNext()) {
            val (_, h) = it.next()
            if (!isStillPresent(h.provider, h.model)) it.remove()
        }
    }

    fun recordProbe(provider: String, model: String, outcome: ProbeOutcome, error: String? = null) {
        val h = getOrCreate(provider, model)
        val now = System.currentTimeMillis()
        h.lastProbe = now
        h.lastSeen = now
        h.lastOutcome = outcome
        h.lastError = error
        when (outcome) {
            ProbeOutcome.OK -> {
                val wasQuarantined = h.status == HealthStatus.QUARANTINED
                h.status = HealthStatus.HEALTHY
                h.consecutiveFailures = 0
                h.retryAfter = 0L
                h.lastOk = now
                h.detail = null
                if (wasQuarantined) push(HealthEvent.Revived(provider, model))
            }
            ProbeOutcome.THROTTLED -> {
                h.retryAfter = now + 30 * 60_000L
                h.detail = "throttled"
            }
            ProbeOutcome.MISSING -> {
                h.consecutiveFailures++
                h.status = HealthStatus.QUARANTINED
                h.retryAfter = now + 60 * 60_000L
                h.detail = "model missing"
                push(HealthEvent.Quarantined(provider, model, "missing"))
            }
            ProbeOutcome.EXHAUSTED -> {
                h.consecutiveFailures++
                h.status = HealthStatus.QUARANTINED
                h.retryAfter = now + 60 * 60_000L
                h.detail = "quota/credit exhausted"
                push(HealthEvent.Quarantined(provider, model, "exhausted"))
            }
            ProbeOutcome.AUTH -> {
                h.consecutiveFailures++
                if (h.consecutiveFailures >= 3) {
                    h.status = HealthStatus.QUARANTINED
                    h.retryAfter = backoff(h.consecutiveFailures)
                    h.detail = "auth error"
                    push(HealthEvent.Quarantined(provider, model, "auth"))
                }
            }
            ProbeOutcome.TRANSIENT -> {
                h.consecutiveFailures++
                if (h.consecutiveFailures >= 3) {
                    h.status = HealthStatus.QUARANTINED
                    h.retryAfter = backoff(h.consecutiveFailures)
                    h.detail = error ?: "transient error"
                    push(HealthEvent.Quarantined(provider, model, "transient"))
                }
            }
        }
    }

    private fun backoff(failures: Int): Long {
        val minutes = min(30 * (1 shl min(failures - 1, 5)), 12 * 60)
        return System.currentTimeMillis() + minutes * 60_000L
    }

    private fun push(e: HealthEvent) = synchronized(events) { events.add(e) }
}
