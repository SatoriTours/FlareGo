package com.flarego.core.application.r2

import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.invalidInputFailure

/** Official `r2_bucket_name` from the pinned schema: minLength 3, maxLength 64, `^[a-z0-9][a-z0-9-]*[a-z0-9]`. */
private val BUCKET_NAME = Regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$")

/** An IPv4-shaped name is refused even though it also matches the character rules. */
private val IPV4_SHAPE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** `r2_bucket_location` enum in the pinned schema. */
internal val R2_LOCATION_HINTS = setOf("apac", "eeur", "enam", "weur", "wnam", "oc")

/** `r2_storage_class` enum in the pinned schema. */
internal val R2_STORAGE_CLASSES = setOf("Standard", "InfrequentAccess")

/** Longest object key the R2 REST API accepts, measured in UTF-8 bytes. */
private const val MAX_OBJECT_KEY_BYTES = 1024

/**
 * Validates a bucket name and returns it unchanged.
 *
 * The character rules come from the pinned schema; the extra rejections
 * (IPv4 shape, `xn--` prefix, `-s3alias` suffix) are S3-compatibility rules the
 * provider also enforces. The rejected value is never echoed back.
 */
fun validateR2BucketName(name: String): String {
    if (name.length !in 3..64 || !BUCKET_NAME.matches(name)) throw invalidInputFailure()
    if (IPV4_SHAPE.matches(name)) throw invalidInputFailure()
    if (name.startsWith("xn--") || name.endsWith("-s3alias")) throw invalidInputFailure()
    return name
}

/**
 * Validates an object key and returns it unchanged; no decoding happens here.
 *
 * The key travels as a literal path. [encodeR2ObjectKeyPath] keeps `/` literal, as
 * the provider requires, and percent-encodes every other reserved character.
 */
fun validateR2ObjectKey(key: String): String {
    if (key.isEmpty() || key == "." || key == ".." || key.startsWith('/')) throw invalidInputFailure()
    if (key.encodeToByteArray().size > MAX_OBJECT_KEY_BYTES) throw invalidInputFailure()
    for (character in key) {
        val code = character.code
        if (character == '\\' || code <= 0x1F || code in 0x7F..0x9F) throw invalidInputFailure()
    }
    return key
}

/**
 * Validates a listing prefix or continuation key.
 *
 * An empty value means "no prefix" and is legal; anything else must have the shape of
 * an object key, because it is sent into the same path/query position.
 */
fun validateR2ObjectKeyPrefix(prefix: String): String {
    if (prefix.isEmpty()) return prefix
    if (prefix.encodeToByteArray().size > MAX_OBJECT_KEY_BYTES) throw invalidInputFailure()
    for (character in prefix) {
        val code = character.code
        if (character == '\\' || code <= 0x1F || code in 0x7F..0x9F) throw invalidInputFailure()
    }
    return prefix
}

/** The official grouping delimiter is exactly one character. */
fun validateR2Delimiter(delimiter: String): String {
    if (delimiter.length != 1) throw invalidInputFailure()
    val code = delimiter[0].code
    if (code <= 0x1F || code in 0x7F..0x9F) throw invalidInputFailure()
    return delimiter
}

/** Validates creation input against the official enums; an unknown value never reaches a write. */
fun validateR2CreateInput(input: R2BucketCreateInput): R2BucketCreateInput {
    val name = validateR2BucketName(input.name)
    input.locationHint?.let { if (it !in R2_LOCATION_HINTS) throw invalidInputFailure() }
    input.storageClass?.let { if (it !in R2_STORAGE_CLASSES) throw invalidInputFailure() }
    return input.copy(name = name)
}

/**
 * Encodes one object key for a path template.
 *
 * The pinned `object_key` parameter states that slashes inside a key MUST be sent
 * literally and MUST NOT become `%2F`, while other reserved characters are
 * percent-encoded as usual. A literal `%` therefore becomes `%25`, so typed text can
 * never turn into a path separator.
 */
fun encodeR2ObjectKeyPath(key: String): String =
    key.encodeToByteArray().joinToString("") { byte ->
        val value = byte.toInt() and 255
        val character = value.toChar()
        val unreserved = character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '_' || character == '.' || character == '~'
        if (character == '/' || unreserved) character.toString()
        else "%" + value.toString(16).uppercase().padStart(2, '0')
    }
