package com.flarego.ui.d1

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.r2.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class D1Field { SQL, PARAMS, JSON, TABLES, TABLE, FILTER_COLUMN, FILTER_VALUE, SORT_COLUMN, BOOKMARK, TIMESTAMP, PREVIOUS_BOOKMARK, TYPED }
enum class D1InputKind { SQL_TEXT, SQL_JSON, IMPORT_SQL }
data class D1ContentState(
    val revision: Long = 0, val secure: Boolean = false, val surface: Long? = null, val revealed: Boolean = false,
    val inputKind: D1InputKind? = null, val inputBytes: Long? = null, val resultReady: Boolean = false,
    val resultBytes: Long? = null, val resultPageNumber: Int = 0, val hasNext: Boolean = false,
    val confirmation: D1ConfirmationHandle? = null, val exportReady: Boolean = false, val resultSql: Boolean = false,
)

/** Plaintext is available only by a direct call from the protected foreground surface. */
class D1ContentOwner(private val store: D1ContentStore, private val window: () -> SecureWindowPort?, private val changed: () -> Unit) {
    private val mutable = MutableStateFlow(D1ContentState())
    val state = mutable.asStateFlow()
    private var port: SecureWindowPort? = null
    private var held: SecureWindowSession? = null
    private var surface = 0L
    private var revision = 0L
    private var shown = false
    private val fields = mutableMapOf<D1Field, String>()
    private var input: D1Content? = null
    private var kind: D1InputKind? = null
    private var result: D1Content? = null
    private var resultSql = false
    private var page: D1PrivatePage? = null
    private val offsets = mutableListOf<Long>()
    private var confirmation: D1ConfirmationView? = null
    private var export: D1FileOutcome? = null
    private class Retiring(val port: SecureWindowPort, val held: SecureWindowSession)
    private val retiring = mutableMapOf<Long, Retiring>()

    fun begin(): Boolean {
        if (secureNow()) return true
        val p = window() ?: return false
        val h = p.request(SecurePurpose.D1_CONTENT) ?: return false
        if (!h.isProtected()) { p.release(h); return false }
        port = p; held = h; surface++; revision++; publish(); return true
    }
    fun secureNow(): Boolean {
        val h = held ?: return false
        val valid = window() === port && h.generation == port?.generation && h.isProtected()
        if (!valid) withdraw()
        return valid
    }
    fun lease(): Long { requireSecure(); return surface }
    fun reveal(show: Boolean) { if (secureNow()) { shown = show; publish() } }
    fun set(field: D1Field, text: String): Boolean {
        if (!secureNow() || runCatching { text.encodeToByteArray(throwOnInvalidSequence = true).size <= D1_EDITOR_BYTES }.getOrDefault(false) != true) return false
        fields[field] = text
        if (field in setOf(D1Field.SQL, D1Field.JSON)) { input?.close(); input = null; kind = null }
        revision++; publish()
        if (field != D1Field.TYPED) changed()
        return true
    }
    fun field(field: D1Field): String? = if (secureNow()) fields[field].orEmpty() else null
    fun confirmation(): D1ConfirmationView? = if (secureNow()) confirmation else null
    fun confirm(view: D1ConfirmationView?) { requireSecure(); confirmation = view; fields.remove(D1Field.TYPED); publish() }
    fun clearConfirmation() { confirmation = null; fields.remove(D1Field.TYPED); publish() }
    suspend fun importInput(source: ByteSource, kind: D1InputKind) {
        val stamp = try { stamp() } catch (failure: Throwable) { source.close(); throw failure }
        val staged = store.stage(source, D1_MAX_IMPORT_BYTES)
        var claimed = false
        try {
            if (kind == D1InputKind.SQL_JSON) D1SqlJson.prepareJson(staged) else D1ImportText.validate(staged)
            requireStamp(stamp); input?.close(); input = staged; this.kind = kind; claimed = true
            fields.remove(D1Field.SQL); fields.remove(D1Field.JSON); shown = false; revision++; publish(); changed()
        } finally { if (!claimed) staged.close() }
    }
    suspend fun freezeSql(json: Boolean): D1SqlInput {
        val stamp = stamp()
        val sourceKind = kind
        if (sourceKind == D1InputKind.IMPORT_SQL || sourceKind != null && (sourceKind == D1InputKind.SQL_JSON) != json) throw failure()
        val frozen = input?.let { store.stage(it.openVerified(), D1_MAX_IMPORT_BYTES) }
            ?: store.stage(bytesSource(fields[if (json) D1Field.JSON else D1Field.SQL].orEmpty()), D1_EDITOR_BYTES.toLong())
        try {
            val prepared = if (json) D1SqlJson.prepareJson(frozen) else {
                val values = fields[D1Field.PARAMS]?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonArray ?: throw failure() }
                val params = values?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw failure() }.orEmpty()
                D1SqlJson.fromSqlText(frozen, store, params).also { frozen.close() }
            }
            try { requireStamp(stamp); return prepared } catch (failure: Throwable) { prepared.content.close(); throw failure }
        } catch (failure: Throwable) { frozen.close(); throw failure }
    }
    suspend fun freezeImport(md5: D1ContentFingerprint): D1ImportFile {
        val stamp = stamp()
        if (kind != D1InputKind.IMPORT_SQL) throw failure()
        val frozen = store.stage((input ?: throw failure()).openVerified(), D1_MAX_IMPORT_BYTES)
        try { val prepared = D1ImportFile.prepare(frozen, md5); requireStamp(stamp); return prepared }
        catch (failure: Throwable) { frozen.close(); throw failure }
    }
    /** Called synchronously with the lease captured before dispatch. Input and result lifetimes differ. */
    fun acceptResult(content: D1Content, lease: Long? = null, sql: Boolean = false): Boolean {
        if (!secureNow() || lease != null && lease != surface) { content.close(); return false }
        result?.close(); result = content; resultSql = sql; page = null; offsets.clear(); shown = false; publish(); return true
    }
    fun acceptExport(outcome: D1FileOutcome, lease: Long): Boolean {
        if (!secureNow() || lease != surface) { outcome.close(); return false }
        outcome.takeReport()?.let { report ->
            clearExport(); outcome.close(); return acceptResult(report,lease)
        }
        closeResult()
        export?.close(); export = outcome; publish(); return true
    }
    fun closeResult() { result?.close(); result = null; resultSql = false; page = null; offsets.clear(); revision++; publish() }
    fun exportOutcome(): D1FileOutcome? = if (secureNow()) export else null
    fun clearExport() { export?.close(); export = null; publish() }
    suspend fun pageResult(previous: Boolean) {
        val stamp = stamp(); val original = result ?: throw failure()
        val offset = if (previous) { if (offsets.size < 2) return; offsets[offsets.size-2] }
            else if (page == null) 0L else page?.nextOffset ?: return
        val loaded = D1ContentPages.page(original, offset, 2048)
        requireStamp(stamp); if (result !== original) throw failure()
        if (previous) offsets.removeAt(offsets.lastIndex) else offsets += offset
        page = loaded; publish()
    }
    fun resultPage(): D1PrivatePage? = if (secureNow()) page else null
    suspend fun contentPage(content: D1Content, offset: Long): D1PrivatePage {
        val stamp = stamp(); val loaded = D1ContentPages.page(content, offset, 2048); requireStamp(stamp); return loaded
    }
    suspend fun freezeExport(): D1Content {
        val stamp = stamp(); val copy = store.stage((result ?: throw failure()).openVerified(), Long.MAX_VALUE)
        try { requireStamp(stamp); return copy } catch (failure: Throwable) { copy.close(); throw failure }
    }
    fun clearInput() { input?.close(); input = null; kind = null; fields.remove(D1Field.SQL); fields.remove(D1Field.PARAMS); fields.remove(D1Field.JSON); confirmation = null; revision++; publish() }
    fun withdraw() {
        val h = held; val p = port; val oldSurface = surface
        held = null; port = null; shown = false; fields.clear(); clearInput()
        result?.close(); result = null; resultSql = false; page = null; offsets.clear(); export?.close(); export = null
        revision++; publish(); changed()
        if (h != null && p != null && p.armRetirement(h)) retiring[oldSurface] = Retiring(p, h)
    }
    fun onWindowChanged() { if (held != null) secureNow() }
    fun surfaceRemoved(surface: Long?) { retiring.remove(surface)?.let { it.port.contentRemoved(it.held) } }
    fun disposed() { withdraw(); retiring.values.toList().also { retiring.clear() }.forEach { it.port.contentRemoved(it.held) } }
    private fun publish() { mutable.value = D1ContentState(revision, held != null, if (held != null) surface else null, shown, kind, input?.descriptor?.length, result != null, result?.descriptor?.length, offsets.size, page?.hasMore == true, confirmation?.handle, export != null, resultSql) }
    private fun stamp(): Long { requireSecure(); return revision }
    private fun requireStamp(value: Long) { requireSecure(); if (value != revision) throw failure() }
    private fun requireSecure() { if (!secureNow()) throw failure() }
    private fun failure() = CloudFailure(CloudFailureCode.CONFLICT, "D1 私有会话已失效或内容无效，请重新准备；未重发请求")
}

internal fun bytesSource(text: String): ByteSource {
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    return object : ByteSource {
        var at = 0
        override fun read(buffer: ByteArray): Int { if (at == bytes.size) return -1; val n = minOf(buffer.size, bytes.size-at); bytes.copyInto(buffer, 0, at, at+n); at += n; return n }
        override fun close() { bytes.fill(0) }
    }
}
