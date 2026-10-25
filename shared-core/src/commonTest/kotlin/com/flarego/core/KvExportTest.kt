package com.flarego.core

import com.flarego.core.application.kv.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import kotlin.test.*

class KvExportTest {
    private class Tx : KvExportTransaction {
        val bytes=java.io.ByteArrayOutputStream(); var closed=false; var commits=0; var aborts=0
        var failWrite=false;var failCommit=false
        override val sink=object:ByteSink {
            override fun write(buffer:ByteArray,offset:Int,count:Int){check(!closed);if(failWrite)error("fixture");bytes.write(buffer,offset,count)}
            override fun close(){closed=true}
        }
        override suspend fun commit(){check(closed);commits++;if(failCommit)error("fixture")}
        override suspend fun abort(){aborts++}
    }
    private fun broken(length:Long=1,auth:Boolean=false,read:Int=0,onClose:()->Unit={}):KvContent=object:KvContent {
        override val descriptor=KvContentDescriptor("fixture",length)
        override suspend fun openVerified():ByteSource {if(auth)error("auth");return object:ByteSource{
            override fun read(buffer:ByteArray)=read
            override fun close()=onClose()
        }}
        override fun close()=Unit
    }
    @Test fun authenticatesBeforeCreatingAnyPlaintextDestination()=runTest {
        var begins=0
        assertFails { exportKvContent(broken(auth=true),{begins++;Tx()}) }
        assertEquals(0,begins)
    }
    @Test fun exactBinaryAndEmptyPublishOnlyAfterClose()=runTest {
        for(bytes in listOf(byteArrayOf(),ByteArray(140_001){(it%256).toByte()})) {
            val tx=Tx();assertEquals(bytes.size.toLong(),exportKvContent(KvMemoryContent(bytes),{tx}))
            assertContentEquals(bytes,tx.bytes.toByteArray());assertEquals(1,tx.commits);assertEquals(0,tx.aborts)
        }
    }
    @Test fun truncatedNoProgressAndOversizedStreamsAbort()=runTest {
        for(content in listOf(broken(read=-1),broken(read=0),broken(length=0,read=1))) {
            val tx=Tx();assertFails {exportKvContent(content,{tx})};assertEquals(1,tx.aborts);assertEquals(0,tx.commits);assertTrue(tx.closed)
        }
    }
    @Test fun failedOutputOrPublicationNeverRetriesAndAlwaysAborts()=runTest {
        for(commit in listOf(false,true)) {
            val tx=Tx().also {it.failWrite=!commit;it.failCommit=commit}
            assertFails {exportKvContent(KvMemoryContent(byteArrayOf(1)),{tx})}
            assertEquals(if(commit)1 else 0,tx.commits);assertEquals(1,tx.aborts);assertTrue(tx.closed)
        }
    }
    @Test fun staleScopeAtPublishAbortsAndBeginFailureClosesSource()=runTest {
        var guards=0
        val tx=Tx();assertFails {exportKvContent(KvMemoryContent(byteArrayOf(1)),{tx},{if(++guards==2)error("revoked")})}
        assertEquals(0,tx.commits);assertEquals(1,tx.aborts)
        var closed=false;assertFails {exportKvContent(broken(onClose={closed=true}),{error("begin")})};assertTrue(closed)
    }
    @Test fun cancellationUnblocksReadClosesBothEndsAndAbortsPendingDocument()=runTest {
        val entered=java.util.concurrent.CountDownLatch(1);val released=java.util.concurrent.CountDownLatch(1)
        val tx=Tx()
        val content=object:KvContent {
            override val descriptor=KvContentDescriptor("fixture",1)
            override suspend fun openVerified()=object:ByteSource {
                override fun read(buffer:ByteArray):Int{entered.countDown();check(released.await(5,java.util.concurrent.TimeUnit.SECONDS));return -1}
                override fun close(){released.countDown()}
            }
            override fun close()=Unit
        }
        val job=launch(Dispatchers.IO){exportKvContent(content,{tx})}
        withContext(Dispatchers.IO){assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))}
        job.cancelAndJoin();assertEquals(1,tx.aborts);assertEquals(0,tx.commits);assertTrue(tx.closed)
    }
}
