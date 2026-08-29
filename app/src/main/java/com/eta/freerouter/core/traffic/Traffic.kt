package com.eta.freerouter.core.traffic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TrafficStat(
    val requests: AtomicLong = AtomicLong(0),
    val okRequests: AtomicLong = AtomicLong(0),
    val failedRequests: AtomicLong = AtomicLong(0),
    val promptTokens: AtomicLong = AtomicLong(0),
    val completionTokens: AtomicLong = AtomicLong(0),
    val totalTokens: AtomicLong = AtomicLong(0),
)

class TrafficRecorder {
    private val byProvider = ConcurrentHashMap<String, TrafficStat>()
    private val byModel = ConcurrentHashMap<String, TrafficStat>()
    private val global = TrafficStat()

    fun record(provider: String?, model: String?, ok: Boolean, prompt: Long = 0, completion: Long = 0, total: Long = 0) {
        global.requests.incrementAndGet()
        if (ok) global.okRequests.incrementAndGet() else global.failedRequests.incrementAndGet()
        global.promptTokens.addAndGet(prompt)
        global.completionTokens.addAndGet(completion)
        global.totalTokens.addAndGet(total)
        if (provider != null) {
            val s = byProvider.getOrPut(provider) { TrafficStat() }
            s.requests.incrementAndGet()
            if (ok) s.okRequests.incrementAndGet() else s.failedRequests.incrementAndGet()
            s.promptTokens.addAndGet(prompt); s.completionTokens.addAndGet(completion); s.totalTokens.addAndGet(total)
        }
        if (model != null) {
            val s = byModel.getOrPut(model) { TrafficStat() }
            s.requests.incrementAndGet()
            if (ok) s.okRequests.incrementAndGet() else s.failedRequests.incrementAndGet()
            s.promptTokens.addAndGet(prompt); s.completionTokens.addAndGet(completion); s.totalTokens.addAndGet(total)
        }
    }

    fun snapshot(): Pair<Map<String, TrafficStat>, Map<String, TrafficStat>> = byProvider.toMap() to byModel.toMap()
    fun global(): TrafficStat = global
}
