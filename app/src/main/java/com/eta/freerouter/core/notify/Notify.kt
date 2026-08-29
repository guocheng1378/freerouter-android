package com.eta.freerouter.core.notify

interface Notifier {
    fun notify(title: String, message: String)
}

class LogNotifier : Notifier {
    override fun notify(title: String, message: String) {
        android.util.Log.i("FreeRouter", "[$title] $message")
    }
}
