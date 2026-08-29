package com.eta.freerouter.core.state

enum class HealthStatus { UNKNOWN, HEALTHY, QUARANTINED }
enum class ProbeOutcome { OK, AUTH, EXHAUSTED, MISSING, THROTTLED, TRANSIENT }

data class ModelHealth(
    val provider: String,
    val modelId: String,
    var status: HealthStatus = HealthStatus.UNKNOWN,
    var lastOk: Long? = null,
    var retryAfter: Long? = null,
    var lastOutcome: ProbeOutcome? = null,
    var detail: String? = null,
)

class HealthState {
    private val models = mutableMapOf<String, ModelHealth>()

    fun key(provider: String, modelId: String) = "$provider/$modelId"

    fun getOrCreate(provider: String, modelId: String): ModelHealth {
        val k = key(provider, modelId)
        return models.getOrPut(k) { ModelHealth(provider, modelId) }
    }

    fun get(provider: String, modelId: String): ModelHealth? = models[key(provider, modelId)]

    fun all(): List<ModelHealth> = models.values.toList()

    fun snapshot(): Map<String, ModelHealth> = models.toMap()
}
