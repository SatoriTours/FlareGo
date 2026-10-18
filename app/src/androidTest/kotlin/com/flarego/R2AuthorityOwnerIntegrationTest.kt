package com.flarego

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.AuthorizedR2Session
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

/** Real Application credential owner with synthetic provider; no persistent Token/config changes. */
class R2AuthorityOwnerIntegrationTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("not a download")
    }
    private fun envelope(result: String) = HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")
    @Test fun credentialRotationRevokesExistingR2SessionsWithoutReplacingTheApplicationOwner() = runBlocking {
        val owner = ManagementPlatformOwner.forApplication(app)
        val connection = Connection("r2-owner-${UUID.randomUUID()}", "synthetic", UUID.randomUUID().toString().replace("-", ""))
        val account = AccountScope(connection.id, connection.accountId)
        var writes = 0
        val transport = HttpTransport { r ->
            if (r.method != "GET") { writes++; return@HttpTransport envelope("""{"name":"my-bucket"}""") }
            when {
                r.path == "/user/tokens/verify" -> envelope("""{"id":"self","status":"active"}""")
                r.path == "/user/tokens/self" -> envelope("""{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.${connection.accountId}":"*"}}]}""")
                r.path == "/user/tokens/permission_groups" -> envelope("""[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]""")
                r.path.endsWith("/tokens/verify") -> HttpResponse(401,"denied")
                r.path == "/accounts/${connection.accountId}" -> envelope("""{"id":"${connection.accountId}"}""")
                r.path.endsWith("/r2/buckets/my-bucket") -> envelope("""{"name":"my-bucket"}""")
                else -> error("unexpected fixture request")
            }
        }
        owner.registerExistingCredential(connection)
        try {
            val first = owner.acquireCredential(connection, { "synthetic-owner-token" }, { transport }, { transfers })
            val again = owner.acquireCredential(connection, { "synthetic-owner-token" }, { transport }, { transfers })
            assertSame(first.r2Issuer, again.r2Issuer)
            val oldSession = AuthorizedR2Session(connection, first.gateway, first.transfers, first.r2Issuer)
            oldSession.createBucket(account, R2BucketCreateInput("my-bucket"))
            assertEquals(1, writes)
            val transition = owner.beginCredentialChange(connection)
            assertThrows(CloudFailure::class.java) { owner.requireCurrent(first) }
            var rejected = false
            try { oldSession.deleteBucket(account, "my-bucket") } catch (_: CloudFailure) { rejected = true }
            assertTrue(rejected); assertEquals(1, writes)
            owner.finishCredentialChange(transition) {}
            val replacement = owner.acquireCredential(connection, { "synthetic-new-owner-token" }, { transport }, { transfers })
            assertNotSame(first.r2Issuer, replacement.r2Issuer)
            AuthorizedR2Session(connection, replacement.gateway, replacement.transfers, replacement.r2Issuer)
                .createBucket(account, R2BucketCreateInput("my-bucket"))
            assertEquals(2, writes)
        } finally { owner.retireCredential(connection) {} }
    }
}
