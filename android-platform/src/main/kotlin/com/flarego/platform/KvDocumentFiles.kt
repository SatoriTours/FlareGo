package com.flarego.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.flarego.core.application.kv.KvExportTransaction
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Private locators never leave this adapter. This seam also exercises provider failures locally. */
internal interface KvDocumentAccess {
    fun parent(tree:String):String
    fun canCreate(parent:String):Boolean
    fun canWriteDeleteRename(document:String):Boolean
    fun create(parent:String,name:String):String
    fun input(document:String):InputStream
    fun output(document:String):OutputStream
    fun rename(document:String,name:String):Boolean
    fun delete(document:String):Boolean
}

/** Ephemeral SAF grants only. Never opens an existing document for writing or uses plaintext cache. */
class KvDocumentFiles internal constructor(private val access:KvDocumentAccess) {
    constructor(context:Context):this(AndroidKvDocuments(context.applicationContext))
    fun openImport(uri:Uri):ByteSource=openImport(uri.toString())
    internal fun openImport(document:String):ByteSource {
        val input=try{access.input(document)}catch(_:Exception){throw fileFailure()}
        return object:ByteSource {
            private val closed=AtomicBoolean(false)
            override fun read(buffer:ByteArray):Int {if(closed.get())throw fileFailure();return input.read(buffer)}
            override fun close(){if(closed.compareAndSet(false,true))input.close()}
        }
    }
    suspend fun begin(tree:Uri,length:Long,json:Boolean):KvExportTransaction=begin(tree.toString(),length,json)
    internal suspend fun begin(tree:String,length:Long,json:Boolean):KvExportTransaction {
        var published:KvExportTransaction?=null
        try { return withContext(Dispatchers.IO) {
        if(length<0)throw fileFailure()
        val parent=access.parent(tree)
        if(!access.canCreate(parent))throw unsupported()
        val suffix=if(json)"json"else"bin"
        val id=UUID.randomUUID().toString()
        val pending=access.create(parent,"flarego-kv-pending-$id.$suffix")
        var opened=false
        try {
            if(!access.canWriteDeleteRename(pending))throw unsupported()
            val output=access.output(pending);opened=true
            object:KvExportTransaction {
                private val closed=AtomicBoolean(false)
                private var written=0L
                private var publishing=false
                private var committed=false
                override val sink=object:ByteSink {
                    override fun write(buffer:ByteArray,offset:Int,count:Int){
                        if(closed.get()||count<0||written>length-count)throw fileFailure()
                        output.write(buffer,offset,count);written+=count
                    }
                    override fun close(){if(closed.compareAndSet(false,true))output.close()}
                }
                override suspend fun commit() {
                    if(!closed.get()||written!=length||publishing||committed)throw fileFailure()
                    publishing=true
                    if(!runCatching{access.rename(pending,"flarego-kv-$id.$suffix")}.getOrDefault(false))
                        throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"导出发布结果未知；请检查所选目录，不要自动重试",uncertain=true)
                    committed=true
                }
                override suspend fun abort() {
                    runCatching{sink.close()}
                    if(committed)return
                    // A failed rename may already have published a complete document. Never guess/delete it.
                    if(publishing || !access.delete(pending))throw fileFailure()
                }
            }.also{published=it}
        } catch(error:Exception) {
            if(!opened && !runCatching{access.delete(pending)}.getOrDefault(false))
                throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,"无法清理新建的 KV 待完成文档；请检查所选目录",uncertain=true)
            throw error
        }
        } } catch(failure:Throwable) {
            // Prompt cancellation can discard the IO result before the caller owns it.
            published?.let{tx->withContext(NonCancellable+Dispatchers.IO){
                try{tx.abort()}catch(_:Exception){
                    throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,
                        "无法清理新建的 KV 待完成文档；请检查所选目录",uncertain=true)
                }
            }}
            throw failure
        }
    }
}
private class AndroidKvDocuments(context:Context):KvDocumentAccess {
    private val resolver=context.contentResolver
    override fun parent(tree:String):String {
        val uri=Uri.parse(tree)
        if(!DocumentsContract.isTreeUri(uri))throw fileFailure()
        return DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)).toString()
    }
    private fun flags(document:String):Int=resolver.query(Uri.parse(document),arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use{
        if(!it.moveToFirst()||it.isNull(0))throw unsupported();it.getInt(0)
    }?:throw unsupported()
    override fun canCreate(parent:String)=flags(parent) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE!=0
    override fun canWriteDeleteRename(document:String):Boolean {
        val required=DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        return flags(document) and required==required
    }
    override fun create(parent:String,name:String)=DocumentsContract.createDocument(resolver,Uri.parse(parent),"application/octet-stream",name)?.toString()?:throw fileFailure()
    override fun input(document:String)=resolver.openInputStream(Uri.parse(document))?:throw fileFailure()
    override fun output(document:String)=resolver.openOutputStream(Uri.parse(document),"w")?:throw fileFailure()
    override fun rename(document:String,name:String)=DocumentsContract.renameDocument(resolver,Uri.parse(document),name)!=null
    override fun delete(document:String)=DocumentsContract.deleteDocument(resolver,Uri.parse(document))
}
private fun unsupported()=CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY,"该文件提供方不支持安全的新建、删除和完成后改名，请选择其他目录")
private fun fileFailure()=CloudFailure(CloudFailureCode.UNAVAILABLE,"KV 文件无法访问或完整写入，请检查目录、空间与权限")
