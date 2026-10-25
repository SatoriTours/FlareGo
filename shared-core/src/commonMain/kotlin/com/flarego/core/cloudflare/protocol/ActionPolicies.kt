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
    /**
     * True only for reviewed actions whose final placeholder owns an opaque value that
     * may itself contain `/` (R2 object keys, which the provider requires to be sent
     * literally). Every such registration still forbids an encoded separator.
     */
    val trailingPlaceholderSpansSegments: Boolean = false,
) {
    init {
        if (method !in ALLOWED_METHODS) throw invalidInputFailure()
        if (!templateMatchesScope()) throw invalidInputFailure()
        if (pathTemplate.isBlank() || !pathTemplate.startsWith("/") || pathTemplate.length > MAX_MATCH_LENGTH) {
            throw invalidInputFailure()
        }
        if (trailingPlaceholderSpansSegments) {
            val lastSegment = pathTemplate.split('/').drop(1).lastOrNull()
            if (lastSegment == null || !lastSegment.startsWith("{") || !lastSegment.endsWith("}")) {
                throw invalidInputFailure()
            }
        }
    }

    /**
     * True when [method] and the relative [path] (query included) match this
     * registration's method and template.
     *
     * The whole request target is validated before the transport is ever called:
     * a missing leading slash, a protocol-relative or absolute URL, backslash,
     * fragment (raw or encoded), control characters, invalid percent escapes,
     * malformed UTF-8, traversal and dangerous structures in the raw text *and* in
     * every decoded stage are rejected. The decoded path must not cross a
     * placeholder boundary, so an encoded slash cannot turn one placeholder into
     * extra segments. Legitimate opaque identifiers — encoded Unicode, emoji,
     * combining marks, literal percent signs, encoded spaces and query separators —
     * are matched and passed through verbatim.
     */
    internal fun matches(method: String, path: String): Boolean {
        if (method != this.method) return false
        val checked = if (policy.key in KV_KEY_ACTIONS) {
            com.flarego.core.cloudflare.kv.KvEncodedKeyPath.maskForValidation(path) ?: return false
        } else path
        if (!requestShapeIsSafe(checked)) return false
        val rawPath = checked.substringBefore('?')
        val templateSegments = pathTemplate.split('/').drop(1)
        val rawSegments = rawPath.split('/').drop(1)
        // A reviewed trailing placeholder may own the whole remainder, so a value that
        // legitimately contains `/` still stays one placeholder. Separators may still
        // appear only literally: the provider decodes the received path once, so a single
        // `%2F` must be refused while `%252F` (a key that really contains `%2F`) stays whole.
        if (!trailingPlaceholderSpansSegments) {
            if (templateSegments.size == rawSegments.size) {
                if (!segmentsMatch(templateSegments, rawSegments)) return false
                val decodedPath = decodedStages(rawPath)?.lastOrNull() ?: rawPath
                return decodedPath.split('/').drop(1).size == templateSegments.size
            }
            return false
        }
        if (rawSegments.size < templateSegments.size) return false
        val prefixTemplates = templateSegments.dropLast(1)
        if (!segmentsMatch(prefixTemplates, rawSegments)) return false
        val trailing = rawSegments.drop(prefixTemplates.size).joinToString("/")
        if (trailing.isEmpty() || trailing.contains('{') || trailing.contains('}')) return false
        // `decodedStages` returns an empty list when nothing decoded and null when a decoded
        // stage is not valid UTF-8; both fall back to the literal text.
        val singleDecoded = decodedStages(trailing).orEmpty().firstOrNull() ?: trailing
        if (singleDecoded.count { it == '/' } != trailing.count { it == '/' }) return false
        return singleDecoded.isNotEmpty()
    }

    /** Every template segment matches its actual segment; placeholders stay one opaque value. */
    private fun segmentsMatch(templateSegments: List<String>, rawSegments: List<String>): Boolean {
        for ((template, actual) in templateSegments.zip(rawSegments)) {
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
                pathTemplate == "/accounts/{account_id}" || pathTemplate.startsWith("/accounts/{account_id}/") ||
                    // Zone creation carries the selected account in its typed body.
                    // Admit only this reviewed write, never arbitrary user-path actions.
                    (policy.key == "zones-post" && method == "POST" && pathTemplate == "/zones" &&
                        policy.semantics == ActionSemantics.WRITE && policy.responseKind == ResponseKind.ENVELOPE_JSON &&
                        policy.risk == RiskLevel.CHANGE)
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
 * The original P1 actions plus the reviewed P2 Zone lifecycle operations, the
 * reviewed P3 R2 batch A operations and the reviewed P3 R2 batch B1 configuration
 * operations. Each batch owns its own handwritten registration function.
 * Every entry is written by hand here — nothing is loaded
 * from a schema, a remote document or the UI. Read-only actions enable the bounded
 * 429 retry; write actions never retry.
 */
object ActionPolicies {
    internal val registry: ActionRegistry = ActionRegistry.of(*defaultRegistrations())

    /** The handwritten policy for [key], or null when the action is not registered. */
    fun policy(key: String): ActionPolicy? = registry.policy(key)
}

private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private val KV_KEY_ACTIONS = setOf(
    "workers-kv-namespace-read-the-metadata-for-a-key",
    "workers-kv-namespace-read-key-value-pair",
    "workers-kv-namespace-write-key-value-pair-with-metadata",
    "workers-kv-namespace-delete-key-value-pair",
)
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
        read("r2-list-buckets", "/accounts/{account_id}/r2/buckets", ActionScope.ACCOUNT),
        read("worker-script-list-workers", "/accounts/{account_id}/workers/scripts", ActionScope.ACCOUNT),
        read("user-billing-history-(-deprecated)-billing-history-details", "/user/billing/history", ActionScope.USER),
        read("zones-get", "/zones", ActionScope.USER),
        read("dns-records-for-a-zone-list-dns-records", "/zones/{zone_id}/dns_records", ActionScope.ZONE),
        read("dns-records-for-a-zone-dns-record-details", "/zones/{zone_id}/dns_records/{dns_record_id}", ActionScope.ZONE),
        write("dns-records-for-a-zone-create-dns-record", "POST", "/zones/{zone_id}/dns_records", RiskLevel.CHANGE),
        write("dns-records-for-a-zone-delete-dns-record", "DELETE", "/zones/{zone_id}/dns_records/{dns_record_id}", RiskLevel.DESTRUCTIVE),
        write("dns-records-for-a-zone-patch-dns-record", "PATCH", "/zones/{zone_id}/dns_records/{dns_record_id}", RiskLevel.CHANGE),
        write("dns-records-for-a-zone-update-dns-record", "PUT", "/zones/{zone_id}/dns_records/{dns_record_id}", RiskLevel.CHANGE),
        read("accounts-list-accounts", "/accounts", ActionScope.USER),
        read("user-api-tokens-verify-token", "/user/tokens/verify", ActionScope.USER),
        read("account-api-tokens-verify-token", "/accounts/{account_id}/tokens/verify", ActionScope.ACCOUNT),
        read("listWorkers", "/accounts/{account_id}/workers/workers", ActionScope.ACCOUNT),
        read("account-billing-history-get-billing-history", "/accounts/{account_id}/billing/history", ActionScope.ACCOUNT),
        ActionRegistration(writePolicy("zones-post", RiskLevel.CHANGE), "POST", "/zones", ActionScope.ACCOUNT),
        read("zones-0-get", "/zones/{zone_id}", ActionScope.ZONE),
        write("zones-0-patch", "PATCH", "/zones/{zone_id}", RiskLevel.DESTRUCTIVE),
        write("zones-0-delete", "DELETE", "/zones/{zone_id}", RiskLevel.DESTRUCTIVE),
        write("put-zones-zone_id-activation_check", "PUT", "/zones/{zone_id}/activation_check", RiskLevel.CHANGE),
        write("dns-records-for-a-zone-batch-dns-records", "POST", "/zones/{zone_id}/dns_records/batch", RiskLevel.DESTRUCTIVE),
        *zoneSettingsRegistrations(),
        *dnssecRegistrations(),
        *dnsFilesRegistrations(),
        *dnsScanRegistrations(),
        *tokenAuthorityRegistrations(),
        *r2Registrations(),
        *r2ConfigurationRegistrations(),
        *r2DomainRegistrations(),
        *r2SippyRegistrations(),
        *r2JobRegistrations(),
        *r2NotificationRegistrations(),
        *r2MetricsRegistrations(),
        *kvRegistrations(),
        *d1Registrations(),
    )
}

/** Validates the complete request target: raw text, escapes and every decoded stage. */
private fun requestShapeIsSafe(request: String): Boolean {
    if (request.length < 2 || request.length > MAX_MATCH_LENGTH) return false
    if (!request.startsWith("/") || request.startsWith("//")) return false
    for (character in request) {
        if (character.code !in 0x21..0x7E || character == '\\' || character == '#') return false
    }
    if (!hasValidEscapes(request)) return false
    // The raw text is itself a stage: without this, a path with no escapes would
    // have no structural check at all (`all {}` is vacuously true).
    if (!stageIsSafe(request)) return false
    val stages = decodedStages(request) ?: return false
    return stages.all { stageIsSafe(it) }
}

/**
 * Structural safety of one decoded stage. Control code points (C0, DEL, C1) and a
 * fragment are rejected everywhere; the path part must stay a single rooted path
 * without `//`, backslash or `.`/`..` segments; a query value may legitimately
 * contain encoded separators (`/`, `+`, `=`, space), so only controls and
 * backslashes are rejected there.
 */
private fun stageIsSafe(stage: String): Boolean {
    for (character in stage) {
        if (character == '#' || character.isIsoControlCodePoint()) return false
    }
    val pathPart = stage.substringBefore('?')
    val queryPart = stage.substringAfter('?', "")
    if (!pathPart.startsWith("/") || pathPart.contains("//") || pathPart.contains('\\')) return false
    if (pathPart.split('/').any { it == "." || it == ".." }) return false
    if (queryPart.contains('\\')) return false
    return true
}

private fun Char.isIsoControlCodePoint(): Boolean = code <= 0x1F || code in 0x7F..0x9F

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
 * Every decoded stage of [value], or null when a stage is not valid UTF-8.
 *
 * Only valid escapes are decoded, so a literal percent owned by an opaque
 * identifier is copied verbatim; each round strictly shrinks the string, and the
 * bound below only guards against a pathological input.
 */
private fun decodedStages(value: String): List<String>? {
    val stages = mutableListOf<String>()
    var current = value
    var rounds = 0
    while (true) {
        val bytes = decodeValidEscapesToBytes(current) ?: return stages
        if (!bytesAreValidUtf8(bytes)) return null
        current = bytes.decodeToString()
        stages += current
        if (++rounds > value.length) return null
    }
}

/** Decodes one level of valid escapes into bytes; null when nothing was decoded. */
private fun decodeValidEscapesToBytes(value: String): ByteArray? {
    var buffer = ByteArray(value.length + 8)
    var size = 0
    var changed = false
    fun push(byte: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = byte
    }
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()) {
            push(value.substring(index + 1, index + 3).toInt(16).toByte())
            index += 3
            changed = true
        } else {
            for (byte in character.toString().encodeToByteArray()) push(byte)
            index++
        }
    }
    return if (changed) buffer.copyOf(size) else null
}

/**
 * Strict UTF-8: rejects stray continuation bytes, overlong encodings, surrogate
 * code points and anything above U+10FFFF. A legal multi-byte character is opaque
 * identity, and its continuation bytes are never inspected as individual controls.
 */
private fun bytesAreValidUtf8(bytes: ByteArray): Boolean {
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        val extra =
            when {
                first < 0x80 -> 0
                first in 0xC2..0xDF -> 1
                first in 0xE0..0xEF -> 2
                first in 0xF0..0xF4 -> 3
                else -> return false
            }
        if (index + extra >= bytes.size) return false
        for (offset in 1..extra) {
            if ((bytes[index + offset].toInt() and 0xFF) !in 0x80..0xBF) return false
        }
        if (extra >= 1) {
            val second = bytes[index + 1].toInt() and 0xFF
            when (first) {
                0xE0 -> if (second !in 0xA0..0xBF) return false
                0xED -> if (second !in 0x80..0x9F) return false
                0xF0 -> if (second !in 0x90..0xBF) return false
                0xF4 -> if (second !in 0x80..0x8F) return false
            }
        }
        index += extra + 1
    }
    return true
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
