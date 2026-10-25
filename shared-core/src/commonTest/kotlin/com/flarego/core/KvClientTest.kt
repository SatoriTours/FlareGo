package com.flarego.core

import com.flarego.core.application.kv.KvValidation
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.kv.CloudflareKvClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

internal class KvMemoryContent(private val bytes: ByteArray) : KvContent {
    private var closed = false
    override val descriptor = KvContentDescriptor("synthetic-${counter++}", bytes.size.toLong())
    override suspend fun openVerified(): ByteSource {
        check(!closed)
        return object : ByteSource {
            var at = 0
            override fun read(buffer: ByteArray): Int {
                check(!closed)
                if (at == bytes.size) return -1
                val n = minOf(buffer.size, bytes.size - at)
                bytes.copyInto(buffer, 0, at, at + n); at += n; return n
            }
            override fun close() = Unit
        }
    }
    override fun close() { closed = true }
    companion object { private var counter = 0 }
}
internal class KvMemoryStore : KvContentStore {
    override fun writer(maxBytes: Long): KvContentWriter = object : KvContentWriter {
        val bytes = java.io.ByteArrayOutputStream(); var aborted = false
        override fun write(buffer: ByteArray, offset: Int, count: Int) { check(bytes.size() + count <= maxBytes); bytes.write(buffer, offset, count) }
        override fun close() = Unit
        override suspend fun seal(): KvContent { check(!aborted); return KvMemoryContent(bytes.toByteArray()) }
        override fun abort() { aborted = true; bytes.reset() }
    }
    override suspend fun stage(source: ByteSource, maxBytes: Long): KvContent {
        val writer = writer(maxBytes); val buffer = ByteArray(8192)
        try { while (true) { val n = source.read(buffer); if (n < 0) break; writer.write(buffer, 0, n) }; return writer.seal() }
        finally { source.close() }
    }
    override fun releaseAll() = Unit
}

class KvClientTest {
    private val connection = Connection("local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val namespace = KvNamespaceRef(account, "b".repeat(32))
    private val base = "/accounts/${account.accountId}/storage/kv/namespaces"
    private val path = "$base/${namespace.id}"
    private val requests = mutableListOf<HttpRequest>()
    private val sentSensitiveBodies = mutableListOf<String>()
    private var response = HttpResponse(200, """{"success":true,"result":{}}""")
    private var streamBytes = byteArrayOf(0, -1, -128, 65)
    private var streamStatus = 200
    private var guards = 0
    private fun client(guard: (String, AccountScope, String?) -> Unit = { _, _, _ -> guards++ }): CloudflareKvClient {
        val transport = HttpTransport { request ->
            requests += request
            (request.payload as? HttpBody.SensitiveJson)?.let { sentSensitiveBodies += it.consumeBytes().decodeToString() }
            if (request.method == "GET" && request.path == path) HttpResponse(200, """{"success":true,"result":{"id":"${namespace.id}","title":"fixture","supports_url_encoding":true}}""")
            else response
        }
        return CloudflareKvClient(connection, CloudflareGateway(transport, sleeper = {}), object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
                requests += request
                try { if (streamStatus == 200) sink.write(streamBytes, 0, streamBytes.size) }
                finally { sink.close() }
                return HttpResponseHead(streamStatus, mapOf("Content-Type" to listOf("application/octet-stream")), if (streamStatus == 200) streamBytes.size.toLong() else 0)
            }
        }, KvMemoryStore(), { 1000 }, guard)
    }
    private fun result(json: String, success: Boolean = true) { response = HttpResponse(200, """{"success":$success,"result":$json}""") }

    @Test fun namespacesUseExactNumberPagingAndRequireCreationIdentity() = runTest {
        result("""[{"id":"${namespace.id}","title":"fixture"}]""")
        val page = client().listNamespaces(account, KvNamespacePageRequest(2, 30, KvNamespaceOrder.title, KvSortDirection.desc))
        assertEquals(namespace, page.items.single().ref)
        assertEquals("$base?page=2&per_page=30&order=title&direction=desc", requests.single().path)
        result("""{"title":"same-name"}""")
        assertTrue(assertFailsWith<CloudFailure> { client().createNamespace(account, "same-name") }.uncertain)
        assertEquals(1, requests.count { it.method == "POST" })
    }
    @Test fun namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard() = runTest {
        val client = client(); assertEquals(namespace, client.getNamespace(namespace).ref)
        result("""{"id":"${namespace.id}","title":"new"}""")
        assertEquals("new", client.createNamespace(account, "new").title)
        client.renameNamespace(namespace, "renamed"); client.deleteNamespace(namespace)
        assertEquals(listOf("GET", "POST", "PUT", "DELETE"), requests.map { it.method })
        assertEquals(3, guards)
        assertEquals(JsonPrimitive("new"), Json.parseToJsonElement(sentSensitiveBodies.first()).jsonObject["title"])
    }
    @Test fun keysUseLimitNotPerPageAndKeepEmptyPageWithOpaqueCursor() = runTest {
        response = HttpResponse(200, """{"success":true,"result":[],"result_info":{"cursor":"next/+?="}}""")
        val page = client().listKeys(namespace, KvKeyPageRequest(10, "A/", "old", setOf("old")))
        assertEquals(PageToken.Cursor("next/+?="), page.next)
        assertEquals("$path/keys?limit=10&prefix=A%2F&cursor=old", requests.single().path)
        assertFailsWith<CloudFailure> { client().listKeys(namespace, KvKeyPageRequest(10, cursor = "next/+?=")) }
    }
    @Test fun listingNeverPublishesMetadataAndSingleMetadataIsSensitive() = runTest {
        result("""[{"name":"key","expiration":1234,"metadata":{"secret":"private-marker"}}]""")
        val entry = client().listKeys(namespace).items.single()
        assertTrue(entry.hasMetadata); assertEquals(1234, entry.expiration)
        assertFalse(entry.toString().contains("private-marker"))
        result("""{"secret":"private-marker"}""")
        assertFalse(client().readMetadata(KvKeyTarget(namespace, "key")).toString().contains("private-marker"))
    }
    @Test fun exactBinaryReadUsesStreamingAndRefusesPartialOrNoContentStatuses() = runTest {
        val content = client().readValue(KvKeyTarget(namespace, "key"))
        val source = content.content.openVerified(); val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer)); assertContentEquals(streamBytes, buffer); source.close()
        for (status in listOf(202, 204, 206, 403, 404, 429)) {
            streamStatus = status
            assertFailsWith<CloudFailure> { client().readValue(KvKeyTarget(namespace, "key")) }
        }
    }
    @Test fun valueOverwriteStreamsExactBytesAndMetadataWithOneExplicitExpiryStrategy() = runTest {
        val input = KvValueInput(KvMemoryContent(byteArrayOf(0, -1)), KvExpiry.Ttl(60), KvValidation.metadata("null"))
        client().putValue(KvKeyTarget(namespace, "key"), input)
        val write = requests.last()
        assertEquals("$path/values/key?expiration_ttl=60", write.path)
        val parts = (write.payload as HttpBody.Multipart).parts
        assertEquals(listOf("value", "metadata"), parts.map { it.name })
        assertTrue(parts[0].body is HttpBody.Stream)
        assertFalse(parts.toString().contains("private-marker"))
        assertEquals(1, guards)
    }
    @Test fun reservedUnicodeAndLiteralPercentKeysStayExactlyOneEncodedSegment() = runTest {
        for (key in listOf("a/b?c#d%2F\\", "键/😀", "x%GG")) {
            client().deleteValue(KvKeyTarget(namespace, key))
            assertEquals("$path/values/${encodePath(key)}", requests.last().path)
        }
    }
    @Test fun bulkReadIsExplicitPostWithoutWholeResponseBuffering() = runTest {
        streamBytes = """{"success":true,"result":{"values":{"a":{"value":false,"metadata":null},"b":null}}}""".encodeToByteArray()
        client().bulkRead(namespace, KvBulkReadInput(listOf("a", "b"), KvBulkReadType.json, true))
        val req = requests.last(); assertEquals("POST", req.method); assertEquals("$path/bulk/get", req.path)
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals(JsonPrimitive("json"), body["type"]); assertEquals(JsonPrimitive(true), body["withMetadata"])
        assertEquals(0, guards)
    }
    @Test fun bulkWritesAndCurrentPostDeletesValidateCompletePartialResult() = runTest {
        result("""{"successful_key_count":1,"unsuccessful_keys":["b"]}""", false)
        val client = client()
        val input = KvBulkWriteInput(KvMemoryContent("""[{"key":"a","value":"x"},{"key":"b","value":"y"}]""".encodeToByteArray()), listOf("a", "b"))
        val write = client.bulkWrite(namespace, input)
        assertEquals(KvAcceptance.PARTIAL, write.acceptance); assertEquals(listOf("b"), write.failedKeys)
        val deleted = client.bulkDelete(namespace, listOf("a", "b"))
        assertEquals(KvAcceptance.PARTIAL, deleted.acceptance)
        assertEquals("$path/bulk/delete", requests.last().path); assertEquals("POST", requests.last().method)
        assertEquals(2, guards)
    }
    @Test fun malformedBulkCountsAndForeignOrDuplicateFailedKeysAreUnknown() = runTest {
        for (json in listOf("{}", """{"successful_key_count":-1,"unsuccessful_keys":[]}""", """{"successful_key_count":2,"unsuccessful_keys":["a"]}""", """{"successful_key_count":0,"unsuccessful_keys":["a","a"]}""", """{"successful_key_count":1,"unsuccessful_keys":["foreign"]}""")) {
            result(json)
            assertEquals(KvAcceptance.UNKNOWN, client().bulkDelete(namespace, listOf("a", "b")).acceptance)
        }
    }
    @Test fun bulkReadRejectsUnsuccessfulOrIncompleteResponseBeforePublishingContent() = runTest {
        val input = KvBulkReadInput(listOf("a", "b"), KvBulkReadType.json, false)
        for (body in listOf("""{"success":false,"result":{"values":{"a":1,"b":2}}}""", """{"success":true,"result":{"values":{"a":1}}}""")) {
            streamBytes = body.encodeToByteArray()
            assertFailsWith<CloudFailure> { client().bulkRead(namespace, input) }
        }
    }
    @Test fun bulkWriteRechecksWholeFrozenContentAndKeysBeforeAnyWrite() = runTest {
        for (body in listOf("""[{"key":"a","value":null}]""", """[{"key":"foreign","value":"x"}]""", """[{"key":"a","value":"x","expiration":1059}]""")) {
            val input = KvBulkWriteInput(KvMemoryContent(body.encodeToByteArray()), listOf("a"))
            assertFailsWith<CloudFailure> { client().bulkWrite(namespace, input) }
        }
        assertTrue(requests.none { it.method != "GET" }); assertEquals(0, guards)
    }
    @Test fun revokedGuardInvalidScopeAndInvalidInputCauseZeroWriteAndNoRetry() = runTest {
        assertFailsWith<CloudFailure> { client().deleteValue(KvKeyTarget(namespace.copy(account = account.copy(accountId = "foreign")), "key")) }
        assertFailsWith<CloudFailure> { client().deleteValue(KvKeyTarget(namespace, "bad key")) }
        assertFailsWith<CloudFailure> { client { _, _, _ -> throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "拒绝") }.deleteValue(KvKeyTarget(namespace, "key")) }
        assertTrue(requests.none { it.method != "GET" })
        response = HttpResponse(429, "private-marker")
        assertFailsWith<CloudFailure> { client().deleteValue(KvKeyTarget(namespace, "key")) }
        assertEquals(1, requests.count { it.method == "DELETE" })
    }
}
