package com.flarego.core

import com.flarego.core.application.d1.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

internal class D1MemoryContent(private val bytes: ByteArray) : D1Content {
    var closed = false
    override val descriptor = D1ContentDescriptor("fixture-${next++}", bytes.size.toLong())
    override suspend fun openVerified(): ByteSource {
        check(!closed)
        return object : ByteSource {
            private var at = 0; private var ended = false
            override fun read(buffer: ByteArray): Int {
                check(!closed && !ended)
                if (at == bytes.size) return -1
                val n = minOf(4096, buffer.size, bytes.size - at)
                bytes.copyInto(buffer, 0, at, at + n); at += n; return n
            }
            override fun close() { ended = true }
        }
    }
    override fun close() { closed = true }
    companion object { private var next = 0 }
}
internal class D1MemoryStore : D1ContentStore {
    override fun writer(maxBytes: Long): D1ContentWriter = object : D1ContentWriter {
        val output = java.io.ByteArrayOutputStream(); var aborted = false
        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            check(!aborted && count.toLong() <= maxBytes - output.size()); output.write(buffer, offset, count)
        }
        override fun close() = Unit
        override suspend fun seal(): D1Content { check(!aborted); return D1MemoryContent(output.toByteArray()) }
        override fun abort() { aborted = true; output.reset() }
    }
    override suspend fun stage(source: ByteSource, maxBytes: Long): D1Content {
        val w = writer(maxBytes); val buffer = ByteArray(8192)
        try { while (true) { val n = source.read(buffer); if (n == -1) break; check(n in 1..buffer.size); w.write(buffer, 0, n) }; return w.seal() }
        catch (failure: Throwable) { w.abort(); throw failure }
        finally { source.close() }
    }
    override fun releaseAll() = Unit
}
internal fun d1Content(text: String): D1Content = D1MemoryContent(text.encodeToByteArray())
internal suspend fun d1Text(content: D1Content): String {
    val s=content.openVerified(); val out=java.io.ByteArrayOutputStream(); val b=ByteArray(8192)
    try { while(true){val n=s.read(b);if(n == -1)break;out.write(b,0,n)};return out.toByteArray().decodeToString() } finally{s.close()}
}

class D1SqlJsonTest {
    @Test fun sqlTextLargerThanEditorKeepsTriggerWhitespaceAndSemicolons() = runTest {
        val sql="-- ${"x".repeat(100001)}\nCREATE TRIGGER t AFTER INSERT ON a BEGIN UPDATE a SET x=';'; END;\nSELECT '雪';\n"
        val original=d1Content(sql)
        val prepared=D1SqlJson.fromSqlText(original,D1MemoryStore(),listOf("", "0001"))
        val json=Json.parseToJsonElement(d1Text(prepared.content)).jsonObject
        assertEquals(sql,json["sql"]?.jsonPrimitive?.content)
        assertEquals(listOf("", "0001"),json["params"]?.jsonArray?.map{it.jsonPrimitive.content})
        assertEquals(1L,prepared.queryCount)
        assertFalse(prepared.toString().contains("CREATE"))
    }
    @Test fun typedJsonAcceptsOnlyCompleteSingleOrBatchWithStringParameters() = runTest {
        val input=D1SqlJson.prepareJson(d1Content("""{"batch":[{"sql":"SELECT ';'","params":["1"]},{"sql":"PRAGMA user_version = 2"}]}"""))
        assertEquals(2L,input.queryCount)
        for (bad in listOf(
            """{"sql":"SELECT 1","params":[1]}""",
            """{"sql":"SELECT 1","sql":"DELETE FROM x"}""",
            """{"sql":"SELECT 1","batch":[]}""",
            """{"batch":[]}""", """{"sql":" "}""", """{"sql":"SELECT 1","unknown":true}""",
            """{"sql":"\ud800"}""", """{"sql":"x\u0000y"}""", """{"sql":"SELECT 1"}[]""",
        )) assertFailsWith<CloudFailure>(bad) { D1SqlJson.prepareJson(d1Content(bad)) }
    }
    @Test fun parametersUseFullHundredBoundaryWithoutChangingStrings() = runTest {
        for (n in listOf(100,101)) {
            val json=buildJsonObject{put("sql","SELECT 1");put("params",JsonArray(List(n){JsonPrimitive("?")}))}.toString()
            if(n == 100) assertEquals(1L,D1SqlJson.prepareJson(d1Content(json)).queryCount)
            else assertFailsWith<CloudFailure>{D1SqlJson.prepareJson(d1Content(json))}
        }
    }
    @Test fun previewIsPagedInBytesAndNeverClaimsAnOversizeValueComplete() = runTest {
        val prepared=D1SqlJson.prepareJson(d1Content("""{"sql":"${"雪".repeat(30000)}"}"""))
        val first=D1ContentPages.page(prepared.content,0,1024)
        val next=D1ContentPages.page(prepared.content,first.nextOffset!!,1024)
        assertTrue(first.hasMore);assertFalse(first.text.endsWith("�"));assertFalse(next.text.startsWith("�"))
        assertFalse(first.toString().contains("雪"))
    }
}
