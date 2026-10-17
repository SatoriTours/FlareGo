package com.flarego

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.flarego.core.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class DnsDocumentKind { IMPORT, EXPORT }
data class DnsDocumentRequestStamp(val scope:ZoneScope,val operationEpoch:Long,val credential:ManagementCredentialStamp,
    val draftRevision:Long,val bindNonce:Long)
class DnsDocumentLaunch internal constructor(val requestId:String,val kind:DnsDocumentKind,val intent:Intent)
/** Android-only result capability. URI/flags never enter core or shared UI. */
class DnsDocumentSelection internal constructor(internal val uri:Uri,internal val flags:Int,
    internal val kind:DnsDocumentKind,internal val stamp:DnsDocumentRequestStamp) {
    internal val consumed=AtomicBoolean()
    internal var stillCurrent:(()->Boolean)?=null
}
class DnsDocumentPicker(private val context:Context,private val current:()->DnsDocumentRequestStamp?) {
    private data class Flight(val id:String,val kind:DnsDocumentKind,val stamp:DnsDocumentRequestStamp,val generation:Long,var invalid:Boolean=false)
    private var flight:Flight?=null
    private var generation=0L
    @Synchronized fun begin(kind:DnsDocumentKind,stamp:DnsDocumentRequestStamp):DnsDocumentLaunch {
        if(flight!=null) throw pickerConflict()
        if(current()!=stamp || stamp.scope.connectionId!=stamp.credential.connectionId ||
            stamp.scope.account.accountId!=stamp.credential.accountId || stamp.operationEpoch<0 || stamp.draftRevision<0 || stamp.bindNonce<0) throw pickerConflict()
        val intent=Intent(if(kind==DnsDocumentKind.IMPORT) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(if(kind==DnsDocumentKind.IMPORT) "*/*" else "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if(kind==DnsDocumentKind.EXPORT) intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION).putExtra(Intent.EXTRA_TITLE,"zone.bind")
        val pending=Flight(UUID.randomUUID().toString(),kind,stamp,generation);flight=pending
        return DnsDocumentLaunch(pending.id,kind,intent)
    }
    @Synchronized fun parse(kind:DnsDocumentKind,resultCode:Int,data:Intent?):DnsDocumentSelection? {
        val pending=flight?.takeIf { it.kind==kind } ?: throw pickerConflict()
        flight=null
        if(resultCode==Activity.RESULT_CANCELED) return null
        if(resultCode!=Activity.RESULT_OK || pending.invalid || current()!=pending.stamp) throw pickerConflict()
        val uri=data?.data ?: throw pickerInvalid()
        val needed=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            (if(kind==DnsDocumentKind.EXPORT) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if(data.flags and needed!=needed || uri.scheme!="content" || !DocumentsContract.isDocumentUri(context,uri)) throw pickerInvalid()
        data.clipData?.let { if(it.itemCount!=1 || it.getItemAt(0).uri!=uri) throw pickerInvalid() }
        return DnsDocumentSelection(uri,data.flags,kind,pending.stamp).also { selected ->
            selected.stillCurrent={ synchronized(this) { !pending.invalid && pending.generation==generation && current()==selected.stamp } }
        }
    }
    @Synchronized fun invalidate() { generation++;flight?.invalid=true }
    @Synchronized fun hasPending():Boolean=flight!=null
}
private fun pickerConflict()=CloudFailure(CloudFailureCode.CONFLICT,"文件请求已失效或仍有待返回请求，请重新选择")
private fun pickerInvalid()=CloudFailure(CloudFailureCode.PERMISSION_REQUIRED,"请选择单个文档并授予所需的持久读写权限")
