package com.flarego.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** SAF URIs stay in this app-owned instance. Core receives opaque local references only.
 * Picker/grants remain the caller's responsibility. close/release never deletes an import or committed export. */
class DnsDocumentFiles(private val context: Context, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fingerprint: FingerprintPort = AndroidFingerprint(),
    private val exportMaxBytes: Long = 4L * 1024 * 1024) : DnsFileStore, AutoCloseable {
    init { require(exportMaxBytes in 1..R2_MAX_SINGLE_OBJECT_BYTES) }
    private data class Selected(val uri: Uri, val ref: DnsFileRef)
    private data class Target(val uri: Uri, val ref: DnsExportTarget, val claimed: AtomicBoolean = AtomicBoolean(),
        val deleteOwned: AtomicBoolean = AtomicBoolean(true), val publicationStarted: AtomicBoolean = AtomicBoolean(), val cleanup: Any = Any())
    private val closed = AtomicBoolean()
    private val selected = ConcurrentHashMap<String, Selected>()
    private val targets = ConcurrentHashMap<String, Target>()
    private val transactions = ConcurrentHashMap.newKeySet<Export>()
    private val sources = ConcurrentHashMap.newKeySet<DnsDocumentSource>()
    private val seenImports = ConcurrentHashMap.newKeySet<Uri>()
    private val registry = Any()
    // At most 256 adopted targets, including failed cleanup. Refuse new adoption rather than lose ownership.
    private val pendingDiscards = ConcurrentHashMap<String, Target>()
    private val issuedTargets = java.util.WeakHashMap<DnsExportTarget, Boolean>()
    private val resolver get() = context.contentResolver
    suspend fun discardExportTarget(target: DnsExportTarget): Unit = io {
        val owned = synchronized(registry) {
            checkOpen()
            if (issuedTargets.keys.none { it === target }) throw invalidFile()
            val entry = targets[target.id] ?: return@io
            if (entry.ref !== target) throw invalidFile()
            if (!pendingDiscards.containsKey(target.id)) {
                if (!entry.claimed.compareAndSet(false, true)) throw CloudFailure(CloudFailureCode.CONFLICT,"导出已交给任务，不能取消其目标")
                pendingDiscards[target.id] = entry
            }
            entry
        }
        discardNow(owned)
    }
    private fun discardNow(target: Target) = synchronized(target.cleanup) {
        if (pendingDiscards[target.ref.id] !== target) return@synchronized
        deleteNew(target)
        synchronized(registry) { pendingDiscards.remove(target.ref.id); targets.remove(target.ref.id) }
    }
    suspend fun retryPendingCleanup(maxAttempts: Int = 16): DnsDocumentCleanupResult = io {
        if (maxAttempts !in 1..64) throw invalidFile()
        val discarded = pendingDiscards.values.toList().take(maxAttempts)
        var attempted = 0
        for (target in discarded) { currentCoroutineContext().ensureActive(); attempted++; runCatching { discardNow(target) } }
        val aborted = transactions.filter { it.isPendingCleanup() }.take(maxAttempts - attempted)
        for (tx in aborted) { currentCoroutineContext().ensureActive(); attempted++; runCatching { tx.abortNow() } }
        DnsDocumentCleanupResult(attempted,pendingDiscards.size + transactions.count { it.isPendingCleanup() })
    }
    suspend fun resolveImport(uri: Uri): DnsFileRef {
        var acquired: DnsFileRef? = null
        try { return io {
            requireDocument(uri)
            synchronized(registry) {
                checkOpen()
                if (targets.values.any { it.uri == uri }) throw invalidFile()
                seenImports.add(uri)
            }
            val name = displayName(uri)
            val metadata = boundedMetadata(openSource(uri), DNS_IMPORT_MAX_BYTES, fingerprint)
            if (metadata.first == 0L) throw invalidFile()
            val ref = DnsFileRef(UUID.randomUUID().toString(), name, metadata.first, metadata.second)
            synchronized(registry) { checkOpen(); selected[ref.id] = Selected(uri, ref); acquired = ref }
            ref
        } } catch (error: Exception) {
            withContext(NonCancellable + ioDispatcher) { acquired?.let { selected.remove(it.id) } }
            throw error
        }
    }
    /** Caller must supply a newly created empty CreateDocument result. No replacement of existing documents. */
    suspend fun resolveExportTarget(uri: Uri): DnsExportTarget {
        var acquired: DnsExportTarget? = null
        try { return io {
            requireDocument(uri)
            val name = displayName(uri)
            val metadata = boundedMetadata(openSource(uri), 0, fingerprint)
            if (metadata.first != 0L) throw invalidFile()
            val ref = DnsExportTarget(UUID.randomUUID().toString(), name)
            synchronized(registry) {
                checkOpen()
                if (targets.size >= 256 || uri in seenImports || targets.values.any { it.uri == uri } || selected.values.any { it.uri == uri }) throw invalidFile()
                targets[ref.id] = Target(uri, ref); issuedTargets[ref] = true; acquired = ref
            }
            ref
        } } catch (error: Exception) {
            withContext(NonCancellable + ioDispatcher) { acquired?.let { runCatching { discardExportTarget(it) } } }
            throw error
        }
    }
    override suspend fun open(ref: DnsFileRef): ByteSource {
        var acquired: ByteSource? = null
        try {
            return io {
                val record = synchronized(registry) { checkOpen(); selected[ref.id]?.takeIf { it.ref == ref } ?: throw invalidFile() }
                // Reopen original. The core compares actual bytes to frozen metadata before sending.
                openSource(record.uri).also { acquired = it }
            }
        } catch (error: Exception) {
            // withContext may cancel after IO acquired the handle but before the caller receives it.
            withContext(NonCancellable + ioDispatcher) { runCatching { acquired?.close() } }
            throw error
        }
    }
    override suspend fun beginExport(target: DnsExportTarget): DnsExportTransaction {
        var acquired: Export? = null
        try {
            return io {
                val owned = synchronized(registry) {
                    checkOpen()
                    targets[target.id]?.takeIf { it.ref === target && it.claimed.compareAndSet(false, true) } ?: throw invalidFile()
                }
                var temp: File? = null
                try {
                    temp = File.createTempFile("flarego-dns-export-", ".tmp", context.cacheDir)
                    val tx = Export(owned, temp).also { acquired = it }
                    synchronized(registry) { checkOpen(); transactions.add(tx) }
                    tx
                } catch (error: Exception) {
                    if (acquired == null) {
                        // No transaction was delivered. Retain ownership before any fallible cleanup;
                        // a revoked SAF grant must not turn this into an untracked orphan.
                        synchronized(registry) { pendingDiscards[owned.ref.id] = owned }
                        runCatching { temp?.delete() }
                        runCatching { discardNow(owned) }
                    }
                    throw error
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable + ioDispatcher) { runCatching { acquired?.abortNow() } }
            throw error
        }
    }
    override suspend fun release(ref: DnsFileRef) = io<Unit> {
        synchronized(registry) { if (selected[ref.id]?.ref == ref) selected.remove(ref.id) }
    }
    override fun close() {
        val all = synchronized(registry) {
            if (!closed.compareAndSet(false, true)) return
            selected.clear(); transactions.toList()
        }
        all.forEach { runCatching { it.abortNow() } }
        sources.toList().forEach { runCatching { it.close() } }
        targets.values.toList().forEach { target -> runCatching { deleteNew(target) }; targets.remove(target.ref.id) }
        seenImports.clear()
    }
    private fun requireDocument(uri: Uri) {
        checkOpen()
        if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) throw invalidFile()
    }
    private fun checkOpen() { if (closed.get()) throw invalidFile() }
    private fun openSource(uri: Uri): DnsDocumentSource {
        val input = resolver.openInputStream(uri) ?: throw fileIo()
        lateinit var source: DnsDocumentSource
        source = DnsDocumentSource(input) { sources.remove(source) }
        synchronized(registry) {
            if (closed.get()) { runCatching { source.close() }; throw invalidFile() }
            sources.add(source)
        }
        return source
    }
    private fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)?.filter { !it.isISOControl() && it != '/' && it != '\\' }?.take(160)?.trim()
                if (!name.isNullOrBlank()) return name
            }
        }
        return "zone.bind"
    }
    private fun deleteNew(target: Target) {
        if (!target.deleteOwned.get()) return
        // Before our publication, newly observed content belongs to the user. Revocation is final.
        if (!target.publicationStarted.get() && !stillEmpty(target)) return
        if (target.deleteOwned.get() && !DocumentsContract.deleteDocument(resolver, target.uri)) throw fileIo()
    }
    @OptIn(InternalCoroutinesApi::class)
    private fun stillEmpty(target: Target, job: Job? = null): Boolean {
        val source = DnsDocumentSource(resolver.openInputStream(target.uri) ?: throw fileIo())
        val cancelled = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) runCatching { source.close() }
        }
        try {
            job?.ensureActive()
            val n = source.read(ByteArray(1))
            if (n > 0) {
                target.deleteOwned.set(false) // Before any cancellation/exception can trigger cleanup.
                return false
            }
            job?.ensureActive()
            if (n != -1) throw fileIo()
            return true
        } finally { cancelled?.dispose(); source.close() }
    }
    private suspend fun <T> io(block: suspend () -> T): T {
        try { return withContext(ioDispatcher) { currentCoroutineContext().ensureActive(); block() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); throw fileError(error) }
    }
    private inner class Export(private val target: Target, private val temp: File) : DnsExportTransaction {
        private val state = AtomicReference("open")
        private val output = AtomicReference<DnsDocumentSink?>(null)
        private val cleanup = Any()
        override val sink = DnsDocumentSink(FileOutputStream(temp), exportMaxBytes)
        override suspend fun commit(): DnsFileRef = io {
            if (!state.compareAndSet("open", "committing")) throw invalidFile()
            try {
                checkOpen(); sink.close(); currentCoroutineContext().ensureActive()
                // Detect external fills and permanently revoke deletion ownership. SAF provides no CAS:
                // another writer can still race between this probe and opening the destination.
                if (!stillEmpty(target, currentCoroutineContext()[Job])) throw CloudFailure(CloudFailureCode.CONFLICT,
                    "导出目标已被其他程序修改，已保留其内容；请重新选择新建空文档")
                val destination = DnsDocumentSink(resolver.openOutputStream(target.uri, "wt") ?: throw fileIo())
                target.publicationStarted.set(true) // Own partial output may be removed on failure/cancel.
                output.set(destination)
                val job = currentCoroutineContext()[Job]
                @OptIn(InternalCoroutinesApi::class)
                val cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) runCatching { destination.close() }
                }
                try {
                    FileInputStream(temp).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            destination.write(buffer, 0, n)
                        }
                    }
                } finally { cancellation?.dispose(); destination.close(); output.set(null) }
                currentCoroutineContext().ensureActive()
                val local = boundedMetadata(DnsDocumentSource(FileInputStream(temp)), exportMaxBytes, fingerprint)
                val actual = boundedMetadata(openSource(target.uri), exportMaxBytes, fingerprint)
                if (actual != local) throw fileIo()
                val ref = DnsFileRef(UUID.randomUUID().toString(), target.ref.displayName, actual.first, actual.second)
                synchronized(registry) {
                    checkOpen()
                    if (!state.compareAndSet("committing", "committed")) throw invalidFile()
                    selected[ref.id] = Selected(target.uri, ref)
                    targets.remove(target.ref.id); transactions.remove(this)
                }
                temp.delete(); ref
            } catch (error: Exception) {
                withContext(NonCancellable) { runCatching { abortNow() } }
                throw error
            }
        }
        override suspend fun abort() { withContext(NonCancellable + ioDispatcher) { abortNow() } }
        fun isPendingCleanup() = state.get() == "aborted"
        fun abortNow() {
            val old = state.getAndUpdate { if (it == "committed" || it == "cleaned") it else "aborted" }
            if (old == "committed" || old == "cleaned") return
            synchronized(cleanup) {
                if (state.get() == "cleaned") return
                // Independent attempts: local temp is removed even if the SAF grant has expired.
                // Keep the owned target capability until delete succeeds, so regrant + abort can finish.
                var failure: Exception? = null
                fun attempt(block: () -> Unit) { try { block() } catch (error: Exception) { if (failure == null) failure = error } }
                attempt { output.getAndSet(null)?.close() }; attempt { sink.close() }
                attempt { if (temp.exists() && !temp.delete()) throw fileIo() }
                attempt { deleteNew(target) }
                if (failure == null) {
                    state.set("cleaned"); targets.remove(target.ref.id); transactions.remove(this)
                }
                failure?.let { throw fileError(it) }
            }
        }
    }
}

/** File stream adapters own handles. Atomic close can release blocked IO from another thread. */
internal class DnsDocumentSource(private val input: InputStream, private val onClosed: () -> Unit = {}) : ByteSource {
    private val closed = AtomicBoolean()
    override fun read(buffer: ByteArray): Int {
        if (closed.get()) throw invalidFile()
        try { return input.read(buffer) }
        catch (error: Exception) { runCatching { close() }; throw fileError(error) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { input.close() } catch (error: Exception) { throw fileError(error) }
            finally { onClosed() }
        }
    }
}
internal class DnsDocumentSink(private val output: OutputStream, private val maxBytes: Long = Long.MAX_VALUE) : ByteSink {
    private val closed = AtomicBoolean()
    private var count = 0L
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        if (closed.get()) throw invalidFile()
        try {
            if (count < 0 || this.count > maxBytes - count) throw invalidFile()
            output.write(buffer, offset, count); this.count += count
        } catch (error: Exception) { runCatching { close() }; throw fileError(error) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) try { output.close() } catch (error: Exception) { throw fileError(error) }
    }
}

/** Streaming digest/length proof. No complete BIND parser and no URI/body in errors. */
@OptIn(InternalCoroutinesApi::class)
private suspend fun boundedMetadata(source: ByteSource, maxBytes: Long, hash: FingerprintPort): Pair<Long, String> {
    val job = currentCoroutineContext()[Job]
    val cancelled = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) runCatching { source.close() } }
    try {
        val digest = MessageDigest.getInstance("SHA-256"); var length = 0L; val buffer = ByteArray(8192)
        // Imports remain small enough to cross-check the injected hash contract; exports are streamed.
        val small = if (maxBytes <= DNS_IMPORT_MAX_BYTES) ByteArrayOutputStream() else null
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = source.read(buffer)
            currentCoroutineContext().ensureActive()
            if (n == -1) break
            if (n <= 0 || n > buffer.size || length > maxBytes - n) throw invalidFile()
            length += n; digest.update(buffer, 0, n); small?.write(buffer, 0, n)
        }
        val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        if (small != null && hash.sha256(small.toByteArray()) != actual) throw invalidFile()
        return length to actual
    } catch (error: Exception) { currentCoroutineContext().ensureActive(); throw fileError(error) }
    finally { cancelled?.dispose(); source.close() }
}
private fun invalidFile() = CloudFailure(CloudFailureCode.INVALID_INPUT, "文件引用已失效或目标非新建空文档，请重新选择文件")
private fun fileIo() = CloudFailure(CloudFailureCode.UNAVAILABLE, "文件读写失败，请检查存储空间后重新选择文件")
private fun fileError(error: Exception): CloudFailure = when (error) {
    is CloudFailure -> error
    is SecurityException -> CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "文件权限已失效，请重新选择文件或目标")
    else -> fileIo()
}

/** Fixed safe cleanup counts, no document URI or provider exception. */
data class DnsDocumentCleanupResult(val attempted:Int,val pending:Int)
