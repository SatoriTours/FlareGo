package com.flarego.core.application.kv

import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.KvContent
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import kotlinx.coroutines.*
import kotlin.concurrent.atomics.*

/** A newly-created pending document; commit publishes only a fully closed stream. */
interface KvExportTransaction {
    val sink: ByteSink
    suspend fun commit()
    suspend fun abort()
}

@OptIn(ExperimentalAtomicApi::class, InternalCoroutinesApi::class)
suspend fun exportKvContent(content: KvContent, begin: suspend () -> KvExportTransaction,
    beforePublish: () -> Unit = {}): Long {
    val source = content.openVerified() // No destination exists before complete authentication.
    val sink = AtomicReference<ByteSink?>(null)
    val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling=true,invokeImmediately=true) {
        if(it!=null){runCatching{source.close()};runCatching{sink.load()?.close()}}
    }
    var transaction: KvExportTransaction? = null
    var committed = false
    val buffer = ByteArray(65536)
    try {
        currentCoroutineContext().ensureActive();beforePublish()
        val tx=begin();transaction=tx;sink.store(tx.sink)
        var total=0L
        while(true){
            currentCoroutineContext().ensureActive()
            val n=source.read(buffer)
            currentCoroutineContext().ensureActive()
            if(n == -1)break
            if(n !in 1..buffer.size || total>content.descriptor.length-n)throw exportFailure()
            tx.sink.write(buffer,0,n);total+=n
        }
        if(total!=content.descriptor.length)throw exportFailure()
        tx.sink.close()
        currentCoroutineContext().ensureActive();beforePublish()
        tx.commit();committed=true
        return total
    } finally {
        cancel?.dispose();buffer.fill(0);runCatching{source.close()};runCatching{sink.load()?.close()}
        if(!committed)transaction?.let { tx -> withContext(NonCancellable){
            try{tx.abort()}catch(_:Exception){throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,
                "文件未能完成或清理；可能留有 KV 待完成文档，请检查所选目录并手动删除",uncertain=true)}
        } }
    }
}
private fun exportFailure()=CloudFailure(CloudFailureCode.INVALID_RESPONSE,"完整文件验证失败，未发布导出文件")
