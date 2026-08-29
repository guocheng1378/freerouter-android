package com.eta.freerouter.core.registry

import com.eta.freerouter.core.errors.*

fun validateProvider(p: Provider) {
    if (p.referralUrl != null && p.referralNote == null) throw ReferralWithoutDisclosureError(p.id)
    if (p.discovery.mode == DiscoveryMode.LISTING && p.discovery.allow.isEmpty() && !p.discovery.wholeCatalogIsFree)
        throw ListingWithoutFreeEvidenceError(p.id)
}

fun selectModels(modelIds: Iterable<String>, allow: List<String>, deny: List<String>, limit: Int): Pair<List<String>, List<String>> {
    val filtered = modelIds.filter { id -> (allow.isEmpty() || matchesAny(id, allow)) && !matchesAny(id, deny) }.toSet().sorted()
    return filtered.take(limit) to filtered.drop(limit)
}

fun fnmatch(pattern: String, text: String): Boolean {
    val p = pattern; val t = text
    val m = p.length; val n = t.length
    val dp = Array(m + 1) { BooleanArray(n + 1) }
    dp[0][0] = true
    for (i in 1..m) if (p[i - 1] == '*') dp[i][0] = dp[i - 1][0]
    for (i in 1..m) {
        val pc = p[i - 1]
        for (j in 1..n) dp[i][j] = when (pc) {
            '*' -> dp[i - 1][j] || dp[i][j - 1]
            '?' -> dp[i - 1][j - 1]
            else -> dp[i - 1][j - 1] && pc == t[j - 1]
        }
    }
    return dp[m][n]
}

fun matchesAny(modelId: String, patterns: List<String>): Boolean {
    val lower = modelId.lowercase()
    return patterns.any { fnmatch(it.lowercase(), lower) }
}

fun expandEnv(template: String, env: Map<String, String>): String? {
    val regex = Regex("""\$\{([A-Z0-9_]+)\}""")
    val missing = mutableListOf<String>()
    val result = regex.replace(template) { m ->
        val name = m.groupValues[1]
        val v = env[name]?.trim() ?: ""
        if (v.isEmpty()) { missing.add(name); "" } else v
    }
    return if (missing.isNotEmpty()) null else result
}

fun credentialsFor(provider: Provider, env: Map<String, String>? = null): Map<String, String> {
    val source = env ?: System.getenv()
    val required = listOfNotNull(provider.credential) + provider.extraCredentials
    val resolved = mutableMapOf<String, String>()
    for (name in required) {
        val v = source[name]?.trim() ?: ""
        if (v.isEmpty()) return emptyMap()
        resolved[name] = v
    }
    return resolved
}

fun resolveExtraParams(provider: Provider, modelId: String): Map<String, String> {
    val resolved = provider.extraParams.toMutableMap()
    for (rule in provider.extraParamsOverrides) {
        if (matchesAny(modelId, rule.match)) rule.params.forEach { (k, v) -> if (v != null) resolved[k] = v }
    }
    return resolved.filterValues { it != null }
}
