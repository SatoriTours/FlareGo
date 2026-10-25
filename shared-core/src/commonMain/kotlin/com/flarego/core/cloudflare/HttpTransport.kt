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

    /**
     * One-shot sensitive JSON body (batch B3 Sippy credentials).
     *
     * Its content is never part of `toString`, a log line, an error message or a plan/task: the
     * value is readable exactly once through [consumeBytes] and is refused on any second send.
     * The platform transport treats it as ordinary bytes on the wire; the gateway never retries it.
     */
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    class SensitiveJson(
        initialText: String,
        val contentType: String,
    ) : HttpBody {
        init {
            requireMediaType(contentType)
        }

        /** The one temporary text reference; taken out on the single send or on discard. */
        private val text = kotlin.concurrent.atomics.AtomicReference<String?>(initialText)
        private val consumed = kotlin.concurrent.atomics.AtomicReference(false)

        /** Deterministic internal observation for tests; never depends on GC timing. */
        internal val retainsContent: Boolean get() = text.load() != null

        /** The exact UTF-8 bytes of this body, exactly once; a replay is refused, never re-sent. */
        fun consumeBytes(): ByteArray {
            if (!consumed.compareAndSet(false, true)) throw invalidInputFailure()
            while (true) {
                val current = text.load() ?: throw invalidInputFailure()
                if (text.compareAndSet(current, null)) return current.encodeToByteArray()
            }
        }

        /** Drops the temporary encoded value on every refusal path (best effort on the JVM). */
        fun discard() {
            if (consumed.compareAndSet(false, true)) text.store(null)
        }

        override fun toString(): String = "SensitiveJson(redacted)"

        override fun equals(other: Any?): Boolean = this === other

        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/**
 * One multipart part.
 *
 * Only [HttpBody.Text], [HttpBody.Bytes], [HttpBody.Stream] and one-shot [HttpBody.SensitiveJson] are accepted:
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

    /** A sensitive-capable response never prints its body through `toString`. */
    override fun toString(): String =
        "HttpResponse(status=$status, bytes=${bytes.size}, headers=${headers.keys.sorted()})"
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
 * plus optional `;` parameters whose value is a token or a quoted string. The
 * split is quote-aware, so a semicolon inside a quoted parameter stays part of
 * that value; surrounding whitespace, whitespace around `=`, a missing subtype,
 * an unterminated quote or an invalid token is rejected. The rejected value is
 * never echoed.
 */
private fun requireMediaType(value: String): String {
    if (value.isEmpty() || value.length > 255) throw invalidInputFailure()
    if (value != value.trim()) throw invalidInputFailure()
    if (value.any { it.code !in 0x20..0x7E }) throw invalidInputFailure()
    val segments = splitMediaParameters(value)
    val type = segments.first()
    if (type.any { it == ' ' } || type.count { it == '/' } != 1) throw invalidInputFailure()
    val slash = type.indexOf('/')
    if (!isMediaToken(type.substring(0, slash)) || !isMediaToken(type.substring(slash + 1))) {
        throw invalidInputFailure()
    }
    for (parameter in segments.drop(1)) requireMediaParameter(parameter)
    return value
}

/** Splits on `;` outside quoted strings; an unterminated quote is invalid. */
private fun splitMediaParameters(value: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            character == '\\' && quoted && index + 1 < value.length -> {
                current.append(character).append(value[index + 1])
                index += 2
            }
            character == '"' -> {
                quoted = !quoted
                current.append(character)
                index++
            }
            character == ';' && !quoted -> {
                segments.add(current.toString())
                current.setLength(0)
                index++
            }
            else -> {
                current.append(character)
                index++
            }
        }
    }
    if (quoted) throw invalidInputFailure()
    segments.add(current.toString())
    return segments
}

private fun requireMediaParameter(value: String) {
    // Only the optional whitespace after `;` is allowed; whitespace around `=` is not.
    val parameter = value.trimStart(' ')
    val equals = parameter.indexOf('=')
    if (equals <= 0) throw invalidInputFailure()
    if (!isMediaToken(parameter.substring(0, equals))) throw invalidInputFailure()
    val parameterValue = parameter.substring(equals + 1)
    if (parameterValue.isEmpty()) throw invalidInputFailure()
    if (parameterValue.startsWith("\"")) requireMediaQuotedString(parameterValue)
    else if (!isMediaToken(parameterValue)) throw invalidInputFailure()
}

/** A quoted-string parameter value, with `\"`/`\\` pairs allowed and a closing quote required. */
private fun requireMediaQuotedString(value: String) {
    var index = 1
    while (index < value.length) {
        when {
            value[index] == '\\' -> index += 2
            value[index] == '"' -> {
                if (index != value.length - 1) throw invalidInputFailure()
                return
            }
            value[index].code !in 0x20..0x7E -> throw invalidInputFailure()
            else -> index++
        }
    }
    throw invalidInputFailure()
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
