package com.flarego

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.flarego.core.application.d1.exportD1Content
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.platform.D1DocumentFiles
import com.flarego.ui.d1.*
import kotlinx.coroutines.*

/** Picker capabilities are memory-only. The independent encrypted job copy survives onStop. */
internal class D1DocumentBridge(context: Context, private val scope: CoroutineScope, private val browser: D1Browser,
    private val current: () -> D1BrowserContext?) {
    private val files = D1DocumentFiles(context)
    private class Pending(val request: D1DocumentRequest, val context: D1BrowserContext, val database: D1DatabaseRef,
        val copy: D1Content?, val sql: Boolean)
    private var pending: Pending? = null
    private var running: Job? = null
    private var serial = 0L
    suspend fun takeLaunch(request: D1DocumentRequest): Pair<Boolean,Intent>? {
        if (pending != null || running?.isActive == true || request.id <= serial) return null
        serial = request.id
        val state = browser.state.value; val ctx = current()?.takeIf { it == state.context } ?: return null
        val database = state.database?.ref ?: return null
        val export = request.kind == D1DocumentKind.EXPORT_RESULT
        return try {
            val sql = browser.content.state.value.resultSql
            val copy = if (export) browser.content.freezeExport() else null
            val held = Pending(request,ctx,database,copy,sql)
            if (!valid(held)) { copy?.close(); return null }
            pending = held
            export to if (export) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { browser.documentError(); null }
    }
    fun launchFailed() { pending?.copy?.close(); pending = null; browser.documentError() }
    fun result(export: Boolean, code: Int, data: Intent?) {
        val held = pending ?: return
        if (export != (held.copy != null)) return
        pending = null
        if (code != Activity.RESULT_OK) { held.copy?.close(); return }
        val uri = data?.data
        val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (export) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        if (uri == null || data.flags and required != required || !valid(held)) { held.copy?.close(); browser.documentError(); return }
        running = scope.launch {
            var source: ByteSource? = null
            try {
                if (export) withContext(Dispatchers.IO) {
                    val copy = held.copy ?: error("missing sealed job copy")
                    exportD1Content(copy,{ requireCurrent(held); files.begin(uri,copy.descriptor.length,!held.sql) },{ requireCurrent(held) })
                } else {
                    requireCurrent(held)
                    val opened = withContext(Dispatchers.IO) { files.openImport(uri).also { source = it } }
                    try { requireCurrent(held) } catch (failure: Throwable) { opened.close(); throw failure }
                    browser.importDocument(held.request.kind,opened,held.request.editor)
                }
                if (valid(held)) browser.documentNotice(if (export) "完整文件已保存到所选目录；其中明文由你持有，请妥善保管" else "完整文件已加密暂存；尚未发送云端")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (valid(held)) browser.documentFailure(failure as? CloudFailure) }
            finally { runCatching { source?.close() }; held.copy?.close() }
        }
    }
    private fun valid(held: Pending) = current() == held.context && browser.state.value.context == held.context && browser.state.value.database?.ref == held.database
    private fun requireCurrent(held: Pending) { if (!valid(held)) throw CloudFailure(CloudFailureCode.CONFLICT,"文件目标范围已改变，请重新选择") }
    fun close() { pending?.copy?.close(); pending = null; running?.cancel() }
}
