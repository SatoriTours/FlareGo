package com.flarego.platform

import com.flarego.core.cloudflare.*
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody

/** Accepted in-memory response bound: 4 MiB. */
internal const val BOUNDED_RESPONSE_BYTES = 4 * 1024 * 1024

/** Fixed non-echoing message for an oversized response. */
private const val TOO_LARGE_MESSAGE = "响应内容超出安全上限，请改用下载方式获取"

/**
 * Bounded in-memory call path.
 *
 * The response is read through a fixed buffer and fails closed above
 * [BOUNDED_RESPONSE_BYTES]. Every owned upload source and the call itself are
 * released on success, validation rejection, IO failure and cancellation; the
 * cleanup always runs on the platform IO thread, including when the coroutine was
 * already cancelled before the IO body could start.
 */
class CloudflareTransport internal constructor(
    private val token: String,
    private val client: OkHttpClient,
) : HttpTransport {
    constructor(token: String) : this(token, cloudflareHttpClient())

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val owned = OwnedResources(request.payload)
        var primary: Throwable? = null
        try {
            val job = currentCoroutineContext()[Job]
            val call = client.newCall(cloudflareRequest(token, request) { job?.isActive != false })
            return runCloudflareCall(call, owned) { response ->
                HttpResponse(
                    response.code,
                    readBounded(response.body, job),
                    response.headers.toMultimap(),
                )
            }
        } catch (thrown: Throwable) {
            // A cancelled call must surface as cancellation, never as an IO failure.
            primary = thrown
            throw thrown
        } finally {
            val cleanupFailure = owned.closeAllOnIo()
            if (cleanupFailure != null) {
                if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun readBounded(body: ResponseBody?, job: Job?): ByteArray {
        if (body == null) return byteArrayOf()
        if (body.contentLength() > BOUNDED_RESPONSE_BYTES) throw tooLarge()
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                job?.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (output.size().toLong() + read > BOUNDED_RESPONSE_BYTES) throw tooLarge()
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

private fun tooLarge(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, TOO_LARGE_MESSAGE)
