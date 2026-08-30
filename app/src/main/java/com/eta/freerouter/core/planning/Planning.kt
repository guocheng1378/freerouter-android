package com.eta.freerouter.core.planning

import com.eta.freerouter.core.registry.matchesAny

const val POOL_ALIAS = "free-router"
const val DIRECT_NAMESPACE = "fr"
const val MANAGED_PREFIX = "fr-"

data class Deployment(
    val providerId: String,
    val modelId: String,
    val alias: String,
    val groupAlias: String,
    val tier: String,
    val apiBase: String?,
    val apiKeyEnv: String?,
    val rpm: Int?,
    val extraParams: Map<String, String>,
)

fun directAlias(providerId: String, modelId: String): String = "$DIRECT_NAMESPACE/$providerId/$modelId"
fun groupAlias(providerId: String): String = "$providerId-free"

// 手动白名单匹配：entry 格式为 "providerId/modelId"、"providerId/*"、"*/modelId"、"*"。
// 命中即视为免费，绕过价格检测与 allow/deny 过滤。
fun manualFreeMatch(providerId: String, modelId: String, manualFree: Set<String>): Boolean {
    if (manualFree.isEmpty()) return false
    return manualFree.any { entry ->
        val slash = entry.indexOf('/')
        if (slash <= 0) {
            // 纯通配 "*" 表示所有模型；其他无斜杠格式不匹配
            entry == "*"
        } else {
            val pid = entry.substring(0, slash).trim()
            val pat = entry.substring(slash + 1).trim()
            val pidOk = pid == "*" || pid.equals(providerId, ignoreCase = true)
            pidOk && (pat == "*" || matchesAny(modelId, listOf(pat)))
        }
    }
}

fun buildDeployments(
    providerId: String,
    modelIds: List<String>,
    apiBase: String?,
    apiKeyEnv: String?,
    tier: String,
    rpm: Int?,
    extraParams: Map<String, String>,
): List<Deployment> {
    val ga = groupAlias(providerId)
    return modelIds.map { m ->
        Deployment(
            providerId = providerId,
            modelId = m,
            alias = directAlias(providerId, m),
            groupAlias = ga,
            tier = tier,
            apiBase = apiBase,
            apiKeyEnv = apiKeyEnv,
            rpm = rpm,
            extraParams = extraParams,
        )
    }
}
