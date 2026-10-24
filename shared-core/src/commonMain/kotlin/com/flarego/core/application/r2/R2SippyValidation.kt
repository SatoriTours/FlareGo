package com.flarego.core.application.r2

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2SippyDestinationRef
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2SippySourceRef
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_SIPPY_MAX_BODY_BYTES
import com.flarego.core.model.R2_SIPPY_MAX_SECRET_BYTES
import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local validation, the one-shot sensitive encoder and the safe GET projection of batch B3.
 *
 * Nothing here is loaded from a schema at runtime. The rules are:
 * - the pinned request shapes are the only writable fields, and a provider's fields are never
 *   borrowed from another provider;
 * - `bucketUrl` is only ever submitted to Cloudflare — this app never contacts it, and a URL with
 *   userinfo, query or fragment (a place to smuggle a secret) is refused;
 * - the sensitive JSON is built once, bounded, and never kept in a model/snapshot/error;
 * - the GET result is projected through an exact allowlist. Unknown/echoed members (including
 *   nested ones) make the snapshot read-only, and the raw JSON is not retained anywhere.
 */

/** Fixed message for a Sippy write against a snapshot this app cannot interpret. */
internal const val R2_SIPPY_READ_ONLY_WRITE =
    "该 Sippy 配置包含应用尚未支持的字段或取值，已阻止写入以避免覆盖未知内容"

private const val READ_ONLY_UNKNOWN = "该 Sippy 配置包含应用尚未支持的字段或取值，仅可查看"
private const val READ_ONLY_SHAPE = "无法确认该 Sippy 配置的当前状态，仅可查看"

private const val MAX_BUCKET = 255
private const val MAX_REGION = 64
private const val MAX_CONTAINER = 255
private const val MAX_ACCOUNT_NAME = 128
private const val MAX_EMAIL = 320
private const val MAX_ACCESS_KEY_ID = 256
private const val MAX_URL = 2048

private val BUCKET_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
private val REGION = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
private val CONTAINER = Regex("^[a-z0-9][a-z0-9-]{0,254}$")
private val ACCOUNT_NAME = Regex("^[a-z0-9][a-z0-9-]{0,127}$")
private val ACCESS_KEY_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$")
private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$")

private fun fail(code: CloudFailureCode = CloudFailureCode.INVALID_INPUT): Nothing =
    throw CloudFailure(code, "Sippy 输入不符合已审核的字段要求，请检查后重试")

private fun requirePrintableSecret(value: String, allowWhitespace: Boolean = false) {
    if (value.isEmpty() || value.encodeToByteArray().size > R2_SIPPY_MAX_SECRET_BYTES) fail()
    for (character in value) {
        val code = character.code
        val allowedWhitespace = allowWhitespace && (character == '\n' || character == '\r' || character == '\t')
        if (code < 0x20 || code == 0x7F) {
            if (!allowedWhitespace) fail()
        }
    }
}

/** The bucket, jurisdiction and account scope of one Sippy write/read. */
internal fun validateR2SippyTarget(target: R2WriteTarget): R2WriteTarget = validateR2ConfigurationTarget(target)

/**
 * A generic S3-compatible endpoint URL, safe to submit and safe to display.
 *
 * Only `https://host[:port][/path]` is accepted: no userinfo, no query and no fragment (the three
 * places a credential could hide), no whitespace/control and no host wildcard. It is never resolved
 * or contacted by this app.
 */
internal fun validateR2SippyBucketUrl(value: String): String {
    if (value.isEmpty() || value.length > MAX_URL) fail()
    if (value.any { it.code < 0x21 || it.code > 0x7E }) fail()
    if (!value.startsWith("https://")) fail()
    val rest = value.removePrefix("https://")
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
    if (authority.isEmpty() || authority.contains('@')) fail()
    val host = authority.substringBefore(':')
    if (host.isEmpty() || host.length > 253) fail()
    if (host.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-') }) fail()
    if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) fail()
    if (authority.contains(':')) {
        val port = authority.substringAfter(':')
        if (port.isEmpty() || port.length > 5 || port.any { it !in '0'..'9' }) fail()
        if (port.toIntOrNull() !in 1..65535) fail()
    }
    val tail = rest.substring(authority.length)
    if (tail.contains('?') || tail.contains('#')) fail()
    if (tail.isNotEmpty() && !tail.startsWith('/')) fail()
    return value
}

internal fun validateR2SippyDestination(destination: R2SippyDestinationSecrets) {
    if (!ACCESS_KEY_ID.matches(destination.accessKeyId)) fail()
    requirePrintableSecret(destination.secretAccessKey)
}

/** The four reviewed source shapes, with Azure's exactly-one-of secret rule. */
internal fun validateR2SippySource(source: R2SippySourceSecrets) {
    when (source) {
        is R2SippyAwsSourceSecrets -> {
            if (!ACCESS_KEY_ID.matches(source.accessKeyId)) fail()
            requirePrintableSecret(source.secretAccessKey)
            if (!BUCKET_NAME.matches(source.bucket)) fail()
            if (!REGION.matches(source.region)) fail()
        }
        is R2SippyGcsSourceSecrets -> {
            if (!EMAIL.matches(source.clientEmail) || source.clientEmail.length > MAX_EMAIL) fail()
            requirePrintableSecret(source.privateKey, allowWhitespace = true)
            if (!BUCKET_NAME.matches(source.bucket)) fail()
        }
        is R2SippyS3SourceSecrets -> {
            if (!ACCESS_KEY_ID.matches(source.accessKeyId)) fail()
            requirePrintableSecret(source.secretAccessKey)
            validateR2SippyBucketUrl(source.bucketUrl)
        }
        is R2SippyAzureSourceSecrets -> {
            if (!ACCOUNT_NAME.matches(source.accountName)) fail()
            if (!CONTAINER.matches(source.container)) fail()
            val key = source.accountKey
            val sas = source.sasToken
            if ((key == null) == (sas == null)) fail()
            key?.let { requirePrintableSecret(it) }
            sas?.let { requirePrintableSecret(it) }
        }
    }
}

/** True only for a lease that can still be admitted (a draft). */
internal fun R2SippySecretLease.requireDraft() {
    if (!isDraft) fail()
}

/**
 * The one-shot sensitive JSON body of `r2-put-bucket-sippy-config`.
 *
 * Every field is explicit and only the pinned schema fields are written. The function returns the
 * text for immediate wrapping in [com.flarego.core.cloudflare.HttpBody.SensitiveJson]; callers must
 * not store or log it. Validation happens before the final guard.
 */
internal fun encodeR2SippyEnableBody(lease: R2SippySecretLease): com.flarego.core.cloudflare.HttpBody.SensitiveJson {
    val material = lease.borrowMaterialForEncoding() ?: fail()
    validateR2SippySource(material.source)
    validateR2SippyDestination(material.destination)
    val destination = material.destination
    val source = material.source
    val body =
        buildJsonObject {
            put(
                "destination",
                buildJsonObject {
                    put("provider", "r2")
                    put("accessKeyId", destination.accessKeyId)
                    put("secretAccessKey", destination.secretAccessKey)
                },
            )
            put(
                "source",
                when (source) {
                    is R2SippyAwsSourceSecrets ->
                        buildJsonObject {
                            put("provider", "aws")
                            put("bucket", source.bucket)
                            put("region", source.region)
                            put("accessKeyId", source.accessKeyId)
                            put("secretAccessKey", source.secretAccessKey)
                        }
                    is R2SippyGcsSourceSecrets ->
                        buildJsonObject {
                            put("provider", "gcs")
                            put("bucket", source.bucket)
                            put("clientEmail", source.clientEmail)
                            put("privateKey", source.privateKey)
                        }
                    is R2SippyS3SourceSecrets ->
                        buildJsonObject {
                            put("provider", "s3")
                            put("bucketUrl", source.bucketUrl)
                            put("accessKeyId", source.accessKeyId)
                            put("secretAccessKey", source.secretAccessKey)
                        }
                    is R2SippyAzureSourceSecrets ->
                        buildJsonObject {
                            put("provider", "azure")
                            put("accountName", source.accountName)
                            put("container", source.container)
                            source.accountKey?.let { put("accountKey", it) }
                            source.sasToken?.let { put("sasToken", it) }
                        }
                },
            )
        }
    val text = body.toString()
    if (text.encodeToByteArray().size > R2_SIPPY_MAX_BODY_BYTES) fail()
    return com.flarego.core.cloudflare.HttpBody.SensitiveJson(text, "application/json; charset=utf-8")
}

/** The pinned response envelope must carry a `result` object; the raw result is projected, not kept. */
internal fun rawR2SippyResult(root: JsonObject): JsonObject =
    root["result"] as? JsonObject
        ?: throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "响应无法识别，请稍后重试")

private fun readOnly(reason: String) = R2SippySnapshot(false, null, null, editable = false, readOnlyReason = reason)

/**
 * The safe allowlist projection of one Sippy GET result.
 *
 * The destination must be this account/bucket; a different echoed destination is a scope failure.
 * Unknown or unexpected members (including an echoed secret) make the snapshot read-only and are
 * dropped. No raw JSON is retained by the returned value.
 */
internal fun decodeR2SippySnapshot(target: R2WriteTarget, raw: JsonObject): R2SippySnapshot {
    // The pinned r2_sippy object has no required member: a 200 may omit/null destination/source.
    if (raw.keys.any { it !in setOf("destination", "enabled", "source") }) return readOnly(READ_ONLY_UNKNOWN)
    val enabled = raw.booleanOrNull("enabled") ?: return readOnly(READ_ONLY_SHAPE)
    val destinationRaw = raw["destination"]
    val sourceRaw = raw["source"]
    val destination =
        if (destinationRaw == null || destinationRaw is kotlinx.serialization.json.JsonNull) null
        else parseR2SippyDestination(target, destinationRaw) ?: return readOnly(READ_ONLY_UNKNOWN)
    val source =
        if (sourceRaw == null || sourceRaw is kotlinx.serialization.json.JsonNull) null
        else parseR2SippySource(sourceRaw) ?: return readOnly(READ_ONLY_UNKNOWN)
    if (destination == null || source == null) {
        // The pinned "disabled, public details not provided" shape: a known disabled state, never an
        // absence/404 claim. An enabled config without details cannot be interpreted.
        if (enabled && (destination == null && source == null)) return readOnly(READ_ONLY_SHAPE)
        if (enabled) return readOnly(READ_ONLY_UNKNOWN)
        return R2SippySnapshot(
            enabled = false,
            destination = null,
            source = null,
            editable = true,
            readOnlyReason = null,
            detailsProvided = false,
        )
    }
    return R2SippySnapshot(
        enabled = enabled,
        destination = destination,
        source = source,
        editable = true,
        readOnlyReason = null,
        detailsProvided = true,
    )
}

/** The destination allowlist; a different account/bucket is a scope failure, not a read-only state. */
private fun parseR2SippyDestination(target: R2WriteTarget, element: kotlinx.serialization.json.JsonElement): R2SippyDestinationRef? {
    val destination = element as? JsonObject ?: return null
    if (destination.keys != setOf("accessKeyId", "account", "bucket", "provider")) return null
    if (destination.stringOrNull("provider") != "r2") return null
    val account = destination.stringOrNull("account") ?: return null
    val bucket = destination.stringOrNull("bucket") ?: return null
    val accessKeyId = destination.stringOrNull("accessKeyId") ?: return null
    if (!ACCESS_KEY_ID.matches(accessKeyId)) return null
    if (account != target.account.accountId || bucket != target.bucket) {
        throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "该 Sippy 配置不属于当前桶或账号，请刷新后重试")
    }
    return R2SippyDestinationRef(account = account, bucket = bucket, accessKeyId = accessKeyId)
}

/**
 * The pinned `r2_sippy.source` has one uniform property set `{provider, bucket, bucketUrl,
 * container, region}` with every provider-specific member nullable and none required. A provider's
 * fields may therefore be present as null or omitted; a non-null field from another provider is a
 * contradiction, and an unknown member (a possible echo) makes the source uninterpretable.
 */
private fun parseR2SippySource(element: kotlinx.serialization.json.JsonElement): R2SippySourceRef? {
    val source = element as? JsonObject ?: return null
    if (source.keys.any { it !in setOf("provider", "bucket", "bucketUrl", "container", "region") }) return null
    fun stringIfPresent(key: String): String? = source.stringOrNull(key)
    val bucket = stringIfPresent("bucket")
    val bucketUrl = stringIfPresent("bucketUrl")
    val container = stringIfPresent("container")
    val region = stringIfPresent("region")
    return when (source.stringOrNull("provider")) {
        "aws" -> {
            if (bucket == null || !BUCKET_NAME.matches(bucket)) return null
            if (bucketUrl != null || container != null) return null
            if (region != null && !REGION.matches(region)) return null
            R2SippySourceRef(R2SippyProvider.AWS, bucket = bucket, region = region)
        }
        "gcs" -> {
            if (bucket == null || !BUCKET_NAME.matches(bucket)) return null
            if (bucketUrl != null || container != null || region != null) return null
            R2SippySourceRef(R2SippyProvider.GCS, bucket = bucket)
        }
        "s3" -> {
            if (bucketUrl == null) return null
            if (bucket != null || container != null || region != null) return null
            try {
                validateR2SippyBucketUrl(bucketUrl)
            } catch (_: CloudFailure) {
                return null
            }
            R2SippySourceRef(R2SippyProvider.S3, bucketUrl = bucketUrl)
        }
        "azure" -> {
            if (container == null || !CONTAINER.matches(container)) return null
            if (bucket != null || bucketUrl != null || region != null) return null
            R2SippySourceRef(R2SippyProvider.AZURE, container = container)
        }
        else -> null
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
