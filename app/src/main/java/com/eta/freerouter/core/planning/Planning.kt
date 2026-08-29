package com.eta.freerouter.core.planning

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
