package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_NOTIFICATION_BUDGET_EXCEEDED
import com.flarego.core.application.r2.R2_NOTIFICATION_READ_ONLY_UNKNOWN
import com.flarego.core.application.r2.R2_NOTIFICATION_READ_ONLY_WRITE
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.r2.CloudflareR2NotificationClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch C Task 1 (fix1): the typed event-notification wire. Append is not replace; the selected and
 * whole-queue deletions never share a payload, and every write is verified against a bounded,
 * interpreted fresh before document before it is sent. Synthetic transport only.
 */
class R2NotificationClientTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val base = "/accounts/${connection.accountId}/event_notifications/r2/my-bucket/configuration"
    private val queuePath = "$base/queues/q1"

    private val requests = mutableListOf<HttpRequest>()
    private var listStatus = 200
    private var queueStatus = 200
    private var putStatus = 200
    private var deleteStatus = 200
    private var listResult = """{"bucketName":"my-bucket","queues":[${queueJson()}]}"""
    private var queueResult = queueJson()
    private var duringQueueRead: () -> Unit = {}

    private fun envelope(result: String) =
        """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun rule(
        id: String? = "r1",
        actions: String = "\"PutObject\"",
        prefix: String = "img/",
        extra: String = "",
    ): String {
        val idField = if (id == null) "" else "\"ruleId\":\"$id\","
        return """{$idField"actions":[$actions],"description":"d","prefix":"$prefix","suffix":".jpg","createdAt":"2026-10-22T00:00:00Z"$extra}"""
    }

    private fun queueJson(
        queueId: String? = "q1",
        rules: String = rule(),
        extra: String = "",
    ): String {
        val idField = if (queueId == null) "" else "\"queueId\":\"$queueId\","
        return """{$idField"queueName":"first-queue","rules":[$rules]$extra}"""
    }

    private val gateway = CloudflareGateway(
        HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.path == base ->
                    if (listStatus == 200) HttpResponse(200, envelope(listResult)) else HttpResponse(listStatus, envelope("null"))
                request.method == "GET" && request.path == queuePath -> {
                    duringQueueRead()
                    if (queueStatus == 200) HttpResponse(200, envelope(queueResult)) else HttpResponse(queueStatus, envelope("null"))
                }
                request.method == "PUT" && request.path == queuePath ->
                    if (putStatus in 200..299) HttpResponse(putStatus, envelope("{}")) else HttpResponse(putStatus, envelope("null"))
                request.method == "DELETE" && request.path == queuePath ->
                    if (deleteStatus in 200..299) HttpResponse(deleteStatus, envelope("{}")) else HttpResponse(deleteStatus, envelope("null"))
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        },
        sleeper = {},
    )

    private val client = CloudflareR2NotificationClient(connection, gateway)

    private fun mutations() = requests.filter { it.method != "GET" }

    private fun bodyOf(request: HttpRequest): JsonObject =
        Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject

    @Test
    fun theFourReviewedNotificationRoutesAreRegisteredExactlyOnce() {
        for (key in listOf("r2-get-event-notification-configs", "r2-get-event-notification-config")) {
            val policy = assertNotNull(ActionPolicies.policy(key))
            assertEquals(ActionSemantics.READ, policy.semantics)
            assertTrue(policy.retry.enabled)
            assertEquals(RiskLevel.READ_ONLY, policy.risk)
        }
        val append = assertNotNull(ActionPolicies.policy("r2-put-event-notification-config"))
        assertEquals(ActionSemantics.WRITE, append.semantics)
        assertEquals(RiskLevel.CHANGE, append.risk)
        assertFalse(append.retry.enabled)
        val delete = assertNotNull(ActionPolicies.policy("r2-event-notification-delete-config"))
        assertEquals(ActionSemantics.WRITE, delete.semantics)
        assertEquals(RiskLevel.DESTRUCTIVE, delete.risk)
        assertFalse(delete.retry.enabled)
        assertEquals(
            "/accounts/{account_id}/event_notifications/r2/{bucket_name}/configuration/queues/{queue_id}",
            ActionPolicies.registry.registration("r2-put-event-notification-config")!!.pathTemplate,
        )
    }

    @Test
    fun listConfigurationsTreats404AsNotFoundAndKeepsForeignBucketEchoReadOnly() = runTest {
        val collection = client.listConfigurations(target)
        assertEquals("my-bucket", collection.bucketName)
        assertEquals("q1", collection.queues.single().queueId)
        assertTrue(collection.interpretable)
        assertEquals(mapOf("cf-r2-jurisdiction" to "default"), requests.single().headers)

        listResult = """{"bucketName":"another-bucket","queues":[${queueJson()}]}"""
        assertFalse(client.listConfigurations(target).interpretable, "a foreign bucket echo must stay read-only")

        listStatus = 404
        val notFound = assertFailsWith<CloudFailure> { client.listConfigurations(target) }
        assertEquals(CloudFailureCode.NOT_FOUND, notFound.code)
    }

    @Test
    fun missingOrDuplicatedQueueAndRuleIdentityKeepsTheConfigurationReadOnly() = runTest {
        queueResult = queueJson(queueId = null)
        assertFalse(client.getConfiguration(target, "q1").interpretable, "a queue without an ID cannot be a write baseline")

        queueResult = queueJson(rules = rule(id = null))
        assertFalse(client.getConfiguration(target, "q1").interpretable, "a rule without an ID cannot be proven")

        queueResult = queueJson(rules = rule(id = "r1") + "," + rule(id = "r1"))
        assertFalse(client.getConfiguration(target, "q1").interpretable, "duplicate rule IDs are ambiguous")

        listResult = """{"bucketName":"my-bucket","queues":[${queueJson()},${queueJson()}]}"""
        assertFalse(client.listConfigurations(target).interpretable, "duplicate queue IDs are ambiguous")

        // The real write path refuses (0 mutation), not only the helper flag.
        queueResult = queueJson(rules = rule(id = null))
        assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertFailsWith<CloudFailure> {
            client.deleteRules(target, "q1", R2NotificationDeletion.Selected(listOf("r1")))
        }
        assertFailsWith<CloudFailure> { client.deleteRules(target, "q1", R2NotificationDeletion.All) }
        assertTrue(mutations().isEmpty())
    }

    @Test
    fun appendSendsOnlyNewRulesAndKeepsFiltersVerbatim() = runTest {
        queueResult = queueJson(rules = rule(id = "existing-1") + "," + rule(id = "existing-2"))
        val receipt = client.appendRules(
            target,
            "q1",
            listOf(R2NotificationRuleSpec(actions = listOf("PutObject"), description = "new rule", prefix = "img/", suffix = ".jpg")),
        )
        assertIs<ExecutionReceipt.Completed>(receipt)
        val request = mutations().single()
        assertEquals("PUT", request.method)
        assertEquals(mapOf("cf-r2-jurisdiction" to "default"), request.headers)
        val body = bodyOf(request)
        assertEquals(setOf("rules"), body.keys)
        val rules = body.getValue("rules") as JsonArray
        assertEquals(1, rules.size)
        val rule = rules.single().jsonObject
        assertEquals(setOf("actions", "description", "prefix", "suffix"), rule.keys)
        assertEquals("[\"PutObject\"]", rule.getValue("actions").toString())
        assertEquals("\"img/\"", rule.getValue("prefix").toString(), "the prefix must stay verbatim")
        assertFalse(body.toString().contains("existing-1"), "an existing rule identity is never re-submitted")
    }

    @Test
    fun mutableCallerCollectionsCannotEscapeTheValidatedShapeIntoSend() = runTest {
        // Post-construction mutation cannot turn the selected intent into an empty/duplicate send:
        // the model owns a private snapshot, so the frozen original is what goes on the wire.
        queueResult = queueJson(rules = rule(id = "r1") + "," + rule(id = "r2"))
        val ids = mutableListOf("r1")
        val selection = R2NotificationDeletion.Selected(ids)
        ids.clear()
        client.deleteRules(target, "q1", selection)
        assertEquals("""{"ruleIds":["r1"]}""", bodyOf(mutations().single()).toString(), "the frozen selection is sent")

        requests.clear()
        val duplicateSource = mutableListOf("r1")
        val duplicate = R2NotificationDeletion.Selected(duplicateSource)
        duplicateSource.add("r1")
        client.deleteRules(target, "q1", duplicate)
        assertEquals("""{"ruleIds":["r1"]}""", bodyOf(mutations().single()).toString(), "a later duplicate never leaks")

        // A mutation *during* the fresh before read keeps the frozen intent.
        requests.clear()
        val during = mutableListOf("r1")
        val duringSelection = R2NotificationDeletion.Selected(during)
        duringQueueRead = { during.clear() }
        client.deleteRules(target, "q1", duringSelection)
        assertEquals("""{"ruleIds":["r1"]}""", bodyOf(mutations().single()).toString())
        duringQueueRead = {}

        // Mutating a rule spec's action list after construction cannot send an empty/unknown action.
        requests.clear()
        val actions = mutableListOf("PutObject")
        val spec = R2NotificationRuleSpec(actions = actions)
        actions.clear()
        actions.add("UnknownAction")
        client.appendRules(target, "q1", listOf(spec))
        val rule = (bodyOf(mutations().single()).getValue("rules") as JsonArray).single().jsonObject
        assertEquals("[\"PutObject\"]", rule.getValue("actions").toString(), "the frozen action list is sent")

        // A caller list that is already empty/unknown at construction is refused outright.
        requests.clear()
        assertFailsWith<CloudFailure> { R2NotificationRuleSpec(actions = emptyList()) }
        assertFailsWith<CloudFailure> { R2NotificationRuleSpec(actions = listOf("UnknownAction")) }
        assertFailsWith<CloudFailure> { R2NotificationDeletion.Selected(emptyList()) }
        assertTrue(mutations().isEmpty())
    }

    @Test
    fun theNotificationBudgetCoversBeforeDeltaAndExpectedResult() = runTest {
        // 101 rules after the append exceed the client budget: refused before any send.
        val hundred = (1..100).joinToString(",") { rule(id = "r$it") }
        queueResult = queueJson(rules = hundred)
        assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertTrue(mutations().isEmpty())

        // An oversized before document stays read-only and neither deletion may be sent.
        queueResult = queueJson(rules = rule(id = "r1", prefix = "p".repeat(300_000)))
        assertFalse(client.getConfiguration(target, "q1").interpretable)
        assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertFailsWith<CloudFailure> { client.deleteRules(target, "q1", R2NotificationDeletion.All) }
        assertTrue(mutations().isEmpty())

        // The known queue name is part of the configuration budget. It is inside the 1024-char rule
        // text bound and the before document alone is inside 256 KiB, but the merged known
        // configuration (queueName + old rules + delta) exceeds it.
        val bigName = "q".repeat(259_000)
        queueResult = """{"queueId":"q1","queueName":"$bigName","rules":[]}"""
        assertTrue(client.getConfiguration(target, "q1").interpretable, "the raw before document alone is inside the budget")
        val burst = (1..5).map {
            R2NotificationRuleSpec(
                actions = listOf("PutObject"),
                description = "d".repeat(1024),
                prefix = "p".repeat(1024),
                suffix = "s".repeat(1024),
            )
        }
        requests.clear()
        val rejected = assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", burst)
        }
        assertEquals(R2_NOTIFICATION_BUDGET_EXCEEDED, rejected.message, "the merged known configuration is over budget")
        assertTrue(
            requests.any { it.method == "GET" && it.path == queuePath },
            "the refusal happens after the fresh before read, not only at construction/encoding time",
        )
        assertTrue(mutations().isEmpty(), "no PUT is sent for an over-budget merged configuration")

        // Positive control: the same shape with a small known queue name still sends exactly once.
        requests.clear()
        queueResult = queueJson(queueId = "q1", rules = "")
        assertIs<ExecutionReceipt.Completed>(
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject")))),
        )
        assertEquals(1, mutations().size)

        // A legal boundary still works: 99 old + 1 new, small fields.
        requests.clear()
        val ninetyNine = (1..99).joinToString(",") { rule(id = "r$it") }
        queueResult = queueJson(rules = ninetyNine)
        assertIs<ExecutionReceipt.Completed>(
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject")))),
        )
        assertEquals(1, mutations().size)
    }

    @Test
    fun appendRefusesAReadOnlyOrForeignQueueBeforeAnyWrite() = runTest {
        queueResult = queueJson(rules = rule(actions = "\"PutObject\",\"SomeFutureAction\""))
        val unknown = assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, unknown.code)
        assertEquals(R2_NOTIFICATION_READ_ONLY_WRITE, unknown.message)
        assertTrue(mutations().isEmpty())

        queueResult = queueJson(queueId = "some-other-queue")
        val foreign = assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, foreign.code)
        assertTrue(mutations().isEmpty())
    }

    @Test
    fun deleteSelectedSendsNonEmptyUniqueIdsAndAllSendsNoBody() = runTest {
        queueResult = queueJson(rules = rule(id = "r1") + "," + rule(id = "r2"))
        val selected = client.deleteRules(target, "q1", R2NotificationDeletion.Selected(listOf("r1", "r2")))
        assertIs<ExecutionReceipt.Completed>(selected)
        val request = mutations().single()
        assertEquals("DELETE", request.method)
        assertEquals("""{"ruleIds":["r1","r2"]}""", bodyOf(request).toString())

        requests.clear()
        client.deleteRules(target, "q1", R2NotificationDeletion.All)
        val clear = mutations().single()
        assertEquals("DELETE", clear.method)
        assertEquals(HttpBody.Empty, clear.payload, "the whole-queue clear is an explicit no-body delete")
    }

    @Test
    fun deleteSelectedRefusesEmptyDuplicateMissingOrForeignIdsLocally() = runTest {
        queueResult = queueJson(rules = rule(id = "r1"))
        assertFailsWith<CloudFailure> { R2NotificationDeletion.Selected(emptyList()) }
        assertFailsWith<CloudFailure> { R2NotificationDeletion.Selected(listOf("r1", "r1")) }
        assertFailsWith<CloudFailure> { R2NotificationDeletion.Selected(listOf(" ")) }

        val missing = assertFailsWith<CloudFailure> {
            client.deleteRules(target, "q1", R2NotificationDeletion.Selected(listOf("r-missing")))
        }
        assertEquals(CloudFailureCode.INVALID_INPUT, missing.code)
        assertTrue(mutations().isEmpty())

        queueResult = queueJson(rules = rule(actions = "\"PutObject\",\"UnknownAction\""))
        assertFailsWith<CloudFailure> { client.deleteRules(target, "q1", R2NotificationDeletion.All) }
        assertTrue(mutations().isEmpty())
    }

    @Test
    fun writeStatusSemanticsDistinguishCompletedAcceptedAndUndeclaredSuccess() = runTest {
        queueResult = queueJson(rules = rule(id = "r1"))
        putStatus = 202
        assertIs<ExecutionReceipt.Accepted>(
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject")))),
        )
        assertEquals(1, mutations().size)

        requests.clear()
        deleteStatus = 202
        assertIs<ExecutionReceipt.Accepted>(client.deleteRules(target, "q1", R2NotificationDeletion.All))
        assertEquals(1, mutations().size)

        // An undeclared 2xx is not a completion claim.
        requests.clear()
        putStatus = 201
        val undeclared = assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertTrue(undeclared.uncertain, "a sent write with an undeclared success status stays uncertain")
        assertEquals(1, mutations().size)
    }

    @Test
    fun writesNeverRetryAndAnUnknownOutcomeStaysUncertain() = runTest {
        queueResult = queueJson()
        putStatus = 429
        val limited = assertFailsWith<CloudFailure> {
            client.appendRules(target, "q1", listOf(R2NotificationRuleSpec(actions = listOf("PutObject"))))
        }
        assertEquals(CloudFailureCode.RATE_LIMITED, limited.code)
        assertEquals(1, mutations().size)

        requests.clear()
        deleteStatus = 500
        val unavailable = assertFailsWith<CloudFailure> {
            client.deleteRules(target, "q1", R2NotificationDeletion.All)
        }
        assertTrue(unavailable.uncertain)
        assertEquals(1, mutations().size)
    }

    @Test
    fun ruleSpecsAndQueueIdsAreValidatedBeforeAnyRequest() = runTest {
        listOf(emptyList(), listOf("PutObject", "PutObject"), listOf("PutObject", "UnknownAction")).forEach { actions ->
            assertFailsWith<CloudFailure> { R2NotificationRuleSpec(actions = actions) }
        }
        assertFailsWith<CloudFailure> {
            R2NotificationRuleSpec(actions = listOf("PutObject"), prefix = "bad\nprefix")
        }
        assertFailsWith<CloudFailure> { client.getConfiguration(target, " ") }
        assertFailsWith<CloudFailure> { client.appendRules(target, "q\n1", listOf(R2NotificationRuleSpec(listOf("PutObject")))) }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aSingleQueueReadRejectsAForeignQueueEcho() = runTest {
        queueResult = queueJson(queueId = "other")
        val queue = client.getConfiguration(target, "q1")
        assertFalse(queue.interpretable)
        assertEquals(R2_NOTIFICATION_READ_ONLY_UNKNOWN, queue.readOnlyReason)
        assertTrue(queue.queueId == "other")
    }
}
