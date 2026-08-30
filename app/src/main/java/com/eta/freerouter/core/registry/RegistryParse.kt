package com.eta.freerouter.core.registry

import org.json.JSONArray
import org.json.JSONObject

fun optList(o: JSONObject, key: String): List<String> {
    val a = o.optJSONArray(key) ?: return emptyList()
    return List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }
}
fun parseLimits(o: JSONObject?): Limits {
    if (o == null) return Limits()
    return Limits(o.optInt("rpm").takeIf { it != 0 }, o.optInt("rpd").takeIf { it != 0 }, o.optString("note").takeIf { it.isNotEmpty() })
}
fun parseOffer(o: JSONObject?): Offer {
    if (o == null) return Offer()
    return Offer(o.optString("startsOn").takeIf { it.isNotEmpty() }, o.optString("endsOn").takeIf { it.isNotEmpty() }, o.optString("note").takeIf { it.isNotEmpty() })
}
fun parseDiscovery(o: JSONObject): Discovery {
    return Discovery(
        mode = DiscoveryMode.valueOf(o.optString("mode", "LISTING").uppercase()),
        url = o.optString("url").takeIf { it.isNotEmpty() },
        auth = AuthMode.valueOf(o.optString("auth", "NONE").uppercase()),
        itemsPath = o.optString("itemsPath", "data"),
        idPath = o.optString("idPath", "id"),
        idStripPrefix = o.optString("idStripPrefix").takeIf { it.isNotEmpty() },
        promptPricePath = o.optString("promptPricePath").takeIf { it.isNotEmpty() },
        priceValuePath = o.optString("priceValuePath", "value"),
        completionPricePath = o.optString("completionPricePath").takeIf { it.isNotEmpty() },
        inputModalitiesPath = o.optString("inputModalitiesPath").takeIf { it.isNotEmpty() },
        outputModalitiesPath = o.optString("outputModalitiesPath").takeIf { it.isNotEmpty() },
        requireTextIo = o.optBoolean("requireTextIo", false),
        wholeCatalogIsFree = o.optBoolean("wholeCatalogIsFree", false),
        denyOutputModalities = optList(o, "denyOutputModalities"),
        allow = optList(o, "allow"),
        deny = optList(o, "deny"),
        models = optList(o, "models"),
        extraModels = optList(o, "extraModels"),
    )
}
fun parseProbe(o: JSONObject?): ProbeSettings {
    if (o == null) return ProbeSettings()
    return ProbeSettings(
        enabled = o.optBoolean("enabled", true),
        maxPerCycle = o.optInt("maxPerCycle", 6),
        maxTokens = o.optInt("maxTokens", 16),
        prompt = o.optString("prompt", "ping"),
        allow = optList(o, "allow"),
        deny = optList(o, "deny"),
    )
}
fun parseExtraParams(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    val m = mutableMapOf<String, String>()
    val it = o.keys()
    while (it.hasNext()) { val k = it.next(); m[k] = o.getString(k) }
    return m
}
