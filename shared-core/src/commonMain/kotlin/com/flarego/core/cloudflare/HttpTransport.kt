package com.flarego.core.cloudflare

import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.ByteSource

/**
 * Request payload of one Cloudflare call.
 *
 * Each variant carries its own media type, so binary content is never routed
 * through a string, and every variant is sent as a one-shot body. A content type
 * is checked for full media-type syntax here — a shape check is not a syntax
 * proof — and never echoed back on failure.
 */
sealed interface HttpBody {
    /** No payload at all (a `POST` with this body is still sent as an empty body). */
    object Empty : HttpBody

    data class Text(val value: String, val contentType: String) : HttpBody {
        init {
            requireMediaType(contentType)
        }
    }

    data class Bytes(val value: ByteArray, val contentType: String) : HttpBody {
        init {
            requireMediaType(contentType)
        }
    }

    /**
     * Streamed upload. [length] is the declared byte count, or null when the
     * provider length is unknown. A negative length is rejected, a known length
     * that disagrees with the bytes produced fails the call, and an unknown
     * length is never padded.
     */
    data class Stream(val source: ByteSource, val length: Long?, val contentType: String) : HttpBody {
        init {
            requireMediaType(contentType)
            if (length != null && length < 0) throw invalidInputFailure()
        }
    }

    data class Multipart(val parts: List<MultipartPart>) : HttpBody {
        init {
            if (parts.isEmpty()) throw invalidInputFailure()
        }
    }
}

/**
 * One multipart part.
 *
 * Only [HttpBody.Text], [HttpBody.Bytes] and [HttpBody.Stream] are accepted:
 * an empty part or a nested multipart part is rejected, and `name`, `filename`
 * and `contentType` are validated so no CRLF or quoted-string break can be
 * injected into the part headers.
 */
data class MultipartPart(
    val name: String,
    val filename: String? = null,
    val contentType: String,
    val body: HttpBody,
) {
    init {
        requirePartMetadata(name)
        filename?.let { requirePartMetadata(it) }
        requireMediaType(contentType)
        if (body is HttpBody.Empty || body is HttpBody.Multipart) throw invalidInputFailure()
    }
}

/**
 * One request against the fixed Cloudflare API host.
 *
 * [headers] carries reviewed business headers only; bearer credentials and the
 * `Accept` header are injected by the platform transport. Header and path policy
 * is enforced by the platform, because it belongs to the fixed Cloudflare host.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val payload: HttpBody = HttpBody.Empty,
    val headers: Map<String, String> = emptyMap(),
) {
    /** Legacy JSON call site: a non-null string maps to [HttpBody.Text], null to [HttpBody.Empty]. */
    constructor(method: String, path: String, body: String?) : this(
        method,
        path,
        body?.let { HttpBody.Text(it, "application/json; charset=utf-8") } ?: HttpBody.Empty,
    )

    /** Legacy read-only accessor for JSON call sites. */
    val body: String?
        get() = (payload as? HttpBody.Text)?.value
}

/**
 * One response from the fixed Cloudflare API host.
 *
 * [bytes] keeps the exact provider bytes; [headers] keeps multi-value headers and
 * the provider's own casing. The status is never rewritten, so a `202`, `204`,
 * `304` or an error status stays visible to the caller.
 */
data class HttpResponse(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    /** Legacy text construction (JSON call sites only). */
    constructor(status: Int, body: String) :
        this(status, body.encodeToByteArray(), emptyMap())

    /** Legacy UTF-8 text view. Never opens, reads or drains a stream. */
    val body: String
        get() = bytes.decodeToString()
}

fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

fun encodePath(value: String): String =
    value.encodeToByteArray().joinToString("") { byte ->
        val n = byte.toInt() and 255
        val c = n.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~") c.toString()
        else "%" + n.toString(16).uppercase().padStart(2, '0')
    }

/**
 * Validates a media type against the HTTP grammar: `type "/" subtype` of tokens
 * plus optional `;` parameters whose value is a token or a quoted string. A
 * space inside the type, a missing subtype or an unbalanced quote is rejected.
 * The rejected value is never echoed.
 */
private fun requireMediaType(value: String): String {
    if (value.isBlank() || value.length > 255) throw invalidInputFailure()
    if (value.any { it.code !in 0x20..0x7E }) throw invalidInputFailure()
    val segments = value.split(';')
    val type = segments.first().trim()
    if (type.count { it == '/' } != 1) throw invalidInputFailure()
    val slash = type.indexOf('/')
    if (!isMediaToken(type.substring(0, slash)) || !isMediaToken(type.substring(slash + 1))) {
        throw invalidInputFailure()
    }
    for (parameter in segments.drop(1)) requireMediaParameter(parameter)
    return value
}

private fun requireMediaParameter(value: String) {
    val parameter = value.trim()
    val equals = parameter.indexOf('=')
    if (equals <= 0) throw invalidInputFailure()
    if (!isMediaToken(parameter.substring(0, equals).trim())) throw invalidInputFailure()
    val parameterValue = parameter.substring(equals + 1).trim()
    if (parameterValue.isEmpty()) throw invalidInputFailure()
    if (parameterValue.startsWith("\"")) {
        if (parameterValue.length < 2 || !parameterValue.endsWith("\"")) throw invalidInputFailure()
        val quoted = parameterValue.substring(1, parameterValue.length - 1)
        if (quoted.any { it.code !in 0x20..0x7E || it == '"' }) throw invalidInputFailure()
    } else if (!isMediaToken(parameterValue)) {
        throw invalidInputFailure()
    }
}

private fun isMediaToken(value: String): Boolean =
    value.isNotEmpty() && value.all { it.isMediaTokenCharacter() }

private fun Char.isMediaTokenCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this in "!#$%&'*+-.^_`|~"

/** Validates multipart part metadata without echoing the rejected value. */
private fun requirePartMetadata(value: String): String {
    if (value.isBlank() || value.length > 255) throw invalidInputFailure()
    if (value.any { it.code !in 0x20..0x7E || it == '"' || it == '\\' }) throw invalidInputFailure()
    return value
}
