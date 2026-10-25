package com.flarego

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.flarego.core.application.kv.exportKvContent
import com.flarego.core.model.*
import com.flarego.core.ports.KvContent
import com.flarego.platform.KvDocumentFiles
import com.flarego.ui.kv.*
import kotlinx.coroutines.*

/** Memory-only picker capability and encrypted job copy; never restored from a URI or saved state. */
internal class KvDocumentBridge(context:Context,private val scope:CoroutineScope,private val browser:KvBrowser,
    private val current:()->KvBrowserContext?) {
    private val files=KvDocumentFiles(context)
    private class Pending(val request:KvDocumentRequest,val context:KvBrowserContext,val namespace:KvNamespaceRef,
        val key:String,val copy:KvContent?)
    private var pending:Pending?=null
    private var serial=0L
    private var running:Job?=null
    suspend fun takeLaunch(request:KvDocumentRequest):Pair<Boolean,Intent>? {
        if(pending!=null||running?.isActive==true||request.id<=serial)return null
        serial=request.id
        val state=browser.state.value
        val ctx=current()?.takeIf{it==state.context}?:return null
        val ns=state.namespace?.ref?:return null
        val export=request.kind in setOf(KvDocumentKind.EXPORT_VALUE,KvDocumentKind.EXPORT_BULK)
        return try {
            val copy=if(export)browser.content.freezeExport(if(request.kind==KvDocumentKind.EXPORT_VALUE)KvExportContent.VALUE else KvExportContent.BULK)else null
            val held=Pending(request,ctx,ns,state.form.key,copy)
            if(!valid(held)){copy?.close();return null}
            pending=held
            export to if(export)Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch(cancel:CancellationException){throw cancel}catch(_:Exception){browser.documentError();null}
    }
    fun launchFailed(){pending?.copy?.close();pending=null;browser.documentError()}
    fun result(export:Boolean,code:Int,data:Intent?) {
        val held=pending?:return
        if(export!=(held.copy!=null))return
        pending=null
        if(code!=Activity.RESULT_OK){held.copy?.close();return}
        val uri=data?.data
        val required=Intent.FLAG_GRANT_READ_URI_PERMISSION or if(export)Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        if(uri==null||data.flags and required!=required||!valid(held)){held.copy?.close();browser.documentError();return}
        running=scope.launch {
            var imported:com.flarego.core.ports.ByteSource?=null
            try {
                if(export)withContext(Dispatchers.IO){
                    val copy=held.copy?:error("missing")
                    exportKvContent(copy,{requireCurrent(held);files.begin(uri,copy.descriptor.length,held.request.kind==KvDocumentKind.EXPORT_BULK)},{requireCurrent(held)})
                } else {
                    requireCurrent(held)
                    val source=withContext(Dispatchers.IO){files.openImport(uri).also{imported=it}}
                    try{requireCurrent(held)}catch(failure:Exception){source.close();throw failure}
                    browser.importDocument(held.request.kind,source)
                }
                if(valid(held))browser.documentNotice(if(export)"完整文件已导出到所选目录；其中明文由你持有，请妥善保管"else"完整文件已加密暂存；尚未发送云端")
            } catch(cancel:CancellationException){throw cancel}catch(failure:Exception){
                if(valid(held))browser.documentFailure(failure as? CloudFailure)
            } finally {runCatching{imported?.close()};held.copy?.close()}
        }
    }
    private fun valid(held:Pending):Boolean {
        val state=browser.state.value
        return current()==held.context&&state.context==held.context&&state.namespace?.ref==held.namespace&&state.form.key==held.key
    }
    private fun requireCurrent(held:Pending){if(!valid(held))throw CloudFailure(CloudFailureCode.CONFLICT,"文件范围已改变，请重新选择")}
    fun close(){pending?.copy?.close();pending=null;running?.cancel()}
}
