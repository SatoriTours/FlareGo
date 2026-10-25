package com.flarego.ui.kv

import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.SecureWindowPort
import com.flarego.ui.r2.SecureWindowSession
import com.flarego.ui.r2.SecurePurpose
import com.flarego.core.application.kv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class KvSensitiveInput private constructor(private val text: String) {
    internal fun raw() = text
    override fun toString() = "KvSensitiveInput(redacted)"
    companion object { fun of(text: String) = KvSensitiveInput(text) }
}
enum class KvBulkContentKind { WRITE, DELETE, RESPONSE }
enum class KvExportContent { VALUE, BULK }
data class KvContentState(
    val revision: Long = 0, val secure: Boolean = false, val surface: Long? = null,
    val revealed: Boolean = false, val valueReady: Boolean = false, val valueBytes: Long? = null,
    val metadataReady: Boolean = false, val bulkKind: KvBulkContentKind? = null,
    val bulkBytes: Long? = null, val bulkKeys: List<String> = emptyList(), val textEditable: Boolean = true,
    val previewReady: Boolean = false,
)
class KvContentOwner(
    private val store: KvContentStore, private val secureWindow: () -> SecureWindowPort?,
    private val onChanged: () -> Unit,
) {
    private val mutable = MutableStateFlow(KvContentState())
    val state = mutable.asStateFlow()
    private var session: SecureWindowSession? = null
    private var port: SecureWindowPort? = null
    private var epoch = 0L
    private var revision = 0L
    private var shown = false
    private var text: String? = null
    private var metadata: String? = null
    private var value: KvContent? = null
    private var editable = true
    private var bulk: KvContent? = null
    private var bulkKind: KvBulkContentKind? = null
    private var keys = emptyList<String>()
    private var responseInput: KvBulkReadInput? = null
    private var preview: String? = null
    private data class Retirement(val port: SecureWindowPort, val session: SecureWindowSession)
    private val retirements = mutableMapOf<Long, Retirement>()

    fun begin(): Boolean {
        if (secureNow()) return true
        val candidate = secureWindow() ?: return false
        val held = candidate.request(SecurePurpose.KV_CONTENT) ?: return false
        if (!held.isProtected()) { candidate.release(held); return false }
        port = candidate; session = held; epoch++
        publish(); return true
    }
    fun secureNow(): Boolean {
        val held = session ?: return false
        val valid = secureWindow() === port && held.generation == port?.generation && held.isProtected()
        if (!valid) withdraw()
        return valid
    }
    fun reveal(show: Boolean) { if (secureNow()) { shown = show; publish() } }
    fun setValue(input: KvSensitiveInput): Boolean {
        if (!secureNow()) return false
        val next = input.raw()
        if (utf8(next)?.size?.let { it <= KV_EDITOR_BYTES } != true) return false
        value?.close(); value = null; text = next; editable = true
        changed(); return true
    }
    fun setMetadata(input: KvSensitiveInput): Boolean {
        if (!secureNow()) return false
        val next = input.raw()
        if (utf8(next)?.size?.let { it <= 16384 } != true) return false
        metadata = next; changed(); return true
    }
    fun valueForDisplay(): KvSensitiveInput? = if (secureNow()) text?.let(KvSensitiveInput::of) else null
    fun metadataForDisplay(): KvSensitiveInput? = if (secureNow()) metadata?.let(KvSensitiveInput::of) else null
    fun previewForDisplay(): KvSensitiveInput? = if (secureNow() && shown) preview?.let(KvSensitiveInput::of) else null
    suspend fun importValue(source: ByteSource) {
        val stamp = try { stamp() } catch (failure: Throwable) { source.close(); throw failure }
        val staged = store.stage(source, KV_MAX_VALUE_BYTES)
        installValue(staged, stamp)
    }
    suspend fun acceptValue(content: KvContent) {
        val current = try { stamp() } catch (failure: Throwable) { content.close(); throw failure }
        installValue(content, current)
    }
    private suspend fun installValue(content: KvContent, stamp: Long) {
        var owned = false
        try {
            if (content.descriptor.length !in 0..KV_MAX_VALUE_BYTES) throw failure()
            val loaded = readText(content)
            requireStamp(stamp)
            value?.close(); value = content; owned = true; text = loaded; editable = loaded != null
            shown = false; changed()
        } finally { if (!owned) content.close() }
    }
    fun acceptMetadata(metadata: KvMetadata) {
        requireSecure()
        metadata.withJsonText { this.metadata = it }
        shown = false; changed()
    }
    suspend fun freezeValue(expiry: KvExpiry, includeMetadata: Boolean): KvValueInput {
        val stamp = stamp()
        val meta = if (includeMetadata) KvValidation.metadata(metadata ?: throw failure()) else null
        val sealed = copyValue()
        try { requireStamp(stamp); return KvValueInput(sealed, expiry, meta) }
        catch (failure: Throwable) { sealed.close(); throw failure }
    }
    suspend fun importBulk(source: ByteSource, kind: KvBulkContentKind, nowSeconds: Long) {
        val stamp = try { stamp() } catch (failure: Throwable) { source.close(); throw failure }
        if (kind == KvBulkContentKind.RESPONSE) { source.close(); throw failure() }
        val staged = store.stage(source, KV_MAX_BULK_BYTES - 1)
        var owned = false
        try {
            val full = if (kind == KvBulkContentKind.WRITE) KvBulkJson.prepareWrite(staged, nowSeconds).keys else KvBulkJson.prepareDelete(staged)
            requireStamp(stamp)
            bulk?.close(); bulk = staged; owned = true; bulkKind = kind; keys = full.toList(); preview = null; responseInput = null
            shown = false; changed()
        } finally { if (!owned) staged.close() }
    }
    suspend fun freezeBulkWrite(nowSeconds: Long): KvBulkWriteInput {
        val stamp = stamp()
        if (bulkKind != KvBulkContentKind.WRITE) throw failure()
        val original = bulk ?: throw failure()
        val frozen = store.stage(original.openVerified(), KV_MAX_BULK_BYTES - 1)
        try {
            val checked = KvBulkJson.prepareWrite(frozen, nowSeconds)
            requireStamp(stamp)
            if (checked.keys != keys) throw failure()
            return checked
        } catch (failure: Throwable) { frozen.close(); throw failure }
    }
    fun bulkDeleteKeys(): List<String> {
        requireSecure()
        if (bulkKind != KvBulkContentKind.DELETE) throw failure()
        return keys.toList()
    }
    suspend fun acceptBulkResponse(content: KvContent, input: KvBulkReadInput) {
        val stamp = try { stamp() } catch (failure: Throwable) { content.close(); throw failure }
        var owned = false
        try {
            val frozen = input.copy(keys = input.keys.toList())
            KvBulkJson.validateRead(content, frozen); requireStamp(stamp)
            bulk?.close(); bulk = content; owned = true; bulkKind = KvBulkContentKind.RESPONSE
            keys = frozen.keys; responseInput = frozen; preview = null; shown = false; changed()
        } finally { if (!owned) content.close() }
    }
    suspend fun previewBulk(key: String) {
        val stamp = stamp(); val content = bulk ?: throw failure(); val input = responseInput ?: throw failure()
        val text = KvBulkJson.previewRead(content, input, key)
        requireStamp(stamp); preview = text; changed()
    }
    /** Transfer of a new encrypted copy to a user-approved file job; it survives clearing this editor. */
    suspend fun freezeExport(kind: KvExportContent): KvContent {
        val stamp = stamp()
        val copy = when (kind) {
            KvExportContent.VALUE -> copyValue()
            KvExportContent.BULK -> store.stage((bulk ?: throw failure()).openVerified(), 16L*1024*1024*1024)
        }
        try { requireStamp(stamp); return copy } catch (failure: Throwable) { copy.close(); throw failure }
    }
    private suspend fun copyValue(): KvContent = text?.let { store.stage(source(utf8(it) ?: throw failure()), KV_MAX_VALUE_BYTES) }
        ?: store.stage((value ?: throw failure()).openVerified(), KV_MAX_VALUE_BYTES)
    fun withdraw() {
        val old = session; val issuing = port; val oldEpoch = epoch
        session = null; port = null; shown = false
        text = null; metadata = null; preview = null; responseInput = null; keys = emptyList(); editable = true
        value?.close(); value = null; bulk?.close(); bulk = null; bulkKind = null
        revision++; publish(); onChanged()
        // Keep FLAG_SECURE until the exact old surface has left and the Window sees a clean frame.
        if (old != null && issuing != null && issuing.armRetirement(old)) retirements[oldEpoch] = Retirement(issuing, old)
    }
    fun onWindowChanged() { if (session != null) secureNow() }
    fun surfaceRemoved(epoch: Long?) { retirements.remove(epoch)?.let { it.port.contentRemoved(it.session) } }
    fun disposed() {
        withdraw()
        retirements.values.toList().also { retirements.clear() }.forEach { it.port.contentRemoved(it.session) }
    }
    private fun publish() {
        mutable.value = KvContentState(revision, session != null, if(session != null)epoch else null, shown,
            text != null || value != null, text?.let { utf8(it)?.size?.toLong() } ?: value?.descriptor?.length,
            metadata != null, bulkKind, bulk?.descriptor?.length, keys.toList(), editable, preview != null)
    }
    private fun changed() { revision++; publish(); onChanged() }
    private fun stamp(): Long { requireSecure(); return revision }
    private fun requireStamp(stamp: Long) { requireSecure(); if(revision != stamp)throw failure() }
    private fun requireSecure() { if(!secureNow())throw failure() }
    private fun utf8(value: String) = try { value.encodeToByteArray(throwOnInvalidSequence = true) }catch(_:Exception){null}
    private suspend fun readText(content: KvContent): String? = withContext(Dispatchers.Default) {
        val input = content.openVerified()
        try {
            if(content.descriptor.length > KV_EDITOR_BYTES)return@withContext null
            val bytes = ByteArray(content.descriptor.length.toInt()); val buffer=ByteArray(8192);var at=0
            while(true){currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;if(n==0||at+n>bytes.size)throw failure();buffer.copyInto(bytes,at,0,n);at+=n}
            if(at!=bytes.size)throw failure()
            try { bytes.decodeToString(throwOnInvalidSequence=true) }catch(_:Exception){null}
        }finally{input.close()}
    }
    private fun source(bytes: ByteArray) = object : ByteSource {
        var at=0
        override fun read(buffer:ByteArray):Int{if(at==bytes.size)return -1;val n=minOf(buffer.size,bytes.size-at);bytes.copyInto(buffer,0,at,at+n);at+=n;return n}
        override fun close(){bytes.fill(0)}
    }
    private fun failure() = CloudFailure(CloudFailureCode.INVALID_INPUT,"KV 私有内容不可用、窗口已失效或超出编辑上限；未发送内容")
}
