package com.flarego.ui.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2WriteRuntime
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
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockRule
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
import com.flarego.core.ports.R2ConfigurationSession
import com.flarego.core.ports.R2Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bucket settings surface: real typed drafts and exact targets, explicit risk
 * acknowledgements, read-only documents that never publish a command, and the single
 * activity route that revokes a prepared configuration preview on any target, panel,
 * account or credential change. The screen (batch Task 3) only sees state and intents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2ConfigurationBrowserTest {
    private val connection = Connection("one", "fixture", "account-one")
    private var context = R2BrowserContext(connection, 1, 1)
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.toList()
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) { rows.clear() }
    }

    private val corsRule = R2CorsRule(allowed = com.flarego.core.model.R2CorsAllowed(listOf("GET"), listOf("https://a.example")))

    private fun corsJson(id: String = "one") = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"rules":[{"id":"$id","allowed":{"methods":["GET"],"origins":["https://a.example"],"headers":[]},"exposeHeaders":[]}]}""",
    ) as JsonObject

    private inner class ConfigurationSession : R2ConfigurationSession {
        val documents = mutableMapOf<String, JsonObject>()
        var editable = true
        var readOnlyReason: String? = null
        val writes = mutableListOf<R2ConfigurationWrite>()

        private fun key(t: R2WriteTarget, kind: R2ConfigurationKind) = "${t.jurisdiction}|${t.bucket}|${kind.name}"

        fun put(target: R2WriteTarget, kind: R2ConfigurationKind, json: String) {
            documents[key(target, kind)] = kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject
        }

        fun seed() {
            documents[key(target, R2ConfigurationKind.CORS)] = corsJson()
            documents[key(target, R2ConfigurationKind.LIFECYCLE)] =
                css("""{"rules":[{"id":"expire","enabled":true,"conditions":{"prefix":""},"storageClassTransitions":[]}]}""")
            documents[key(target, R2ConfigurationKind.LOCAL_UPLOADS)] = css("""{"enabled":true}""")
            documents[key(target, R2ConfigurationKind.LOCK)] = css("""{"rules":[]}""")
        }

        private fun css(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject

        private fun conditionJson(condition: R2LifecycleCondition): JsonObject = buildJsonObject {
            when (condition) {
                is R2LifecycleCondition.Age -> {
                    put("type", "Age"); put("maxAge", condition.maxAge)
                }
                is R2LifecycleCondition.Date -> {
                    put("type", "Date"); put("date", condition.utcDate)
                }
            }
        }

        private fun document(t: R2WriteTarget, kind: R2ConfigurationKind): R2ConfigurationDocument {
            val raw = documents[key(t, kind)] ?: css(if (kind == R2ConfigurationKind.LOCAL_UPLOADS) """{"enabled":false}""" else """{"rules":[]}""")
            return R2ConfigurationDocument(t, kind, raw, editable, if (editable) null else (readOnlyReason ?: "包含未知内容"))
        }

        override suspend fun getBucket(target: R2WriteTarget): R2BucketDocument =
            R2BucketDocument(target.bucket, target.account, buildJsonObject { put("name", target.bucket) })

        override suspend fun getCors(target: R2WriteTarget) = document(target, R2ConfigurationKind.CORS)
        override suspend fun getLifecycle(target: R2WriteTarget) = document(target, R2ConfigurationKind.LIFECYCLE)
        override suspend fun getLocalUploads(target: R2WriteTarget) = document(target, R2ConfigurationKind.LOCAL_UPLOADS)
        override suspend fun getLock(target: R2WriteTarget) = document(target, R2ConfigurationKind.LOCK)

        override suspend fun putCors(target: R2WriteTarget, rules: List<R2CorsRule>): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.PutCors(target, rules.toList())) {}
        override suspend fun deleteCors(target: R2WriteTarget): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.DeleteCors(target)) {}
        override suspend fun putLifecycle(target: R2WriteTarget, rules: List<R2LifecycleRule>): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.PutLifecycle(target, rules.toList())) {}
        override suspend fun putLocalUploads(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.PutLocalUploads(target, enabled)) {}
        override suspend fun putLock(target: R2WriteTarget, rules: List<R2LockRule>): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.PutLock(target, rules.toList())) {}
        override suspend fun deleteLock(target: R2WriteTarget): ExecutionReceipt =
            writeConfiguration(R2ConfigurationWrite.DeleteLock(target)) {}

        override suspend fun writeConfiguration(
            write: R2ConfigurationWrite,
            beforeSend: (R2ConfigurationDocument?) -> Unit,
        ): ExecutionReceipt {
            writes += write
            val kind = when (write) {
                is R2ConfigurationWrite.PutCors, is R2ConfigurationWrite.DeleteCors -> R2ConfigurationKind.CORS
                is R2ConfigurationWrite.PutLifecycle -> R2ConfigurationKind.LIFECYCLE
                is R2ConfigurationWrite.PutLocalUploads -> R2ConfigurationKind.LOCAL_UPLOADS
                is R2ConfigurationWrite.PutLock, is R2ConfigurationWrite.DeleteLock -> R2ConfigurationKind.LOCK
            }
            beforeSend(document(write.target, kind))
            when (write) {
                is R2ConfigurationWrite.PutLocalUploads ->
                    documents[key(write.target, kind)] = buildJsonObject { put("enabled", write.enabled) }
                is R2ConfigurationWrite.PutCors ->
                    documents[key(write.target, kind)] = buildJsonObject {
                        put("rules", buildJsonArray {
                            write.rules.forEach { rule ->
                                add(buildJsonObject {
                                    rule.id?.let { put("id", it) }
                                    put("allowed", buildJsonObject {
                                        put("methods", JsonArray(rule.allowed.methods.map(::JsonPrimitive)))
                                        put("origins", JsonArray(rule.allowed.origins.map(::JsonPrimitive)))
                                        put("headers", JsonArray(rule.allowed.headers.map(::JsonPrimitive)))
                                    })
                                    put("exposeHeaders", JsonArray(rule.exposeHeaders.map(::JsonPrimitive)))
                                })
                            }
                        })
                    }
                is R2ConfigurationWrite.PutLifecycle ->
                    documents[key(write.target, kind)] = buildJsonObject {
                        put("rules", buildJsonArray {
                            write.rules.forEach { rule ->
                                add(buildJsonObject {
                                    put("id", rule.id)
                                    put("enabled", rule.enabled)
                                    put("conditions", buildJsonObject { put("prefix", rule.conditions.prefix) })
                                    rule.deleteObjectsTransition?.let { transition ->
                                        put("deleteObjectsTransition", buildJsonObject { put("condition", conditionJson(transition)) })
                                    }
                                    rule.abortMultipartUploadsTransition?.let { transition ->
                                        put("abortMultipartUploadsTransition", buildJsonObject { put("condition", conditionJson(transition)) })
                                    }
                                    put("storageClassTransitions", buildJsonArray {
                                        rule.storageClassTransitions.forEach { transition ->
                                            add(buildJsonObject {
                                                put("condition", conditionJson(transition.condition))
                                                put("storageClass", transition.storageClass)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }
                is R2ConfigurationWrite.DeleteCors, is R2ConfigurationWrite.DeleteLock ->
                    documents.remove(key(write.target, kind))
                is R2ConfigurationWrite.PutLock -> Unit
            }
            return ExecutionReceipt.Completed()
        }
    }

    private inner class BucketSession : R2Session {
        override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> =
            CloudPage(listOf(R2BucketDocument("my-bucket", account, buildJsonObject { put("name", "my-bucket") })), null, 1)

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

    private inner class Harness(scope: TestScope) {
        var ids = 0
        var revision = 1L
        val store = Store()
        val configuration = ConfigurationSession().apply { seed() }
        val buckets = BucketSession()
        val runtime: R2WriteRuntime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { 1_000 }, OperationIds { "ui-${++ids}" },
            { R2LiveSelection(connection, revision, browser.confirmationEpoch, browser.liveCommand()) },
            { listOf(connection) }, { revision }, { buckets },
            configSession = { configuration },
        )
        val browser: R2Browser = R2Browser(scope.backgroundScope, { context }, runtime, { configuration }) { buckets }

        fun open(panel: R2ConfigurationPanel = R2ConfigurationPanel.CORS) {
            browser.refreshContext()
            browser.onConfigurationIntent(R2ConfigurationIntent.Open("my-bucket"))
            if (panel != R2ConfigurationPanel.CORS) browser.onConfigurationIntent(R2ConfigurationIntent.PanelSelected(panel))
        }

        fun acknowledge(fullReplacement: Boolean = true, destructive: Boolean = false, typedName: String = "") {
            browser.onConfigurationIntent(
                R2ConfigurationIntent.RisksChanged(R2ConfigurationRisks(fullReplacement, destructive)),
            )
            browser.onConfigurationIntent(R2ConfigurationIntent.TypedTargetNameChanged(typedName))
        }
    }

    @Test
    fun openingTheSettingsLoadsOnlyThatDocumentAndSeedsRealTypedDrafts() = runTest {
        val h = Harness(this)
        h.open()
        runCurrent()
        val state = h.browser.configuration.state.value
        assertTrue(state.open)
        assertFalse(state.loading)
        assertNull(state.error)
        assertTrue(state.editable)
        assertEquals(1, state.cors.size)
        assertEquals("GET", state.cors.single().methods)
        assertEquals("https://a.example", state.cors.single().origins)
        assertTrue(h.configuration.writes.isEmpty())
    }

    @Test
    fun previewRequiresTheTypedTargetNameAndBothRiskAcknowledgements() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.LOCK)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.AddLockRule)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LockIdChanged(0, "lock"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LockAgeSecondsChanged(0, "3600"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(fullReplacement = true, destructive = false)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "wrong")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        val preview = assertNotNull(h.browser.state.value.preview)
        assertTrue(preview.detail.impact.contains("锁"))
        val shown = assertNotNull(h.browser.configuration.state.value.preview)
        assertTrue(shown.requiresTypedTargetName)
        assertEquals("default/my-bucket", shown.target)
        assertTrue(h.configuration.writes.isEmpty())
    }

    @Test
    fun aReadOnlyDocumentNeverPublishesACommand() = runTest {
        val h = Harness(this)
        h.configuration.editable = false
        h.configuration.readOnlyReason = "配置包含应用尚未支持的字段或取值，为避免覆盖未知内容，仅可查看"
        h.open()
        runCurrent()
        val state = h.browser.configuration.state.value
        assertFalse(state.editable)
        assertNotNull(state.readOnlyReason)
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertFalse(h.browser.configuration.state.value.canPreview)
        assertTrue(h.configuration.writes.isEmpty())
    }

    @Test
    fun switchingPanelJurisdictionOrBucketRevokesThePreparedConfigurationPreview() = runTest {
        for (change in listOf("panel", "jurisdiction", "bucket")) {
            val h = Harness(this)
            h.open()
            runCurrent()
            h.acknowledge()
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            runCurrent()
            assertNotNull(h.browser.state.value.preview, change)
            val epoch = h.browser.confirmationEpoch
            when (change) {
                "panel" -> h.browser.onConfigurationIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LIFECYCLE))
                "jurisdiction" -> h.browser.onConfigurationIntent(R2ConfigurationIntent.JurisdictionChanged("eu"))
                "bucket" -> h.browser.onConfigurationIntent(R2ConfigurationIntent.BucketChanged("other-bucket"))
            }
            runCurrent()
            assertNull(h.browser.state.value.preview, change)
            assertNull(h.browser.configuration.state.value.preview, change)
            assertTrue(h.browser.confirmationEpoch > epoch, change)
            if (change == "bucket") {
                // The previous bucket's document is never reused for the newly typed name.
                assertFalse(h.browser.configuration.state.value.editable)
                assertNull(h.browser.configuration.state.value.rawDocumentText)
                assertTrue(h.browser.configuration.state.value.cors.isEmpty())
            }
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
            runCurrent()
            assertTrue(h.configuration.writes.isEmpty(), "$change must not send")
        }
    }

    @Test
    fun changingACredentialOrAccountDiscardsTheSettingsAndAnyPreview() = runTest {
        val h = Harness(this)
        h.open()
        runCurrent()
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        context = context.copy(credentialRevision = 2)
        h.revision = 2
        h.browser.refreshContext()
        runCurrent()
        assertFalse(h.browser.configuration.state.value.open)
        assertNull(h.browser.state.value.preview)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        assertTrue(h.configuration.writes.isEmpty())
    }

    @Test
    fun localUploadsFalseIsSentExplicitlyOnlyAfterTheFinalConfirmation() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.LOCAL_UPLOADS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LocalUploadsEnabledChanged(false))
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        assertTrue(h.configuration.writes.isEmpty(), "a preview never writes")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        val write = h.configuration.writes.single()
        assertEquals(false, (write as R2ConfigurationWrite.PutLocalUploads).enabled)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertNull(h.browser.state.value.preview)
    }

    @Test
    fun aFailedConfirmationDoesNotLeaveAnExecutablePreviewOrAStaleCommand() = runTest {
        val h = Harness(this)
        h.open()
        runCurrent()
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.revision = -1
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        assertTrue(h.configuration.writes.isEmpty())
        assertNull(h.browser.state.value.preview)
        assertFalse(h.browser.state.value.writing)
    }

    @Test
    fun cancelRevokesThePreviewAndAnInvalidNumericFieldIsReportedInsteadOfSilentlyOmitted() = runTest {
        val h = Harness(this)
        h.open(R2ConfigurationPanel.LOCK)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.AddLockRule)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LockIdChanged(0, "lock"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LockAgeSecondsChanged(0, "not-a-number"))
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertNotNull(h.browser.configuration.state.value.validationMessage)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LockAgeSecondsChanged(0, "3600"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CancelPreview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.configuration.writes.isEmpty())
    }

    @Test
    fun anOversizedOrUnknownConfigurationStillShowsItsOriginalTextForReadOnlyViewing() = runTest {
        val h = Harness(this)
        h.configuration.editable = false
        h.open()
        runCurrent()
        assertEquals(
            """{"rules":[{"id":"one","allowed":{"methods":["GET"],"origins":["https://a.example"],"headers":[]},"exposeHeaders":[]}]}""",
            h.browser.configuration.state.value.rawDocumentText,
        )
    }

    @Test
    fun noSettingsIntentExposesARawClientOrAnExecutablePlanId() = runTest {
        val h = Harness(this)
        h.open()
        runCurrent()
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        // The route owns the plan; the settings state only carries display text.
        assertNotNull(h.browser.state.value.preview?.planId)
        val shown = assertNotNull(h.browser.configuration.state.value.preview)
        assertTrue(shown.impact.isNotBlank())
        assertTrue(shown.concurrencyWarning.isNotBlank())
        // Confirming twice cannot send twice.
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        assertEquals(1, h.configuration.writes.size)
    }

    private val lifecycleWithDates = """
        {"rules":[
          {"id":"keep","enabled":true,"conditions":{"prefix":"logs/"},
           "deleteObjectsTransition":{"condition":{"type":"Date","date":"2026-01-02T03:04:05.123456789Z"}},
           "storageClassTransitions":[{"condition":{"type":"Date","date":"2026-02-03T04:05:06.5Z"},"storageClass":"InfrequentAccess"}]},
          {"id":"second","enabled":true,"conditions":{"prefix":""},
           "abortMultipartUploadsTransition":{"condition":{"type":"Age","maxAge":3600}}}
        ]}
    """.trimIndent()

    @Test
    fun dateConditionsSurviveAnUnrelatedEditAndKeepTheirExactTextAndOrder() = runTest {
        val h = Harness(this)
        h.configuration.put(target, R2ConfigurationKind.LIFECYCLE, lifecycleWithDates)
        h.open(R2ConfigurationPanel.LIFECYCLE)
        runCurrent()
        val seeded = h.browser.configuration.state.value.lifecycle
        assertEquals(2, seeded.size)
        assertEquals(R2LifecycleDeleteMode.DATE, seeded[0].deleteMode)
        assertEquals("2026-01-02T03:04:05.123456789Z", seeded[0].deleteDate)
        assertEquals(R2LifecycleConditionType.DATE, seeded[0].transitions.single().conditionType)
        assertEquals("2026-02-03T04:05:06.5Z", seeded[0].transitions.single().utcDate)
        assertEquals("3600", seeded[1].abortAgeSeconds)

        h.browser.onConfigurationIntent(R2ConfigurationIntent.LifecycleEnabledChanged(0, false))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LifecyclePrefixChanged(0, "logs/2026/"))
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        val write = h.configuration.writes.single() as R2ConfigurationWrite.PutLifecycle
        assertEquals(listOf("keep", "second"), write.rules.map { it.id })
        assertEquals(false, write.rules[0].enabled)
        assertEquals("logs/2026/", write.rules[0].conditions.prefix)
        assertEquals(R2LifecycleCondition.Date("2026-01-02T03:04:05.123456789Z"), write.rules[0].deleteObjectsTransition)
        assertEquals(
            listOf(R2LifecycleStorageTransition(R2LifecycleCondition.Date("2026-02-03T04:05:06.5Z"), "InfrequentAccess")),
            write.rules[0].storageClassTransitions,
        )
        assertEquals(R2LifecycleCondition.Age(3600), write.rules[1].abortMultipartUploadsTransition)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
    }

    @Test
    fun aNewDateConditionIsEditableAndAnInvalidDateIsRefusedWithoutFallingBackToAge() = runTest {
        val h = Harness(this)
        h.configuration.put(target, R2ConfigurationKind.LIFECYCLE, """{"rules":[]}""")
        h.open(R2ConfigurationPanel.LIFECYCLE)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.AddLifecycleRule)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LifecycleIdChanged(0, "new"))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.LifecycleDeleteModeChanged(0, R2LifecycleDeleteMode.DATE))
        h.browser.onConfigurationIntent(
            R2ConfigurationIntent.LifecycleDeleteDateChanged(0, "2026-05-06T07:08:09.123Z"),
        )
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        val sent = h.configuration.writes.single() as R2ConfigurationWrite.PutLifecycle
        assertEquals(R2LifecycleCondition.Date("2026-05-06T07:08:09.123Z"), sent.rules.single().deleteObjectsTransition)

        runCurrent()
        h.browser.onConfigurationIntent(
            R2ConfigurationIntent.LifecycleDeleteDateChanged(0, "2026-02-30T00:00:00Z"),
        )
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.configuration.state.value.validationMessage.orEmpty().isNotBlank())
        assertEquals(R2LifecycleDeleteMode.DATE, h.browser.configuration.state.value.lifecycle[0].deleteMode)
        assertEquals(1, h.configuration.writes.size, "an invalid date never becomes an Age write")
    }

    @Test
    fun existingRuleIdsAndKeysAreNeverRewrittenByTrimming() = runTest {
        val h = Harness(this)
        h.configuration.put(
            target,
            R2ConfigurationKind.CORS,
            """{"rules":[{"id":" cors ","allowed":{"methods":["GET"],"origins":["https://a.example"],"headers":[]},"exposeHeaders":[]}]}""",
        )
        h.open(R2ConfigurationPanel.CORS)
        runCurrent()
        assertEquals(" cors ", h.browser.configuration.state.value.cors.single().id)
        h.acknowledge()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        val cors = h.configuration.writes.single() as R2ConfigurationWrite.PutCors
        assertEquals(" cors ", cors.rules.single().id)

        val lifecycle = Harness(this)
        lifecycle.configuration.put(
            target,
            R2ConfigurationKind.LIFECYCLE,
            """{"rules":[{"id":" keep ","enabled":true,"conditions":{"prefix":" keep/ "},"storageClassTransitions":[]}]}""",
        )
        lifecycle.open(R2ConfigurationPanel.LIFECYCLE)
        runCurrent()
        assertEquals(" keep ", lifecycle.browser.configuration.state.value.lifecycle.single().id)
        lifecycle.browser.onConfigurationIntent(R2ConfigurationIntent.LifecycleEnabledChanged(0, false))
        lifecycle.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        lifecycle.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        lifecycle.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        val sent = lifecycle.configuration.writes.single() as R2ConfigurationWrite.PutLifecycle
        assertEquals(" keep ", sent.rules.single().id)
        assertEquals(" keep/ ", sent.rules.single().conditions.prefix)
    }

    @Test
    fun deletingAConfigurationIsAnExplicitDestructiveCommand() = runTest {
        for (panel in listOf(R2ConfigurationPanel.CORS, R2ConfigurationPanel.LOCK)) {
            val h = Harness(this)
            h.open(panel)
            runCurrent()
            h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteConfiguration)
            assertTrue(h.browser.configuration.state.value.deleteRequested, panel.name)
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            runCurrent()
            assertNull(h.browser.state.value.preview, "no acknowledgement, no delete preview")
            h.acknowledge(fullReplacement = true, destructive = false)
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            runCurrent()
            assertNull(h.browser.state.value.preview)
            h.acknowledge(fullReplacement = true, destructive = true, typedName = "wrong")
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            runCurrent()
            assertNull(h.browser.state.value.preview)
            h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
            runCurrent()
            val preview = assertNotNull(h.browser.state.value.preview)
            assertTrue(preview.detail.impact.contains("删除"), panel.name)
            assertTrue(h.configuration.writes.isEmpty(), "a delete preview never writes")
            h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
            runCurrent()
            val write = h.configuration.writes.single()
            when (panel) {
                R2ConfigurationPanel.CORS -> assertTrue(write is R2ConfigurationWrite.DeleteCors)
                R2ConfigurationPanel.LOCK -> assertTrue(write is R2ConfigurationWrite.DeleteLock)
                else -> error("unexpected panel")
            }
            assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        }
    }

    @Test
    fun deleteModeExistsOnlyForCorsAndLockAndCancelOrRetargetRevokesIt() = runTest {
        for (panel in listOf(R2ConfigurationPanel.LIFECYCLE, R2ConfigurationPanel.LOCAL_UPLOADS)) {
            val h = Harness(this)
            h.open(panel)
            runCurrent()
            h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteConfiguration)
            assertFalse(h.browser.configuration.state.value.deleteRequested, panel.name)
        }

        val h = Harness(this)
        h.open(R2ConfigurationPanel.CORS)
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteConfiguration)
        h.acknowledge(fullReplacement = true, destructive = true, typedName = "my-bucket")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview)
        val epoch = h.browser.confirmationEpoch
        h.browser.onConfigurationIntent(R2ConfigurationIntent.JurisdictionChanged("eu"))
        runCurrent()
        assertNull(h.browser.state.value.preview)
        assertTrue(h.browser.confirmationEpoch > epoch)
        assertEquals(0, h.configuration.writes.size)

        h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDeleteConfiguration)
        assertTrue(h.browser.configuration.state.value.deleteRequested)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.CancelDeleteConfiguration)
        assertFalse(h.browser.configuration.state.value.deleteRequested)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.browser.state.value.preview, "after cancelling delete the panel previews a normal PUT again")
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Confirm)
        runCurrent()
        assertTrue(h.configuration.writes.single() is R2ConfigurationWrite.PutCors)
    }
}
