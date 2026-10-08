package com.flarego.core.cloudflare.protocol

import com.flarego.core.model.invalidInputFailure

/** Whether a registered action reads or writes provider state. */
enum class ActionSemantics {
    READ,
    WRITE,
}

/** How the response body of a registered action is decoded. */
enum class ResponseKind {
    ENVELOPE_JSON,
    RAW_JSON,
    TEXT,
    BINARY,
    EMPTY,
    NOT_MODIFIED,
}

/** How risky a registered action is. */
enum class RiskLevel {
    READ_ONLY,
    CHANGE,
    DESTRUCTIVE,
    FINANCIAL,
}

/**
 * Read-only retry policy.
 *
 * This batch never allows more than [MAX_READ_RETRIES] retries or more than
 * [MAX_READ_WAIT_MS] of cumulative waiting per send, and a write action may never
 * enable retries. Invalid parameters are rejected with a stable error.
 */
data class ReadRetryPolicy(
    val enabled: Boolean = false,
    val maxRetries: Int = 2,
    val maxWaitMs: Long = 60_000,
) {
    init {
        if (maxRetries !in 0..MAX_READ_RETRIES) throw invalidInputFailure()
        if (maxWaitMs !in 0..MAX_READ_WAIT_MS) throw invalidInputFailure()
    }

    companion object {
        const val MAX_READ_RETRIES: Int = 2
        const val MAX_READ_WAIT_MS: Long = 60_000
    }
}

/** The fixed public policy of one registered action. */
data class ActionPolicy(
    val key: String,
    val semantics: ActionSemantics,
    val responseKind: ResponseKind,
    val risk: RiskLevel,
    val retry: ReadRetryPolicy,
) {
    init {
        if (key.isBlank()) throw invalidInputFailure()
        if (semantics == ActionSemantics.WRITE && retry.enabled) throw invalidInputFailure()
    }
}

/**
 * The API scope a handwritten registration belongs to.
 *
 * This is a routing fact only: it says which part of the Cloudflare API the
 * action addresses, never that the current connection owns that account, zone or
 * user. Real ownership proof stays with the explicit scope guard.
 */
enum class ActionScope {
    USER,
    ACCOUNT,
    ZONE,
}

/** A handwritten method/path-template/scope binding for one action key. */
data class ActionRegistration(
    val policy: ActionPolicy,
    val method: String,
    val pathTemplate: String,
    val scope: ActionScope,
) {
    init {
        if (method !in ALLOWED_METHODS) throw invalidInputFailure()
        if (!templateMatchesScope()) throw invalidInputFailure()
        if (pathTemplate.isBlank() || !pathTemplate.startsWith("/") || pathTemplate.length > MAX_MATCH_LENGTH) {
            throw invalidInputFailure()
        }
    }

    /**
     * True when [method] and the relative [path] (query allowed, ignored for
     * matching) match this registration's method and template. Dangerous
     * structures — a missing leading slash, a protocol-relative or absolute URL,
     * backslash, fragment, control characters, empty or extra placeholders and
     * traversal in raw or encoded form — never match, so they are rejected before
     * the transport is called. Legitimate opaque identifiers are matched verbatim.
     */
    internal fun matches(method: String, path: String): Boolean {
        if (method != this.method) return false
        val relative = path.substringBefore('?')
        if (relative.length < 2 || relative.length > MAX_MATCH_LENGTH) return false
        if (!relative.startsWith("/") || relative.startsWith("//")) return false
        for (character in relative) {
            if (character.code !in 0x21..0x7E || character == '\\' || character == '#') return false
        }
        if (!hasValidEscapes(relative) || hasDangerousStructure(relative)) return false
        val templateSegments = pathTemplate.split('/').drop(1)
        val actualSegments = relative.split('/').drop(1)
        if (templateSegments.size != actualSegments.size) return false
        for ((template, actual) in templateSegments.zip(actualSegments)) {
            if (template.startsWith("{") && template.endsWith("}")) {
                if (actual.isEmpty() || actual.contains('{') || actual.contains('}')) return false
            } else if (template != actual) {
                return false
            }
        }
        return true
    }

    private fun templateMatchesScope(): Boolean =
        when (scope) {
            ActionScope.ACCOUNT ->
                pathTemplate == "/accounts/{account_id}" || pathTemplate.startsWith("/accounts/{account_id}/")
            ActionScope.ZONE -> pathTemplate.startsWith("/zones/{zone_id}")
            ActionScope.USER ->
                pathTemplate.startsWith("/user/") || pathTemplate == "/accounts" || pathTemplate == "/zones"
        }
}

/** A read-only lookup of handwritten registrations. */
class ActionRegistry internal constructor(internal val registrations: Map<String, ActionRegistration>) {
    fun policy(key: String): ActionPolicy? = registrations[key]?.policy

    internal fun registration(key: String): ActionRegistration? = registrations[key]

    internal companion object {
        fun of(vararg registrations: ActionRegistration): ActionRegistry =
            ActionRegistry(registrations.associateBy { it.policy.key })
    }
}

/**
 * The release-time handwritten policy registry.
 *
 * Sixteen actions: the eleven operations this app already calls plus the five P1
 * read-only operations. Every entry is written by hand here — nothing is loaded
 * from a schema, a remote document or the UI. Read-only actions enable the bounded
 * 429 retry; write actions never retry.
 */
object ActionPolicies {
    internal val registry: ActionRegistry = ActionRegistry.of(*defaultRegistrations())

    /** The handwritten policy for [key], or null when the action is not registered. */
    fun policy(key: String): ActionPolicy? = registry.policy(key)
}

private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private const val MAX_MATCH_LENGTH = 4096

private fun readPolicy(key: String) =
    ActionPolicy(key, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true))

private fun writePolicy(key: String, risk: RiskLevel) =
    ActionPolicy(key, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy())

private fun defaultRegistrations(): Array<ActionRegistration> {
    fun read(key: String, pathTemplate: String, scope: ActionScope) =
        ActionRegistration(readPolicy(key), "GET", pathTemplate, scope)

    fun write(key: String, method: String, pathTemplate: String, risk: RiskLevel) =
        ActionRegistration(writePolicy(key, risk), method, pathTemplate, ActionScope.ZONE)

    return arrayOf(
        read("accounts-account-details", "/accounts/{account_id}", ActionScope.ACCOUNT),
        read("d1-list-databases", "/accounts/{account_id}/d1/database", ActionScope.ACCOUNT),
        read("r2-list-buckets", "/accounts/{account_id}/r2/buckets", ActionScope.ACCOUNT),
        read("workers-kv-namespace-list-namespaces", "/accounts/{account_id}/storage/kv/namespaces", ActionScope.ACCOUNT),
        read("worker-script-list-workers", "/accounts/{account_id}/workers/scripts", ActionScope.ACCOUNT),
        read("user-billing-history-(-deprecated)-billing-history-details", "/user/billing/history", ActionScope.USER),
        read("zones-get", "/zones", ActionScope.USER),
        read("dns-records-for-a-zone-list-dns-records", "/zones/{zone_id}/dns_records", ActionScope.ZONE),
        write("dns-records-for-a-zone-create-dns-record", "POST", "/zones/{zone_id}/dns_records", RiskLevel.CHANGE),
        write("dns-records-for-a-zone-delete-dns-record", "DELETE", "/zones/{zone_id}/dns_records/{dns_record_id}", RiskLevel.DESTRUCTIVE),
        write("dns-records-for-a-zone-patch-dns-record", "PATCH", "/zones/{zone_id}/dns_records/{dns_record_id}", RiskLevel.CHANGE),
        read("accounts-list-accounts", "/accounts", ActionScope.USER),
        read("user-api-tokens-verify-token", "/user/tokens/verify", ActionScope.USER),
        read("account-api-tokens-verify-token", "/accounts/{account_id}/tokens/verify", ActionScope.ACCOUNT),
        read("listWorkers", "/accounts/{account_id}/workers/workers", ActionScope.ACCOUNT),
        read("account-billing-history-get-billing-history", "/accounts/{account_id}/billing/history", ActionScope.ACCOUNT),
    )
}

private fun hasValidEscapes(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            if (index + 2 >= value.length) return false
            if (!value[index + 1].isHexDigit() || !value[index + 2].isHexDigit()) return false
            index += 3
        } else {
            index++
        }
    }
    return true
}

/**
 * Decodes every valid escape level and reports whether any level contains a
 * dangerous structure (`//`, backslash, `.`/`..` segments). A literal percent is
 * copied verbatim, so an opaque identifier is never rejected for containing one.
 */
private fun hasDangerousStructure(path: String): Boolean {
    var current = path
    var rounds = 0
    while (true) {
        if (current.contains("//") || current.contains('\\')) return true
        if (current.split('/').any { it == "." || it == ".." }) return true
        val next = decodeValidEscapes(current) ?: return false
        if (next == current) return false
        current = next
        if (++rounds > path.length) return true
    }
}

private fun decodeValidEscapes(value: String): String? {
    val decoded = StringBuilder(value.length)
    var index = 0
    var changed = false
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()) {
            decoded.append(value.substring(index + 1, index + 3).toInt(16).toChar())
            index += 3
            changed = true
        } else {
            decoded.append(character)
            index++
        }
    }
    return if (changed) decoded.toString() else null
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
