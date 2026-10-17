package com.flarego.core.ports

import com.flarego.core.model.*

interface DnsFilesPort {
    /** Sink must be an uncommitted temporary target; validate head before publishing. */
    suspend fun exportRecords(scope: ZoneScope, sink: ByteSink): HttpResponseHead
    /** Takes source ownership on every outcome, including validation rejection/cancellation. */
    suspend fun importRecords(scope: ZoneScope, source: ByteSource, length: Long, fingerprint: String, proxied: Boolean): DnsImportOutcome
}
interface DnsFilesSession : DnsFilesPort {
    suspend fun observe(scope: ZoneScope): DnsFileImportState
    suspend fun readback(scope: ZoneScope): DnsImportReadback
    /** Complete all quota/owner/file preflight before beforeSend; after it returns, perform at most
     * one mutation send immediately, with no further pre-send failure points. Never send if it throws.
     * A typed rejection before this callback is a trusted zero-mutation fact, not a provider receipt. */
    suspend fun writeImport(scope: ZoneScope, source: suspend () -> ByteSource, length: Long, fingerprint: String, proxied: Boolean,
        beforeSend: (DnsFileImportState) -> Unit, afterRead: () -> Unit): DnsImportOutcome
}
interface DnsFileStore {
    suspend fun open(ref: DnsFileRef): ByteSource
    suspend fun beginExport(target: DnsExportTarget): DnsExportTransaction
    suspend fun release(ref: DnsFileRef)
}
interface DnsExportTransaction {
    val sink: ByteSink
    suspend fun commit(): DnsFileRef
    /** Idempotent cleanup of owned temporary/new target only, never the user's source file. */
    suspend fun abort()
}
