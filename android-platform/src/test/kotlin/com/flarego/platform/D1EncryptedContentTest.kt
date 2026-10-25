package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class D1EncryptedContentTest {
    @Test fun dedicatedD1OwnerCannotRevokeKvAndWholeAuthenticationPrecedesPrefix()=runBlocking {
        val root=Files.createTempDirectory("d1-owner-test").toFile()
        try {
            val d1=D1EncryptedContentStore(File(root,"d1"));val kv=KvEncryptedContentStore(File(root,"kv"))
            val content=d1.stage(source(200000),200000);val other=kv.stage(source(1),1)
            val reader=content.openVerified();d1.releaseAll()
            assertFailsWith<CloudFailure>{reader.read(ByteArray(1))}
            assertFailsWith<CloudFailure>{content.openVerified()}
            other.openVerified().close();other.close()
            val corrupt=d1.stage(source(200000),200000)
            val file=File(root,"d1").listFiles()!!.single()
            java.io.RandomAccessFile(file,"rw").use {val position=it.length()-3;it.seek(position);val original=it.read();it.seek(position);it.write(original xor 1)}
            assertFailsWith<CloudFailure>{corrupt.openVerified()};corrupt.close()
            assertTrue(File(root,"d1").listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun fullThirtyMiBStreamIsSealedWithoutWholePlaintextBuffer()=runBlocking {
        val dir=Files.createTempDirectory("d1-stream-test").toFile();var closed=false
        try {
            val store=D1EncryptedContentStore(dir);val length=30L*1024*1024
            val content=store.stage(source(length){closed=true},length)
            assertTrue(closed);assertEquals(length,content.descriptor.length)
            assertFalse(content.toString().contains(dir.path))
            val input=content.openVerified();val buffer=ByteArray(8192);var read=0L
            try {while(true){val n=input.read(buffer);if(n == -1)break;read+=n;assertTrue(buffer.take(n).all{it==71.toByte()})}}
            finally {input.close()}
            assertEquals(length,read);store.releaseAll();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun cancelledBeforeDispatchAndNoProgressInputsAreClosedAndLeaveNoFiles()=runBlocking {
        val dir=Files.createTempDirectory("d1-cancel-test").toFile();var closed=false;var reads=0
        try {
            val store=D1EncryptedContentStore(dir)
            val task=launch(start=CoroutineStart.UNDISPATCHED){currentCoroutineContext().cancel();store.stage(object:ByteSource{
                override fun read(buffer:ByteArray):Int{reads++;return -1}
                override fun close(){closed=true}
            },10)}
            task.join();assertTrue(closed);assertEquals(0,reads)
            assertFailsWith<CloudFailure>{store.stage(object:ByteSource{override fun read(buffer:ByteArray)=0;override fun close()=Unit},10)}
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    private fun source(size:Long,close:()->Unit={}):ByteSource=object:ByteSource{
        var left=size
        override fun read(buffer:ByteArray):Int{assertTrue(buffer.size<=65536);if(left==0L)return -1;val n=minOf(left,buffer.size.toLong()).toInt();buffer.fill(71,0,n);left-=n;return n}
        override fun close()=close.invoke()
    }
}
