package com.flarego.core.application.r2

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2CustomDomainStatus
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2ManagedDomain
import com.flarego.core.model.R2_DOMAIN_OWNERSHIP_VALUES
import com.flarego.core.model.R2_DOMAIN_SSL_VALUES
import com.flarego.core.model.R2_MAX_CONFIGURATION_DOCUMENT_BYTES
import com.flarego.core.model.R2_MIN_TLS_VERSIONS
import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local validation and encoding for the batch B2 domain plane.
 *
 * Everything here is a local decision taken before any request exists. Two rules drive the whole
 * file:
 *
 * - **Explicit state only.** A domain write never relies on a provider default: a new domain sends
 *   `enabled` explicitly and `minTLS` at least 1.2, and an edit omits every field it did not change.
 *   Only `minTLS` has a documented "defaults to previous value" omission; `enabled` and `ciphers`
 *   do not, so `enabled` is always explicit and `ciphers` is only ever the provider's own current
 *   list re-sent verbatim. This stage has **no reviewed evidence** for writing a new cipher list
 *   (including an empty one), so an explicit cipher edit is refused rather than described as a
 *   "clear" or a "default".
 * - **A domain is not a URL.** Only a canonical DNS name of at least two labels is accepted; scheme,
 *   userinfo, port, path, query, whitespace, wildcard and uppercase input are refused instead of
 *   being normalised into something else.
 */

private const val MAX_DOMAIN_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63
private const val MAX_ZONE_ID_LENGTH = 64
private const val MAX_CIPHERS = 32
private const val MAX_CIPHER_LENGTH = 64

/**
 * The syntactic safety bound of a provider-returned cipher list.
 *
 * The pinned schema gives no enum for `ciphers` ("BoringSSL format" only), so this is **not** a
 * claim about the provider's cipher vocabulary or support: it only bounds a token the app is
 * willing to copy back verbatim when it must preserve the current list. Algorithm-family guessing
 * is deliberately absent — the app never claims that a particular family or token is supported.
 */
private val CIPHER_TOKEN = Regex("^[A-Z0-9][A-Z0-9_-]{0,63}$")

private val LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
private val ZONE_ID = Regex("^[a-f0-9]{16,32}$")

private val MANAGED_KEYS = setOf("bucketId", "domain", "enabled")
private val CUSTOM_DOMAIN_KEYS = setOf("ciphers", "domain", "enabled", "minTLS", "status", "zoneId", "zoneName")
private val STATUS_KEYS = setOf("ownership", "ssl")
private val LIST_KEYS = setOf("domains")

private const val READ_ONLY_UNKNOWN = "该域名配置包含应用尚未支持的字段或取值，为避免覆盖未知内容，仅可查看"
private const val READ_ONLY_SHAPE = "无法确认该域名配置的当前状态，仅可查看"
private const val READ_ONLY_OVERSIZE = "该域名配置超过本地编辑上限（256 KiB），仅可查看原文，不会被截断或覆盖"

/**
 * The syntactic bound applied to a provider-returned cipher list before it may be preserved
 * verbatim. It says nothing about provider support and is never used to justify a new cipher list.
 */
internal fun validateR2Ciphers(ciphers: List<String>) {
    if (ciphers.size > MAX_CIPHERS) throw invalidInputFailure()
    for (cipher in ciphers) {
        if (cipher.length > MAX_CIPHER_LENGTH || !CIPHER_TOKEN.matches(cipher)) throw invalidInputFailure()
    }
}

/** The client's writing subset of `minTLS`: the pinned enum, and never below 1.2 for a new domain. */
internal fun validateR2MinTls(value: String) {
    if (value !in R2_MIN_TLS_VERSIONS) throw invalidInputFailure()
}

/**
 * Canonical DNS name validation, with no normalisation.
 *
 * The value must already be a lowercase ASCII name of at least two labels, each 1..63 characters of
 * `[a-z0-9-]` that starts and ends with an alphanumeric character, total length at most 253. A
 * mismatch (uppercase, trailing dot, scheme, userinfo, port, path, query, whitespace, wildcard) is
 * refused rather than converted, so the provider key is exactly what the caller reviewed.
 */
internal fun validateR2DomainName(value: String): String {
    if (value.isEmpty() || value.length > MAX_DOMAIN_LENGTH) throw invalidInputFailure()
    if (value != value.trim() || value.any { it.code !in 0x21..0x7E }) throw invalidInputFailure()
    if (value.contains("://") || value.contains('@') || value.contains('/') || value.contains('?') ||
        value.contains('#') || value.contains(':') || value.contains('*') || value.contains('_')
    ) {
        throw invalidInputFailure()
    }
    val labels = value.split('.')
    if (labels.size < 2) throw invalidInputFailure()
    for (label in labels) {
        if (label.isEmpty() || label.length > MAX_LABEL_LENGTH || !LABEL.matches(label)) throw invalidInputFailure()
    }
    // A numeric top-level label is not a DNS name this app will send.
    if (labels.last().all { it.isDigit() }) throw invalidInputFailure()
    return value
}

/** The zone id vocabulary of this app: a plain provider identifier, never a URL or a name. */
internal fun validateR2ZoneId(value: String): String {
    if (value.length !in 16..MAX_ZONE_ID_LENGTH || !ZONE_ID.matches(value)) throw invalidInputFailure()
    return value
}

/**
 * Whether [domain] is inside [zoneName] on a DNS label boundary.
 *
 * `cdn.example.com` belongs to `example.com`, but `evil-example.com` does **not**: the comparison is
 * on the label suffix, never on a plain string suffix.
 */
internal fun r2DomainBelongsToZone(domain: String, zoneName: String): Boolean {
    val canonicalDomain = validateR2DomainName(domain)
    val canonicalZone = validateR2DomainName(zoneName)
    if (canonicalDomain == canonicalZone) return true
    return canonicalDomain.endsWith(".$canonicalZone")
}

/** Validates the reviewed bucket target plus, for the single-domain actions, the fixed domain. */
internal fun validateR2DomainTarget(target: R2DomainTarget, kind: R2DomainKind): R2DomainTarget {
    val bucket = validateR2ConfigurationTarget(target.target)
    val domain = target.domain
    if (kind == R2DomainKind.CUSTOM_DOMAIN) {
        validateR2DomainName(domain ?: throw invalidInputFailure())
    } else if (domain != null) {
        throw invalidInputFailure()
    }
    return R2DomainTarget(bucket, domain)
}

/** Every reviewed B2 endpoint declares the controlled `cf-r2-jurisdiction` parameter. */
internal fun supportsR2DomainJurisdiction(kind: R2DomainKind): Boolean = kind in R2DomainKind.entries

/**
 * A new domain is never published by default: `enabled` and `minTLS` are always explicit.
 *
 * An explicit cipher list is refused: this stage has no reviewed evidence about which cipher
 * values (or an empty list) the provider accepts, so it neither guesses a default nor claims a
 * clear. A cipher list the provider already reports is only preserved verbatim on an edit.
 */
internal fun r2AddCustomDomainBody(input: R2CustomDomainCreateInput): JsonObject {
    val domain = validateR2DomainName(input.domain)
    val zoneId = validateR2ZoneId(input.zoneId)
    validateR2MinTls(input.minTls)
    if (input.ciphers != null) throw unsupportedCipherWrite()
    return buildJsonObject {
        put("domain", domain)
        put("zoneId", zoneId)
        put("enabled", input.enabled)
        put("minTLS", input.minTls)
    }
}

private fun unsupportedCipherWrite(): CloudFailure =
    CloudFailure(
        CloudFailureCode.UNSUPPORTED_CAPABILITY,
        "没有已审核证据表明新的 cipher 列表或空列表可安全写入；本阶段不发送 cipher 修改，仅按当前值原样保留",
    )

/**
 * The explicit part of an edit, used to validate the caller's intent before the current document is
 * read. Only `minTLS` may be omitted here: the pinned schema documents that an unset `minTLS`
 * "defaults to previous value", while neither `enabled` nor `ciphers` carries such a statement.
 */
internal fun r2EditCustomDomainBody(edit: R2CustomDomainEditInput): JsonObject {
    if (edit.enabled == null && edit.minTls == null && edit.ciphers == null) throw invalidInputFailure()
    // No reviewed evidence supports writing a new cipher list (or clearing one) in this stage.
    if (edit.ciphers != null) throw unsupportedCipherWrite()
    return buildJsonObject {
        edit.enabled?.let { put("enabled", it) }
        edit.minTls?.let { validateR2MinTls(it); put("minTLS", it) }
    }
}

/**
 * The final edit body, built from the caller's intent **and** the independently read current domain.
 *
 * `enabled` is always sent explicitly: the pinned schema never says an omitted `enabled` keeps the
 * previous value, so the proven current boolean is used when the caller did not change it. `ciphers`
 * is sent as the caller's explicit list, or as the current list re-sent verbatim when that list is
 * complete and inside the documented client subset; a missing or unreadable current list is not a
 * proven "keep previous", so that edit is refused instead of guessed. `minTLS` stays omitted unless
 * the caller changed it, because that omission is documented as "defaults to previous value".
 */
internal fun r2EditCustomDomainBody(edit: R2CustomDomainEditInput, current: R2CustomDomain): JsonObject {
    if (edit.ciphers != null) throw unsupportedCipherWrite()
    val enabled = edit.enabled ?: current.enabled
    // `ciphers` has no documented "omit keeps previous" semantic, so the provider's own current
    // list is re-sent verbatim. A missing or syntactically unsafe list is not a proven preserve.
    val ciphers =
        current.ciphers ?: throw CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "无法证实现有 cipher 允许列表，未发送域名编辑；本阶段不写入新的 cipher 列表",
        )
    validateR2Ciphers(ciphers)
    return buildJsonObject {
        put("enabled", enabled)
        edit.minTls?.let { validateR2MinTls(it); put("minTLS", it) }
        put("ciphers", JsonArray(ciphers.map(::JsonPrimitive)))
    }
}

/** The complete edited body cannot exceed the local safe editing bound. */
internal fun requireR2DomainBodyWithinLimits(body: JsonObject) {
    if (body.toString().encodeToByteArray().size > R2_MAX_CONFIGURATION_DOCUMENT_BYTES) throw invalidInputFailure()
}

/** `enabled` travels explicitly for the r2.dev endpoint, so closing it is never an omission. */
internal fun r2ManagedDomainBody(enabled: Boolean): JsonObject = buildJsonObject { put("enabled", enabled) }

/** The complete body of one domain write, or null for the delete (no payload). */
internal fun r2DomainWriteBody(write: R2DomainConfigurationWrite): JsonObject? =
    when (write) {
        is R2DomainConfigurationWrite.PutManagedDomain -> r2ManagedDomainBody(write.enabled)
        is R2DomainConfigurationWrite.AddCustomDomain -> r2AddCustomDomainBody(write.input)
        is R2DomainConfigurationWrite.EditCustomDomain -> r2EditCustomDomainBody(write.edit)
        is R2DomainConfigurationWrite.DeleteCustomDomain -> null
    }

/** The provider's own result object of one domain read, checked only for the declared shape. */
internal fun rawR2DomainResult(root: JsonObject, kind: R2DomainKind): JsonObject {
    val result = root["result"] as? JsonObject ?: throw invalidResponseShape()
    when (kind) {
        R2DomainKind.MANAGED_DOMAIN -> {
            result["enabled"]?.let { element ->
                val primitive = element as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) throw invalidResponseShape()
            }
        }
        R2DomainKind.CUSTOM_DOMAINS -> result["domains"]?.let { if (it !is JsonArray) throw invalidResponseShape() }
        R2DomainKind.CUSTOM_DOMAIN -> {
            result["enabled"]?.let { element ->
                val primitive = element as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) throw invalidResponseShape()
            }
        }
    }
    return result
}

/**
 * Why a provider domain document cannot be edited losslessly, or null when it can.
 *
 * `enabled` is only one field: ownership and SSL are separate provider states and are kept as such.
 * An unknown member or enum makes the whole document read-only instead of being dropped.
 */
internal fun r2DomainReadOnlyReason(kind: R2DomainKind, raw: JsonObject): String? {
    if (raw.toString().encodeToByteArray().size > R2_MAX_CONFIGURATION_DOCUMENT_BYTES) return READ_ONLY_OVERSIZE
    return when (kind) {
        R2DomainKind.MANAGED_DOMAIN ->
            when {
                raw.keys != MANAGED_KEYS -> READ_ONLY_UNKNOWN
                raw.stringOrNull("domain") == null || raw.stringOrNull("bucketId") == null -> READ_ONLY_SHAPE
                raw.booleanOrNull("enabled") == null -> READ_ONLY_SHAPE
                else -> null
            }
        R2DomainKind.CUSTOM_DOMAINS -> {
            if (raw.keys != LIST_KEYS) {
                READ_ONLY_UNKNOWN
            } else {
                val rows = raw["domains"] as? JsonArray ?: return READ_ONLY_SHAPE
                if (rows.all { decodeReadOnlyDomain(it) == null }) null else READ_ONLY_UNKNOWN
            }
        }
        R2DomainKind.CUSTOM_DOMAIN ->
            if (decodeReadOnlyDomain(raw) == null) null else READ_ONLY_UNKNOWN
    }
}

/** Null when the row is a fully understood custom domain, otherwise the rejection reason. */
private fun decodeReadOnlyDomain(element: kotlinx.serialization.json.JsonElement): String? {
    val row = element as? JsonObject ?: return READ_ONLY_SHAPE
    if (row.keys != CUSTOM_DOMAIN_KEYS) return READ_ONLY_UNKNOWN
    if (row.stringOrNull("domain") == null || row.booleanOrNull("enabled") == null) return READ_ONLY_SHAPE
    row.stringOrNull("minTLS")?.let { if (it !in R2_MIN_TLS_VERSIONS) return READ_ONLY_UNKNOWN }
    row["ciphers"]?.let { ciphers ->
        val array = ciphers as? JsonArray ?: return READ_ONLY_UNKNOWN
        val values =
            array.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content ?: return READ_ONLY_UNKNOWN }
        // An outside-subset value keeps the document read-only; it is never re-sent as supported.
        try {
            validateR2Ciphers(values)
        } catch (_: CloudFailure) {
            return READ_ONLY_UNKNOWN
        }
    }
    row["status"]?.let { status ->
        val object_ = status as? JsonObject ?: return READ_ONLY_UNKNOWN
        if (object_.keys != STATUS_KEYS) return READ_ONLY_UNKNOWN
        val ownership = object_.stringOrNull("ownership") ?: return READ_ONLY_SHAPE
        val ssl = object_.stringOrNull("ssl") ?: return READ_ONLY_SHAPE
        if (ownership !in R2_DOMAIN_OWNERSHIP_VALUES || ssl !in R2_DOMAIN_SSL_VALUES) return READ_ONLY_UNKNOWN
    }
    return null
}

/** Typed decode of one custom domain; used only after [r2DomainReadOnlyReason] approved the row. */
internal fun decodeR2CustomDomain(row: JsonObject): R2CustomDomain {
    val status = (row["status"] as? JsonObject)?.let {
        R2CustomDomainStatus(
            ownership = it.stringOrNull("ownership") ?: throw invalidInputFailure(),
            ssl = it.stringOrNull("ssl") ?: throw invalidInputFailure(),
        )
    }
    return R2CustomDomain(
        domain = row.stringOrNull("domain") ?: throw invalidInputFailure(),
        enabled = row.booleanOrNull("enabled") ?: throw invalidInputFailure(),
        minTls = row.stringOrNull("minTLS"),
        ciphers = (row["ciphers"] as? JsonArray)?.map { (it as JsonPrimitive).content },
        zoneId = row.stringOrNull("zoneId"),
        zoneName = row.stringOrNull("zoneName"),
        status = status,
        raw = row,
    )
}

internal fun decodeR2ManagedDomain(raw: JsonObject): R2ManagedDomain =
    R2ManagedDomain(
        domain = raw.stringOrNull("domain") ?: throw invalidInputFailure(),
        enabled = raw.booleanOrNull("enabled") ?: throw invalidInputFailure(),
        bucketId = raw.stringOrNull("bucketId"),
    )

/** The observable, order-independent state of one domain document, for a later read-back compare. */
internal fun r2DomainObservable(kind: R2DomainKind, raw: JsonObject): JsonObject =
    when (kind) {
        R2DomainKind.MANAGED_DOMAIN ->
            buildJsonObject {
                put("domain", raw.stringOrNull("domain"))
                put("enabled", raw.booleanOrNull("enabled"))
            }
        R2DomainKind.CUSTOM_DOMAINS ->
            buildJsonObject {
                put("domains", JsonArray((raw["domains"] as? JsonArray).orEmpty().map { row -> r2DomainObservable(R2DomainKind.CUSTOM_DOMAIN, row as JsonObject) }))
            }
        R2DomainKind.CUSTOM_DOMAIN ->
            buildJsonObject {
                put("domain", raw.stringOrNull("domain"))
                put("enabled", raw.booleanOrNull("enabled"))
                put("minTLS", raw.stringOrNull("minTLS"))
                put("ciphers", raw["ciphers"] ?: JsonArray(emptyList()))
            }
    }

private fun invalidResponseShape(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, "响应无法识别，请稍后重试")

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** Text used by the client when a document cannot be edited. */
internal const val R2_DOMAIN_READ_ONLY_WRITE =
    "该域名配置包含应用尚未支持的字段或取值，已阻止写入以避免覆盖未知内容"
