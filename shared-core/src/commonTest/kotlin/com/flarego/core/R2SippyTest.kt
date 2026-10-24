package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2SippyAzureSourceSecrets
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippyGcsSourceSecrets
import com.flarego.core.application.r2.R2SippyLeaseState
import com.flarego.core.application.r2.R2SippyS3SourceSecrets
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.draftForTest
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.application.r2.encodeR2SippyEnableBody
import com.flarego.core.application.r2.requireDraft
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.r2.CloudflareR2SippyClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch B3 Task 5a backend: the typed Sippy wire, the memory-only secret lease and the safe GET
 * allowlist. Only synthetic credentials are used, and assertions never print a secret value.
 */
class R2SippyTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val path = "/accounts/${connection.accountId}/r2/buckets/my-bucket/sippy"
    private val bucketPath = "/accounts/${connection.accountId}/r2/buckets/my-bucket"

    // Obviously synthetic material; never a real key, and never printed by an assertion.
    private val awsKeyId = "SYNTHETIC-AWS-KEY-ID-1"
    private val awsSecret = "SYNTHETIC-AWS-SECRET-1"
    private val gcsEmail = "synthetic-sippy@example.invalid"
    private val gcsPrivateKey = "-----SYNTHETIC-PRIVATE-KEY-1-----"
    private val s3KeyId = "SYNTHETIC-S3-KEY-ID-1"
    private val s3Secret = "SYNTHETIC-S3-SECRET-1"
    private val azureAccount = "syntheticstorage"
    private val azureContainer = "synthetic-container"
    private val azureKey = "SYNTHETIC-AZURE-ACCOUNT-KEY-1"
    private val azureSas = "SYNTHETIC-AZURE-SAS-1"
    private val destKeyId = "SYNTHETIC-R2-ACCESS-KEY-ID-1"
    private val destSecret = "SYNTHETIC-R2-SECRET-1"
    private val bucketUrl = "https://s3.example.invalid/synthetic-bucket"

    private val requests = mutableListOf<HttpRequest>()
    private val sentBodies = mutableListOf<String>()
    private var getStatus = 200
    private var putStatus = 200
    private var deleteStatus = 200
    private var sippyResult: String = ""
    private var errorEcho: String? = null

    private fun envelope(result: String) =
        """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun validSnapshotResult(
        enabled: Boolean = true,
        provider: String = "aws",
        extraDestination: String = "",
        extraSource: String = "",
        url: String? = null,
    ): String {
        val source =
            when (provider) {
                "aws" -> """{"provider":"aws","bucket":"source-bucket","region":"us-east-1"$extraSource}"""
                "gcs" -> """{"provider":"gcs","bucket":"source-bucket"$extraSource}"""
                "s3" -> """{"provider":"s3","bucketUrl":"${url ?: bucketUrl}"$extraSource}"""
                "azure" -> """{"provider":"azure","container":"$azureContainer"$extraSource}"""
                else -> """{"provider":"$provider","bucket":"source-bucket"$extraSource}"""
            }
        return """{"enabled":$enabled,"destination":{"accessKeyId":"r2-config-key-id","account":"${connection.accountId}","bucket":"my-bucket","provider":"r2"$extraDestination},"source":$source}"""
    }

    private val gateway = CloudflareGateway(
        HttpTransport { request ->
            requests += request
            when {
                request.path == bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                request.path == path && request.method == "GET" ->
                    if (getStatus == 200) HttpResponse(200, envelope(sippyResult))
                    else HttpResponse(getStatus, envelope("""null"""))
                request.path == path && request.method == "PUT" -> {
                    val payload = request.payload
                    assertIs<HttpBody.SensitiveJson>(payload)
                    sentBodies += payload.consumeBytes().decodeToString()
                    if (putStatus == 200) {
                        HttpResponse(200, envelope(validSnapshotResult()))
                    } else {
                        // A provider error that echoes the secret: it must never surface anywhere.
                        HttpResponse(putStatus, envelope("""{"echo":"$destSecret"}"""))
                    }
                }
                request.path == path && request.method == "DELETE" ->
                    if (deleteStatus == 200) HttpResponse(200, envelope("""{"enabled":false}"""))
                    else HttpResponse(deleteStatus, envelope("""null"""))
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        },
        sleeper = {},
    )

    private val client = CloudflareR2SippyClient(connection, gateway)

    private fun destination() = R2SippyDestinationSecrets(destKeyId, destSecret)

    private fun awsSource() = R2SippyAwsSourceSecrets(awsKeyId, awsSecret, "source-bucket", "us-east-1")

    private fun gcsSource() = R2SippyGcsSourceSecrets(gcsEmail, gcsPrivateKey, "source-bucket")

    private fun s3Source() = R2SippyS3SourceSecrets(s3KeyId, s3Secret, bucketUrl)

    private fun azureSource(sas: Boolean = false) =
        R2SippyAzureSourceSecrets(azureAccount, azureContainer, accountKey = if (sas) null else azureKey, sasToken = if (sas) azureSas else null)

    private fun draft(
        source: com.flarego.core.application.r2.R2SippySourceSecrets,
        bindingId: () -> String = { "binding-${System.nanoTime()}" },
    ) = draftForTest(source, destination(), bindingId)

    private fun putMutations() = requests.count { it.method == "PUT" && it.path == path }

    private fun sippyBody(): JsonObject = Json.parseToJsonElement(sentBodies.last()).jsonObject

    @Test
    fun fourProvidersSendExactlyThePinnedFieldsOnce() = runTest {
        val cases: List<Pair<R2SippyProvider, com.flarego.core.application.r2.R2SippySourceSecrets>> =
            listOf(
                R2SippyProvider.AWS to awsSource(),
                R2SippyProvider.GCS to gcsSource(),
                R2SippyProvider.S3 to s3Source(),
                R2SippyProvider.AZURE to azureSource(),
                R2SippyProvider.AZURE to azureSource(sas = true),
            )
        for ((provider, source) in cases) {
            requests.clear()
            sentBodies.clear()
            sippyResult = validSnapshotResult(provider = provider.wireValue)
            val lease = draft(source)
            val receipt = client.putSippy(target, lease)
            assertTrue(receipt is ExecutionReceipt.Completed, provider.name)
            assertEquals(1, requests.count { it.method == "PUT" }, "one PUT for ${provider.name}")
            val mutation = requests.single { it.method == "PUT" }
            assertEquals(path, mutation.path)
            assertEquals("default", mutation.headers["cf-r2-jurisdiction"])
            val body = sippyBody()
            assertEquals(setOf("destination", "source"), body.keys)
            val destination = body.getValue("destination").jsonObject
            assertEquals(setOf("provider", "accessKeyId", "secretAccessKey"), destination.keys)
            assertEquals("r2", destination.getValue("provider").jsonPrimitive.content)
            assertTrue(destination.getValue("accessKeyId").jsonPrimitive.content == destKeyId)
            assertTrue(destination.getValue("secretAccessKey").jsonPrimitive.content == destSecret)
            val sourceJson = body.getValue("source").jsonObject
            assertEquals(provider.wireValue, sourceJson.getValue("provider").jsonPrimitive.content)
            when (provider) {
                R2SippyProvider.AWS -> {
                    assertEquals(setOf("provider", "bucket", "region", "accessKeyId", "secretAccessKey"), sourceJson.keys)
                    assertTrue(sourceJson.getValue("secretAccessKey").jsonPrimitive.content == awsSecret)
                }
                R2SippyProvider.GCS -> {
                    assertEquals(setOf("provider", "bucket", "clientEmail", "privateKey"), sourceJson.keys)
                    assertTrue(sourceJson.getValue("privateKey").jsonPrimitive.content == gcsPrivateKey)
                }
                R2SippyProvider.S3 -> {
                    assertEquals(setOf("provider", "bucketUrl", "accessKeyId", "secretAccessKey"), sourceJson.keys)
                    assertTrue(sourceJson.getValue("secretAccessKey").jsonPrimitive.content == s3Secret)
                }
                R2SippyProvider.AZURE -> {
                    val azure = source as R2SippyAzureSourceSecrets
                    val expected =
                        if (azure.sasToken != null) setOf("provider", "accountName", "container", "sasToken")
                        else setOf("provider", "accountName", "container", "accountKey")
                    assertEquals(expected, sourceJson.keys)
                }
            }
            assertFalse(body.containsKey("enabled"), "the PUT enables implicitly and never invents an enabled field")
            assertEquals(R2SippyLeaseState.CONSUMED, lease.leaseState)
            // A replayed lease is refused before any request.
            val before = requests.size
            assertFailsWith<CloudFailure> { client.putSippy(target, lease) }
            assertEquals(before, requests.size, "a consumed lease never causes a second request")
        }
    }

    @Test
    fun safeSippySnapshotDropsUnexpectedEchoedSecretsAndUnknownProvidersStayReadOnly() = runTest {
        sippyResult = validSnapshotResult()
        val snapshot = client.getSippy(target)
        assertTrue(snapshot.editable)
        assertTrue(snapshot.enabled)
        assertEquals(R2SippyProvider.AWS, snapshot.source?.provider)
        assertEquals("source-bucket", snapshot.source?.bucket)
        assertEquals("my-bucket", snapshot.destination?.bucket)

        // A nested echoed secret (an unknown member) keeps the snapshot read-only and is dropped.
        sippyResult = validSnapshotResult(extraSource = ",\"secretAccessKey\":\"$awsSecret\"")
        val echoed = client.getSippy(target)
        assertFalse(echoed.editable)
        assertNotNull(echoed.readOnlyReason)
        assertFalse(echoed.toString().contains(awsSecret))

        // A top-level echoed secret is also unknown.
        sippyResult = validSnapshotResult(extraDestination = ",\"secretAccessKey\":\"$destSecret\"")
        assertFalse(client.getSippy(target).editable)

        // An unknown provider is readable but never editable.
        sippyResult = validSnapshotResult(provider = "future-provider")
        val unknown = client.getSippy(target)
        assertFalse(unknown.editable)
        assertNotNull(unknown.readOnlyReason)

        // A provider-echoed bucket URL with userinfo is not a displayable safe value.
        sippyResult = validSnapshotResult(provider = "s3", url = "https://user:pass@s3.example.invalid/bucket")
        assertFalse(client.getSippy(target).editable)

        // A response that claims another bucket is a scope failure, not this bucket's snapshot.
        sippyResult =
            """{"enabled":true,"destination":{"accessKeyId":"k","account":"${connection.accountId}","bucket":"other-bucket","provider":"r2"},"source":{"provider":"aws","bucket":"b","region":"us-east-1"}}"""
        val mismatch = assertFailsWith<CloudFailure> { client.getSippy(target) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, mismatch.code)
    }

    @Test
    fun secretsNeverAppearInToStringRequestOrErrorPaths() = runTest {
        val lease = draft(awsSource())
        val write = R2SippyWrite.PutSippy(target, lease)
        assertFalse(lease.toString().contains(awsSecret))
        assertFalse(lease.toString().contains(awsKeyId))
        assertFalse(write.toString().contains(awsSecret))
        assertFalse(write.toString().contains(destSecret))
        val encoded = encodeR2SippyEnableBody(lease)
        assertFalse(encoded.toString().contains(awsSecret))
        assertTrue(encoded.consumeBytes().decodeToString().contains(awsSecret)) // the wire body is the only place a secret may appear

        sippyResult = validSnapshotResult()
        client.putSippy(target, draft(awsSource()))
        val mutation = requests.last { it.method == "PUT" }
        assertFalse(mutation.toString().contains(awsSecret))
        assertFalse(mutation.toString().contains(destSecret))
        assertFalse(mutation.payload.toString().contains(destSecret))

        // The provider error echoes the secret; the app-owned failure never does.
        putStatus = 400
        val failure = assertFailsWith<CloudFailure> { client.putSippy(target, draft(awsSource())) }
        assertFalse(failure.publicMessage.contains(destSecret))
        assertFalse(failure.toString().contains(destSecret))
        assertFalse(failure.cause?.toString().orEmpty().contains(destSecret))
        assertEquals(2, putMutations(), "exactly one PUT per write and an error is never retried")
    }

    @Test
    fun retiredDraftDoesNotCloseAdmittedTransportAndSecondConsumptionIsRefused() {
        val draftLease = draft(awsSource(), bindingId = { "draft-a" })
        assertTrue(draftLease.isDraft)
        assertTrue(draftLease.retire())
        assertFailsWith<CloudFailure> { draftLease.requireDraft() }
        assertEquals(R2SippyLeaseState.RETIRED, draftLease.leaseState)

        val admitted = draft(awsSource(), bindingId = { "draft-b" })
        admitted.requireDraft()
        val material = admitted.admitForTransport()
        assertNotNull(material)
        assertEquals(R2SippyLeaseState.SENDING, admitted.leaseState)
        assertFalse(admitted.retire(), "an admitted transport is not closed by the editor")
        assertEquals(R2SippyLeaseState.SENDING, admitted.leaseState)
        assertFailsWith<CloudFailure> { admitted.requireDraft() }
        admitted.completeTransport()
        assertEquals(R2SippyLeaseState.CONSUMED, admitted.leaseState)
    }

    @Test
    fun aConsumedLeaseIsRejectedBeforeAnyRequestAndAFreshDraftWorks() = runTest {
        sippyResult = validSnapshotResult()
        val old = draft(awsSource(), bindingId = { "binding-old" })
        val fresh = draft(awsSource(), bindingId = { "binding-new" })
        assertNotEquals(old.bindingId, fresh.bindingId)
        assertIs<ExecutionReceipt.Completed>(client.putSippy(target, old))
        val afterOld = requests.size
        assertFailsWith<CloudFailure> { client.putSippy(target, old) }
        assertEquals(afterOld, requests.size, "an old lease cannot be admitted again")
        assertIs<ExecutionReceipt.Completed>(client.putSippy(target, fresh))
        assertEquals(2, putMutations())
    }

    @Test
    fun bucketUrlMustBeSafeAndIsNeverProbed() = runTest {
        for (bad in listOf(
            "http://s3.example.invalid/bucket",
            "https://user:pass@s3.example.invalid/bucket",
            "https://s3.example.invalid/bucket?X-Amz-Signature=SYNTHETIC",
            "https://s3.example.invalid/bucket#fragment",
            "https://s3.example.invalid/bucket with space",
            "https:///bucket",
            "https://s3.example.invalid:0/bucket",
        )) {
            assertFailsWith<CloudFailure> { draft(R2SippyS3SourceSecrets(s3KeyId, s3Secret, bad)) }
        }
        assertEquals(0, requests.size, "a rejected URL never causes any request")

        sippyResult = validSnapshotResult(provider = "s3")
        client.putSippy(target, draft(s3Source()))
        val mutations = requests.filter { it.method != "GET" }
        assertEquals(1, mutations.size)
        assertEquals(path, mutations.single().path)
        assertTrue(
            requests.none { it.path.contains("s3.example.invalid") },
            "the bucket URL is submitted to Cloudflare only and never contacted as a host",
        )
    }

    @Test
    fun azureRequiresExactlyOneSecretAndTheProviderFieldsAreTyped() {
        assertFailsWith<CloudFailure> {
            draft(R2SippyAzureSourceSecrets(azureAccount, azureContainer, accountKey = null, sasToken = null))
        }
        assertFailsWith<CloudFailure> {
            draft(R2SippyAzureSourceSecrets(azureAccount, azureContainer, accountKey = azureKey, sasToken = azureSas))
        }
        // Required provider fields are enforced before a request can exist.
        assertFailsWith<CloudFailure> { draft(R2SippyAwsSourceSecrets("", awsSecret, "bucket", "us-east-1")) }
        assertFailsWith<CloudFailure> { draft(R2SippyAwsSourceSecrets(awsKeyId, awsSecret, "bucket", "")) }
        assertFailsWith<CloudFailure> { draft(R2SippyGcsSourceSecrets("not-an-email", gcsPrivateKey, "bucket")) }
        assertFailsWith<CloudFailure> { draft(R2SippyS3SourceSecrets(s3KeyId, s3Secret, "")) }
        assertFailsWith<CloudFailure> { R2SippySecrets.draft(awsSource(), R2SippyDestinationSecrets("", destSecret)) }
        assertFailsWith<CloudFailure> { R2SippySecrets.draft(awsSource(), R2SippyDestinationSecrets(destKeyId, "")) }
    }

    @Test
    fun theThreeReviewedSippyOperationsAreRegistered() {
        val rows =
            listOf("r2-get-bucket-sippy-config", "r2-put-bucket-sippy-config", "r2-delete-bucket-sippy-config")
                .map { id -> assertNotNull(ActionPolicies.registry.registration(id), id) }
        assertEquals(
            listOf("GET", "PUT", "DELETE"),
            rows.map { it.method },
        )
        assertTrue(rows.all { it.scope == com.flarego.core.cloudflare.protocol.ActionScope.ACCOUNT })
        assertTrue(rows[0].policy.semantics == ActionSemantics.READ && rows[0].policy.retry.enabled)
        assertTrue(rows.drop(1).all { it.policy.semantics == ActionSemantics.WRITE && !it.policy.retry.enabled })
        assertTrue(rows.drop(1).all { it.policy.risk == RiskLevel.CHANGE })
        // The credential-issuing operation remains unapproved.
        assertEquals(null, ActionPolicies.registry.registration("r2-create-temp-access-credentials"))
        // The live registry now also carries C8 (107 = 55 READ + 52 WRITE). This is
        // a current count only; historical B2/B3 matrices retain their frozen registry observations.
        val all = ActionPolicies.registry.registrations.values
        assertEquals(107, all.size)
        assertEquals(55, all.count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(52, all.count { it.policy.semantics == ActionSemantics.WRITE })
    }

    @Test
    fun aSensitiveBodyCanBeSentOnlyOnceEvenOutsideTheClient() {
        val body = HttpBody.SensitiveJson("""{"x":"SYNTHETIC"}""", "application/json; charset=utf-8")
        assertEquals("""{"x":"SYNTHETIC"}""", body.consumeBytes().decodeToString())
        assertFailsWith<CloudFailure> { body.consumeBytes() }
        assertFalse(body.toString().contains("SYNTHETIC"))
    }

    @Test
    fun deleteSendsNoSensitiveBodyAndRequiresAReadableSnapshot() = runTest {
        sippyResult = validSnapshotResult()
        val receipt = client.deleteSippy(target)
        assertIs<ExecutionReceipt.Completed>(receipt)
        val mutation = requests.single { it.method == "DELETE" }
        assertEquals(path, mutation.path)
        assertEquals(HttpBody.Empty, mutation.payload)
        assertEquals("default", mutation.headers["cf-r2-jurisdiction"])

        getStatus = 404
        assertFailsWith<CloudFailure> { client.deleteSippy(target) }
        assertEquals(1, requests.count { it.method == "DELETE" }, "an unreadable current state never deletes")
    }
}
