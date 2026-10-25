package com.flarego.core

import com.flarego.core.application.kv.KvBulkJson
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KvBulkJsonTest {
    private fun content(text: String) = KvMemoryContent(text.encodeToByteArray())
    @Test fun completeMixedBulkWriteFreezesEveryKeyAndAcceptsEmptyAndBinaryValues() = runTest {
        val file = content("""[{"key":"text","value":""},{"key":"binary/键","value":"AP+A","base64":true,"metadata":null,"expiration_ttl":60},{"expiration":1060,"value":"x","key":"expiry"}]""")
        val prepared = KvBulkJson.prepareWrite(file, 1000)
        assertEquals(listOf("text", "binary/键", "expiry"), prepared.keys)
        assertSame(file, prepared.content)
    }
    @Test fun duplicateKeysFieldsUnknownFieldsAndBothExpiryModesAreRejected() = runTest {
        for (file in listOf(
            """[{"key":"x","value":"a"},{"key":"x","value":"b"}]""",
            """[{"key":"x","key":"y","value":"a"}]""",
            """[{"key":"x","value":"a","base64":false,"base64":true}]""",
            """[{"key":"x","value":"a","unknown":true}]""",
            """[{"key":"x","value":"a","expiration":1060,"expiration_ttl":60}]""",
            """[{"key":"x","value":null}]""", "[]", "{}", "[{}]", """[{"key":"x","value":"a"}] trailing""",
        )) assertFailsWith<CloudFailure>(file) { KvBulkJson.prepareWrite(content(file), 1000) }
    }
    @Test fun base64ValidationIsStrictEvenWhenFlagAppearsAfterValue() = runTest {
        for (value in listOf("A", "AA=", "AAAA=", "A===", "AA A", "AB==", "AAB=", "😀", "====")) {
            val file = """[{"key":"x","value":"$value","base64":true}]"""
            assertFailsWith<CloudFailure>(value) { KvBulkJson.prepareWrite(content(file), 1000) }
        }
        for (value in listOf("", "AA==", "AAA=", "AAAA", "AP+A")) {
            KvBulkJson.prepareWrite(content("""[{"base64":true,"value":"$value","key":"x"}]"""), 1000)
        }
    }
    @Test fun utf8MetadataAndExpiryLimitsAreCheckedWithoutTruncation() = runTest {
        val good = "\"" + "a".repeat(1022) + "\""
        KvBulkJson.prepareWrite(content("""[{"key":"x","value":"x","metadata":$good}]"""), 1000)
        val bad = "\"" + "a".repeat(1023) + "\""
        assertFailsWith<CloudFailure> { KvBulkJson.prepareWrite(content("""[{"key":"x","value":"x","metadata":$bad}]"""), 1000) }
        for (tail in listOf("\"expiration_ttl\":59", "\"expiration\":1059", "\"expiration\":1.5", "\"expiration_ttl\":\"60\"")) {
            assertFailsWith<CloudFailure> { KvBulkJson.prepareWrite(content("""[{"key":"x","value":"x",$tail}]"""), 1000) }
        }
    }
    @Test fun fullTenThousandKeysArePreservedAndTenThousandOneIsRejected() = runTest {
        fun file(n: Int) = (0 until n).joinToString(",", "[", "]") { """{"key":"key-$it","value":"x"}""" }
        val prepared = KvBulkJson.prepareWrite(content(file(10000)), 1000)
        assertEquals(10000, prepared.keys.size); assertEquals("key-9999", prepared.keys.last())
        assertFailsWith<CloudFailure> { KvBulkJson.prepareWrite(content(file(10001)), 1000) }
        assertEquals(listOf("a", "键/😀"), KvBulkJson.prepareDelete(content("""["a","键/😀"]""")))
        assertFailsWith<CloudFailure> { KvBulkJson.prepareDelete(content("""["a","a"]""")) }
    }
    @Test fun bulkReadKeepsAllJsonTypesAndRequiresEveryRequestedKeyAndSuccess() = runTest {
        val input = KvBulkReadInput(listOf("object", "array", "bool", "number", "null"), KvBulkReadType.json, false)
        val file = content("""{"success":true,"result":{"values":{"object":{"x":1},"array":[1,"x"],"bool":false,"number":1.25,"null":null}}}""")
        KvBulkJson.validateRead(file, input)
        assertEquals("false", KvBulkJson.previewRead(file, input, "bool"))
        assertEquals("null", KvBulkJson.previewRead(file, input, "null"))
        assertEquals("{\"x\":1}", KvBulkJson.previewRead(file, input, "object"))
        for (body in listOf("""{"success":false,"result":{"values":{}}}""", """{"success":true,"result":{"values":{"object":1}}}""", """{"success":true,"result":{"values":{"object":1,"object":2}}}""")) {
            assertFailsWith<CloudFailure> { KvBulkJson.validateRead(content(body), input) }
        }
    }
    @Test fun selectedPreviewHasABoundedSizeAndFullResponseRemainsAvailable() = runTest {
        val input = KvBulkReadInput(listOf("small", "large"), KvBulkReadType.text, false)
        val file = content("""{"success":true,"result":{"values":{"small":"ok","large":"${"x".repeat(100000)}"}}}""")
        KvBulkJson.validateRead(file, input)
        assertNull(KvBulkJson.previewRead(file, input, "large", 1024))
        assertEquals("\"ok\"", KvBulkJson.previewRead(file, input, "small", 1024))
    }
    @Test fun fullTwentyFiveMiBValueIsAcceptedAndOneExtraByteRejectedAfterBoundedReading() = runTest {
        val prefix = "[{\"key\":\"large\",\"value\":\"".encodeToByteArray()
        val suffix = "\"}]".encodeToByteArray()
        for (valueSize in listOf(KV_MAX_VALUE_BYTES, KV_MAX_VALUE_BYTES + 1)) {
            var consumed = 0L
            val file = object : KvContent {
                override val descriptor = KvContentDescriptor("synthetic-stream", prefix.size + valueSize + suffix.size)
                override suspend fun openVerified(): ByteSource = object : ByteSource {
                    var offset = 0L
                    override fun read(buffer: ByteArray): Int {
                        assertTrue(buffer.size <= 65536)
                        if (offset == descriptor.length) return -1
                        val n = minOf(buffer.size.toLong(), descriptor.length-offset).toInt()
                        repeat(n) { i -> val at = offset+i; buffer[i] = when {
                            at < prefix.size -> prefix[at.toInt()]
                            at < prefix.size+valueSize -> 65
                            else -> suffix[(at-prefix.size-valueSize).toInt()]
                        } }
                        offset += n; consumed += n; return n
                    }
                    override fun close() = Unit
                }
                override fun close() = Unit
            }
            if (valueSize == KV_MAX_VALUE_BYTES) assertEquals(listOf("large"), KvBulkJson.prepareWrite(file,1000).keys)
            else assertFailsWith<CloudFailure> { KvBulkJson.prepareWrite(file,1000) }
            assertTrue(consumed >= prefix.size + KV_MAX_VALUE_BYTES, "Must reach the real value boundary")
        }
    }
    @Test fun oneHundredMbLimitChecksWholeValidDocumentWithBoundedMemory() = runTest {
        for(total in listOf(KV_MAX_BULK_BYTES-1,KV_MAX_BULK_BYTES)) {
            val prefixes=(0..3).map{"{\"key\":\"k$it\",\"value\":\"".encodeToByteArray()}
            val suffix="\"}".encodeToByteArray()
            val overhead=prefixes.sumOf{it.size}+4*suffix.size+5
            val sizes=listOf(25_000_000L,25_000_000L,25_000_000L,total-overhead-75_000_000L)
            val pieces=mutableListOf<Pair<ByteArray?,Long>>()
            fun literal(text:ByteArray){pieces+=text to text.size.toLong()}
            literal("[".encodeToByteArray())
            for(i in 0..3){if(i>0)literal(",".encodeToByteArray());literal(prefixes[i]);pieces+=null to sizes[i];literal(suffix)}
            literal("]".encodeToByteArray())
            assertEquals(total,pieces.sumOf{it.second})
            var consumed=0L;var opens=0
            val file=object:KvContent {
                override val descriptor=KvContentDescriptor("generated-limit",total)
                override suspend fun openVerified():ByteSource {
                    opens++
                    return object:ByteSource {
                        var piece=0;var offset=0L
                        override fun read(buffer:ByteArray):Int {
                            assertTrue(buffer.size<=65536)
                            if(piece==pieces.size)return -1
                            val (bytes,length)=pieces[piece]
                            val n=minOf(buffer.size.toLong(),length-offset).toInt()
                            if(bytes==null)buffer.fill(65,0,n)else bytes.copyInto(buffer,0,offset.toInt(),offset.toInt()+n)
                            offset+=n;consumed+=n;if(offset==length){piece++;offset=0}
                            return n
                        }
                        override fun close()=Unit
                    }
                }
                override fun close()=Unit
            }
            if(total<KV_MAX_BULK_BYTES){assertEquals(listOf("k0","k1","k2","k3"),KvBulkJson.prepareWrite(file,1000).keys);assertEquals(total,consumed)}
            else {assertFailsWith<CloudFailure>{KvBulkJson.prepareWrite(file,1000)};assertEquals(0,opens)}
        }
    }

}
