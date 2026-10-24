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
import com.flarego.core.cloudflare.identity.AuthorizedR2NotificationSession
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.TransferPort
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch C Task 2, event notifications across the real stack: `R2WriteRuntime` +
 * `R2NotificationController` + `AuthorizedR2NotificationSession` + the reviewed wire client + a
 * stateful synthetic provider and a real `R2TokenAuthorityIssuer`.
 *
 * The double really mutates its rules, so every post-write proof is a genuine independent read:
 * append-only behaviour, identity by rule ID (never by description), action-specific deletion
 * contracts, and the wire's own fresh pre-write document as the guard's baseline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2CNotificationRuntimeTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val queueId = "queue-1"
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"

    private fun envelope(result: JsonElement) =
        HttpResponse(
            200,
            buildJsonObject {
                put("success", true)
                put("errors", JsonArray(emptyList()))
                put("messages", JsonArray(emptyList()))
                put("result", result)
            }.toString(),
        )

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()

        override fun get(id: String) = rows[id]

        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }

        override fun put(task: OperationTask) {
            rows[task.id] = task
        }

        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)

        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private class RuleRow(
        val id: String,
        val actions: List<String>,
        val description: String? = null,
        val prefix: String? = null,
        val suffix: String? = null,
    )

    private inner class Harness(scope: TestScope) {
        var actual = R2LiveSelection(connection, 7, 1, null)
        var revision = 7L
        var ids = 0
        var now = 1_000L
        val store = Store()
        val calls = mutableListOf<HttpRequest>()
        val rules = mutableListOf<RuleRow>()
        var queueName: String? = "events"
        /** Extra unexpected member that makes the provider document display-only. */
        var unexpectedQueueMember = false
        var appendStatus = 200
        var deleteStatus = 200
        /** Replaces an existing rule instead of appending (a provider defect probe). */
        var replaceOnAppend = false
        /** Drops one extra unrelated rule on a deletion (a provider defect probe). */
        var dropExtraOnDelete = false
        /** Keeps one rule behind on a whole-queue clear (a provider defect probe). */
        var keepOneOnClear = false
        /** Suspends the fresh authority verification read. */
        var authorityGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        /** After the first write, a single-queue GET answers 404 (a queue whose rules were all removed). */
        var singleGetFailsAfterWrite = false
        /** Overrides the collection's bucket name (foreign / missing identity probes). */
        var collectionBucketName: String? = "my-bucket"
        /** Typed fault injection for the single-queue after read (used once a write happened). */
        var singleGetOverride: com.flarego.core.model.R2NotificationQueue? = null
        private var writes = 0
        private var newRuleIds = 0

        private val transfers = object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: com.flarego.core.ports.ByteSink): com.flarego.core.ports.HttpResponseHead = error("unexpected download")
        }

        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, TokenKind.USER_OWNED)

        private fun ruleJson(rule: RuleRow) =
            buildJsonObject {
                put("ruleId", rule.id)
                put("actions", JsonArray(rule.actions.map { JsonPrimitive(it) }))
                rule.description?.let { put("description", it) }
                rule.prefix?.let { put("prefix", it) }
                rule.suffix?.let { put("suffix", it) }
                put("createdAt", "2026-10-22T00:00:00Z")
            }

        private fun queueJsonOf(queue: com.flarego.core.model.R2NotificationQueue) =
            buildJsonObject {
                queue.queueId?.let { put("queueId", it) }
                queue.queueName?.let { put("queueName", it) }
                put(
                    "rules",
                    JsonArray(
                        queue.rules.map { rule ->
                            buildJsonObject {
                                rule.ruleId?.let { put("ruleId", it) }
                                put("actions", JsonArray(rule.actions.map { JsonPrimitive(it) }))
                                rule.description?.let { put("description", it) }
                                rule.prefix?.let { put("prefix", it) }
                                rule.suffix?.let { put("suffix", it) }
                                put("createdAt", "2026-10-22T00:00:00Z")
                            }
                        },
                    ),
                )
            }

        private fun queueJson() =
            buildJsonObject {
                put("queueId", queueId)
                queueName?.let { put("queueName", it) }
                put("rules", JsonArray(rules.map { ruleJson(it) }))
                if (unexpectedQueueMember) put("unknownMember", true)
            }

        private val gateway = CloudflareGateway(
            HttpTransport { request ->
                calls += request
                val base = "/accounts/${connection.accountId}/event_notifications/r2/my-bucket/configuration"
                when {
                    request.path == "/user/tokens/verify" -> {
                        authorityGate?.await()
                        envelope(buildJsonObject { put("id", "self"); put("status", "active") })
                    }
                    request.path.endsWith("/permission_groups") ->
                        envelope(
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("id", "admin")
                                        put("name", "Workers R2 Storage Write")
                                        put("scopes", JsonArray(listOf(JsonPrimitive("com.cloudflare.api.account"))))
                                    },
                                ),
                            ),
                        )
                    request.path.endsWith("/self") ->
                        envelope(
                            buildJsonObject {
                                put("id", "self")
                                put("status", "active")
                                put(
                                    "policies",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("id", "p")
                                                put("effect", "allow")
                                                put("permission_groups", JsonArray(listOf(buildJsonObject { put("id", "admin") })))
                                                put("resources", buildJsonObject { put(accountResource, JsonPrimitive("*")) })
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    request.path == "/accounts/${connection.accountId}" ->
                        envelope(buildJsonObject { put("id", connection.accountId) })
                    request.path == base -> envelope(
                        buildJsonObject {
                            if (collectionBucketName != null) put("bucketName", collectionBucketName!!) else put("missingBucketName", true)
                            put("queues", JsonArray(if (rules.isEmpty() && singleGetFailsAfterWrite) emptyList() else listOf(queueJson())))
                        },
                    )
                    request.path == "$base/queues/$queueId" && request.method == "GET" -> {
                        val override = singleGetOverride
                        when {
                            writes > 0 && override != null -> envelope(queueJsonOf(override))
                            singleGetFailsAfterWrite && writes > 0 -> HttpResponse(404, "not found")
                            else -> envelope(queueJson())
                        }
                    }
                    request.path == "$base/queues/$queueId" && request.method == "PUT" -> {
                        writes++
                        val body = (request.payload as HttpBody.Text).value
                        val submitted = Json.parseToJsonElement(body).jsonObject.getValue("rules").jsonArray
                        if (replaceOnAppend) rules.clear()
                        for (element in submitted) {
                            val row = element.jsonObject
                            rules += RuleRow(
                                id = "rule-new-${++newRuleIds}",
                                actions = row.getValue("actions").jsonArray.map { it.jsonPrimitive.content },
                                description = (row["description"] as? JsonPrimitive)?.content,
                                prefix = (row["prefix"] as? JsonPrimitive)?.content,
                                suffix = (row["suffix"] as? JsonPrimitive)?.content,
                            )
                        }
                        HttpResponse(appendStatus, buildJsonObject { put("success", true) }.toString())
                    }
                    request.path == "$base/queues/$queueId" && request.method == "DELETE" -> {
                        writes++
                        val body = request.payload
                        if (body is HttpBody.Text) {
                            val selected = Json.parseToJsonElement(body.value).jsonObject.getValue("ruleIds").jsonArray
                                .map { it.jsonPrimitive.content }.toSet()
                            val removedFirst = rules.firstOrNull { it.id in selected }
                            rules.removeAll { it.id in selected }
                            if (dropExtraOnDelete) rules.removeAll { it.id != removedFirst?.id && selected.isNotEmpty() && rules.indexOf(it) == 0 }
                        } else {
                            if (keepOneOnClear) rules.removeAll { it.id != rules.lastOrNull()?.id }
                            else rules.clear()
                        }
                        HttpResponse(deleteStatus, buildJsonObject { put("success", true) }.toString())
                    }
                    else -> error("unexpected synthetic request ${request.method} ${request.path}")
                }
            },
            sleeper = {},
        )

        /** Typed-session fault injection for the complete-configuration read only. */
        var collectionOverride: com.flarego.core.model.R2NotificationCollection? = null
        private val wireSession = AuthorizedR2NotificationSession(connection, gateway, issuer)
        val session: com.flarego.core.ports.R2NotificationSession =
            object : com.flarego.core.ports.R2NotificationSession {
                override suspend fun listConfigurations(target: R2WriteTarget) =
                    collectionOverride ?: wireSession.listConfigurations(target)

                override suspend fun getConfiguration(target: R2WriteTarget, queueId: String) =
                    wireSession.getConfiguration(target, queueId)

                override suspend fun appendRules(target: R2WriteTarget, queueId: String, rules: List<R2NotificationRuleSpec>) =
                    wireSession.appendRules(target, queueId, rules)

                override suspend fun deleteRules(target: R2WriteTarget, queueId: String, deletion: R2NotificationDeletion) =
                    wireSession.deleteRules(target, queueId, deletion)

                override suspend fun writeNotification(
                    write: R2NotificationWrite,
                    beforeSend: (com.flarego.core.model.R2NotificationQueue) -> Unit,
                ) = wireSession.writeNotification(write, beforeSend)
            }

        val runtime = R2WriteRuntime(
            scope.backgroundScope,
            store,
            StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { now },
            OperationIds { "runtime-${++ids}" },
            { actual },
            { listOf(connection) },
            { revision },
            { error("no batch A session in this test") },
            notificationSession = { session },
        )

        fun select(command: R2Command) {
            actual = actual.copy(command = command)
        }

        fun mutations() = calls.filter { it.method != "GET" }

        fun putBody(): String = (mutations().single().payload as HttpBody.Text).value

        /** A fresh runtime instance over the same store and provider (a process restart). */
        fun restartedRuntime(scope: TestScope): R2WriteRuntime =
            R2WriteRuntime(
                scope.backgroundScope,
                store,
                StandardTestDispatcher(scope.testScheduler),
                FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
                OperationClock { now },
                OperationIds { "restarted-${++ids}" },
                { actual },
                { listOf(connection) },
                { revision },
                { error("no batch A session in this test") },
                notificationSession = { session },
            )
    }

    private fun append(vararg rules: R2NotificationRuleSpec) =
        R2Command.Notification(R2NotificationWrite.Append(target, queueId, rules.toList()))

    private fun deleteSelected(vararg ids: String) =
        R2Command.Notification(R2NotificationWrite.Delete(target, queueId, R2NotificationDeletion.Selected(ids.toList())))

    private fun deleteAll() =
        R2Command.Notification(R2NotificationWrite.Delete(target, queueId, R2NotificationDeletion.All))

    private fun rule(actions: List<String> = mutableListOf("PutObject"), description: String? = null) =
        R2NotificationRuleSpec(actions, description)

    @Test
    fun anAppendSendsOnlyNewRulesAndProvesThemWithAnIndependentRead() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-old", listOf("DeleteObject"), description = "keep me")
        val command = append(rule(description = "new events"))
        h.select(command)
        val preview = h.runtime.prepare(command)
        assertEquals(0, h.mutations().size, "a preview never sends")
        val task = h.runtime.confirm(preview.planId)
        assertEquals(1, h.mutations().size)
        assertEquals("PUT", h.mutations().single().method)
        val body = h.putBody()
        // Only the new rule travels: the existing rule's identity is never re-submitted.
        assertTrue(body.contains("new events"), body)
        assertFalseRuleId(body)
        // Old rule preserved, new rule added with a provider-assigned ID.
        assertEquals(2, h.rules.size)
        assertEquals("rule-old", h.rules.first().id)
        assertEquals(TaskState.SUCCEEDED, task.state)
        // At least one independent GET after the write.
        val getsAfter = h.calls.dropWhile { it.method != "PUT" }.count { it.method == "GET" && it.path.contains("/queues/") }
        assertTrue(getsAfter >= 1, "the append must be verified by an independent read")
    }

    private fun assertFalseRuleId(body: String) {
        assertTrue(!body.contains("ruleId"), "a submission never carries a rule identity: $body")
    }

    @Test
    fun aRuleDescriptionIsNeverAnIdentity() = runTest {
        // Same description as an existing rule: both must exist afterwards (different identities).
        val h = Harness(this)
        h.rules += RuleRow("rule-old", listOf("PutObject"), description = "same")
        val command = append(rule(description = "same"))
        h.select(command)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(h.runtime.prepare(command).planId).state)
        assertEquals(2, h.rules.size)

        // A provider that replaced the old rule is not a successful append.
        val replaced = Harness(this)
        replaced.rules += RuleRow("rule-old", listOf("PutObject"), description = "same")
        replaced.replaceOnAppend = true
        val second = append(rule(description = "same"))
        replaced.select(second)
        assertEquals(TaskState.UNKNOWN, replaced.runtime.confirm(replaced.runtime.prepare(second).planId).state)
    }

    @Test
    fun aSelectedDeletionRemovesExactlyThoseIdsAndKeepsTheOthers() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val command = deleteSelected("rule-a")
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(1, h.mutations().size)
        assertEquals("DELETE", h.mutations().single().method)
        assertTrue(h.mutations().single().payload is HttpBody.Text, "a selected deletion carries an explicit body")
        assertEquals(listOf("rule-b"), h.rules.map { it.id })
        assertEquals(TaskState.SUCCEEDED, task.state)

        // A provider that also dropped another rule is not a successful selected deletion.
        val lossy = Harness(this)
        lossy.rules += RuleRow("rule-a", listOf("PutObject"))
        lossy.rules += RuleRow("rule-b", listOf("DeleteObject"))
        lossy.dropExtraOnDelete = true
        val second = deleteSelected("rule-a")
        lossy.select(second)
        assertEquals(TaskState.UNKNOWN, lossy.runtime.confirm(lossy.runtime.prepare(second).planId).state)
    }

    @Test
    fun theWholeQueueClearUsesTheBodylessPathAndIsProvenByTheConfigurationList() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val command = deleteAll()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(1, h.mutations().size)
        assertTrue(h.mutations().single().payload is HttpBody.Empty, "the clear never degrades into a selected body")
        assertTrue(h.rules.isEmpty())
        assertEquals(TaskState.SUCCEEDED, task.state)

        // A clear that leaves a rule behind is never a success.
        val keep = Harness(this)
        keep.rules += RuleRow("rule-a", listOf("PutObject"))
        keep.rules += RuleRow("rule-b", listOf("DeleteObject"))
        keep.keepOneOnClear = true
        val second = deleteAll()
        keep.select(second)
        assertEquals(TaskState.UNKNOWN, keep.runtime.confirm(keep.runtime.prepare(second).planId).state)
    }

    @Test
    fun aSelectionThatIsNoLongerInTheFreshDocumentNeverSends() = runTest {
        // Unknown at preview time.
        val unknown = Harness(this)
        unknown.rules += RuleRow("rule-a", listOf("PutObject"))
        val command = deleteSelected("rule-missing")
        unknown.select(command)
        assertEquals(CloudFailureCode.INVALID_INPUT, assertFailsWith<CloudFailure> { unknown.runtime.prepare(command) }.code)
        assertEquals(0, unknown.mutations().size)

        // Removed by someone else between the preview and the send: the wire's own fresh read refuses.
        val raced = Harness(this)
        raced.rules += RuleRow("rule-a", listOf("PutObject"))
        val racedCommand = deleteSelected("rule-a")
        raced.select(racedCommand)
        val preview = raced.runtime.prepare(racedCommand)
        raced.rules.clear()
        val refused = raced.runtime.confirm(preview.planId)
        assertEquals(TaskState.FAILED, refused.state, "a pre-send refusal is a trusted local failure")
        assertEquals(0, raced.mutations().size)
    }

    @Test
    fun anUninterpretableOrForeignQueueIsReadOnly() = runTest {
        val unknown = Harness(this)
        unknown.unexpectedQueueMember = true
        val command = append(rule())
        unknown.select(command)
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { unknown.runtime.prepare(command) }.code,
        )
        assertEquals(0, unknown.mutations().size)
    }

    @Test
    fun anAppendOverTheClientBudgetIsRefusedBeforeAnySend() = runTest {
        val h = Harness(this)
        repeat(100) { h.rules += RuleRow("rule-$it", listOf("PutObject")) }
        val command = append(rule())
        h.select(command)
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { h.runtime.prepare(command) }.code,
        )
        assertEquals(0, h.mutations().size)
    }

    @Test
    fun theGuardComparesTheWireOwnFreshDocument() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        val command = append(rule(description = "new"))
        h.select(command)
        val preview = h.runtime.prepare(command)
        // Another client changes the queue after the preview: the guard's baseline is the fresh read,
        // so the send is refused instead of overwriting an unseen change.
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val refused = h.runtime.confirm(preview.planId)
        assertEquals(TaskState.FAILED, refused.state)
        assertEquals(0, h.mutations().size)
        assertEquals(2, h.rules.size, "nothing was written")
    }

    @Test
    fun aWithdrawnCredentialNeverConfirmsThePreview() = runTest {
        val h = Harness(this)
        val command = append(rule())
        h.select(command)
        val preview = h.runtime.prepare(command)
        h.revision = -1
        assertFailsWith<CloudFailure> { h.runtime.confirm(preview.planId) }
        assertEquals(0, h.mutations().size)
    }

    @Test
    fun anAcceptedWriteIsNeverUpgradedAndTheClearCanBeRecoveredReadOnly() = runTest {
        // A 202 on a whole-queue clear stays accepted, and a read-only recovery can prove it.
        val clear = Harness(this)
        clear.deleteStatus = 202
        clear.rules += RuleRow("rule-a", listOf("PutObject"))
        val clearCommand = deleteAll()
        clear.select(clearCommand)
        val accepted = clear.runtime.confirm(clear.runtime.prepare(clearCommand).planId)
        assertEquals(TaskState.ACCEPTED, accepted.state, "a 202 is never completion")
        assertEquals(TaskState.SUCCEEDED, clear.runtime.reconcile(accepted.id).state)
        assertEquals(1, clear.mutations().size, "recovery is read-only")

        // An accepted append cannot be proven later without the frozen document: it stays as it is.
        val append = Harness(this)
        append.appendStatus = 202
        val command = append(rule())
        append.select(command)
        val acceptedAppend = append.runtime.confirm(append.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, acceptedAppend.state)
        assertEquals(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            assertFailsWith<CloudFailure> { append.runtime.reconcile(acceptedAppend.id) }.code,
        )
        assertEquals(TaskState.ACCEPTED, append.store.rows.getValue(acceptedAppend.id).state)
        assertEquals(1, append.mutations().size)
    }

    @Test
    fun aSelectedDeletionAcceptedByTheProviderIsNeverSettledByAGuess() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val command = deleteSelected("rule-a")
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        assertNull(task.providerRequestId)
        // The expected remaining rule set is frozen at preview time, so a read-only recovery can prove
        // this selected deletion without storing any payload.
        assertEquals(TaskState.SUCCEEDED, h.runtime.reconcile(task.id).state)
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun aWholeQueueClearIsProvenByTheConfigurationListEvenWhenTheSingleReadIs404() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.singleGetFailsAfterWrite = true
        val command = deleteAll()
        h.select(command)
        // The pre-write read still sees the queue; the post-write single read is a legitimate 404.
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(h.runtime.prepare(command).planId).state)
        val afterWriteSingleGets = h.calls.dropWhile { it.method != "DELETE" }.count { it.method == "GET" && it.path.contains("/queues/") }
        assertEquals(0, afterWriteSingleGets, "a cleared queue must be proven by the complete configuration list")
    }

    @Test
    fun aSelectedDeletionThatClearsEveryRuleIsProvenByTheConfigurationList() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.singleGetFailsAfterWrite = true
        val command = deleteSelected("rule-a")
        h.select(command)
        assertEquals(TaskState.SUCCEEDED, h.runtime.confirm(h.runtime.prepare(command).planId).state)

        // The same shape must not become a success when rules should have remained.
        val wrong = Harness(this)
        wrong.rules += RuleRow("rule-a", listOf("PutObject"))
        wrong.rules += RuleRow("rule-b", listOf("DeleteObject"))
        wrong.singleGetFailsAfterWrite = true
        val wrongCommand = deleteSelected("rule-a")
        wrong.select(wrongCommand)
        assertEquals(TaskState.UNKNOWN, wrong.runtime.confirm(wrong.runtime.prepare(wrongCommand).planId).state)
    }

    @Test
    fun aForeignOrMissingCollectionIdentityIsNeverABaseline() = runTest {
        for (bucketName in listOf("other-bucket", null)) {
            val h = Harness(this)
            h.collectionBucketName = bucketName
            val command = deleteAll()
            h.select(command)
            assertEquals(TaskState.UNKNOWN, h.runtime.confirm(h.runtime.prepare(command).planId).state, "bucket=$bucketName")
        }
    }

    @Test
    fun aMutatedCommandNeverSendsMoreThanThePreviewedContent() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-old", listOf("DeleteObject"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.authorityGate = gate
        val appendCommand = R2NotificationWrite.Append(target, queueId, listOf(rule(description = "frozen")))
        val command = R2Command.Notification(appendCommand)
        h.select(command)
        val planId = h.runtime.prepare(command).planId
        val confirmation = launch { h.runtime.confirm(planId) }
        runCurrent()
        // A caller (or a compromised editor) mutates the public lists while the proof is in flight.
        // The public list and the nested action list are plain mutable collections: a caller can reach
        // them without reflection, which is exactly why the command must not treat them as authority.
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (appendCommand.rules as MutableList<R2NotificationRuleSpec>).add(rule(description = "injected"))
        }
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (appendCommand.rules.first().actions as MutableList<String>).add("DeleteObject")
        }
        gate.complete(Unit)
        runCurrent()
        confirmation.join()
        if (h.mutations().isNotEmpty()) {
            val body = h.putBody()
            assertFalse(body.contains("injected"), "the wire must only send the frozen content: $body")
            assertFalse(body.contains("DeleteObject"), "the wire must not widen the frozen actions: $body")
        }

        // A new command with different content can never confirm the old preview.
        val second = Harness(this)
        val frozen = R2NotificationWrite.Append(target, queueId, listOf(rule(description = "one")))
        second.select(R2Command.Notification(frozen))
        val secondPlan = second.runtime.prepare(R2Command.Notification(frozen)).planId
        val edited = R2Command.Notification(R2NotificationWrite.Append(target, queueId, listOf(rule(description = "one"), rule(description = "two"))))
        second.select(edited)
        assertFailsWith<CloudFailure> { second.runtime.confirm(secondPlan) }
        assertEquals(0, second.mutations().size)
    }

    @Test
    fun aRecoveryRecomputesTheObservedStateInsteadOfEchoingTheDesiredHash() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        val command = deleteAll()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        // A tampered desired hash must never be echoed back to itself as "matched".
        h.store.rows[task.id] = task.copy(desiredHash = "0".repeat(64))
        assertEquals(TaskState.UNKNOWN, h.runtime.reconcile(task.id).state)
    }

    @Test
    fun aRecoveryNeverAcceptsAForeignFamilyForItsAction() = runTest {
        val h = Harness(this)
        h.appendStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        val command = append(rule(description = "new"))
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        // The append action must never be recovered through an "ALL" locator family.
        val locator = assertNotNull(task.resourceRef)
        val tampered = locator.copy(externalId = locator.externalId.replace("/NOTIFICATION_APPEND/", "/NOTIFICATION_ALL/"))
        h.store.rows[task.id] = task.copy(resourceRef = tampered)
        val recovered = runCatching { h.runtime.reconcile(task.id) }
        assertTrue(
            recovered.isFailure || recovered.getOrNull()?.state != TaskState.SUCCEEDED,
            "an append action must not be settled through the whole-queue-clear contract",
        )
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun aTerminalRowKeepsItsHistoryAndSelectedRecoveryNeedsNoStoredPayload() = runTest {
        // A successful append stays successful even if the queue changes afterwards.
        val done = Harness(this)
        done.rules += RuleRow("rule-old", listOf("DeleteObject"))
        val command = append(rule(description = "new"))
        done.select(command)
        val succeeded = done.runtime.confirm(done.runtime.prepare(command).planId)
        assertEquals(TaskState.SUCCEEDED, succeeded.state)
        done.rules += RuleRow("rule-later", listOf("PutObject"))
        assertEquals(TaskState.SUCCEEDED, done.runtime.reconcile(succeeded.id).state, "history is not rewritten")

        // A trusted refusal stays FAILED even if the provider later looks clean.
        val refused = Harness(this)
        refused.rules += RuleRow("rule-a", listOf("PutObject"))
        val clear = deleteAll()
        refused.select(clear)
        val refusedPlan = refused.runtime.prepare(clear)
        refused.rules.clear() // someone else already cleared it: the guard refuses the send
        val failed = refused.runtime.confirm(refusedPlan.planId)
        assertEquals(TaskState.FAILED, failed.state)
        assertEquals(TaskState.FAILED, refused.runtime.reconcile(failed.id).state, "a trusted refusal is final")

        // A selected deletion accepted with 202 can be proven later by recomputing the expected state.
        val selected = Harness(this)
        selected.deleteStatus = 202
        selected.rules += RuleRow("rule-a", listOf("PutObject"))
        selected.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val selectedCommand = deleteSelected("rule-a")
        selected.select(selectedCommand)
        val accepted = selected.runtime.confirm(selected.runtime.prepare(selectedCommand).planId)
        assertEquals(TaskState.ACCEPTED, accepted.state)
        val restarted = selected.restartedRuntime(this)
        assertEquals(TaskState.SUCCEEDED, restarted.reconcile(accepted.id).state)
        assertEquals(1, selected.mutations().size, "recovery is read-only")
    }

    @Test
    fun aSelectedDeletionThatClearedEverythingRecoversFromALegallyAbsentQueue() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        h.singleGetFailsAfterWrite = true
        val command = deleteSelected("rule-a", "rule-b")
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        // The queue is gone from the complete list, which is exactly the expected end state here.
        val restarted = h.restartedRuntime(this)
        assertEquals(TaskState.SUCCEEDED, restarted.reconcile(task.id).state)
        assertEquals(1, h.mutations().size, "recovery is read-only")
        assertTrue(
            assertNotNull(h.store.rows.getValue(task.id).resourceRef).externalId.contains("/NOTIFICATION_SELECTED/"),
            "the locator keeps the action-specific family",
        )
    }

    @Test
    fun aSelectedDeletionThatShouldLeaveRulesNeverSucceedsOnAnAbsentQueue() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        h.singleGetFailsAfterWrite = true
        val command = deleteSelected("rule-a")
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        // The provider dropped the whole queue although rule-b had to remain: never a success.
        h.rules.clear()
        val restarted = h.restartedRuntime(this)
        val recovered = runCatching { restarted.reconcile(task.id) }
        assertTrue(
            recovered.isFailure || recovered.getOrNull()?.state != TaskState.SUCCEEDED,
            "a vanished queue with rules that had to survive is a contradiction, not a success",
        )
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun aRecoveryNeverTrustsAnAmbiguousOrForeignCollectionFromATypedSession() = runTest {
        val h = Harness(this)
        h.deleteStatus = 202
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        val command = deleteAll()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.ACCEPTED, task.state)
        val badCollections = listOf(
            // interpretable=true forced, but no bucket identity.
            com.flarego.core.model.R2NotificationCollection(
                bucketName = null,
                queues = emptyList(),
                interpretable = true,
            ),
            // A foreign bucket.
            com.flarego.core.model.R2NotificationCollection(
                bucketName = "other-bucket",
                queues = emptyList(),
                interpretable = true,
            ),
            // A duplicated target queue identity.
            com.flarego.core.model.R2NotificationCollection(
                bucketName = "my-bucket",
                queues =
                    listOf(
                        com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), true),
                        com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), true),
                    ),
                interpretable = true,
            ),
            // A queue without identity.
            com.flarego.core.model.R2NotificationCollection(
                bucketName = "my-bucket",
                queues = listOf(com.flarego.core.model.R2NotificationQueue(null, "events", emptyList(), true)),
                interpretable = true,
            ),
        )
        for (bad in badCollections) {
            h.collectionOverride = bad
            val recovered = runCatching { h.runtime.reconcile(task.id) }
            // Refused or conservatively unproven — never settled as a success.
            assertTrue(
                recovered.isFailure || recovered.getOrNull()?.state != TaskState.SUCCEEDED,
                "an ambiguous/foreign collection must never settle a task: $bad",
            )
            assertNotEquals(TaskState.SUCCEEDED, h.store.rows.getValue(task.id).state)
        }
        assertEquals(1, h.mutations().size)
    }

    @Test
    fun theImmediateAfterAlsoRejectsAnAmbiguousCollectionFromATypedSession() = runTest {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        h.collectionOverride = com.flarego.core.model.R2NotificationCollection(
            bucketName = null,
            queues = emptyList(),
            interpretable = true,
        )
        val command = deleteAll()
        h.select(command)
        val task = h.runtime.confirm(h.runtime.prepare(command).planId)
        assertEquals(TaskState.UNKNOWN, task.state, "the immediate after must not accept a collection without identity")
        assertEquals(1, h.mutations().size)
    }

    // ---------------------------------------------------------------- fix3: one evidence predicate

    /** The own-but-uninterpretable child document the independent QA used for its RED probe. */
    private fun ownUninterpretableEmpty(queueId: String) =
        com.flarego.core.model.R2NotificationCollection(
            bucketName = "my-bucket",
            queues = listOf(com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), false)),
            interpretable = true,
        )

    private fun ownInterpretableEmpty(queueId: String) =
        com.flarego.core.model.R2NotificationCollection(
            bucketName = "my-bucket",
            queues = listOf(com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), true)),
            interpretable = true,
        )

    /** Send counts are asserted before any state assertion, so a failing state can never hide them. */
    private suspend fun TestScope.deletionCase(
        intent: String,
        shape: String?,
        path: String,
    ): Triple<TaskState, TaskState, Int> {
        val h = Harness(this)
        h.rules += RuleRow("rule-a", listOf("PutObject"))
        if (intent == "SELECTED_PARTIAL") h.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val command =
            when (intent) {
                "ALL" -> deleteAll()
                "SELECTED_CLEAR_ALL" -> deleteSelected("rule-a")
                "SELECTED_PARTIAL" -> deleteSelected("rule-a")
                else -> error(intent)
            }
        h.select(command)
        val planId = h.runtime.prepare(command).planId
        if (path != "immediate") h.deleteStatus = 202
        h.collectionOverride = shape?.let { describeShape(it, queueId) }
        val task = h.runtime.confirm(planId)
        val target = if (path == "restarted") h.restartedRuntime(this) else h.runtime
        val settled = runCatching { target.reconcile(task.id) }.getOrNull() ?: task
        return Triple(task.state, h.store.rows.getValue(task.id).state, h.mutations().size)
    }

    private fun describeShape(shape: String, queueId: String): com.flarego.core.model.R2NotificationCollection =
        when (shape) {
            "parentFalse" -> ownInterpretableEmpty(queueId).copy(interpretable = false)
            "legitAbsent" -> ownInterpretableEmpty(queueId).copy(queues = emptyList())
            "legitEmpty" -> ownInterpretableEmpty(queueId)
            "ownQueueFalse" -> ownUninterpretableEmpty(queueId)
            "foreignBucket" -> ownInterpretableEmpty(queueId).copy(bucketName = "other-bucket")
            "missingBucket" -> ownInterpretableEmpty(queueId).copy(bucketName = null)
            "duplicateId" ->
                ownInterpretableEmpty(queueId).copy(
                    queues =
                        listOf(
                            com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), true),
                            com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), true),
                        ),
                )
            "missingQueueId" ->
                ownInterpretableEmpty(queueId).copy(
                    queues = listOf(com.flarego.core.model.R2NotificationQueue(null, "events", emptyList(), true)),
                )
            else -> error("unknown shape $shape")
        }

    @Test
    fun anOwnButUninterpretableQueueNeverProvesADeletionOnTheImmediatePath() = runTest {
        for (intent in listOf("ALL", "SELECTED_CLEAR_ALL")) {
            val h = Harness(this)
            h.rules += RuleRow("rule-a", listOf("PutObject"))
            val command = if (intent == "ALL") deleteAll() else deleteSelected("rule-a")
            h.select(command)
            val planId = h.runtime.prepare(command).planId
            h.collectionOverride = ownUninterpretableEmpty(queueId)
            val task = h.runtime.confirm(planId)
            // The send count first: a failing state assertion must never hide it.
            assertEquals(1, h.mutations().size, "$intent: exactly one DELETE")
            assertNotEquals(TaskState.SUCCEEDED, task.state, "$intent: an own but non-interpretable queue must not prove it")
            assertNotEquals(TaskState.SUCCEEDED, h.store.rows.getValue(task.id).state, "$intent: store")
        }
    }

    @Test
    fun theSameEvidencePredicateGuardsEveryPath() = runTest {
        for (path in listOf("immediate", "sameRuntime", "restarted")) {
            for (intent in listOf("ALL", "SELECTED_CLEAR_ALL")) {
                val bad = deletionCase(intent, "ownQueueFalse", path)
                assertEquals(1, bad.third, "$path/$intent: exactly one DELETE")
                assertNotEquals(TaskState.SUCCEEDED, bad.first, "$path/$intent: immediate state")
                assertNotEquals(TaskState.SUCCEEDED, bad.second, "$path/$intent: stored state")
                val good = deletionCase(intent, "legitEmpty", path)
                assertEquals(1, good.third, "$path/$intent: exactly one DELETE")
                assertEquals(TaskState.SUCCEEDED, good.second, "$path/$intent: a fully interpretable empty queue still settles")
            }
        }
    }

    @Test
    fun theDocumentShapeMatrixNeverSettlesFromInvalidDocuments() = runTest {
        val invalid = listOf("parentFalse", "ownQueueFalse", "foreignBucket", "missingBucket", "duplicateId", "missingQueueId")
        for (shape in invalid) {
            for (intent in listOf("ALL", "SELECTED_CLEAR_ALL")) {
                val outcome = deletionCase(intent, shape, "immediate")
                assertEquals(1, outcome.third, "$shape/$intent: exactly one DELETE")
                assertNotEquals(TaskState.SUCCEEDED, outcome.first, "$shape/$intent: task")
                assertNotEquals(TaskState.SUCCEEDED, outcome.second, "$shape/$intent: store")
            }
        }
        // The two legitimate end states keep succeeding (never a blanket refusal).
        for (shape in listOf("legitEmpty", "legitAbsent")) {
            for (intent in listOf("ALL", "SELECTED_CLEAR_ALL")) {
                val outcome = deletionCase(intent, shape, "immediate")
                assertEquals(TaskState.SUCCEEDED, outcome.second, "$shape/$intent: legitimate end state")
            }
        }
        // A selected deletion that must leave rules is proven by the single-queue read with the same
        // queue-identity predicate (a vanished queue is a contradiction there, covered by
        // `aSelectedDeletionWithRemainingRulesUsesTheSameQueueIdentityPredicate` and the fix2 recovery case).
    }

    @Test
    fun aSelectedDeletionWithRemainingRulesUsesTheSameQueueIdentityPredicate() = runTest {
        // A legitimate remaining rule set still succeeds through the single-queue read.
        val legit = Harness(this)
        legit.rules += RuleRow("rule-a", listOf("PutObject"))
        legit.rules += RuleRow("rule-b", listOf("DeleteObject"))
        val command = deleteSelected("rule-a")
        legit.select(command)
        assertEquals(TaskState.SUCCEEDED, legit.runtime.confirm(legit.runtime.prepare(command).planId).state)
        assertEquals(1, legit.mutations().size)

        // The same read returning a non-interpretable queue is never evidence.
        val bad = Harness(this)
        bad.rules += RuleRow("rule-a", listOf("PutObject"))
        bad.rules += RuleRow("rule-b", listOf("DeleteObject"))
        bad.singleGetOverride =
            com.flarego.core.model.R2NotificationQueue(queueId, "events", emptyList(), interpretable = false)
        val badCommand = deleteSelected("rule-a")
        bad.select(badCommand)
        val task = bad.runtime.confirm(bad.runtime.prepare(badCommand).planId)
        assertEquals(1, bad.mutations().size)
        assertNotEquals(TaskState.SUCCEEDED, task.state)

        // A queue that vanished although a rule had to remain is a contradiction.
        val absent = Harness(this)
        absent.rules += RuleRow("rule-a", listOf("PutObject"))
        absent.rules += RuleRow("rule-b", listOf("DeleteObject"))
        absent.singleGetFailsAfterWrite = true
        val absentCommand = deleteSelected("rule-a")
        absent.select(absentCommand)
        val absentTask = absent.runtime.confirm(absent.runtime.prepare(absentCommand).planId)
        assertEquals(1, absent.mutations().size)
        assertNotEquals(TaskState.SUCCEEDED, absentTask.state)
    }

    @Test
    fun aFullyInterpretableEmptyQueueStillSettlesBothDeletionIntents() = runTest {
        for (intent in listOf("ALL", "SELECTED_CLEAR_ALL")) {
            val h = Harness(this)
            h.rules += RuleRow("rule-a", listOf("PutObject"))
            val command = if (intent == "ALL") deleteAll() else deleteSelected("rule-a")
            h.select(command)
            h.collectionOverride = ownInterpretableEmpty(queueId)
            val task = h.runtime.confirm(h.runtime.prepare(command).planId)
            assertEquals(1, h.mutations().size, "$intent: exactly one DELETE")
            assertEquals(TaskState.SUCCEEDED, task.state, "$intent: the one legitimate success shape")
        }
    }
}
