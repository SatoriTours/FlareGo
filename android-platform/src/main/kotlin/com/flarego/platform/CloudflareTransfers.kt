package com.flarego.platform

import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Streaming downloads against the fixed Cloudflare API host.
 *
 * The response is streamed through a fixed buffer: no whole-file buffering, no
 * in-memory size bound (that bound belongs to [CloudflareTransport]), an error
 * response is never written to the sink, and the sink plus every upload source
 * are closed on success, failure and cancellation. Coroutine cancellation is
 * bridged onto the real OkHttp call, and the write loop stops as soon as the
 * coroutine is no longer active.
 */
class CloudflareTransfers internal constructor(
    private val token: String,
    private val client: OkHttpClient,
) : TransferPort {
    constructor(token: String) : this(token, cloudflareFileClient())

    override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            try {
                val call = client.newCall(cloudflareRequest(token, request))
                try {
                    runCloudflareCall(call) { response -> stream(response, sink, job) }
                } catch (failure: IOException) {
                    rethrowUnlessCancelled(failure)
                    throw failure
                }
            } finally {
                closePayloadSources(request.payload)
                sink.close()
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
                    sink.write(buffer, 0, read)
                    transferred += read
                }
            }
        }
        return HttpResponseHead(response.code, headers, transferred)
    }
}
