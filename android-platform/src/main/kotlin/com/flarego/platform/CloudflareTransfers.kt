package com.flarego.platform

import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Streaming downloads against the fixed Cloudflare API host.
 *
 * The response is streamed through a fixed buffer: no whole-file buffering, no
 * in-memory size bound (that bound belongs to [CloudflareTransport]), an error
 * response is never written to the sink, and the sink plus every upload source
 * are released on success, validation rejection, IO failure and cancellation.
 * Cleanup is independent per resource and always runs on the platform IO thread,
 * including when the coroutine was already cancelled before the IO body started.
 */
class CloudflareTransfers internal constructor(
    private val token: String,
    private val client: OkHttpClient,
) : TransferPort {
    constructor(token: String) : this(token, cloudflareFileClient())

    override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
        val owned = OwnedResources(request.payload, sink)
        var primary: Throwable? = null
        try {
            val job = currentCoroutineContext()[Job]
            val call = client.newCall(cloudflareRequest(token, request) { job?.isActive != false })
            return runCloudflareCall(call, owned) { response -> stream(response, sink, job) }
        } catch (thrown: Throwable) {
            primary = thrown
            throw thrown
        } finally {
            val cleanupFailure = owned.closeAllOnIo()
            if (cleanupFailure != null) {
                if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun stream(response: Response, sink: ByteSink, job: Job?): HttpResponseHead {
        val headers = response.headers.toMultimap()
        if (response.code !in 200..299) {
            // Nothing from an error response is written; the body is closed by the caller's `use`.
            return HttpResponseHead(response.code, headers, 0L)
        }
        var transferred = 0L
        val input = response.body?.byteStream()
        if (input != null) {
            input.use {
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (true) {
                    job?.ensureActive()
                    val read = it.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    job?.ensureActive()
                    sink.write(buffer, 0, read)
                    transferred += read
                }
            }
        }
        return HttpResponseHead(response.code, headers, transferred)
    }
}
