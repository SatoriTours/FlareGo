package com.flarego.platform

import com.flarego.core.cloudflare.*
import com.flarego.core.ports.*

/** Resolve before each request, even for cached sessions. Never replay a business request after 401. */
class RenewingHttpTransport(
    private val token: suspend () -> String,
    private val transport: (String) -> HttpTransport,
) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val delegate = try { transport(token()) } catch (failure: Throwable) {
            OwnedResources(request.payload).closeAllOnIo()?.let(failure::addSuppressed)
            throw failure
        }
        return delegate.execute(request)
    }
}

class RenewingTransferPort(
    private val token: suspend () -> String,
    private val transfer: (String) -> TransferPort,
) : TransferPort {
    override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
        val delegate = try { transfer(token()) } catch (failure: Throwable) {
            OwnedResources(request.payload, sink).closeAllOnIo()?.let(failure::addSuppressed)
            throw failure
        }
        return delegate.download(request, sink)
    }
}
