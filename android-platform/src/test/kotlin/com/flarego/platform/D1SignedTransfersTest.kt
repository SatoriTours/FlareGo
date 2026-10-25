package com.flarego.platform

import com.flarego.core.application.d1.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.mockwebserver.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.*

class D1SignedTransfersTest {
    private val account=AccountScope("fixture","a".repeat(32))
    private val database=D1DatabaseRef(account,"12345678-1234-4321-abcd-123456789abc")
    private val safe="https://fixture.r2.cloudflarestorage.com/key?sig=private-marker"
    private suspend fun receipt(url:String,store:D1ContentStore,download:Boolean=false):D1FileReceipt {
        val json=if(download)"""{"success":true,"type":"export","status":"complete","result":{"filename":"private.sql","signed_url":"$url"}}"""
        else """{"success":true,"type":"import","filename":"private.sql","upload_url":"$url"}"""
        val client=CloudflareD1Client(Connection(account.connectionId,"fixture",account.accountId),CloudflareGateway(HttpTransport{HttpResponse(200,"""{"success":true,"result":$json}""")}),object:TransferPort{
            override suspend fun download(request:HttpRequest,sink:ByteSink):HttpResponseHead=error("unexpected API SQL")
        },store,{_,_,_->})
        return if(download)client.exportDatabase(database,D1ExportOptions())else client.importDatabase(database,D1ImportRequest.Init("0".repeat(32)))
    }
    private fun allowed(url:HttpUrl)=url.host=="fixture.r2.cloudflarestorage.com" && url.encodedPath=="/key"
    private fun transfer(server:MockWebServer)=D1SignedTransfers(::allowed){request->request.newBuilder().url(request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()).build()}
    private fun md5(bytes:ByteArray)=MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){(it.toInt()and 255).toString(16).padStart(2,'0')}

    @Test fun isolatedPutAndGetUseExactContentWithoutApiCredentialsOrCookies()=runBlocking {
        val dir=Files.createTempDirectory("d1-signed-test").toFile();val store=D1EncryptedContentStore(dir);val server=MockWebServer();server.start()
        try {
            val bytes="CREATE TABLE x(a); -- 中文".encodeToByteArray();val hash=md5(bytes)
            val input=store.stage(RecordingSource(listOf(bytes)),bytes.size.toLong())
            assertEquals(hash,D1Md5Fingerprint.md5(input))
            server.enqueue(MockResponse().setHeader("ETag","\"$hash\"").setHeader("Set-Cookie","secret=fixture").setBody(""))
            val port=transfer(server);port.upload(assertNotNull(receipt(safe,store).upload),input,hash)
            val put=assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));assertEquals("PUT",put.method);assertEquals("/key?sig=private-marker",put.path)
            assertContentEquals(bytes,put.body.readByteArray());assertEquals(bytes.size.toString(),put.getHeader("Content-Length"))
            assertNull(put.getHeader("Authorization"));assertNull(put.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val writer=store.writer(1024)
            assertEquals(bytes.size.toLong(),port.download(assertNotNull(receipt(safe,store,true).download),writer))
            val get=assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));assertEquals("GET",get.method);assertNull(get.getHeader("Authorization"));assertNull(get.getHeader("Cookie"))
            val exported=writer.seal();val source=exported.openVerified();val buffer=ByteArray(1024);val n=source.read(buffer);assertContentEquals(bytes,buffer.copyOf(n));source.close()
            store.releaseAll();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {server.shutdown();store.releaseAll();dir.deleteRecursively()}
    }
    @Test fun productionGateAndMalformedOrUnapprovedUrlsFailBeforeNetworkAndCloseSink()=runBlocking {
        val dir=Files.createTempDirectory("d1-signed-test").toFile();val store=D1EncryptedContentStore(dir);val server=MockWebServer();server.start()
        try {
            for(url in listOf("http://fixture.r2.cloudflarestorage.com/key","https://127.0.0.1/key","https://[::1]/key","https://u:p@fixture.r2.cloudflarestorage.com/key","$safe#fragment","https://foreign.invalid/key","https://fixture.r2.cloudflarestorage.com/other","https://fixture.r2.cloudflarestorage.com:8443/key")) {
                var closed=false
                val error=assertFailsWith<CloudFailure>{transfer(server).download(assertNotNull(receipt(url,store,true).download),object:ByteSink{override fun write(buffer:ByteArray,offset:Int,count:Int)=error("no write");override fun close(){closed=true}})}
                assertTrue(closed);assertFalse(error.toString().contains("private-marker"))
            }
            val source=store.stage(RecordingSource(listOf(byteArrayOf(65))),1)
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure>{d1SignedTransfers().upload(assertNotNull(receipt(safe,store).upload),source,md5(byteArrayOf(65)))}.code)
            assertEquals(0,server.requestCount)
        } finally {server.shutdown();store.releaseAll();dir.deleteRecursively()}
    }
    @Test fun redirectsWrongEtagAndHttpFailureNeverRetryOrLeakUrl()=runBlocking {
        val dir=Files.createTempDirectory("d1-signed-test").toFile();val store=D1EncryptedContentStore(dir);val server=MockWebServer();server.start()
        try {
            val content=store.stage(RecordingSource(listOf(byteArrayOf(65))),1);val port=transfer(server);val location=assertNotNull(receipt(safe,store).upload)
            for(response in listOf(MockResponse().setResponseCode(307).setHeader("Location",server.url("/redirect")),MockResponse().setHeader("ETag","wrong"),MockResponse().setResponseCode(503).setBody("private-marker"))) {
                val before=server.requestCount;server.enqueue(response)
                val failure=assertFailsWith<CloudFailure>{port.upload(location,content,md5(byteArrayOf(65)))}
                assertTrue(failure.uncertain);assertFalse(failure.toString().contains("private-marker"));assertEquals(before+1,server.requestCount)
            }
        } finally {server.shutdown();store.releaseAll();dir.deleteRecursively()}
    }
}
