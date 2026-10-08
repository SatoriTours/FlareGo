package com.flarego.core.ports

import com.flarego.core.cloudflare.HttpRequest

/**
 * Synchronous byte source owned by a transport call.
 *
 * [read] returns the number of bytes written into `buffer`, or `-1` at end of
 * stream. Implementations are only called from the platform IO thread.
 *
 * Ownership contract: once the payload is handed to the transport, the transport
 * closes the source on every outcome (success, validation rejection, IO failure
 * and cancellation). [close] must therefore release the underlying resource and
 * **must unblock a `read` that is currently blocked** when the implementation's
 * IO can be released that way (for example by interrupting or closing the
 * underlying stream). It must tolerate being called while a `read` is in flight
 * and it must be safe to call from another thread. A `read` that returns `0` is
 * treated as no progress and must not spin forever. An implementation whose IO
 * cannot be released by [close] is outside this contract: the transport cannot
 * force-terminate arbitrary third-party IO.
 */
interface ByteSource {
    fun read(buffer: ByteArray): Int

    fun close()
}

/**
 * Synchronous byte sink owned by a transport call.
 *
 * Implementations are only called from the platform IO thread.
 *
 * Ownership contract: the transport closes the sink on every outcome, including
 * cancellation, so [close] must release the underlying resource and **must
 * unblock a `write` that is currently blocked** when the implementation's IO can
 * be released that way. It must tolerate being called while a `write` is in
 * flight and it must be safe to call from another thread.
 */
interface ByteSink {
    fun write(buffer: ByteArray, offset: Int, count: Int)

    fun close()
}

/**
 * Result head of a streamed transfer: the provider status and headers plus how
 * many bytes were actually transferred. A missing provider `Content-Length` is
 * never replaced by a fabricated total.
 */
data class HttpResponseHead(
    val status: Int,
    val headers: Map<String, List<String>>,
    val transferredBytes: Long,
)

/**
 * Streaming transfer port. Implementations must not buffer a whole upload or
 * download in memory, must leave an error response unwritten, and must close the
 * request's sources and the caller's sink on every path.
 */
interface TransferPort {
    suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead
}
