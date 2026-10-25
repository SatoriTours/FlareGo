package com.flarego.platform

import com.flarego.core.application.d1.D1Validation
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** Production evidence is pending; there is no caller URL/host override or UI enable switch. */
fun d1SignedTransfers(): D1SignedTransferPort = D1SignedTransfers()

/** Trusted in-module fixture seam; never exported through the application configuration. */
internal class D1SignedTransfers(
    private val approvedLocation: (HttpUrl) -> Boolean = { false },
    rewrite: ((Request) -> Request)? = null,
) : D1SignedTransferPort {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(600, TimeUnit.SECONDS)
        .apply { if (rewrite != null) addInterceptor { chain -> chain.proceed(rewrite(chain.request())) } }
        .build()

    private fun location(raw: String): HttpUrl {
        if (raw.any { it.code <= 0x20 || it.code == 0x7f } || '\\' in raw) throw rejected()
        val url = raw.toHttpUrlOrNull() ?: throw rejected()
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.fragment != null || url.host.contains(':') || url.host.all { it in '0'..'9' || it == '.' } ||
            url.encodedPath == "/" || url.encodedPath.contains("%2f", true) || url.encodedPath.contains("%5c", true)) throw rejected()
        if (!approvedLocation(url)) throw rejected()
        return url
    }

    override suspend fun upload(location: D1UploadLocation, content: D1Content, md5: String) {
        val url = location.withUrl(::location)
        val hash = D1Validation.md5(md5).lowercase()
        if (content.descriptor.length !in 1..D1_MAX_IMPORT_BYTES) throw invalidInputFailure()
        val source = content.openVerified()
        val owned = OwnedResources(HttpBody.Stream(source, content.descriptor.length, "application/octet-stream"))
        try {
            val job = currentCoroutineContext()[Job]
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = content.descriptor.length
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    val digest = MessageDigest.getInstance("MD5")
                    val buffer = ByteArray(65536); var written = 0L
                    try {
                        while (true) {
                            job?.ensureActive()
                            val n = source.read(buffer)
                            if (n == -1) break
                            if (n !in 1..buffer.size || n.toLong() > contentLength() - written) throw IOException("D1 incomplete stream")
                            job?.ensureActive(); digest.update(buffer, 0, n); sink.write(buffer, 0, n); written += n
                        }
                        if (written != contentLength() || hex(digest.digest()) != hash) throw IOException("D1 incomplete stream")
                    } finally { buffer.fill(0) }
                }
            }
            val request = Request.Builder().url(url).put(body).build()
            runCloudflareCall(client.newCall(request), owned) { response ->
                if (response.code != 200) throw unknown()
                val etag = response.headers.values("ETag").singleOrNull() ?: throw unknown()
                if (etag.lowercase() != hash && etag.lowercase() != "\"$hash\"") throw unknown()
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: CloudFailure) { throw failure
        } catch (_: Exception) { throw unknown()
        } finally { owned.closeAllOnIo() }
    }

    override suspend fun download(location: D1DownloadLocation, sink: ByteSink): Long {
        val owned = OwnedResources(HttpBody.Empty, sink)
        try {
            val url = location.withUrl(::location)
            val job = currentCoroutineContext()[Job]
            return runCloudflareCall(client.newCall(Request.Builder().url(url).get().build()), owned) { response ->
                if (response.code != 200) throw unknown()
                val input = response.body?.byteStream() ?: throw unknown()
                var total = 0L; val buffer = ByteArray(65536)
                try {
                    while (true) {
                        job?.ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        if (n !in 1..buffer.size || n.toLong() > Long.MAX_VALUE - total) throw unknown()
                        job?.ensureActive(); sink.write(buffer, 0, n); total += n
                    }
                    total
                } finally { buffer.fill(0); input.close() }
            }
        } catch (cancel: CancellationException) { throw cancel
        } catch (failure: CloudFailure) { throw failure
        } catch (_: Exception) { throw unknown()
        } finally { owned.closeAllOnIo() }
    }
    private fun rejected() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "D1 签名传输缺少官方精确地址准入证据，生产入口暂不可用")
    private fun unknown() = CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "D1 文件传输未能确认完整完成；未自动重试", uncertain = true)
}

object D1Md5Fingerprint : D1ContentFingerprint {
    override suspend fun md5(content: D1Content): String {
        val source = content.openVerified()
        try { return withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            @OptIn(InternalCoroutinesApi::class)
            val cancel = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) runCatching { source.close() } }
            val digest = MessageDigest.getInstance("MD5"); val buffer = ByteArray(65536); var total = 0L
            try {
                while (true) {
                    ensureActive(); val n = source.read(buffer)
                    if (n == -1) break
                    if (n !in 1..buffer.size || n.toLong() > content.descriptor.length - total) throw invalidInputFailure()
                    digest.update(buffer, 0, n); total += n
                }
                if (total != content.descriptor.length) throw invalidInputFailure()
                hex(digest.digest())
            } finally { cancel?.dispose(); buffer.fill(0) }
        } } finally { source.close() }
    }
}
private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
private fun invalidInputFailure() = CloudFailure(CloudFailureCode.INVALID_INPUT, "D1 私有文件长度或内容无法验证")
