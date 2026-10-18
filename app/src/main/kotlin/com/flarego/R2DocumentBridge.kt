package com.flarego

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.flarego.core.model.R2_MAX_SINGLE_OBJECT_BYTES
import com.flarego.core.ports.DnsExportTransaction
import com.flarego.platform.DnsDocumentFiles
import com.flarego.platform.stageR2Upload
import com.flarego.ui.r2.*
import kotlinx.coroutines.*

/** Android owns URI grants; the presenter sees only a frozen request and opaque file metadata. */
internal class R2DocumentBridge(private val application: Application, private val scope: CoroutineScope, private val browser: R2Browser) {
    private val files = DnsDocumentFiles(application, exportMaxBytes = R2_MAX_SINGLE_OBJECT_BYTES)
    private var pending: R2DocumentRequest? = null
    private var closed = false

    fun takeLaunch(id: Long): Pair<R2DocumentKind, Intent>? {
        if (pending != null || closed) return null
        val request = browser.takeDocumentRequest(id) ?: return null
        if (!browser.isDocumentCurrent(id)) { browser.documentFinished(id, false); return null }
        pending = request
        val upload = request.kind == R2DocumentKind.UPLOAD
        val title = request.key.substringAfterLast('/').filter { it.code >= 32 && it != '\\' }.take(128).ifBlank { "object.bin" }
        val intent = Intent(if (upload) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (!upload) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION).putExtra(Intent.EXTRA_TITLE, title)
        return request.kind to intent
    }
    fun launchFailed(kind: R2DocumentKind) {
        pending?.takeIf { it.kind == kind }?.let { pending = null; browser.documentFinished(it.id, false) }
    }
    fun result(kind: R2DocumentKind, code: Int, data: Intent?) {
        val request = pending?.takeIf { it.kind == kind } ?: return
        pending = null
        if (closed || code != Activity.RESULT_OK || !browser.isDocumentCurrent(request.id)) {
            browser.documentFinished(request.id, false, cancelled = code == Activity.RESULT_CANCELED); return
        }
        val uri = data?.data
        val modes = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (kind == R2DocumentKind.DOWNLOAD) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        if (uri == null || uri.scheme != "content" || !DocumentsContract.isDocumentUri(application, uri) ||
            data.flags and (modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != (modes or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) ||
            data.clipData?.let { it.itemCount != 1 || it.getItemAt(0).uri != uri } == true) {
            browser.documentFinished(request.id, false); return
        }
        scope.launch {
            var added = 0
            var tx: DnsExportTransaction? = null
            var succeeded = false
            try {
                val resolver = application.contentResolver
                withContext(Dispatchers.IO) {
                    val baseline = resolver.persistedUriPermissions.filter { it.uri == uri }.fold(0) { flags, grant ->
                        flags or (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    }
                    // Record cleanup ownership before a fallible/cancellable acquisition returns.
                    added = modes and baseline.inv()
                    resolver.takePersistableUriPermission(uri, modes)
                }
                if (closed || !browser.isDocumentCurrent(request.id)) throw CancellationException("stale document")
                if (kind == R2DocumentKind.UPLOAD) {
                    val staged = stageR2Upload(application.cacheDir, { resolver.openInputStream(uri) ?: error("no input") })
                    // acceptUpload releases the copy when the independent route/draft changed.
                    browser.acceptUpload(request.id, staged)
                } else {
                    // Only a newly created empty CreateDocument result is adopted. Existing content
                    // is never truncated; the existing transaction rechecks and verifies publication.
                    val target = files.resolveExportTarget(uri)
                    tx = files.beginExport(target)
                    browser.download(request.id, tx!!.sink)
                    if (closed || !browser.isDocumentCurrent(request.id)) throw CancellationException("stale document")
                    val receipt = tx!!.commit()
                    files.release(receipt)
                }
                succeeded = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Publish completion only after the cleanup below has actually returned.
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) {
                        // abort is idempotent and a no-op after a successful commit.
                        runCatching { tx?.abort() }
                        if (added != 0) runCatching { application.contentResolver.releasePersistableUriPermission(uri, added) }
                    }
                    // A provider may revoke a grant during deletion. Observing grant
                    // disappearance (or the old early failure notice) is not an abort barrier.
                    browser.documentFinished(request.id, succeeded)
                }
            }
        }
    }
    fun close() { closed = true; files.close() }
}
