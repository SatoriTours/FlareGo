package com.flarego.core.application.d1

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*

class D1ImportFile private constructor(val content: D1Content, internal val md5: String) : AutoCloseable {
    override fun close() = content.close()
    override fun toString() = "D1ImportFile(redacted)"
    companion object {
        /** Takes ownership even on validation/cancellation failure. */
        suspend fun prepare(content: D1Content, fingerprint: D1ContentFingerprint): D1ImportFile {
            try {
                if (content.descriptor.length !in 1..D1_MAX_IMPORT_BYTES) throw invalidInputFailure()
                D1ImportText.validate(content)
                return D1ImportFile(content, D1Validation.md5(fingerprint.md5(content)).lowercase())
            } catch (failure: Throwable) { content.close(); throw failure }
        }
    }
}

enum class D1FileKind { IMPORT, EXPORT }
/** Memory-only polling capability. Never a SQL program, a signed URL or a reusable start request. */
class D1FileLocator internal constructor(val database: D1DatabaseRef, val kind: D1FileKind, internal val bookmark: String) {
    override fun toString() = "D1FileLocator(database=$database, kind=$kind)"
}
class D1FileOutcome internal constructor(
    val acceptance: D1Acceptance, val locator: D1FileLocator? = null, internal var download: D1DownloadLocation? = null,
    private var report: D1Content? = null,
) : AutoCloseable {
    internal val hasReport get() = report != null
    fun takeReport(): D1Content? = report.also { report = null }
    override fun close() { download = null; report?.close(); report = null }
    override fun toString() = "D1FileOutcome(acceptance=$acceptance)"
}

/**
 * Called only after the runtime's final confirmation and durable RUNNING transition.
 * There is no automatic restart path. Explicit continuation accepts only an issued
 * locator and polls that same database/task. All file bytes remain in private owners.
 */
class D1FilePipeline(
    private val session: D1Session,
    private val transfers: D1SignedTransferPort,
    private val contents: D1ContentStore,
    private val nowMs: () -> Long,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val onLocator: suspend (D1FileLocator) -> Unit = {},
) {
    suspend fun startImport(database: D1DatabaseRef, input: D1ImportFile): D1FileOutcome {
        validate(database)
        // Init can start import immediately if the MD5 already exists. Never move it before confirmation.
        var receipt = api { session.importDatabase(database, D1ImportRequest.Init(input.md5)) }
        requireReceipt(receipt, database)
        if (receipt.status == D1FileStatus.UPLOAD) {
            val upload = receipt.upload ?: throw unknown()
            if (upload.database != database) throw unknown()
            withTimeout(600_000) { transfers.upload(upload, input.content, input.md5) }
            currentCoroutineContext().ensureActive()
            receipt = api { session.importDatabase(database, D1ImportRequest.Ingest(input.md5, upload.filename)) }
            requireReceipt(receipt, database)
            if (receipt.status == D1FileStatus.UPLOAD) throw unknown()
        }
        return settle(database, D1FileKind.IMPORT, receipt)
    }

    suspend fun startExport(database: D1DatabaseRef, options: D1ExportOptions): D1FileOutcome {
        validate(database); D1Validation.exportOptions(options)
        val receipt = api { session.exportDatabase(database, options) }
        requireReceipt(receipt, database)
        return settle(database, D1FileKind.EXPORT, receipt)
    }

    suspend fun continuePoll(locator: D1FileLocator): D1FileOutcome {
        validate(locator.database); D1Validation.bookmark(locator.bookmark)
        onLocator(locator)
        return poll(locator)
    }

    /** Private file task, independent of the already-settled cloud operation. Never starts export. */
    suspend fun downloadExport(outcome: D1FileOutcome): D1Content {
        if (outcome.acceptance != D1Acceptance.COMPLETE) throw invalidInputFailure()
        val location = outcome.download ?: throw invalidInputFailure()
        val writer = contents.writer(Long.MAX_VALUE)
        var sealed: D1Content? = null
        try {
            val length = withTimeout(600_000) { transfers.download(location, writer) }
            val content = writer.seal().also { sealed = it }
            if (outcome.download !== location || content.descriptor.length != length) throw unknown()
            D1ImportText.validate(content)
            if (outcome.download !== location) throw unknown()
            return content.also { sealed = null }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) { throw unknown()
        } finally { writer.abort(); sealed?.close() }
    }

    private suspend fun settle(database: D1DatabaseRef, kind: D1FileKind, receipt: D1FileReceipt): D1FileOutcome {
        if (receipt.status != D1FileStatus.PROCESSING) return terminal(database, kind, receipt)
        val locator = D1FileLocator(database, kind, receipt.bookmark ?: throw unknown())
        onLocator(locator) // Publish before any delay/next request, including cancellation paths.
        return poll(locator)
    }

    private suspend fun poll(locator: D1FileLocator): D1FileOutcome {
        val started = nowMs()
        if (started < 0) throw unknown()
        repeat(30) {
            currentCoroutineContext().ensureActive()
            val before = elapsed(started)
            if (before >= POLL_BUDGET_MS) return processing(locator)
            sleeper(minOf(2_000, POLL_BUDGET_MS - before))
            val remaining = POLL_BUDGET_MS - elapsed(started)
            if (remaining <= 0) return processing(locator)
            val receipt = withTimeout(minOf(30_000, remaining)) {
                when (locator.kind) {
                    D1FileKind.IMPORT -> session.importDatabase(locator.database, D1ImportRequest.Poll(locator.bookmark))
                    D1FileKind.EXPORT -> session.pollExport(locator.database, locator.bookmark)
                }
            }
            requireReceipt(receipt, locator.database)
            if (receipt.bookmark != null && receipt.bookmark != locator.bookmark) throw unknown()
            if (receipt.status != D1FileStatus.PROCESSING) return terminal(locator.database, locator.kind, receipt)
            if (receipt.bookmark == null) throw unknown()
        }
        return processing(locator)
    }

    private suspend fun terminal(database: D1DatabaseRef, kind: D1FileKind, receipt: D1FileReceipt): D1FileOutcome {
        return when (receipt.status) {
            D1FileStatus.ERROR -> D1FileOutcome(D1Acceptance.UNKNOWN)
            D1FileStatus.COMPLETE -> {
                if (kind == D1FileKind.IMPORT) {
                    val bytes = receipt.details.toString().encodeToByteArray()
                    val source = object : ByteSource {
                        var at = 0
                        override fun read(buffer: ByteArray): Int {
                            if (at == bytes.size) return -1
                            val size = minOf(buffer.size, bytes.size-at)
                            bytes.copyInto(buffer,0,at,at+size); at += size; return size
                        }
                        override fun close() { bytes.fill(0) }
                    }
                    // A local viewer/cache failure cannot erase an already observed cloud completion.
                    val report = try { contents.stage(source,bytes.size.toLong()) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                    D1FileOutcome(D1Acceptance.COMPLETE, report = report)
                }
                else {
                    val location = receipt.download ?: throw unknown()
                    if (location.database != database) throw unknown()
                    D1FileOutcome(D1Acceptance.COMPLETE, download = location)
                }
            }
            else -> throw unknown()
        }
    }
    private fun elapsed(started: Long): Long {
        val now = nowMs()
        if (now < started) throw unknown()
        return now - started
    }
    private fun processing(locator: D1FileLocator) = D1FileOutcome(D1Acceptance.PROCESSING, locator)
    private fun validate(ref: D1DatabaseRef) { D1Validation.databaseId(ref.id) }
    private fun requireReceipt(receipt: D1FileReceipt, database: D1DatabaseRef) { if (receipt.database != database) throw unknown() }
    private suspend fun <T> api(send: suspend () -> T): T = withTimeout(30_000) { send() }
    private fun unknown() = CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "D1 文件操作结果未能确认；请核对原任务，不要重新启动", uncertain = true)
    private companion object { const val POLL_BUDGET_MS = 120_000L }
}
