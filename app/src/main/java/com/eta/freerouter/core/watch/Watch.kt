package com.eta.freerouter.core.watch

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class WatchScheduler(
    private val intervalMs: Long,
    private val task: () -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var running = false

    fun start() {
        if (running) return
        running = true
        executor.scheduleWithFixedDelay({
            try { task() } catch (e: Throwable) { Log.e("FreeRouter", "watch cycle failed", e) }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        executor.shutdownNow()
    }
}
