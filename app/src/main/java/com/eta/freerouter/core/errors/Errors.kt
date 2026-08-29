package com.eta.freerouter.core.errors

open class FreeRouterException(message: String, cause: Throwable? = null) : Exception(message, cause)
class RegistryError(val path: String, reason: String) : FreeRouterException("registry error at $path: $reason")
class ListingWithoutFreeEvidenceError(providerId: String) :
    FreeRouterException("provider '$providerId' uses discovery mode 'listing' but has neither an allow list nor whole_catalog_is_free; refusing to trust the whole catalog as free")
class ReferralWithoutDisclosureError(providerId: String) :
    FreeRouterException("provider '$providerId' has a referral_url but no referral_note")
class GatewayError(action: String, status: Int, body: String) :
    FreeRouterException("gateway action '$action' failed: HTTP $status $body")
class UnsupportedUrlError(url: String) : FreeRouterException("unsupported url scheme: $url")
class DiscoveryError(providerId: String, reason: String) :
    FreeRouterException("discovery failed for '$providerId': $reason")
