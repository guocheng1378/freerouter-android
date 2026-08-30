package com.eta.freerouter.core.notify

import java.util.concurrent.ConcurrentLinkedDeque

interface Notifier {
    fun notify(title: String, message: String)
}

class LogNotifier : Notifier {
    override fun notify(title: String, message: String) {
        android.util.Log.i("FreeRouter", "[$title] $message")
    }
}

data class ChangeEntry(val ts: Long, val kind: String, val text: String)

// 轻量 changelog（上游 changelog.jsonl 的等价），供 UI 展示最近变更
class Changelog(private val cap: Int = 200) {
    private val deque = ConcurrentLinkedDeque<ChangeEntry>()
    fun add(kind: String, text: String) {
        deque.addLast(ChangeEntry(System.currentTimeMillis(), kind, text))
        while (deque.size > cap) deque.pollFirst()
    }
    fun recent(n: Int = 50): List<ChangeEntry> = deque.takeLast(n)
    fun clear() = deque.clear()
}
