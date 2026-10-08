package com.flarego.platform

import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.MultipartPart
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.ByteSource
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** The only accepted API origin, including the Cloudflare API version prefix. */
internal const val CLOUDFLARE_API_BASE = "https://api.cloudflare.com:443/client/v4"

internal const val CLOUDFLARE_API_HOST = "api.cloudflare.com"

/** Fixed non-echoing message for every rejected request shape. */
private const val INVALID_INPUT_MESSAGE = "请求内容或路径不符合安全规则，请检查后重试"

private const val MAX_PATH_LENGTH = 4096
private const val MAX_DECODE_ROUNDS = 4

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
 * Test-only seam: the same production policy, plus an application interceptor
 * that may rewrite the already-constructed fixed Cloudflare URL before DNS and
 * before any connection is opened.
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
 * allowlisted set of business headers and a one-shot body.
 */
internal fun cloudflareRequest(token: String, request: HttpRequest): Request {
    val method = requireHttpMethod(request.method)
    val path = requireApiPath(request.path)
    val bodyless = method == "GET" || method == "HEAD"
    if (bodyless && request.payload != HttpBody.Empty) throw invalidInput()
    val headers = requireBusinessHeaders(request.headers, request.payload)
    val body = if (bodyless) null else request.payload.toRequestBody()
    val builder =
        Request.Builder()
            .url(requireCloudflareUrl(path))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    for ((name, value) in headers) builder.header(name, value)
    return builder.method(method, body).build()
}

/**
 * Runs one blocking OkHttp call on the platform IO dispatcher and bridges
 * coroutine cancellation onto the real [Call].
 *
 * The cancellation hook is registered on a cancellable continuation, so it fires
 * as soon as the coroutine is cancelled (not when the blocking body eventually
 * returns). That closes the socket and makes a blocked read fail promptly, so no
 * IO keeps running in the background after cancellation. Callers run this on the
 * platform IO dispatcher, so all blocking IO and all closing stay off the UI
 * thread.
 */
internal suspend fun <T> runCloudflareCall(call: Call, block: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        try {
            val result = call.execute().use(block)
            if (continuation.isActive) continuation.resume(result)
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }

/** Turns an IO failure caused by our own cancellation back into cancellation. */
internal suspend fun rethrowUnlessCancelled(failure: IOException) {
    currentCoroutineContext().ensureActive()
    throw failure
}

/**
 * Closes every upload source owned by [payload] exactly once, even when the same
 * source instance is referenced by more than one multipart part. Never called by
 * the request builder: the transport owns closing on every outcome.
 */
internal fun closePayloadSources(payload: HttpBody) {
    val seen = java.util.Collections.newSetFromMap(IdentityHashMap<ByteSource, Boolean>())
    var firstFailure: Throwable? = null
    fun closeOnce(source: ByteSource) {
        if (!seen.add(source)) return
        try {
            source.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
    }
    when (payload) {
        is HttpBody.Stream -> closeOnce(payload.source)
        is HttpBody.Multipart ->
            payload.parts.forEach { part ->
                val body = part.body
                if (body is HttpBody.Stream) closeOnce(body.source)
            }
        else -> {}
    }
    firstFailure?.let { throw it }
}

private fun requireHttpMethod(raw: String): String {
    if (raw !in ALLOWED_METHODS) throw invalidInput()
    return raw
}

/**
 * Accepts only a relative API path that stays inside the fixed prefix after
 * normalization: no scheme, no protocol-relative `//host`, no backslash, no
 * fragment, no control characters, no traversal in raw or multiply
 * percent-encoded form. Legitimate percent-encoded query content (for example a
 * pagination cursor) stays valid.
 */
private fun requireApiPath(raw: String): String {
    if (raw.length <= 1 || raw.length > MAX_PATH_LENGTH) throw invalidInput()
    if (!raw.startsWith("/") || raw.startsWith("//")) throw invalidInput()
    for (character in raw) {
        val code = character.code
        if (code !in 0x21..0x7E || character == '\\' || character == '#') throw invalidInput()
    }
    requireValidEscapes(raw)
    val queryAt = raw.indexOf('?')
    val pathPart = if (queryAt < 0) raw else raw.substring(0, queryAt)
    requireSafeSegments(pathPart)
    var decoded = pathPart
    var rounds = 0
    while (rounds < MAX_DECODE_ROUNDS && decoded.contains('%')) {
        decoded = decodeOnce(decoded)
        requireSafeSegments(decoded)
        rounds++
    }
    return raw
}

private fun requireSafeSegments(pathPart: String) {
    if (!pathPart.startsWith("/") || pathPart.contains("//")) throw invalidInput()
    for (segment in pathPart.split('/')) {
        if (segment == "." || segment == "..") throw invalidInput()
    }
}

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

private fun decodeOnce(value: String): String {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            decoded.append(value.substring(index + 1, index + 3).toInt(16).toChar())
            index += 3
        } else {
            decoded.append(value[index])
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
    when (payload) {
        is HttpBody.Empty -> return
        is HttpBody.Multipart -> throw invalidInput()
        is HttpBody.Text -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Bytes -> requireSameMediaType(value, payload.contentType)
        is HttpBody.Stream -> requireSameMediaType(value, payload.contentType)
    }
}

private fun requireSameMediaType(value: String, declared: String) {
    if (!value.trim().equals(declared.trim(), ignoreCase = true)) throw invalidInput()
}

private fun HttpBody.toRequestBody(): RequestBody =
    when (this) {
        is HttpBody.Empty -> EmptyBody
        is HttpBody.Text -> BytesBody(value.toByteArray(Charsets.UTF_8), contentType)
        is HttpBody.Bytes -> BytesBody(value, contentType)
        is HttpBody.Stream -> StreamBody(source, length, contentType)
        is HttpBody.Multipart -> multipartBody(parts)
    }

/** An explicit zero-length one-shot body: `POST` with no payload still sends a body. */
private object EmptyBody : RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()

    override fun contentLength() = 0L

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {}
}

private class BytesBody(private val bytes: ByteArray, private val mediaType: String) : RequestBody() {
    override fun contentType() = mediaType.toMediaType()

    override fun contentLength() = bytes.size.toLong()

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

/**
 * One-shot streaming body with a fixed buffer. A declared length is reported to
 * the server and verified against the bytes the source really produced; an
 * unknown length is sent without padding.
 */
private class StreamBody(
    private val source: ByteSource,
    private val length: Long?,
    private val mediaType: String,
) : RequestBody() {
    override fun contentType() = mediaType.toMediaType()

    override fun contentLength() = length ?: -1L

    override fun isOneShot() = true

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(IO_BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            written += read
        }
        val declared = length
        if (declared != null && written != declared) {
            throw java.io.IOException("Upload body length mismatch")
        }
    }
}

private fun multipartBody(parts: List<MultipartPart>): RequestBody {
    val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
    for (part in parts) {
        val body = part.body.toPartBody(part)
        if (part.filename == null) builder.addFormDataPart(part.name, null, body)
        else builder.addFormDataPart(part.name, part.filename, body)
    }
    return builder.build()
}

private fun HttpBody.toPartBody(part: MultipartPart): RequestBody =
    when (this) {
        is HttpBody.Text -> BytesBody(value.toByteArray(Charsets.UTF_8), part.contentType)
        is HttpBody.Bytes -> BytesBody(value, part.contentType)
        is HttpBody.Stream -> StreamBody(source, length, part.contentType)
        else -> throw invalidInput()
    }

internal const val IO_BUFFER_BYTES = 64 * 1024
