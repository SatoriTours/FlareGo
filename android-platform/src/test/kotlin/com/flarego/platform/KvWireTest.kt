package com.flarego.platform

import com.flarego.core.cloudflare.kv.CloudflareKvClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.application.kv.KvValidation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class KvWireTest {
    @Test fun exactReservedKeyEncodingAndBinaryMultipartTravelThroughProductionTransport()=runBlocking {
        val dir=Files.createTempDirectory("kv-wire").toFile();val store=KvEncryptedContentStore(dir)
        val server=MockWebServer();server.start()
        try {
            val connection=Connection("fixture","fixture","a".repeat(32))
            val ns=KvNamespaceRef(AccountScope(connection.id,connection.accountId),"b".repeat(32))
            val client=CloudflareKvClient(connection,CloudflareGateway(testTransport(server)),testTransfers(server),store,{1000},{_,_,_->})
            val base="/client/v4/accounts/${connection.accountId}/storage/kv/namespaces/${ns.id}"
            val bytes=byteArrayOf(0,-1,-128,65)
            suspend fun staged()=store.stage(RecordingSource(listOf(bytes)),KV_MAX_VALUE_BYTES)
            for(meta in listOf(false,true)) {
                server.enqueue(MockResponse().setBody("""{"success":true,"result":{"id":"${ns.id}","title":"fixture","supports_url_encoding":true}}"""))
                server.enqueue(MockResponse().setBody("""{"success":true,"result":null}"""))
                val payload=staged()
                try {client.putValue(KvKeyTarget(ns,"a/b?#%\\键"),KvValueInput(payload,KvExpiry.Ttl(60),if(meta)KvValidation.metadata("{\"x\":1}")else null))}
                finally {payload.close()}
                assertEquals(base,server.takeRequest().path)
                val sent=server.takeRequest()
                assertEquals("PUT",sent.method)
                assertEquals("$base/values/a%2Fb%3F%23%25%5C%E9%94%AE?expiration_ttl=60",sent.path)
                if(!meta)assertContentEquals(bytes,sent.body.readByteArray())
                else {
                    val wire=sent.body.readByteArray();val text=wire.toString(Charsets.ISO_8859_1)
                    assertTrue(sent.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
                    assertTrue(text.contains("name=\"metadata\""));assertTrue(text.contains("{\"x\":1}"))
                    assertTrue(wire.asList().windowed(bytes.size).any{it==bytes.asList()})
                }
            }
        } finally {server.shutdown();store.releaseAll();dir.deleteRecursively()}
    }
}
