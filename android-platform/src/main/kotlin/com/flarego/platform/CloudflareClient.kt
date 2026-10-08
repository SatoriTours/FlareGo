package com.flarego.platform

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.MultipartPart
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import java.io.IOException
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
internal fun cloudflareRequest(
    token: String,
    request: HttpRequest,
    isActive: () -> Boolean = { true },
): Request {
    val method = requireHttpMethod(request.method)
    val path = requireApiPath(request.path)
    val bodyless = method == "GET" || method == "HEAD"
    if (bodyless && request.payload != HttpBody.Empty) throw invalidInput()
    val headers = requireBusinessHeaders(request.headers, request.payload)
    val body = if (bodyless) null else request.payload.toRequestBody(isActive)
    val builder =
        Request.Builder()
            .url(requireCloudflareUrl(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
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
    private var sinkClosed = false
    private var failure: Throwable? = null

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
    val queryAt = raw.indexOf('?')
    val rawPath = if (queryAt < 0) raw else raw.substring(0, queryAt)
    val rawQuery = if (queryAt < 0) "" else raw.substring(queryAt + 1)
    requireSafePath(rawPath)
    requireSafeQuery(rawQuery)
    var decoded = rawPath
    var rounds = 0
    while (decoded.contains('%')) {
        val next = decodeKnownEscapes(decoded)
        if (next == decoded) break
        // Never accept an ambiguous budget: every decoded stage is checked.
        requireSafePath(next)
        decoded = next
        rounds++
        if (rounds > raw.length) throw invalidInput()
    }
    var decodedQuery = rawQuery
    var queryRounds = 0
    while (decodedQuery.contains('%')) {
        val next = decodeKnownEscapes(decodedQuery)
        if (next == decodedQuery) break
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

/** Decoded URL text: printable ASCII, controls rejected. */
private fun Char.isDecodedUrlCharacter(): Boolean = code in 0x20..0x7E

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
 * Decodes one level of *valid* percent escapes only. A `%` that is not followed
 * by two hex digits is a literal percent (an opaque identifier may contain one)
 * and is copied verbatim, so no parse exception can escape and the identifier is
 * preserved.
 */
private fun decodeKnownEscapes(value: String): String {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%' &&
            index + 2 < value.length &&
            value[index + 1].isHexDigit() &&
            value[index + 2].isHexDigit()
        ) {
            decoded.append(value.substring(index + 1, index + 3).toInt(16).toChar())
            index += 3
        } else {
            decoded.append(character)
            index++
        }
    }
    return decoded.toString()
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

private fun requireBusinessHeaders(
    headers: Map<String, String>,
    payload: HttpBody,
): Map<String, String> {
    val accepted = LinkedHashMap<String, String>()
    for ((name, value) in headers) {
        val normalized = name.lowercase()
        if (normalized !in ALLOWED_BUSINESS_HEADERS) throw invalidInput()
        if (name.isEmpty() || name.length > 255 || name.any { it.code !in 0x21..0x7E }) throw invalidInput()
        if (value.length > 4096) throw invalidInput()
        if (value.any { it.code !in 0x20..0x7E }) throw invalidInput()
        if (normalized == "content-type") requireMatchingContentType(value, payload)
        accepted[name] = value
    }
    return accepted
}

private fun requireMatchingContentType(value: String, payload: HttpBody) {
    requireParseableMediaType(value)
    when (payload) {
        is HttpBody.Empty -> return
        is HttpBody.Multipart -> throw invalidInput()
        is HttpBody.Text -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Bytes -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Stream -> requireSameMediaType(value, payload.contentType)
    }
}

/**
 * Platform-side fail-closed parse of a user-supplied media type. The core layer
 * already proves the syntax; this is the last gate before OkHttp parses it, so a
 * value OkHttp would reject can never reach the request builder or echo itself
 * through an `IllegalArgumentException`.
 */
private fun requireParseableMediaType(value: String) {
    if (value.toMediaTypeOrNull() == null) throw invalidInput()
}

private fun requireSameMediaType(value: String, declared: String) {
    if (!value.trim().equals(declared.trim(), ignoreCase = true)) throw invalidInput()
}

private fun HttpBody.toRequestBody(isActive: () -> Boolean): RequestBody =
    when (this) {
        is HttpBody.Empty -> EmptyBody
        is HttpBody.Text -> BytesBody(value.encodeToByteArray(), contentType)
        is HttpBody.Bytes -> BytesBody(value, contentType)
        is HttpBody.Stream -> StreamBody(source, length, contentType, isActive)
        is HttpBody.Multipart -> multipartBody(parts, isActive)
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

private fun multipartBody(parts: List<MultipartPart>, isActive: () -> Boolean): RequestBody {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
    for (part in parts) {
        val body = part.body.toPartBody(part, isActive)
        if (part.filename == null) builder.addFormDataPart(part.name, null, body)
        else builder.addFormDataPart(part.name, part.filename, body)
    }
    return builder.build()
}

private fun HttpBody.toPartBody(part: MultipartPart, isActive: () -> Boolean): RequestBody =
    when (this) {
        is HttpBody.Text -> BytesBody(value.encodeToByteArray(), part.contentType)
        is HttpBody.Bytes -> BytesBody(value, part.contentType)
        is HttpBody.Stream -> StreamBody(source, length, part.contentType, isActive)
        else -> throw invalidInput()
    }

internal const val IO_BUFFER_BYTES = 64 * 1024
