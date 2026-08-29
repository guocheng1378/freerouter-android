package com.eta.freerouter.core.engine

import com.eta.freerouter.core.registry.Provider
import com.eta.freerouter.core.transport.*

fun forwardChat(
    provider: Provider,
    modelId: String,
    bodyJson: String,
    secrets: Map<String, String>,
    timeoutMs: Int = 90000,
): Response {
    val base = provider.apiBase?.removeSuffix("/") ?: return Response(0, "missing apiBase".toByteArray())
    val url = "$base/chat/completions"
    val key = secrets[provider.credential]?.trim() ?: ""
    val headers = mutableMapOf("Content-Type" to "application/json")
    if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
    return request(url, method = "POST", headers = headers, body = bodyJson, timeoutMs = timeoutMs)
}
