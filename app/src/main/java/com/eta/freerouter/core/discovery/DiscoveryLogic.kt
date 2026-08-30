package com.eta.freerouter.core.discovery

import com.eta.freerouter.core.registry.*
import com.eta.freerouter.core.transport.*

private fun catalogRequest(d: Discovery, secrets: Map<String, String>, credential: String?): Pair<String, Map<String, String>>? {
    if (d.url == null) return null
    var url = expandEnv(d.url, secrets) ?: return null
    val key = secrets[credential]?.trim() ?: ""
    val headers = mutableMapOf<String, String>()
    when (d.auth) {
        AuthMode.BEARER -> if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        AuthMode.QUERY_KEY -> if (key.isNotEmpty()) url += (if ("?" in url) "&" else "?") + "key=" + key
        AuthMode.NONE -> {}
    }
    return url to headers
}

private fun skipReason(provider: Provider, secrets: Map<String, String>, requireCredentials: Boolean): String? {
    if (provider.status == ProviderStatus.RETIRED) return "retired"
    if (!provider.routable) return provider.status.name.lowercase()
    if (requireCredentials && provider.credential != null && secrets.isEmpty()) {
        return "missing " + (listOfNotNull(provider.credential) + provider.extraCredentials).joinToString()
    }
    return null
}

private fun finish(provider: Provider, modelIds: List<String>): Pair<List<String>, List<String>> {
    val (kept, dropped) = selectModels(modelIds, provider.discovery.allow, provider.discovery.deny, provider.maxModels)
    return (kept.toSet() + provider.discovery.extraModels.toSet()).toList().sorted() to dropped
}

private fun discoverStatic(provider: Provider): DiscoveryResult {
    val (kept, dropped) = finish(provider, provider.discovery.models)
    return DiscoveryResult(provider.id, kept, dropped)
}

private fun discoverCatalog(provider: Provider, secrets: Map<String, String>, manualFree: Set<String> = emptySet()): DiscoveryResult {
    val resolved = catalogRequest(provider.discovery, secrets, provider.credential)
        ?: return DiscoveryResult(provider.id, error = "catalog URL could not be resolved")
    val resp = request(resolved.first, method = "GET", headers = resolved.second)
    if (!resp.ok) return DiscoveryResult(provider.id, error = "catalog request failed: HTTP ${resp.status}")
    val payload = resp.json() ?: return DiscoveryResult(provider.id, error = "catalog response is not JSON")
    val ids = modelIds(payload, provider.discovery, provider.id, manualFree)
    val (kept, dropped) = finish(provider, ids)
    // 白名单具体模型即使被 allow/deny/limit 过滤掉也强制追加
    val forced = mutableListOf<String>()
    for (entry in manualFree) {
        val slash = entry.indexOf('/')
        if (slash <= 0) continue
        val pid = entry.substring(0, slash).trim()
        val pat = entry.substring(slash + 1).trim()
        if (!pid.equals(provider.id, ignoreCase = true) && pid != "*") continue
        if (pat == "*" || pat.isEmpty()) continue
        if (!pat.contains('*') && !pat.contains('?') && pat !in kept) forced.add(pat)
    }
    return DiscoveryResult(provider.id, (kept + forced).distinct().sorted(), dropped)
}

fun discover(provider: Provider, secrets: Map<String, String> = emptyMap(), manualFree: Set<String> = emptySet()): DiscoveryResult {
    val skip = skipReason(provider, secrets, requireCredentials = true)
    if (skip != null) return DiscoveryResult(provider.id, skipped = skip)
    return when (provider.discovery.mode) {
        DiscoveryMode.STATIC -> discoverStatic(provider)
        else -> discoverCatalog(provider, secrets, manualFree)
    }
}