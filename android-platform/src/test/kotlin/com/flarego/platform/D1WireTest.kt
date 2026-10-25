package com.flarego.platform

import com.flarego.core.application.d1.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class D1WireTest {
    @Test fun fullSqlJsonAndRawResultsUseProductionStreamWireWithoutTextRoundTrip()=runBlocking {
        val dir=Files.createTempDirectory("d1-wire-test").toFile();val store=D1EncryptedContentStore(dir);val server=MockWebServer();server.start()
        try {
            val connection=Connection("fixture","fixture","a".repeat(32));val ref=D1DatabaseRef(AccountScope(connection.id,connection.accountId),"12345678-1234-4321-abcd-123456789abc")
            val sql="/*"+"x".repeat(110000)+"*/ SELECT ';'; SELECT '中文';"
            val input=D1SqlJson.fromStatements(listOf(D1Statement(sql,listOf("001"))),store,false)
            val reply="""{"success":true,"result":[{"success":true,"meta":{"changes":0},"results":{"columns":["n"],"rows":[[9223372036854775807],[null]]}}]}"""
            server.enqueue(MockResponse().setBody(reply))
            var guards=0
            val result=CloudflareD1Client(connection,CloudflareGateway(testTransport(server)),testTransfers(server),store,{_,_,_->guards++}).raw(ref,input)
            assertEquals(1,guards);assertEquals(2,result.summary.rowCount)
            val sent=assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));assertEquals("POST",sent.method)
            assertEquals("/client/v4/accounts/${connection.accountId}/d1/database/${ref.id}/raw",sent.path)
            val expected=buildString{val source=input.content.openVerified();try{val buffer=ByteArray(8192);while(true){val n=source.read(buffer);if(n == -1)break;append(buffer.decodeToString(0,n))}}finally{source.close()}}
            assertEquals(expected,sent.body.readUtf8());assertEquals("Bearer fixture",sent.getHeader("Authorization"))
            assertEquals(reply,D1ContentPages.page(result.content,0).text)
            result.close();input.content.close();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {server.shutdown();store.releaseAll();dir.deleteRecursively()}
    }
}
