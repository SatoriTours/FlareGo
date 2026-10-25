package com.flarego.platform

import com.flarego.core.application.d1.exportD1Content
import com.flarego.core.model.CloudFailure
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import org.junit.Test
import java.io.*
import kotlin.test.*

class D1DocumentFilesTest {
    private class Provider:D1DocumentAccess {
        var createAllowed=true;var flags=true;var renameAllowed=true;var deleteAllowed=true
        var outputFailure=false;var writeFailure=false;var closeFailure=false
        var onOutput:(()->Unit)?=null
        var opens=0;var deletes=0;var renames=0;var inputOpens=0;var inputCloses=0
        var createdName="";var publishedName="";var closed=false
        val bytes=ByteArrayOutputStream()
        override fun parent(tree:String):String{check(tree=="tree");return "parent"}
        override fun canCreate(parent:String)=createAllowed
        override fun canWriteDeleteRename(document:String)=flags
        override fun create(parent:String,name:String):String{check(parent=="parent");createdName=name;return "owned-pending"}
        override fun input(document:String):InputStream {inputOpens++;return object:ByteArrayInputStream(byteArrayOf(0,-1,65)){
            override fun close(){inputCloses++;super.close()}
        }}
        override fun output(document:String):OutputStream {
            assertEquals("owned-pending",document);opens++;if(outputFailure)error("fixture")
            val output=object:OutputStream(){
                override fun write(value:Int){if(writeFailure)error("fixture");bytes.write(value)}
                override fun write(b:ByteArray,offset:Int,len:Int){if(writeFailure)error("fixture");bytes.write(b,offset,len)}
                override fun close(){closed=true;if(closeFailure)error("fixture")}
            }
            onOutput?.invoke()
            return output
        }
        override fun rename(document:String,name:String):Boolean{assertEquals("owned-pending",document);assertTrue(closed);renames++;publishedName=name;return renameAllowed}
        override fun delete(document:String):Boolean{assertEquals("owned-pending",document);deletes++;return deleteAllowed}
    }
    private fun content(bytes:ByteArray):D1Content=object:D1Content {
        override val descriptor=D1ContentDescriptor("fixture",bytes.size.toLong())
        override suspend fun openVerified():ByteSource=object:ByteSource{
            val input=ByteArrayInputStream(bytes)
            override fun read(buffer:ByteArray)=input.read(buffer)
            override fun close()=input.close()
        }
        override fun close()=Unit
    }
    @Test fun binaryAndEmptyFilesAreNewPendingThenPublishedOnlyAfterFullClose()=runBlocking {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(0,-1,-128,65))) {
            val p=Provider();val files=D1DocumentFiles(p)
            assertEquals(bytes.size.toLong(),exportD1Content(content(bytes),{files.begin("tree",bytes.size.toLong(),false)}))
            assertContentEquals(bytes,p.bytes.toByteArray());assertEquals(1,p.opens);assertEquals(1,p.renames);assertEquals(0,p.deletes)
            assertTrue(p.createdName.startsWith("flarego-d1-pending-"));assertFalse(p.publishedName.contains("pending"))
        }
    }
    @Test fun unsupportedProviderIsRejectedBeforeAnyPlaintextWrite()=runBlocking {
        for(parent in listOf(false,true)) {
            val p=Provider().also{it.createAllowed=parent;it.flags=false};val f=D1DocumentFiles(p)
            assertFailsWith<CloudFailure>{f.begin("tree",1,false)}
            assertEquals(0,p.opens);assertEquals(if(parent)1 else 0,p.deletes);assertEquals(0,p.bytes.size())
        }
    }
    @Test fun openWriteAndCloseFailuresDeleteOnlyTheOwnedPendingDocument()=runBlocking {
        for(mode in 0..2) {
            val p=Provider().also{it.outputFailure=mode==0;it.writeFailure=mode==1;it.closeFailure=mode==2}
            assertFails {exportD1Content(content(byteArrayOf(1)),{D1DocumentFiles(p).begin("tree",1,false)})}
            assertEquals(1,p.deletes);assertEquals(0,p.renames)
        }
    }
    @Test fun failedCleanupOrUncertainRenameIsExplicitAndNeverAutomaticallyRetried()=runBlocking {
        val p=Provider().also{it.renameAllowed=false}
        assertTrue(assertFailsWith<CloudFailure>{exportD1Content(content(byteArrayOf(1)),{D1DocumentFiles(p).begin("tree",1,false)})}.uncertain)
        assertEquals(1,p.renames);assertEquals(0,p.deletes)
        val denied=Provider().also{it.writeFailure=true;it.deleteAllowed=false}
        assertTrue(assertFailsWith<CloudFailure>{exportD1Content(content(byteArrayOf(1)),{D1DocumentFiles(denied).begin("tree",1,false)})}.uncertain)
        assertEquals(1,denied.deletes)
    }
    @Test fun cancellationBeforeReturningTheCreatedTransactionClosesAndDeletesPendingDocument()=runBlocking {
        val call=Job()
        val p=Provider().also{it.onOutput={call.cancel()}}
        assertFailsWith<CancellationException>{withContext(call){D1DocumentFiles(p).begin("tree",1,false)}}
        assertTrue(p.closed,"The output opened before cancellation must be closed")
        assertEquals(1,p.deletes);assertEquals(0,p.renames);assertEquals(0,p.bytes.size())
    }
    @Test fun cancelledTransactionCleanupFailureIsReportedAsUncertain()=runBlocking {
        val call=Job()
        val p=Provider().also{it.onOutput={call.cancel()};it.deleteAllowed=false}
        val failure=assertFailsWith<CloudFailure>{withContext(call){D1DocumentFiles(p).begin("tree",1,false)}}
        assertTrue(failure.uncertain);assertEquals(com.flarego.core.model.CloudFailureCode.UNKNOWN_RESULT,failure.code)
        assertTrue(p.closed);assertEquals(1,p.deletes);assertEquals(0,p.renames)
    }
    @Test fun importOpensOriginalOnceAndCloseIsIdempotent(){
        val p=Provider();val source=D1DocumentFiles(p).openImport("original")
        val bytes=ByteArray(3);assertEquals(3,source.read(bytes));assertContentEquals(byteArrayOf(0,-1,65),bytes)
        source.close();source.close();assertEquals(1,p.inputOpens);assertEquals(1,p.inputCloses)
    }
}
