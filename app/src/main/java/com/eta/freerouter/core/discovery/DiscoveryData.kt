package com.eta.freerouter.core.discovery

import com.eta.freerouter.core.registry.*
import com.eta.freerouter.core.transport.*
import org.json.JSONArray

data class DiscoveryResult(
    val providerId: String,
    val models: List<String> = emptyList(),
    val droppedByCap: List<String> = emptyList(),
    val skipped: String? = null,
    val error: String? = null,
) {
    val usable: Boolean get() = skipped == null && error == null
}

private const val TEXT_MODALITY = "text"

fun isZeroPrice(value: JsonValue, valuePath: String): Boolean? {
    return when (value) {
        is JSONArray -> {
            if (value.length() == 0) return null
            val rates = (0 until value.length()).map { asPrice(dig(value.get(it), valuePath)) }
            if (rates.any { it == null }) return null
            rates.all { it == 0.0 }
        }
        else -> {
            val p = asPrice(dig(value, valuePath))
            if (p == null) null else p == 0.0
        }
    }
}

fun isFree(item: JsonValue, d: Discovery): Boolean {
    if (d.promptPricePath == null || d.completionPricePath == null) return false
    val prompt = isZeroPrice(dig(item, d.promptPricePath), d.priceValuePath)
    val completion = isZeroPrice(dig(item, d.completionPricePath), d.priceValuePath)
    return prompt == true && completion == true
}

fun isTextModel(item: JsonValue, d: Discovery): Boolean {
    if (d.inputModalitiesPath == null || d.outputModalitiesPath == null) return true
    val outputs = asStrings(dig(item, d.outputModalitiesPath))
    if (outputs.any { it in d.denyOutputModalities }) return false
    if (!d.requireTextIo) return true
    val inputs = asStrings(dig(item, d.inputModalitiesPath))
    return TEXT_MODALITY in inputs && TEXT_MODALITY in outputs
}

fun modelIds(payload: JsonValue, d: Discovery): List<String> {
    val collected = mutableListOf<String>()
    for (item in asItems(dig(payload, d.itemsPath))) {
        var modelId = asText(dig(item, d.idPath)) ?: continue
        if (d.idStripPrefix != null && modelId.startsWith(d.idStripPrefix)) modelId = modelId.removePrefix(d.idStripPrefix)
        if (d.mode == DiscoveryMode.PRICED_CATALOG && !isFree(item, d)) continue
        if (!isTextModel(item, d)) continue
        collected.add(modelId)
    }
    return collected
}
