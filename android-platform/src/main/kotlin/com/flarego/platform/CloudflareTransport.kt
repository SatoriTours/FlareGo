package com.flarego.platform

import com.flarego.core.cloudflare.*
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
 * [BOUNDED_RESPONSE_BYTES]; every response body, every upload source and the call
 * itself are closed on success, failure and cancellation, and coroutine
 * cancellation is bridged onto the real OkHttp call so a blocked read stops
 * instead of continuing in the background.
 */
class CloudflareTransport internal constructor(
    private val token: String,
    private val client: OkHttpClient,
) : HttpTransport {
    constructor(token: String) : this(token, cloudflareHttpClient())

    override suspend fun execute(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            try {
                val call = client.newCall(cloudflareRequest(token, request))
                try {
                    runCloudflareCall(call) { response ->
                        HttpResponse(
                            response.code,
                            readBounded(response.body, job),
                            response.headers.toMultimap(),
                        )
                    }
                } catch (failure: IOException) {
                    // A cancelled call must surface as cancellation, never as an IO failure.
                    rethrowUnlessCancelled(failure)
                    throw failure
                }
            } finally {
                closePayloadSources(request.payload)
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
                if (output.size().toLong() + read > BOUNDED_RESPONSE_BYTES) throw tooLarge()
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

private fun tooLarge(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_RESPONSE, TOO_LARGE_MESSAGE)
