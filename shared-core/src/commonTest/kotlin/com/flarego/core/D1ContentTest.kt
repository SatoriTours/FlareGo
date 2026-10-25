package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class D1ContentTest {
    @Test fun exactFiveGiBAndOneExtraUseBoundedGeneratedStreamWithoutAllocation() {
        fun check(size: Long, accepted: Boolean) {
            var left=size; var closed=false
            val input=D1BoundedSource(object:ByteSource {
                override fun read(buffer:ByteArray):Int {
                    assertTrue(buffer.size<=65536)
                    if(left==0L)return -1
                    val n=minOf(left,buffer.size.toLong()).toInt();left-=n;return n
                }
                override fun close(){closed=true}
            },D1_MAX_IMPORT_BYTES)
            val buffer=ByteArray(65536)
            try {
                if(accepted){while(input.read(buffer)!=-1){};assertEquals(D1_MAX_IMPORT_BYTES,input.count)}
                else assertFailsWith<CloudFailure>{while(input.read(buffer)!=-1){}}
            } finally {input.close()}
            assertTrue(closed)
        }
        check(D1_MAX_IMPORT_BYTES,true);check(D1_MAX_IMPORT_BYTES+1,false)
    }
    @Test fun zeroProgressAndOverflowingLengthAreRejectedWithoutSpinning() {
        var reads=0;var closed=false
        val input=D1BoundedSource(object:ByteSource{
            override fun read(buffer:ByteArray):Int {reads++;return 0}
            override fun close(){closed=true}
        },Long.MAX_VALUE)
        assertFailsWith<CloudFailure>{input.read(ByteArray(1))};input.close()
        assertEquals(1,reads);assertTrue(closed)
        assertFailsWith<CloudFailure>{D1BoundedSource(object:ByteSource{override fun read(buffer:ByteArray)=-1;override fun close()=Unit},-1)}
    }
    @Test fun fullImportTextMustBeNonblankStrictUtf8ButKeepsMultiStatementProgram()=runTest {
        D1ImportText.validate(d1Content("/* ; */ CREATE TRIGGER t AFTER INSERT ON x BEGIN SELECT ';'; SELECT '中文'; END;"))
        for(bytes in listOf(byteArrayOf(),"  \r\n\t".encodeToByteArray(),byteArrayOf(0),byteArrayOf(0xc0.toByte(),0x80.toByte()),byteArrayOf(0xed.toByte(),0xa0.toByte(),0x80.toByte()),byteArrayOf(0xf0.toByte(),0x9f.toByte()))) {
            assertFailsWith<CloudFailure>{D1ImportText.validate(D1MemoryContent(bytes))}
        }
    }
}
