package com.flarego.ui.r2

import com.flarego.core.application.operations.*
import com.flarego.core.application.r2.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.AuthorizedR2NotificationSession
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real root, runtime, authority issuer, authorized session and wire; only HTTP is synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
class R2CBrowserRuntimeIntegrationTest {
    private val connection = Connection("c-browser", "fixture", "a".repeat(32))

    private class Store : OperationStore {
        val rows = linkedMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
    }

    private inner class Harness(scope: TestScope) {
        var context = R2BrowserContext(connection, 7, 3)
        val store = Store()
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<JsonObject>(rule("rule-old", "DeleteObject", "keep/"))
        var readGate: CompletableDeferred<Unit>? = null
        var authorityGate: CompletableDeferred<Unit>? = null
        var preflightGate: CompletableDeferred<Unit>? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var afterReadInvalid = false
        var authorityObserved = false
        var mutations = 0
        private var ids = 0
        private val base = "/accounts/${connection.accountId}/event_notifications/r2/my-bucket/configuration"
        private fun envelope(result: JsonElement) = HttpResponse(200, buildJsonObject {
            put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
        }.toString())
        private fun queue() = buildJsonObject {
            put("queueId", "queue-1"); put("queueName", "events")
            put("rules", JsonArray(rules.toList()))
            if (afterReadInvalid && mutations > 0) put("unsupported", true)
        }
        private val gateway = CloudflareGateway(HttpTransport { request ->
            requests += request
            when {
                request.path == "/user/tokens/verify" -> {
                    authorityObserved = true
                    authorityGate?.await()
                    envelope(buildJsonObject { put("id", "self"); put("status", "active") })
                }
                request.path.endsWith("/permission_groups") -> envelope(buildJsonArray { add(buildJsonObject {
                    put("id", "admin"); put("name", "Workers R2 Storage Write")
                    put("scopes", buildJsonArray { add("com.cloudflare.api.account") })
                }) })
                request.path.endsWith("/self") -> envelope(buildJsonObject {
                    put("id", "self"); put("status", "active")
                    put("policies", buildJsonArray { add(buildJsonObject {
                        put("id", "p"); put("effect", "allow")
                        put("permission_groups", buildJsonArray { add(buildJsonObject { put("id", "admin") }) })
                        put("resources", buildJsonObject { put("com.cloudflare.api.account.${connection.accountId}", "*") })
                    }) })
                })
                request.path == "/accounts/${connection.accountId}" -> envelope(buildJsonObject { put("id", connection.accountId) })
                request.path == base && request.method == "GET" -> {
                    readGate?.await()
                    envelope(buildJsonObject { put("bucketName", "my-bucket"); put("queues", buildJsonArray { add(queue()) }) })
                }
                request.path == "$base/queues/queue-1" && request.method == "GET" -> {
                    readGate?.await()
                    if (authorityObserved && mutations == 0) preflightGate?.await()
                    envelope(queue())
                }
                request.path == "$base/queues/queue-1" && request.method == "PUT" -> {
                    mutations++
                    sendGate?.await()
                    val submitted = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject.getValue("rules").jsonArray
                    submitted.forEach { element -> rules += buildJsonObject {
                        element.jsonObject.forEach { (key, value) -> put(key, value) }
                        put("ruleId", "rule-new-${rules.size}"); put("createdAt", "2026-10-24T00:00:00Z")
                    } }
                    HttpResponse(200, "{\"success\":true}")
                }
                request.path == "$base/queues/queue-1" && request.method == "DELETE" -> {
                    mutations++
                    sendGate?.await()
                    val payload = request.payload
                    if (payload is HttpBody.Text) {
                        val selected = Json.parseToJsonElement(payload.value).jsonObject.getValue("ruleIds").jsonArray.map { it.jsonPrimitive.content }
                        rules.removeAll { it.getValue("ruleId").jsonPrimitive.content in selected }
                    } else rules.clear()
                    HttpResponse(200, "{\"success\":true}")
                }
                request.method == "GET" && request.path.contains("/r2/buckets?") ->
                    envelope(buildJsonObject { put("buckets", buildJsonArray { add(buildJsonObject { put("name", "my-bucket") }) }) })
                else -> error("Unexpected synthetic request ${request.method} ${request.path}")
            }
        }, sleeper = {})
        private val issuer = R2TokenAuthorityIssuer(connection, 7, { context.credentialRevision }, OperationClock { 1_000 }, TokenKind.USER_OWNED)
        private val notifications = AuthorizedR2NotificationSession(connection, gateway, issuer)
        private val buckets = CloudflareR2Client(connection, gateway, object : TransferPort {
            override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead = error("unexpected download")
        })
        val runtime = R2WriteRuntime(scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "c-task-${++ids}" },
            { R2LiveSelection(connection, context.credentialRevision, browser.confirmationEpoch, browser.liveCommand()) },
            { listOf(connection) }, { context.credentialRevision }, { buckets }, notificationSession = { notifications })
        val browser: R2Browser = R2Browser(scope.backgroundScope, { context }, runtime,
            notificationSession = { notifications }, session = { buckets })

        fun open() {
            browser.refreshContext()
            browser.onCBucketSelected("my-bucket")
            browser.c.open(R2CScreen.NOTIFICATIONS)
        }
        fun draft(prefix: String = "images/") {
            browser.c.onNotificationDraftChanged(R2CNotificationDraft(actions = setOf("PutObject"), prefix = prefix))
        }
    }

    private fun rule(id: String, action: String, prefix: String) = buildJsonObject {
        put("ruleId", id); put("actions", buildJsonArray { add(action) }); put("prefix", prefix)
        put("createdAt", "2026-10-22T00:00:00Z")
    }

    @Test fun preparingDoesNotInventReadyBeforeTheRootReadCompletes() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft()
        val gate = CompletableDeferred<Unit>(); h.readGate = gate
        assertTrue(h.browser.c.prepare()); runCurrent()
        assertNull(h.browser.state.value.preview)
        assertNull(h.browser.c.state.value.previewTitle, "A started prepare is not a ready preview")
        assertNull(h.browser.c.confirmReviewed())
        assertEquals(0, h.mutations)
        gate.complete(Unit); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertNotNull(h.browser.c.state.value.previewTitle)
    }

    @Test fun readyCarriesTheFrozenTypedDetailAndAppendSettlesOnTheCPage() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        val detail = assertNotNull(h.browser.c.state.value.previewDetail)
        assertTrue(detail.contains("queue-1") && detail.contains("images/"), detail)
        val job = assertNotNull(h.browser.c.confirmReviewed()); runCurrent(); job.join()
        assertEquals(1, h.mutations)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertEquals(listOf("rule-old", "rule-new-1"), h.rules.map { it.getValue("ruleId").jsonPrimitive.content })
        assertNull(h.browser.c.state.value.previewTitle)
        assertTrue(assertNotNull(h.browser.c.state.value.notice).contains("成功"))
        h.browser.c.refreshNotifications(); runCurrent()
        assertTrue(assertNotNull(h.browser.c.state.value.notice).contains("成功"), "reload must retain settlement")
    }

    @Test fun busyLastsUntilTheOriginalConfirmationJobFinishes() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        val gate = CompletableDeferred<Unit>(); h.authorityGate = gate
        val job = assertNotNull(h.browser.c.confirmReviewed()); runCurrent()
        assertFalse(job.isCompleted)
        assertTrue(h.browser.c.state.value.writing, "returning a Job is not completion")
        assertNull(h.browser.c.confirmReviewed(), "no duplicate confirmation while the original job is pending")
        assertEquals(0, h.mutations)
        gate.complete(Unit); runCurrent(); job.join()
        assertFalse(h.browser.c.state.value.writing)
        assertEquals(1, h.mutations)
    }

    @Test fun editorChangesRevokeTheRootPreviewNotOnlyTheDisplay() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.draft("other/")
        assertNull(h.browser.state.value.preview)
        assertNull(h.browser.c.confirmReviewed())
        assertEquals(0, h.mutations)
    }

    @Test fun incompleteOrHiddenCEditorNeverFallsBackToThePreparedCommand() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        h.browser.c.onNotificationDraftChanged(R2CNotificationDraft())
        assertNull(h.browser.liveCommand(), "incomplete active C editor must not resolve to cached command")
        h.draft(); h.browser.c.prepare(); runCurrent(); h.browser.c.hide()
        assertNull(h.browser.c.liveCommand())
        assertNull(h.browser.liveCommand(), "leaving C must withdraw the cached C command")
        assertNull(h.browser.confirmPreview())
        assertEquals(0, h.mutations)
    }

    @Test fun latePrepareAfterEditNeverPublishesReady() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft()
        val gate = CompletableDeferred<Unit>(); h.readGate = gate
        h.browser.c.prepare(); runCurrent(); h.draft("new/"); gate.complete(Unit); runCurrent()
        assertNull(h.browser.state.value.preview)
        assertNull(h.browser.c.state.value.previewTitle)
        assertEquals(0, h.mutations)
    }

    @Test fun suspendedFreshAuthorityAndFinalPreflightObserveRealRouteAndCredentialRevocation() = runTest {
        for (phase in listOf("authority", "preflight")) for (change in listOf("route", "credential")) {
            val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
            val gate = CompletableDeferred<Unit>()
            if (phase == "authority") h.authorityGate = gate else h.preflightGate = gate
            val job = assertNotNull(h.browser.c.confirmReviewed()); runCurrent()
            assertFalse(job.isCompleted, "$phase must actually suspend before revocation: ${h.store.rows.values}; calls=${h.requests.map { it.method + it.path }}")
            h.context = if (change == "route") h.context.copy(routeEpoch = 99) else h.context.copy(credentialRevision = 99)
            gate.complete(Unit); runCurrent(); job.join()
            assertEquals(0, h.mutations, "$phase/$change")
            assertTrue(job.isCompleted)
        }
    }

    @Test fun hidingAfterTransportAdmissionDoesNotCancelOrReplayTheWrite() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        val gate = CompletableDeferred<Unit>(); h.sendGate = gate
        val job = assertNotNull(h.browser.c.confirmReviewed()); runCurrent()
        assertEquals(1, h.mutations, "${h.store.rows.values}; calls=${h.requests.map { it.method + it.path }}"); assertFalse(job.isCompleted)
        h.browser.c.hide(); gate.complete(Unit); runCurrent(); job.join()
        assertEquals(1, h.mutations)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
    }

    @Test fun everyOldDialogCallbackIsBoundToItsOriginalHandle() = runTest {
        val h = Harness(this); h.open(); runCurrent()
        h.browser.c.onSelectionChanged(setOf("rule-old")); h.browser.c.prepare(); runCurrent()
        h.browser.c.beginDeletion(R2CDeleteKind.NOTIFICATION_SELECTED)
        val old = h.browser.c.state.value.confirmation.handle
        h.browser.c.acknowledgeRisk(old); h.browser.c.typeBucketName("my-bucket", old)
        assertTrue(h.browser.c.openFinal(old))
        h.browser.c.cancelConfirmation(old)
        // Identical payload, new dialog: payload equality is not surface identity.
        h.browser.c.prepare(); runCurrent(); h.browser.c.beginDeletion(R2CDeleteKind.NOTIFICATION_SELECTED)
        val fresh = h.browser.c.state.value.confirmation.handle
        assertNotEquals(old, fresh)
        h.browser.c.acknowledgeRisk(old)
        assertEquals(R2CStage.RISK, h.browser.c.state.value.confirmation.stage)
        h.browser.c.acknowledgeRisk(fresh)
        h.browser.c.typeBucketName("my-bucket", old)
        assertEquals("", h.browser.c.state.value.confirmation.typedBucketName)
        h.browser.c.typeBucketName("my-bucket", fresh)
        assertFalse(h.browser.c.openFinal(old))
        h.browser.c.cancelConfirmation(old)
        assertEquals(R2CStage.TYPED_NAME, h.browser.c.state.value.confirmation.stage)
        assertTrue(h.browser.c.openFinal(fresh))
        assertNull(h.browser.c.confirmFinal(old))
        assertEquals(0, h.mutations)
        val job = assertNotNull(h.browser.c.confirmFinal(fresh)); runCurrent(); job.join()
        assertEquals(1, h.mutations)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertNull(h.browser.c.confirmFinal(fresh))
        assertEquals(1, h.mutations)
    }

    @Test fun genericRootConfirmCannotBypassCDeletionAndWrongKindCannotOpenStages() = runTest {
        val h = Harness(this); h.open(); runCurrent()
        h.browser.c.onSelectionChanged(setOf("rule-old")); h.browser.c.prepare(); runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertNull(h.browser.confirmPreview())
        assertNull(h.browser.c.confirmReviewed())
        h.browser.c.beginDeletion(R2CDeleteKind.BUCKET_CLEAR)
        assertEquals(R2CStage.NONE, h.browser.c.state.value.confirmation.stage)
        h.browser.c.beginDeletion(R2CDeleteKind.NOTIFICATION_SELECTED)
        h.browser.c.acknowledgeRisk(); h.browser.c.typeBucketName("wrong-bucket")
        assertFalse(h.browser.c.openFinal())
        assertNull(h.browser.c.confirmFinal())
        assertEquals(0, h.mutations)
    }

    @Test fun unknownAfterWriteRemainsVisibleAndRefreshNeverReplays() = runTest {
        val h = Harness(this); h.open(); runCurrent(); h.draft(); h.browser.c.prepare(); runCurrent()
        h.afterReadInvalid = true
        val job = assertNotNull(h.browser.c.confirmReviewed()); runCurrent(); job.join()
        assertEquals(1, h.mutations)
        assertEquals(TaskState.UNKNOWN, h.store.rows.values.single().state)
        val notice = assertNotNull(h.browser.c.state.value.notice)
        assertTrue(notice.contains("只读") && notice.contains("不"), notice)
        h.browser.c.refreshNotifications(); runCurrent()
        assertEquals(notice, h.browser.c.state.value.notice)
        assertNull(h.browser.c.confirmReviewed())
        assertEquals(1, h.mutations)
    }
}
