package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.AuthorizedR2DomainConfigurationSession
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudPage
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.PageRequest
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2BulkDeleteOutcome
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.R2DomainConfigurationSession
import com.flarego.core.ports.R2Session
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-layer DELETE regression: the **real** `CloudflareR2DomainConfigurationClient` behind the
 * production `AuthorizedR2DomainConfigurationSession`, driven through the shared
 * `R2WriteRuntime`/controller over a synthetic transport.
 *
 * It pins the separation between the mutation path/resource family (a single-domain DELETE) and the
 * before-observation contract (the complete custom-domain collection): the client must hand the
 * controller the collection document it was asked for, all reads happen before the final guard, and
 * only one DELETE is ever sent.
 *
 * Only methods and paths are captured; headers, tokens and bodies are never printed or stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2DomainDeleteCrossLayerTest {
    private val connection = Connection("r2-local", "fixture", "d".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "dom-bucket")
    private val domain = "cdn.example.com"
    private val zoneId = "0123456789abcdef0123456789abcdef"

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private data class Seen(val method: String, val path: String)

    private inner class Harness(scope: TestScope) {
        val seen = mutableListOf<Seen>()
        val events = mutableListOf<String>()
        val domains = linkedMapOf(domain to true, "keep.example.com" to false)
        val tls = linkedMapOf(domain to "1.2", "keep.example.com" to "1.2")
        var incompleteCollection = false
        var collectionStatus = 200
        var deleteStatus = 200
        var now = 1_000L
        var revision = 7L
        var routeEpoch = 1L
        var command: R2Command? = null
        var afterCollectionRead: (() -> Unit)? = null
        val store = Store()
        private var ids = 0

        private fun domainRow(name: String): String =
            """{"domain":"$name","enabled":${domains[name] ?: false},"minTLS":"${tls[name] ?: "1.2"}","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}"""

        private fun listJson(): String =
            buildString {
                append("""{"domains":[""")
                append(domains.keys.joinToString(",") { domainRow(it) })
                append("]")
                if (incompleteCollection) append(""","cursor":"x"""")
                append("}")
            }

        private fun envelope(result: String) = HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")

        private val gateway = CloudflareGateway(
            HttpTransport { request ->
                seen += Seen(request.method, request.path)
                when {
                    request.path == "/accounts/${connection.accountId}/tokens/verify" -> HttpResponse(401, "denied")
                    request.path == "/user/tokens/verify" -> envelope("""{"id":"self","status":"active"}""")
                    request.path == "/user/tokens/self" ->
                        envelope(
                            """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.${connection.accountId}":"*"}}]}""",
                        )
                    request.path == "/user/tokens/permission_groups" ->
                        envelope("""[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]""")
                    request.path == "/accounts/${connection.accountId}" ->
                        envelope("""{"id":"${connection.accountId}","name":"fixture"}""")
                    request.path.endsWith("/r2/buckets/${target.bucket}") ->
                        envelope("""{"name":"${target.bucket}","storage_class":"Standard"}""")
                    request.path.endsWith("/domains/custom") && request.method == "GET" -> {
                        val response =
                            if (collectionStatus == 200) {
                                envelope(listJson())
                            } else {
                                HttpResponse(collectionStatus, """{"success":false,"errors":[],"messages":[],"result":null}""")
                            }
                        afterCollectionRead?.invoke()
                        response
                    }
                    request.path.endsWith("/domains/custom/$domain") && request.method == "DELETE" -> {
                        if (deleteStatus == 202) {
                            HttpResponse(202, """{"success":true,"errors":[],"messages":[],"result":{}}""")
                        } else {
                            domains.remove(domain)
                            tls.remove(domain)
                            envelope("""{"domain":"$domain"}""")
                        }
                    }
                    else -> error("unexpected synthetic request ${request.method} ${request.path}")
                }
            },
            sleeper = {},
        )
        private val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now })
        private val authorized = AuthorizedR2DomainConfigurationSession(
            connection,
            gateway,
            issuer,
            CloudflareZoneClient(connection, gateway),
        )
        private val logging = object : R2DomainConfigurationSession by authorized {
            override suspend fun writeDomainConfiguration(
                write: R2DomainConfigurationWrite,
                beforeSend: (R2DomainDocument?) -> Unit,
            ): ExecutionReceipt =
                authorized.writeDomainConfiguration(write) { document ->
                    events += "beforeSend:${document?.kind}"
                    beforeSend(document)
                }
        }
        private val unusedR2 = object : R2Session {
            override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> = TODO()
            override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument = TODO()
            override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument = TODO()
            override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument = TODO()
            override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument = TODO()
            override suspend fun deleteBucket(account: AccountScope, name: String) = TODO()
            override suspend fun listObjects(account: AccountScope, bucket: String, filter: R2ObjectFilter, page: PageRequest): CloudPage<R2ObjectEntry> = TODO()
            override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead = TODO()
            override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry = TODO()
            override suspend fun deleteObject(target: R2ObjectTarget) = TODO()
            override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome = TODO()
            override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt = TODO()
            override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt = TODO()
        }
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { now }, OperationIds { "del-${++ids}" },
            { R2LiveSelection(connection, revision, routeEpoch, command) },
            { listOf(connection) }, { revision }, { unusedR2 },
            domainSession = { logging },
        )

        fun deleteCommand() = R2Command.Domain(R2DomainConfigurationWrite.DeleteCustomDomain(target, domain))

        fun select(command: R2Command) { this.command = command }

        val mutations get() = seen.filter { it.method != "GET" }
        val collectionReads get() = seen.count { it.method == "GET" && it.path.endsWith("/domains/custom") }
    }

    @Test
    fun aDeleteSendsOneSingleDomainDeleteAfterTheCompleteCollectionWasObserved() = runTest {
        val h = Harness(this)
        val command = h.deleteCommand()
        h.select(command)
        val preview = h.runtime.prepare(command)
        assertEquals(0, h.mutations.size, "a preview never sends")

        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(1, h.mutations.size)
        assertEquals("DELETE", h.mutations.single().method)
        assertEquals(
            "/accounts/${connection.accountId}/r2/buckets/${target.bucket}/domains/custom/$domain",
            h.mutations.single().path,
            "the mutation path stays the reviewed single-domain path",
        )
        assertEquals(listOf("beforeSend:CUSTOM_DOMAINS"), h.events, "the before-observation is the complete collection")
        // One collection read at prepare, one by the client before the send, one read-back after it.
        assertEquals(3, h.collectionReads)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
    }

    @Test
    fun aCollectionThatLosesTheDomainAfterThePreviewSendsNoDelete() = runTest {
        val h = Harness(this)
        val command = h.deleteCommand()
        h.select(command)
        val preview = h.runtime.prepare(command)
        h.domains.remove(domain)
        val task = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(0, h.mutations.size)
    }

    @Test
    fun aCollectionWithoutTheDomainCannotBePreviewed() = runTest {
        val h = Harness(this)
        h.domains.remove(domain)
        h.select(h.deleteCommand())
        val failure = assertFailsWith<CloudFailure> { h.runtime.prepare(h.deleteCommand()) }
        assertEquals(CloudFailureCode.CONFLICT, failure.code)
        assertEquals(0, h.mutations.size)
    }

    @Test
    fun anIncompleteCollectionOrAReadFailureNeverDeletes() = runTest {
        val incomplete = Harness(this)
        incomplete.incompleteCollection = true
        incomplete.select(incomplete.deleteCommand())
        assertFailsWith<CloudFailure> { incomplete.runtime.prepare(incomplete.deleteCommand()) }
        assertEquals(0, incomplete.mutations.size)

        val failing = Harness(this)
        failing.collectionStatus = 500
        failing.select(failing.deleteCommand())
        assertFailsWith<CloudFailure> { failing.runtime.prepare(failing.deleteCommand()) }
        assertEquals(0, failing.mutations.size)

        val missing = Harness(this)
        missing.collectionStatus = 404
        missing.select(missing.deleteCommand())
        assertFailsWith<CloudFailure> { missing.runtime.prepare(missing.deleteCommand()) }
        assertEquals(0, missing.mutations.size, "a 404 on the collection is not an absence proof")
    }

    @Test
    fun aFinalGuardFailureAfterTheReadsSendsNoDelete() = runTest {
        val h = Harness(this)
        val command = h.deleteCommand()
        h.select(command)
        val preview = h.runtime.prepare(command)
        // The proof/guard is evaluated after the pre-send read; expire it exactly there.
        h.afterCollectionRead = { h.now = 31_000; h.afterCollectionRead = null }
        val task = h.runtime.confirm(preview.planId)
        assertEquals(0, h.mutations.size)
        assertTrue(task.state != TaskState.SUCCEEDED, "a revoked guard is never success")
    }

    @Test
    fun anAcceptedDeleteIsNotUpgradedAndIsNeverReplayed() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        val command = h.deleteCommand()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(1, h.mutations.size)
        assertEquals(2, h.collectionReads, "an async receipt is not upgraded by a read-back")
        // The 202 was not applied yet: the read-only recovery stays UNKNOWN and never replays.
        assertEquals(TaskState.UNKNOWN, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.mutations.size, "reconciliation never replays the DELETE")
    }
}
