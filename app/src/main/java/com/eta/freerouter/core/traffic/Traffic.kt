package com.eta.freerouter.core.traffic

import java.util.LinkedList
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

// 上游 cli `calls` 等价：单条请求的明细（别名 -> 真实模型、耗时、token、来源）。
data class CallRecord(
    val time: Long = System.currentTimeMillis(),
    val ok: Boolean = false,
    val alias: String = "",
    val realModel: String = "",
    val durationMs: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val isProbe: Boolean = false,
)

class TrafficRecorder {
    private val byProvider = ConcurrentHashMap<String, TrafficStat>()
    private val byModel = ConcurrentHashMap<String, TrafficStat>()
    private val lastOkAt = ConcurrentHashMap<String, AtomicLong>()
    private val global = TrafficStat()
    private val calls = LinkedList<CallRecord>()
    private val callsLock = Any()

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
            if (ok) lastOkAt[model] = AtomicLong(System.currentTimeMillis())
        }
    }

    fun recordCall(rec: CallRecord) {
        synchronized(callsLock) {
            calls.addLast(rec)
            while (calls.size > 50) calls.removeFirst()
        }
    }

    fun recentCalls(limit: Int = 20): List<CallRecord> = synchronized(callsLock) {
        calls.takeLast(limit).reversed()
    }

    // 上游 refresh.py 的 traffic-verified 等价：近期（默认24h）真实调用成功的模型直接采信，不再探测。
    fun lastOkWithin(model: String, windowMs: Long = 24 * 60 * 60 * 1000L): Boolean {
        val t = lastOkAt[model]?.get() ?: return false
        return System.currentTimeMillis() - t <= windowMs
    }

    fun snapshot(): Pair<Map<String, TrafficStat>, Map<String, TrafficStat>> = byProvider.toMap() to byModel.toMap()
    fun global(): TrafficStat = global
}
