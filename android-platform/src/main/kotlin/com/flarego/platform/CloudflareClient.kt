package com.flarego.platform

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.MultipartPart
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2_JURISDICTIONS
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

/** The only accepted API origin, including the Cloudflare API version prefix. */
internal const val CLOUDFLARE_API_BASE = "https://api.cloudflare.com:443/client/v4"

internal const val CLOUDFLARE_API_HOST = "api.cloudflare.com"

/** Fixed non-echoing message for every rejected request shape. */
private const val INVALID_INPUT_MESSAGE = "请求内容或路径不符合安全规则，请检查后重试"

private const val MAX_PATH_LENGTH = 4096

/** HTTP methods this transport is allowed to construct. */
private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

/**
 * The only business headers a caller may set. Everything else (credentials,
 * `Host`, hop-by-hop headers, framing headers, or any unknown header) is refused,
 * so a business call site cannot bypass the injected request policy.
 */
private val ALLOWED_BUSINESS_HEADERS =
    setOf(
        "content-type",
        "if-match",
        "if-none-match",
        "if-modified-since",
        "if-unmodified-since",
        "range",
        // R2 bucket writes carry the default storage class here: the pinned
        // `r2-patch-bucket` and `r2-create-bucket-by-name` have no request body at all.
        "cf-r2-storage-class",
        // The controlled jurisdiction header of the pinned R2 configuration endpoints.
        // Its value is restricted to the schema enum below, so no call site can smuggle
        // an arbitrary value onto the wire through this header.
        "cf-r2-jurisdiction",
        // The controlled data-catalog protection header of the reviewed create-job request.
        // Its value is fixed to `true` and its route to a POST bucket-jobs call below.
        "cf-r2-data-catalog-check",
    )

internal fun invalidInput(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, INVALID_INPUT_MESSAGE)

/**
 * The production client policy: no connection-failure retry, no redirects, no
 * automatic authentication follow-up, and the bounded 15/20/35 second timeouts.
 */
internal fun cloudflareClientBuilder(): OkHttpClient.Builder =
    OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)

/** Production entry point. Has no URL rewriter and no host override. */
internal fun cloudflareHttpClient(): OkHttpClient = cloudflareClientBuilder().build()

/**
 * Trusted test seam: the same production policy, plus an application interceptor
 * that may rewrite the already-constructed fixed Cloudflare URL before DNS and
 * before any connection is opened. It is a trusted-code seam for local tests, not
 * a runtime permission check, and production call sites do not use it.
 */
internal fun cloudflareHttpClient(rewrite: (Request) -> Request): OkHttpClient =
    cloudflareClientBuilder().addInterceptor { chain -> chain.proceed(rewrite(chain.request())) }.build()

/** File-transfer policy: longer total and read timeouts, identical safety policy. */
internal fun cloudflareFileClientBuilder(): OkHttpClient.Builder =
    cloudflareClientBuilder().callTimeout(600, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)

internal fun cloudflareFileClient(): OkHttpClient = cloudflareFileClientBuilder().build()

internal fun cloudflareFileClient(rewrite: (Request) -> Request): OkHttpClient =
    cloudflareFileClientBuilder()
        .addInterceptor { chain -> chain.proceed(rewrite(chain.request())) }
        .build()

/**
 * Builds the only request shape this client may send: the fixed Cloudflare
 * origin, a reviewed relative path, the injected bearer/accept headers, an
 * allowlisted set of business headers and a one-shot body. [isActive] lets the
 * streaming body stop as soon as the calling coroutine is no longer active.
 */
/** Internal response negotiation only; business headers cannot override it. */
internal enum class CloudflareAccept(val header: String) { JSON("application/json"), FILE("*/*") }

internal fun cloudflareRequest(
    token: String,
    request: HttpRequest,
    accept: CloudflareAccept = CloudflareAccept.JSON,
    isActive: () -> Boolean = { true },
    onSensitiveBody: ((SensitiveBytesBody) -> Unit)? = null,
): Request {
    val method = requireHttpMethod(request.method)
    val path = requireApiPath(request.path)
    val bodyless = method == "GET" || method == "HEAD"
    if (bodyless && request.payload != HttpBody.Empty) throw invalidInput()
    val headers = requireBusinessHeaders(method, path, request.headers, request.payload)
    val body = if (bodyless) null else request.payload.toRequestBody(isActive, onSensitiveBody)
    val builder =
        Request.Builder()
            .url(requireCloudflareUrl(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", accept.header)
    for ((name, value) in headers) builder.header(name, value)
    return builder.method(method, body).build()
}

/**
 * Runs one blocking OkHttp call on the platform IO thread and owns its
 * cancellation lifecycle.
 *
 * The blocking call runs on a separate IO worker while this coroutine awaits it.
 * When the caller is cancelled, the resumption happens on this same IO thread, so
 * the call is cancelled *and* the owned upload sources/sink are closed there —
 * before the worker returns. Closing releases a blocked `read`/`write` for any
 * implementation that follows the ownership contract, so the worker stops
 * promptly instead of continuing in the background.
 */
internal suspend fun <T> runCloudflareCall(
    call: Call,
    owned: OwnedResources,
    block: (Response) -> T,
): T {
    return withContext(Dispatchers.IO) {
        // The worker captures its own outcome, including the socket error that
        // `call.cancel()` may produce, so a cancelled call can never surface as an
        // IO failure of the caller and no worker failure can escape unobserved.
        val running = async(Dispatchers.IO) { runCatching { call.execute().use(block) } }
        try {
            running.await().getOrThrow()
        } catch (cancelled: CancellationException) {
            call.cancel()
            owned.closeAll()
            running.cancel()
            running.await() // the worker stops as soon as the socket or the source is released
            throw cancelled
        }
    }
}

/**
 * Owned upload sources plus the optional download sink, each closed at most once.
 *
 * Cleanup is independent per resource: a failing `close` never skips the other
 * sources or the sink, and repeated calls are no-ops. The first cleanup failure
 * is reported so the caller can keep a primary cancellation/validation/IO failure
 * authoritative.
 */
internal class OwnedResources(payload: HttpBody, private val sink: ByteSink? = null) {
    private val sources: List<ByteSource> = uploadSourcesOf(payload)
    private val closedSources: MutableSet<ByteSource> =
        Collections.newSetFromMap(IdentityHashMap<ByteSource, Boolean>())
    private val sensitiveBodies = mutableListOf<SensitiveBytesBody>()
    private var sinkClosed = false
    private var failure: Throwable? = null

    /** The transport adopts the sensitive body it built, so every exit path can clear its buffer. */
    fun adoptSensitive(body: SensitiveBytesBody) {
        synchronized(this) { sensitiveBodies += body }
    }

    /** Closes every owned resource once, never throwing. */
    fun closeAll(): Throwable? =
        synchronized(this) {
            for (source in sources) {
                if (!closedSources.add(source)) continue
                attempt { source.close() }
            }
            val ownedSink = sink
            if (ownedSink != null && !sinkClosed) {
                sinkClosed = true
                attempt { ownedSink.close() }
            }
            for (body in sensitiveBodies) attempt { body.clearSensitiveBuffer() }
            failure
        }

    /**
     * Cleanup on the platform IO thread. `NonCancellable` matters: when the caller
     * is already cancelled, a plain `withContext` would never enter its block and
     * the ownership transfer would leak.
     */
    suspend fun closeAllOnIo(): Throwable? =
        withContext(NonCancellable + Dispatchers.IO) { closeAll() }

    private inline fun attempt(close: () -> Unit) {
        try {
            close()
        } catch (thrown: Throwable) {
            if (failure == null) failure = thrown
        }
    }
}

private fun uploadSourcesOf(payload: HttpBody): List<ByteSource> =
    when (payload) {
        is HttpBody.Stream -> listOf(payload.source)
        is HttpBody.Multipart ->
            payload.parts.mapNotNull { (it.body as? HttpBody.Stream)?.source }
        else -> emptyList()
    }

private fun requireHttpMethod(raw: String): String {
    if (raw !in ALLOWED_METHODS) throw invalidInput()
    return raw
}

/**
 * Accepts only a relative API path that stays inside the fixed prefix after
 * normalization: no scheme, no protocol-relative `//host`, no backslash, no
 * fragment, no control characters, no traversal in raw or repeatedly
 * percent-encoded form. Legitimate percent-encoded query content (for example a
 * pagination cursor) stays valid, and an opaque identifier that legitimately
 * contains a literal percent sign (for example `encodePath("id%GG")` →
 * `id%25GG`) is preserved verbatim instead of being decoded again as a new
 * escape.
 *
 * The check is closed: the raw path and *every* fully decoded form are validated
 * for the same dangerous structures, and decoding continues until no valid escape
 * remains (each round strictly shrinks the string, so this terminates). A form
 * that still contains an undecidable `%` sequence is a literal percent, not
 * ambiguity, and is only accepted when the decoded form is safe.
 */
private fun requireApiPath(raw: String): String {
    if (raw.length <= 1 || raw.length > MAX_PATH_LENGTH) throw invalidInput()
    if (!raw.startsWith("/") || raw.startsWith("//")) throw invalidInput()
    for (character in raw) {
        if (!character.isRawUrlCharacter() || character == '\\' || character == '#') throw invalidInput()
    }
    requireValidEscapes(raw)
    val inspected = com.flarego.core.cloudflare.kv.KvEncodedKeyPath.maskForValidation(raw) ?: raw
    val queryAt = inspected.indexOf('?')
    val rawPath = if (queryAt < 0) inspected else inspected.substring(0, queryAt)
    val rawQuery = if (queryAt < 0) "" else inspected.substring(queryAt + 1)
    requireSafePath(rawPath)
    requireSafeQuery(rawQuery)
    var decoded = rawPath
    var rounds = 0
    while (decoded.contains('%')) {
        val next = decodeStage(decoded) ?: break
        // Never accept an ambiguous budget: every decoded stage is checked.
        requireSafePath(next)
        decoded = next
        rounds++
        if (rounds > raw.length) throw invalidInput()
    }
    var decodedQuery = rawQuery
    var queryRounds = 0
    while (decodedQuery.contains('%')) {
        val next = decodeStage(decodedQuery) ?: break
        requireSafeQuery(next)
        decodedQuery = next
        queryRounds++
        if (queryRounds > raw.length) throw invalidInput()
    }
    return raw
}

private fun requireSafePath(pathPart: String) {
    if (!pathPart.startsWith("/") || pathPart.contains("//")) throw invalidInput()
    for (segment in pathPart.split('/')) {
        if (segment == "." || segment == "..") throw invalidInput()
    }
    for (character in pathPart) {
        if (!character.isDecodedUrlCharacter() || character == '\\' || character == '#') {
            throw invalidInput()
        }
    }
}

/**
 * Query values are opaque data (a cursor may legitimately contain `/`, `+`, `=`
 * or an encoded space), so the decoded query only rejects control characters —
 * the structure checks belong to the path.
 */
private fun requireSafeQuery(queryPart: String) {
    for (character in queryPart) {
        if (!character.isDecodedUrlCharacter()) throw invalidInput()
    }
}

/** Raw URL text: printable ASCII without space, controls or non-ASCII. */
private fun Char.isRawUrlCharacter(): Boolean = code in 0x21..0x7E

/**
 * Decoded URL text: any legal Unicode character is opaque identity (a canonical
 * UTF-8 multi-byte character is not a control byte and its continuation bytes are
 * never inspected individually), but a code point that is itself a control
 * character is rejected like any other control: C0 (U+0000..U+001F), DEL
 * (U+007F) and C1 (U+0080..U+009F). This matches `Character.isISOControl`; a
 * canonical UTF-8 encoding of a C1 code point still decodes to a real control.
 */
private fun Char.isDecodedUrlCharacter(): Boolean = !isIsoControlCodePoint()

private fun Char.isIsoControlCodePoint(): Boolean = code <= 0x1F || code in 0x7F..0x9F

private fun requireValidEscapes(value: String) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            if (index + 2 >= value.length) throw invalidInput()
            if (!value[index + 1].isHexDigit() || !value[index + 2].isHexDigit()) throw invalidInput()
            index += 3
        } else {
            index++
        }
    }
}

/**
 * Decodes one level of *valid* percent escapes into bytes and re-reads those
 * bytes as strict UTF-8. A `%` that is not followed by two hex digits is a
 * literal percent (an opaque identifier may contain one) and is copied verbatim,
 * so no parse exception can escape and the identifier is preserved. A malformed
 * UTF-8 sequence (truncated, overlong, surrogate or stray continuation) is
 * rejected. Returns null when there is no valid escape left to decode.
 */
private fun decodeStage(value: String): String? {
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    var decoded = false
    while (index < value.length) {
        val character = value[index]
        if (character == '%' &&
            index + 2 < value.length &&
            value[index + 1].isHexDigit() &&
            value[index + 2].isHexDigit()
        ) {
            bytes.write(value.substring(index + 1, index + 3).toInt(16))
            index += 3
            decoded = true
        } else {
            bytes.write(character.toString().encodeToByteArray())
            index++
        }
    }
    if (!decoded) return null
    return try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        throw invalidInput()
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Final normalization check: whatever the caller wrote, the parsed URL must
 * still be the fixed Cloudflare origin and stay under the API prefix.
 */
private fun requireCloudflareUrl(path: String): HttpUrl {
    val url = (CLOUDFLARE_API_BASE + path).toHttpUrlOrNull() ?: throw invalidInput()
    if (url.scheme != "https") throw invalidInput()
    if (url.host != CLOUDFLARE_API_HOST) throw invalidInput()
    if (url.port != 443) throw invalidInput()
    if (url.fragment != null) throw invalidInput()
    if (!url.encodedPath.startsWith("/client/v4/")) throw invalidInput()
    return url
}

/** The exact reviewed create-job route shape; the protection header is not a substring match. */
private val R2_CREATE_JOB_PATH = Regex("^/accounts/[^/]+/r2/buckets/[^/]+/jobs$")

private fun requireBusinessHeaders(
    method: String,
    path: String,
    headers: Map<String, String>,
    payload: HttpBody,
): Map<String, String> {
    // A case-variant duplicate of the same business header is ambiguous and refused.
    val catalogHeaderEntries = headers.keys.count { it.equals("cf-r2-data-catalog-check", ignoreCase = true) }
    if (catalogHeaderEntries > 1) throw invalidInput()
    val accepted = LinkedHashMap<String, String>()
    for ((name, value) in headers) {
        val normalized = name.lowercase()
        if (normalized !in ALLOWED_BUSINESS_HEADERS) throw invalidInput()
        if (name.isEmpty() || name.length > 255 || name.any { it.code !in 0x21..0x7E }) throw invalidInput()
        if (value.length > 4096) throw invalidInput()
        if (value.any { it.code !in 0x20..0x7E }) throw invalidInput()
        if (normalized == "content-type") requireMatchingContentType(value, payload)
        if (normalized == "cf-r2-jurisdiction" && value !in R2_JURISDICTIONS) throw invalidInput()
        if (normalized == "cf-r2-data-catalog-check") {
            // Only the exact reviewed create-job POST may carry it, and only the fixed `true` value.
            if (method != "POST") throw invalidInput()
            if (!R2_CREATE_JOB_PATH.matches(path)) throw invalidInput()
            if (value != "true") throw invalidInput()
        }
        accepted[name] = value
    }
    return accepted
}

private fun requireMatchingContentType(value: String, payload: HttpBody) {
    requireSendableMediaType(value)
    when (payload) {
        is HttpBody.Empty -> return
        is HttpBody.Multipart -> throw invalidInput()
        is HttpBody.Text -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Bytes -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Stream -> requireSameMediaType(value, payload.contentType)
        is HttpBody.SensitiveJson -> requireSameMediaType(value, payload.contentType)
    }
}

/**
 * The single platform-side send gate for every media type that reaches the wire:
 * typed bodies, multipart parts and the business `Content-Type` header all pass
 * through here. The core layer already proves the syntax; this is the last gate
 * before OkHttp parses the value, so a value OkHttp cannot parse can never be sent
 * with its content type silently dropped, and it never echoes itself through an
 * `IllegalArgumentException`.
 */
private fun requireSendableMediaType(value: String): String {
    if (value.toMediaTypeOrNull() == null) throw invalidInput()
    return value
}

private fun requireSameMediaType(value: String, declared: String) {
    if (!value.trim().equals(declared.trim(), ignoreCase = true)) throw invalidInput()
}

private fun HttpBody.toRequestBody(
    isActive: () -> Boolean,
    onSensitiveBody: ((SensitiveBytesBody) -> Unit)? = null,
): RequestBody =
    when (this) {
        is HttpBody.Empty -> EmptyBody
        is HttpBody.Text -> BytesBody(value.encodeToByteArray(), requireSendableMediaType(contentType))
        is HttpBody.Bytes -> BytesBody(value, requireSendableMediaType(contentType))
        is HttpBody.Stream -> StreamBody(source, length, requireSendableMediaType(contentType), isActive)
        // A sensitive body becomes bytes exactly once; the transport owns the buffer so it can clear
        // it even when the call fails or is cancelled before the write.
        is HttpBody.SensitiveJson -> {
            val sensitive =
                SensitiveBytesBody(consumeBytes(), requireSendableMediaType(contentType).toMediaTypeOrNull())
            onSensitiveBody?.invoke(sensitive)
            sensitive
        }
        is HttpBody.Multipart -> multipartBody(parts, isActive, onSensitiveBody)
    }

/**
 * The transport-owned buffer of one sensitive body.
 *
 * It is written exactly once and then best-effort zeroed, so the platform never leaves the encoded
 * credential bytes in a reusable [RequestBody]. This is not a promise of physical erasure (the JVM
 * may have copied the bytes already); a second write is refused.
 */
internal class SensitiveBytesBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
) : RequestBody() {
    private var written = false

    @Volatile
    private var cleared = false

    /** Deterministic internal observation for tests; never prints or reflects material. */
    internal val isSensitiveBufferCleared: Boolean get() = cleared

    /**
     * Best-effort release of the transport-owned buffer; idempotent and never throws.
     *
     * It is called after the single write (success or failure) and by the transport's exit
     * cleanup even when the call failed or was cancelled before the write.
     */
    internal fun clearSensitiveBuffer() {
        if (cleared) return
        cleared = true
        bytes.fill(0)
    }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    /** The sensitive body is never replayed, exactly like the ordinary one-shot bodies. */
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        if (written) throw java.io.IOException("sensitive body already written")
        written = true
        // A failing write must still release the transport-owned buffer.
        try {
            sink.write(bytes)
        } finally {
            clearSensitiveBuffer()
        }
    }
}

/** An explicit zero-length one-shot body: `POST` with no payload still sends a body. */
private object EmptyBody : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaTypeOrNull()

    override fun contentLength() = 0L

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {}
}

private class BytesBody(private val bytes: ByteArray, private val mediaType: String) : RequestBody() {
    override fun contentType() = mediaType.toMediaTypeOrNull()

    override fun contentLength() = bytes.size.toLong()

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

/**
 * One-shot streaming body with a fixed buffer. A declared length is reported to
 * the server and verified against the bytes the source really produced; an
 * unknown length is sent without padding. Activity is checked before every read
 * and before every write, so cancellation stops the upload between reads instead
 * of spinning or continuing in the background.
 */
private class StreamBody(
    private val source: ByteSource,
    private val length: Long?,
    private val mediaType: String,
    private val isActive: () -> Boolean,
) : RequestBody() {
    override fun contentType() = mediaType.toMediaTypeOrNull()

    override fun contentLength() = length ?: -1L

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var written = 0L
        while (true) {
            requireActive()
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            requireActive()
            sink.write(buffer, 0, read)
            written += read
        }
        val declared = length
        if (declared != null && written != declared) {
            throw IOException("Upload body length mismatch")
        }
    }

    private fun requireActive() {
        if (!isActive()) throw IOException("Upload cancelled")
    }
}

private fun multipartBody(parts: List<MultipartPart>, isActive: () -> Boolean,
    onSensitiveBody: ((SensitiveBytesBody) -> Unit)?): RequestBody {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
    for (part in parts) {
        val body = part.body.toPartBody(part, isActive, onSensitiveBody)
        if (part.filename == null) builder.addFormDataPart(part.name, null, body)
        else builder.addFormDataPart(part.name, part.filename, body)
    }
    return builder.build()
}

private fun HttpBody.toPartBody(part: MultipartPart, isActive: () -> Boolean,
    onSensitiveBody: ((SensitiveBytesBody) -> Unit)?): RequestBody =
    when (this) {
        is HttpBody.Text -> BytesBody(value.encodeToByteArray(), requireSendableMediaType(part.contentType))
        is HttpBody.Bytes -> BytesBody(value, requireSendableMediaType(part.contentType))
        is HttpBody.Stream -> StreamBody(source, length, requireSendableMediaType(part.contentType), isActive)
        is HttpBody.SensitiveJson -> {
            requireSameMediaType(part.contentType, contentType)
            toRequestBody(isActive, onSensitiveBody)
        }
        else -> throw invalidInput()
    }

internal const val IO_BUFFER_BYTES = 64 * 1024
