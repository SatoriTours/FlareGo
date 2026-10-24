package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2ConfigurationClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2_MAX_CONFIGURATION_DOCUMENT_BYTES
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch B1 wire behavior: the ten reviewed routes, the controlled jurisdiction header,
 * full rule-set replacement, explicit `false`/empty arrays, the two different age field
 * names, read-only documents that can never be overwritten, and the single send after the
 * final guard.
 */
class R2ConfigurationClientTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private val foreignAccount = AccountScope("local", "account-two")
    private val target = R2WriteTarget(account, "my-bucket")
    private val requests = mutableListOf<HttpRequest>()

    private fun ok(result: String = "{}") =
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")

    private fun bucketRow(name: String = "my-bucket") = ok("""{"name":"$name"}""")

    /** CORS, lifecycle and lock all answer with a `result.rules` array. */
    private fun rulesRow(rules: String) = ok("""{"rules":$rules}""")

    private var respond: suspend (HttpRequest) -> HttpResponse = { ok() }

    private fun client(beforeMutation: () -> Unit = {}) =
        CloudflareR2ConfigurationClient(
            connection,
            CloudflareGateway(HttpTransport { request ->
                requests += request
                respond(request)
            }),
            beforeMutation,
        )

    /** The expected pre-write conversation: a bucket proof, a snapshot, then the mutation. */
    private fun defaultResponder(configResult: String): suspend (HttpRequest) -> HttpResponse = { request ->
        when {
            request.method != "GET" -> ok()
            request.path.endsWith("/cors") -> rulesRow(configResult)
            request.path.endsWith("/lifecycle") -> rulesRow(configResult)
            request.path.endsWith("/lock") -> rulesRow(configResult)
            else -> bucketRow()
        }
    }

    private fun HttpRequest.bodyJson(): JsonObject =
        Json.parseToJsonElement((payload as HttpBody.Text).value).jsonObject

    private fun List<HttpRequest>.mutation(): HttpRequest = single { it.method != "GET" }

    private fun List<HttpRequest>.writes(): List<HttpRequest> = filter { it.method != "GET" }

    private val oneCorsRule = listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example"))))

    @Test
    fun corsPutReplacesExplicitRulesAndPreservesFalseAndArrayOrder() = runTest {
        respond = defaultResponder("[]")
        val client = client()

        val cors = client.putCors(
            target,
            listOf(
                R2CorsRule(
                    id = "first",
                    allowed = R2CorsAllowed(methods = listOf("PUT", "GET"), origins = listOf("https://b.example")),
                    exposeHeaders = emptyList(),
                    maxAgeSeconds = 3600,
                ),
                R2CorsRule(
                    allowed = R2CorsAllowed(methods = listOf("HEAD", "GET"), origins = listOf("https://a.example"), headers = emptyList()),
                ),
            ),
        )
        assertIs<ExecutionReceipt.Completed>(cors)
        val put = requests.mutation()
        assertEquals("PUT", put.method)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/cors", put.path)
        assertEquals("default", put.headers["cf-r2-jurisdiction"])
        val rules = put.bodyJson().getValue("rules").jsonArray
        assertEquals(2, rules.size)
        assertEquals("first", rules[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(
            listOf("PUT", "GET"),
            rules[0].jsonObject.getValue("allowed").jsonObject.getValue("methods").jsonArray.map { it.jsonPrimitive.content },
            "the submitted method order is never re-sorted",
        )
        assertTrue(rules[0].jsonObject.getValue("exposeHeaders").jsonArray.isEmpty(), "an explicit empty array is sent, never dropped")
        assertEquals(3600, rules[0].jsonObject.getValue("maxAgeSeconds").jsonPrimitive.long)
        assertFalse(rules[1].jsonObject.containsKey("id"), "an absent optional field is omitted, not invented")
        assertFalse(rules[1].jsonObject.containsKey("maxAgeSeconds"))
        assertTrue(rules[1].jsonObject.getValue("allowed").jsonObject.getValue("headers").jsonArray.isEmpty())
        assertEquals(1, requests.writes().size)

        requests.clear()
        val lifecycle = client.putLifecycle(
            target,
            listOf(
                R2LifecycleRule(id = "disabled", enabled = false, conditions = R2LifecycleConditions(prefix = "")),
                R2LifecycleRule(id = "enabled", enabled = true, conditions = R2LifecycleConditions(prefix = "logs/")),
            ),
        )
        assertIs<ExecutionReceipt.Completed>(lifecycle)
        val rows = requests.mutation().bodyJson().getValue("rules").jsonArray
        assertEquals(listOf("disabled", "enabled"), rows.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertFalse(rows[0].jsonObject.getValue("enabled").jsonPrimitive.boolean, "enabled=false must be sent explicitly")
        assertTrue(rows[1].jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertEquals("", rows[0].jsonObject.getValue("conditions").jsonObject.getValue("prefix").jsonPrimitive.content)
    }

    @Test
    fun lifecycleAndLockUseDifferentAgeFields() = runTest {
        respond = defaultResponder("[]")
        val client = client()

        client.putLifecycle(
            target,
            listOf(
                R2LifecycleRule(
                    id = "age",
                    enabled = true,
                    conditions = R2LifecycleConditions(""),
                    deleteObjectsTransition = R2LifecycleCondition.Age(maxAge = 86400),
                    abortMultipartUploadsTransition = R2LifecycleCondition.Age(maxAge = 3600),
                    storageClassTransitions =
                        listOf(
                            R2LifecycleStorageTransition(
                                R2LifecycleCondition.Date("2026-01-01T00:00:00Z"),
                                "InfrequentAccess",
                            ),
                        ),
                ),
            ),
        )
        val rule = requests.mutation().bodyJson().getValue("rules").jsonArray.single().jsonObject
        val delete = rule.getValue("deleteObjectsTransition").jsonObject.getValue("condition").jsonObject
        assertEquals("Age", delete.getValue("type").jsonPrimitive.content)
        assertEquals(86400, delete.getValue("maxAge").jsonPrimitive.long)
        assertFalse(delete.containsKey("maxAgeSeconds"), "the lifecycle age field is maxAge")
        assertEquals(
            3600,
            rule.getValue("abortMultipartUploadsTransition").jsonObject.getValue("condition").jsonObject.getValue("maxAge").jsonPrimitive.long,
        )
        val transition = rule.getValue("storageClassTransitions").jsonArray.single().jsonObject
        assertEquals("Date", transition.getValue("condition").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("2026-01-01T00:00:00Z", transition.getValue("condition").jsonObject.getValue("date").jsonPrimitive.content)
        assertEquals("InfrequentAccess", transition.getValue("storageClass").jsonPrimitive.content)

        requests.clear()
        client.putLock(
            target,
            listOf(
                R2LockRule(id = "age", enabled = true, prefix = "", condition = R2LockCondition.Age(maxAgeSeconds = 100)),
                R2LockRule(id = "date", enabled = true, prefix = "w/", condition = R2LockCondition.Date("2027-02-03T04:05:06Z")),
                R2LockRule(id = "forever", enabled = true, prefix = "", condition = R2LockCondition.Indefinite),
            ),
        )
        val lockRules = requests.mutation().bodyJson().getValue("rules").jsonArray.map { it.jsonObject }
        val age = lockRules[0].getValue("condition").jsonObject
        assertEquals(100, age.getValue("maxAgeSeconds").jsonPrimitive.long)
        assertFalse(age.containsKey("maxAge"), "the lock age field is maxAgeSeconds")
        assertEquals("2027-02-03T04:05:06Z", lockRules[1].getValue("condition").jsonObject.getValue("date").jsonPrimitive.content)
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("Indefinite"))), lockRules[2].getValue("condition").jsonObject)

        // A duration or date the provider cannot represent is refused before the single send.
        requests.clear()
        assertFailsWith<CloudFailure> {
            client.putLock(target, listOf(R2LockRule("big", true, "", R2LockCondition.Age(Long.MAX_VALUE))))
        }
        assertFailsWith<CloudFailure> { client.putLock(target, listOf(R2LockRule("zero", true, "", R2LockCondition.Age(0)))) }
        assertFailsWith<CloudFailure> {
            client.putLock(target, listOf(R2LockRule("offset", true, "", R2LockCondition.Date("2026-01-01T00:00:00+08:00"))))
        }
        assertFailsWith<CloudFailure> {
            client.putLock(target, listOf(R2LockRule("bad", true, "", R2LockCondition.Date("2026-01-01"))))
        }
        assertFailsWith<CloudFailure> {
            client.putLock(target, listOf(R2LockRule("calendar", true, "", R2LockCondition.Date("2026-02-30T00:00:00Z"))))
        }
        assertFailsWith<CloudFailure> {
            client.putLifecycle(target, listOf(R2LifecycleRule("zero", true, R2LifecycleConditions(""), deleteObjectsTransition = R2LifecycleCondition.Age(0))))
        }
        assertFailsWith<CloudFailure> {
            client.putLifecycle(
                target,
                listOf(
                    R2LifecycleRule(
                        id = "class",
                        enabled = true,
                        conditions = R2LifecycleConditions(""),
                        storageClassTransitions = listOf(R2LifecycleStorageTransition(R2LifecycleCondition.Age(10), "Glacier")),
                    ),
                ),
            )
        }
        assertFailsWith<CloudFailure> {
            client.putCors(target, listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example")), maxAgeSeconds = 86401)))
        }
        assertFailsWith<CloudFailure> {
            client.putCors(target, listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("TRACE"), listOf("https://a.example")))))
        }
        assertFailsWith<CloudFailure> {
            client.putLifecycle(target, listOf(R2LifecycleRule("dup", true, R2LifecycleConditions("")), R2LifecycleRule("dup", true, R2LifecycleConditions(""))))
        }
        assertFailsWith<CloudFailure> { client.putLock(target, listOf(R2LockRule("", true, "", R2LockCondition.Indefinite))) }
        assertTrue(requests.writes().isEmpty(), "no invalid rule set may reach the provider")
    }

    @Test
    fun nonDefaultLocalUploadsNeverCallsTransport() = runTest {
        respond = defaultResponder("[]")
        val eu = R2WriteTarget(account, "my-bucket", "eu")
        val client = client()

        val read = assertFailsWith<CloudFailure> { client.getLocalUploads(eu) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, read.code)
        val write = assertFailsWith<CloudFailure> { client.putLocalUploads(eu, true) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, write.code)
        assertEquals(0, requests.size, "a non-default local-uploads target never reaches the provider, not even a fallback read of the default bucket")

        respond = { request ->
            when {
                request.method != "GET" -> ok()
                request.path.endsWith("/local-uploads") -> ok("""{"enabled":false}""")
                else -> bucketRow()
            }
        }
        val document = client.getLocalUploads(target)
        assertEquals(R2ConfigurationKind.LOCAL_UPLOADS, document.kind)
        assertTrue(document.editable)
        assertEquals(JsonPrimitive(false), document.raw.getValue("enabled"))
        assertFalse(requests.single().headers.containsKey("cf-r2-jurisdiction"), "local uploads declares no jurisdiction header")

        requests.clear()
        assertIs<ExecutionReceipt.Completed>(client.putLocalUploads(target, false))
        val put = requests.mutation()
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/local-uploads", put.path)
        assertFalse(put.headers.containsKey("cf-r2-jurisdiction"))
        assertEquals(JsonPrimitive(false), put.bodyJson().getValue("enabled"), "enabled=false must be sent explicitly")
    }

    @Test
    fun unknownNestedRuleAndOversizeDocumentRemainReadOnly() = runTest {
        val client = client()

        respond = { request ->
            when {
                request.method != "GET" -> ok()
                request.path.endsWith("/cors") ->
                    rulesRow("""[{"id":"x","allowed":{"methods":["GET"],"origins":["https://a.example"],"future_flag":true},"lifecycle_note":"keep me"}]""")
                else -> bucketRow()
            }
        }
        val unknown = client.getCors(target)
        assertFalse(unknown.editable)
        assertNotNull(unknown.readOnlyReason)
        assertEquals(
            JsonPrimitive("keep me"),
            unknown.raw.getValue("rules").jsonArray.single().jsonObject.getValue("lifecycle_note"),
            "an unknown provider field is preserved verbatim",
        )
        requests.clear()
        assertFailsWith<CloudFailure> { client.putCors(target, oneCorsRule) }
        assertTrue(requests.writes().isEmpty(), "a document with unknown nested content can never be replaced by a guessed payload")

        requests.clear()
        respond = { request ->
            when {
                request.method != "GET" -> ok()
                request.path.endsWith("/lock") -> rulesRow("""[{"id":"l","enabled":true,"condition":{"type":"Age","maxAgeSeconds":100},"retention_extra":{}}]""")
                else -> bucketRow()
            }
        }
        assertFalse(client.getLock(target).editable, "an unknown nested field is not editable even when every known field looks normal")

        requests.clear()
        val many = (1..101).joinToString(",") { """{"id":"r$it","enabled":true,"conditions":{"prefix":""}}""" }
        respond = { request ->
            when {
                request.method != "GET" -> ok()
                request.path.endsWith("/lifecycle") -> rulesRow("[$many]")
                else -> bucketRow()
            }
        }
        assertFalse(client.getLifecycle(target).editable, "more than 100 rules stays readable but never editable")

        requests.clear()
        val huge = "x".repeat(R2_MAX_CONFIGURATION_DOCUMENT_BYTES)
        respond = { request ->
            when {
                request.method != "GET" -> ok()
                request.path.endsWith("/lifecycle") -> rulesRow("""[{"id":"big","enabled":true,"conditions":{"prefix":"$huge"}}]""")
                else -> bucketRow()
            }
        }
        val oversize = client.getLifecycle(target)
        assertFalse(oversize.editable)
        assertTrue(oversize.raw.toString().encodeToByteArray().size > R2_MAX_CONFIGURATION_DOCUMENT_BYTES)
        assertTrue(oversize.raw.toString().contains(huge), "an oversized document is kept verbatim, never truncated")
    }

    @Test
    fun everyB1WriteProvesTheBucketAndSendsOnceUnderTheSameJurisdiction() = runTest {
        respond = defaultResponder("[]")
        val eu = R2WriteTarget(account, "my-bucket", "eu")
        val events = mutableListOf<String>()
        var writesBeforeTheFinalGuard = -1
        val client = client(beforeMutation = {
            events += "guard"
            writesBeforeTheFinalGuard = requests.writes().size
        })
        val receipt = client.writeConfiguration(R2ConfigurationWrite.PutCors(eu, oneCorsRule)) { document ->
            events += "beforeSend"
            assertNotNull(document)
            assertTrue(document.editable)
            assertEquals(eu, document.target)
            assertEquals(R2ConfigurationKind.CORS, document.kind)
        }

        assertIs<ExecutionReceipt.Completed>(receipt)
        assertEquals(listOf("beforeSend", "guard"), events)
        assertEquals(0, writesBeforeTheFinalGuard, "the final guard runs before the single write")
        assertEquals(listOf("GET", "GET", "PUT"), requests.map { it.method })
        assertEquals("/accounts/account-one/r2/buckets/my-bucket", requests[0].path)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/cors", requests[1].path)
        assertEquals("/accounts/account-one/r2/buckets/my-bucket/cors", requests[2].path)
        assertTrue(
            requests.all { it.headers["cf-r2-jurisdiction"] == "eu" },
            "the bucket proof, the snapshot and the write share one controlled jurisdiction",
        )
    }

    @Test
    fun aMissingOrUnreadableSnapshotIsNeverTreatedAsAnEmptyConfiguration() = runTest {
        respond = { HttpResponse(404, """{"success":false,"errors":[],"messages":[],"result":null}""") }
        val client = client()
        val failure = assertFailsWith<CloudFailure> { client.getCors(target) }
        assertEquals(CloudFailureCode.NOT_FOUND, failure.code)
        assertFailsWith<CloudFailure> { client.putCors(target, oneCorsRule) }
        assertTrue(requests.writes().isEmpty(), "a 404 is not an empty rule set to overwrite")

        requests.clear()
        respond = { ok("""{"rules":"not-an-array"}""") }
        assertFailsWith<CloudFailure> { client.getLock(target) }

        requests.clear()
        respond = { ok("{}") }
        val unstructured = client.getLifecycle(target)
        assertFalse(unstructured.editable, "a result without the expected member is unreadable, not empty")
        assertFailsWith<CloudFailure> { client.putLifecycle(target, listOf(R2LifecycleRule("r", true, R2LifecycleConditions("")))) }
        assertTrue(requests.writes().isEmpty())
    }

    @Test
    fun foreignAccountsBadJurisdictionsAndIllegalNamesAreRefusedBeforeAnySend() = runTest {
        respond = defaultResponder("[]")
        val client = client()
        assertFailsWith<CloudFailure> { client.getCors(R2WriteTarget(foreignAccount, "my-bucket")) }
        assertFailsWith<CloudFailure> { client.getCors(R2WriteTarget(account, "my-bucket", "mars")) }
        assertFailsWith<CloudFailure> { client.getCors(R2WriteTarget(account, "my-bucket", "")) }
        assertFailsWith<CloudFailure> { client.getCors(R2WriteTarget(account, "Bad_Name")) }
        assertFailsWith<CloudFailure> { client.getBucket(R2WriteTarget(foreignAccount, "my-bucket")) }
        assertFailsWith<CloudFailure> { client.putCors(R2WriteTarget(account, "my-bucket", "MARS"), oneCorsRule) }
        assertFailsWith<CloudFailure> {
            client.putLock(
                R2WriteTarget(account, "my-bucket"),
                listOf(R2LockRule("r", true, "", R2LockCondition.Indefinite), R2LockRule("r", true, "", R2LockCondition.Indefinite)),
            )
        }
        assertEquals(0, requests.size)
    }

    @Test
    fun anOversizedOrTooLargeTargetBodyIsRefusedBeforeTheSingleSend() = runTest {
        respond = defaultResponder("[]")
        val client = client()
        val tooMany = (1..101).map { R2LifecycleRule("r$it", true, R2LifecycleConditions("")) }
        assertFailsWith<CloudFailure> { client.putLifecycle(target, tooMany) }
        val huge = listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example/" + "x".repeat(R2_MAX_CONFIGURATION_DOCUMENT_BYTES)))))
        assertFailsWith<CloudFailure> { client.putCors(target, huge) }
        assertTrue(requests.writes().isEmpty(), "an oversized target body is refused before anything is sent")
    }

    @Test
    fun anUncertainOutcomeAfterTheSingleSendIsReportedAndNeverReplayed() = runTest {
        val healthy = defaultResponder("[]")
        respond = { request -> if (request.method == "PUT") HttpResponse(500, "") else healthy(request) }
        assertIs<ExecutionReceipt.Unknown>(client().putCors(target, oneCorsRule))
        assertEquals(1, requests.writes().size, "an uncertain write is never replayed")
    }

    @Test
    fun aCompletedWriteIsOneReceiptAndNeverAPostWriteRead() = runTest {
        respond = defaultResponder("[]")
        assertIs<ExecutionReceipt.Completed>(client().putCors(target, oneCorsRule))
        assertEquals(1, requests.writes().size, "the write is one send; the same-lock read-back belongs to the controller")
    }

    @Test
    fun anAcceptedStatusIsAReceiptAndNeverADocumentRead() = runTest {
        val healthy = defaultResponder("[]")
        respond = { request ->
            if (request.method == "PUT") HttpResponse(202, """{"success":true,"errors":[],"messages":[],"result":null}""") else healthy(request)
        }
        assertIs<ExecutionReceipt.Accepted>(client().putCors(target, oneCorsRule))
        assertEquals(1, requests.writes().size, "a PUT response is a receipt, not a configuration document")
    }

    @Test
    fun theBucketProofComparesTheEchoedNameWithTheRequestedTarget() = runTest {
        respond = { request -> if (request.method == "GET") bucketRow("another-bucket") else ok() }
        val failure = assertFailsWith<CloudFailure> { client().getBucket(target) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
        assertFailsWith<CloudFailure> { client().putCors(target, oneCorsRule) }
        assertTrue(requests.writes().isEmpty())
    }

    @Test
    fun readsKeepTheProviderEnvelopeVerbatimForTheEditableKinds() = runTest {
        respond = defaultResponder("""[{"id":"keep","allowed":{"methods":["GET"],"origins":["https://a.example"]},"exposeHeaders":[]}]""")
        val document = client().getCors(target)
        assertTrue(document.editable)
        val rule = document.raw.getValue("rules").jsonArray.single().jsonObject
        assertEquals("keep", rule.getValue("id").jsonPrimitive.content)
        assertTrue(rule.getValue("exposeHeaders").jsonArray.isEmpty())
        assertEquals(R2ConfigurationKind.CORS, document.kind)
    }

    @Test
    fun aLeapSecondOrInvalidTimeIsRefusedBeforeAnyRequestOrPreview() = runTest {
        respond = defaultResponder("[]")
        val client = client()
        // The client writes only the unambiguous whole-second 00..59 subset; RFC3339 also allows a
        // `:60` leap second at a real leap-second instant, and this app deliberately does not
        // maintain a leap-second table, so that value is not written back by this app.
        assertFailsWith<CloudFailure> {
            client.putLock(target, listOf(R2LockRule("k", true, "", R2LockCondition.Date("2026-06-30T23:59:60Z"))))
        }
        assertFailsWith<CloudFailure> {
            client.putLifecycle(
                target,
                listOf(R2LifecycleRule("l", true, R2LifecycleConditions(""), deleteObjectsTransition = R2LifecycleCondition.Date("2026-06-30T23:59:60Z"))),
            )
        }
        assertFailsWith<CloudFailure> {
            client.putLifecycle(
                target,
                listOf(
                    R2LifecycleRule(
                        "l",
                        true,
                        R2LifecycleConditions(""),
                        storageClassTransitions =
                            listOf(R2LifecycleStorageTransition(R2LifecycleCondition.Date("2026-01-01T12:34:61Z"), "InfrequentAccess")),
                    ),
                ),
            )
        }
        assertEquals(0, requests.size, "an unrepresentable timestamp is refused before any HTTP, not read, previewed or sent")

        var previewed = false
        assertFailsWith<CloudFailure> {
            client.writeConfiguration(R2ConfigurationWrite.PutLock(target, listOf(R2LockRule("k", true, "", R2LockCondition.Date("2026-01-01T24:00:00Z"))))) { previewed = true }
        }
        assertFalse(previewed, "a value outside the supported subset must not reach a preview")
        assertEquals(0, requests.size)
    }
}
