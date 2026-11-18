package com.flarego.core.cloudflare.registrar

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.cloudflare.protocol.readEnvelope
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.Connection
import com.flarego.core.model.RegistrarCandidate
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.*

/**
 * Read-only Registrar domain discovery for one owned account.
 *
 * The endpoint is the provider's search suggestion API: it returns cached candidates,
 * never a reservation, an availability guarantee or a binding price. Registration
 * (POST /registrar/registrations) is deliberately not implemented, so no code path in
 * this class can spend money.
 *
 * Every field of every candidate is untrusted input: a wrong type, an oversized string or
 * a malformed price is a stable [CloudFailure] that never echoes provider content.
 */
class CloudflareRegistrarClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
) {
    /** Searches [keyword] inside [account]; [limit] is the requested suggestion count. */
    suspend fun search(account: AccountScope, keyword: String, limit: Int = 8): List<RegistrarCandidate> {
        ScopeGuard.requireOwned(account, connection)
        if (limit !in MIN_LIMIT..MAX_LIMIT) throw invalidInputFailure()
        val normalized = RegistrarKeyword.normalize(keyword)
        val root = readEnvelope(
            gateway, ACTION_KEY,
            "/accounts/${encodePath(account.accountId)}/registrar/domain-search" +
                "?q=${encodePath(normalized)}&limit=$limit",
        )
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        val domains = result["domains"] as? JsonArray ?: throw invalidResponse(false)
        // The provider may return more rows than requested; a runaway array is refused
        // instead of being mapped into an unbounded list.
        if (domains.size > MAX_ROWS) throw invalidResponse(false)
        return domains.map(::candidate)
    }

    companion object {
        /** Reviewed action key; it must also be registered in ActionPolicies. */
        const val ACTION_KEY: String = "registrar-domain-discovery-search"
        const val MIN_LIMIT: Int = 1
        const val MAX_LIMIT: Int = 10
        private const val MAX_ROWS: Int = 50
    }
}

/**
 * The only accepted search-term shape.
 *
 * Whitespace is removed (the provider treats "coffee shop" as the phrase "coffeeshop"),
 * the remainder is lowercased, and everything outside [a-z0-9.-] is refused, so an IDN,
 * a scheme, a path fragment or a control character can never reach the query.
 */
internal object RegistrarKeyword {
    const val MAX_LENGTH: Int = 100

    fun normalize(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (character in raw) {
                if (!character.isWhitespace()) append(character.lowercaseChar())
            }
        }
        if (cleaned.isEmpty() || cleaned.length > MAX_LENGTH) throw invalidInputFailure()
        if (cleaned.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) throw invalidInputFailure()
        return cleaned
    }
}

private fun candidate(value: JsonElement): RegistrarCandidate {
    val row = value as? JsonObject ?: throw invalidResponse(false)
    val pricing = when (val raw = row["pricing"]) {
        null, JsonNull -> null
        is JsonObject -> raw
        else -> throw invalidResponse(false)
    }
    return RegistrarCandidate(
        name = row.requiredName(),
        registrable = row.optionalFlag("registrable"),
        tier = row.optionalText("tier", 64),
        currency = pricing?.optionalText("currency", 16),
        registrationCost = pricing?.optionalPrice("registration_cost"),
        renewalCost = pricing?.optionalPrice("renewal_cost"),
        reason = row.optionalText("reason", 500),
    )
}

/** A candidate name is a domain, not free text: bounded, ASCII and dot/hyphen only. */
private fun JsonObject.requiredName(): String {
    val name = optionalText("name", 253) ?: throw invalidResponse(false)
    if (name.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '.' && it != '-' }) {
        throw invalidResponse(false)
    }
    if (name.startsWith('.') || name.endsWith('.') || name.contains("..")) throw invalidResponse(false)
    return name
}

private fun JsonObject.optionalText(key: String, maxLength: Int): String? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw invalidResponse(false)
    if (!primitive.isString) throw invalidResponse(false)
    val content = primitive.content
    if (content.length > maxLength) throw invalidResponse(false)
    return content.takeIf { it.isNotBlank() }
}

/** A price keeps its exact lexical decimal text; no Double, no rounding, no currency math. */
private fun JsonObject.optionalPrice(key: String): String? {
    val content = optionalText(key, 32) ?: return null
    if (!PricePattern.matches(content)) throw invalidResponse(false)
    return content
}

private fun JsonObject.optionalFlag(key: String): Boolean? {
    val value = this[key]
    if (value == null || value == JsonNull) return null
    val primitive = value as? JsonPrimitive ?: throw invalidResponse(false)
    if (primitive.isString) throw invalidResponse(false)
    return primitive.booleanOrNull ?: throw invalidResponse(false)
}

private val PricePattern = Regex("[0-9]{1,12}([.][0-9]{1,6})?")
