package com.eta.freerouter.core.registry

import org.json.JSONArray
import org.json.JSONObject

fun loadProviders(json: String): List<Provider> {
    val root = JSONObject(json)
    val arr = root.getJSONArray("providers")
    val out = mutableListOf<Provider>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val p = Provider(
            id = o.getString("id"),
            name = o.optString("name", o.optString("id")),
            nameZh = o.optString("nameZh", o.optString("name")),
            status = ProviderStatus.valueOf(o.optString("status", "ACTIVE").uppercase()),
            tier = Tier.valueOf(o.optString("tier", "FREE").uppercase()),
            region = o.optString("region", "global"),
            credential = o.optString("credential").takeIf { it.isNotEmpty() },
            extraCredentials = optList(o, "extraCredentials"),
            litellmPrefix = o.optString("litellmPrefix", "openai"),
            apiBase = o.optString("apiBase").takeIf { it.isNotEmpty() },
            docsUrl = o.optString("docsUrl", ""),
            consoleUrl = o.optString("consoleUrl").takeIf { it.isNotEmpty() },
            referralUrl = o.optString("referralUrl").takeIf { it.isNotEmpty() },
            referralNote = o.optString("referralNote").takeIf { it.isNotEmpty() },
            referralRequiresPayment = o.optBoolean("referralRequiresPayment", false),
            summary = o.optString("summary").takeIf { it.isNotEmpty() },
            freeBasis = o.optString("freeBasis", ""),
            freeBasisChecked = o.optString("freeBasisChecked", ""),
            maxModels = o.optInt("maxModels", 20),
            extraParams = parseExtraParams(o.optJSONObject("extraParams")),
            extraParamsOverrides = run {
                val a = o.optJSONArray("extraParamsOverrides") ?: JSONArray()
                List(a.length()) { j -> val r = a.getJSONObject(j); ExtraParamsRule(optList(r, "match"), parseExtraParams(r.optJSONObject("params"))) }
            },
            limits = parseLimits(o.optJSONObject("limits")),
            offer = parseOffer(o.optJSONObject("offer")),
            discovery = parseDiscovery(o.getJSONObject("discovery")),
            probe = parseProbe(o.optJSONObject("probe")),
            retiredOn = o.optString("retiredOn").takeIf { it.isNotEmpty() },
            retirementNote = o.optString("retirementNote").takeIf { it.isNotEmpty() },
        )
        validateProvider(p)
        out.add(p)
    }
    return out
}
