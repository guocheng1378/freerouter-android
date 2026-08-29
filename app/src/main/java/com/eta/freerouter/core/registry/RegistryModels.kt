package com.eta.freerouter.core.registry

enum class DiscoveryMode { PRICED_CATALOG, LISTING, STATIC }
enum class AuthMode { NONE, BEARER, QUERY_KEY }
enum class ProviderStatus { ACTIVE, PAUSED, RETIRED, WATCH }
enum class Tier { FREE, TRIAL, OFFER }

data class Limits(val rpm: Int? = null, val rpd: Int? = null, val note: String? = null)
data class Offer(val startsOn: String? = null, val endsOn: String? = null, val note: String? = null)
data class Discovery(
    val mode: DiscoveryMode,
    val url: String? = null,
    val auth: AuthMode = AuthMode.NONE,
    val itemsPath: String = "data",
    val idPath: String = "id",
    val idStripPrefix: String? = null,
    val promptPricePath: String? = null,
    val priceValuePath: String = "value",
    val completionPricePath: String? = null,
    val inputModalitiesPath: String? = null,
    val outputModalitiesPath: String? = null,
    val requireTextIo: Boolean = false,
    val wholeCatalogIsFree: Boolean = false,
    val denyOutputModalities: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val extraModels: List<String> = emptyList(),
)
data class ExtraParamsRule(val match: List<String>, val params: Map<String, String?> = emptyMap())
data class ProbeSettings(
    val enabled: Boolean = true,
    val maxPerCycle: Int = 6,
    val maxTokens: Int = 16,
    val prompt: String = "ping",
)
data class Provider(
    val id: String,
    val name: String,
    val nameZh: String,
    val status: ProviderStatus = ProviderStatus.ACTIVE,
    val tier: Tier = Tier.FREE,
    val region: String = "global",
    val credential: String? = null,
    val extraCredentials: List<String> = emptyList(),
    val litellmPrefix: String = "openai",
    val apiBase: String? = null,
    val docsUrl: String = "",
    val consoleUrl: String? = null,
    val referralUrl: String? = null,
    val referralNote: String? = null,
    val referralRequiresPayment: Boolean = false,
    val summary: String? = null,
    val freeBasis: String = "",
    val freeBasisChecked: String = "",
    val maxModels: Int = 20,
    val extraParams: Map<String, String> = emptyMap(),
    val extraParamsOverrides: List<ExtraParamsRule> = emptyList(),
    val limits: Limits = Limits(),
    val offer: Offer = Offer(),
    val discovery: Discovery,
    val probe: ProbeSettings = ProbeSettings(),
    val retiredOn: String? = null,
    val retirementNote: String? = null,
) {
    val groupAlias: String get() = "$id-free"
    val routable: Boolean get() = status == ProviderStatus.ACTIVE
}
data class Catalog(val version: String? = null, val providers: List<Provider> = emptyList())
