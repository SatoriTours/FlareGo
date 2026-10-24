package com.flarego

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import com.flarego.ui.FlareGoApp
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Batch C Task 3, native: the real ViewModel + `FlareGoApp` + Compose + the same runtime/Authorized
 * sessions/wire over a synthetic, stateful transport.
 *
 * Only the provider is simulated. The tests click the real production controls: a storage-class
 * migration and an event-notification append use the reviewed preview + explicit confirm, while a
 * prefix delete must pass the three stages (risk → exact typed bucket name → separate final dialog);
 * the first two stages, a wrong typed name, a cancel and a repeated final click never send.
 */
class R2CManagementUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Volatile private var diagnosticPhase = "setup"
    private val diagnosticsDone = java.util.concurrent.CountDownLatch(1)

    @get:Rule val diagnostics = object : org.junit.rules.TestWatcher() {
        override fun starting(description: org.junit.runner.Description) {
            if (description.methodName != "anEventNotificationAppendUsesTheReviewedPreviewAndSendsOnlyNewRules") return
            Thread({
                repeat(2) { sample ->
                    if (diagnosticsDone.await(20, java.util.concurrent.TimeUnit.SECONDS)) return@Thread
                    val state = model?.r2Browser?.c?.state?.value
                    println("R2C_SAMPLE $sample phase=$diagnosticPhase open=${state?.open} loading=${state?.loading} preparing=${state?.preparing} writing=${state?.writing} ready=${state?.previewTitle != null}")
                    println("R2C_CALLS " + synchronized(requests) { requests.map { it.method + " " + it.path } })
                    Thread.getAllStackTraces().filterKeys { it.name == "main" || it.name.startsWith("Instr:") }.forEach { (thread, stack) ->
                        println("R2C_STACK ${thread.name} ${thread.state}\n" + stack.joinToString("\n"))
                    }
                }
            }, "r2-c-bounded-diagnostics").apply { isDaemon = true; start() }
        }
        override fun finished(description: org.junit.runner.Description) { diagnosticsDone.countDown() }
    }

    private val account = "c".repeat(32)
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val jobs = Collections.synchronizedList(mutableListOf<Map<String, String?>>())
    private val jobRules = Collections.synchronizedList(mutableListOf(
        """{"ruleId":"rule-1","actions":["PutObject"],"description":"existing","createdAt":"2026-10-22T00:00:00Z"}""",
    ))
    private var bucketNames = listOf("my-bucket")
    private var lastPrefix: String? = null
    private var lastMigration: String? = null

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request
            sink.write(byteArrayOf(1, 2, 3), 0, 3)
            sink.close()
            return HttpResponseHead(200, emptyMap(), 3)
        }
    }

    private fun ok(result: String) = """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun jobRow(id: String, status: String, prefix: String?): String =
        if (prefix == null) {
            """{"id":"$id","jobType":"storageClassMigration","status":"$status","startTime":"2026-10-22T00:00:00Z",""" +
                """"storageClassMigration":{"sourceStorageClass":"Standard","destinationStorageClass":"InfrequentAccess","migratedObjects":3,"skippedObjects":0,"failedObjects":0,"failedBytes":0,"skippedBytes":0}}"""
        } else {
            """{"id":"$id","jobType":"prefixDelete","status":"$status","startTime":"2026-10-22T00:00:00Z",""" +
                """"prefixDelete":{"prefix":"$prefix","deletedObjects":3}}"""
        }

    private fun install() {
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m = FlareGoViewModel(app, Dispatchers.IO, { HttpTransport { request ->
                requests += request
                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" ->
                        """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                    request.path == "/user/tokens/permission_groups" ->
                        """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.path == "/accounts/$account/tokens/verify" -> return@HttpTransport HttpResponse(401, "denied")
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Test"}"""
                    request.path.endsWith("/r2/buckets?per_page=50") ->
                        """{"buckets":[${bucketNames.joinToString(",") { """{"name":"$it","location":"weur","storage_class":"Standard"}""" }}]}"""
                    request.path.endsWith("/r2/metrics") -> "{}"
                    request.path.endsWith("/r2/buckets/my-bucket") -> """{"name":"my-bucket","storage_class":"Standard"}"""
                    request.path.contains("/r2/buckets/my-bucket/jobs") && request.method == "GET" -> {
                        if (request.path.contains("/jobs/")) {
                            val id = request.path.substringAfterLast('/')
                            val created = jobs.single { it["id"] == id }
                            jobRow(id, "ENQUEUED", created["prefix"])
                        } else {
                            """{"jobs":[${jobs.map { jobRow(it.getValue("id")!!, "COMPLETED", it["prefix"]) }.joinToString(",")}]}"""
                        }
                    }
                    request.path.endsWith("/r2/buckets/my-bucket/jobs") && request.method == "POST" -> {
                        val body = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject
                        val type = body.getValue("jobType").jsonPrimitive.content
                        lastPrefix = (body["prefix"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        lastMigration = type
                        val id = if (type == "prefixDelete") "job-p-${jobs.size + 1}" else "job-m-${jobs.size + 1}"
                        jobs += mapOf("id" to id, "prefix" to if (type == "prefixDelete") lastPrefix else null)
                        jobRow(id, "ENQUEUED", if (type == "prefixDelete") lastPrefix ?: "" else null)
                    }
                    request.path.endsWith("/event_notifications/r2/my-bucket/configuration") ->
                        """{"bucketName":"my-bucket","queues":[{"queueId":"queue-1","queueName":"events","rules":[${jobRules.joinToString(",")}]}]}"""
                    request.path.endsWith("/event_notifications/r2/my-bucket/configuration/queues/queue-1") -> {
                        val body = request.payload
                        if (request.method == "PUT" && body is HttpBody.Text) {
                            val submitted = Json.parseToJsonElement(body.value).jsonObject.getValue("rules").jsonArray
                            submitted.forEach { row ->
                                jobRules += kotlinx.serialization.json.buildJsonObject {
                                    row.jsonObject.forEach { (key, value) -> put(key, value) }
                                    put("ruleId", kotlinx.serialization.json.JsonPrimitive("rule-${jobRules.size + 1}"))
                                    put("createdAt", kotlinx.serialization.json.JsonPrimitive("2026-10-24T00:00:00Z"))
                                }.toString()
                            }
                            """{}"""
                        } else if (request.method == "DELETE") {
                            if (body is HttpBody.Text) {
                                val ids = Json.parseToJsonElement(body.value).jsonObject.getValue("ruleIds").jsonArray.map { it.jsonPrimitive.content }
                                jobRules.removeAll { Json.parseToJsonElement(it).jsonObject.getValue("ruleId").jsonPrimitive.content in ids }
                            } else jobRules.clear()
                            """{}"""
                        } else {
                            """{"queueId":"queue-1","queueName":"events","rules":[${jobRules.joinToString(",")}]}"""
                        }
                    }
                    else -> "[]"
                }
                HttpResponse(200, ok(result))
            } }, { transfers })
            model = m
            owner.put("r2", m)
            initial = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("R2 Test", account, "synthetic-c-token") }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = checkNotNull(model)
        compose.setContent { FlareGoApp(m.controller, { _, _, _ -> }, m::disconnect, {}, r2Browser = m.r2Browser) }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { !m.r2Browser.state.value.buckets.loading && synchronized(requests) { requests.any { it.path.endsWith("/r2/buckets?per_page=50") } } }
    }

    private val owner = androidx.lifecycle.ViewModelStore()

    private fun openC(tag: String) {
        // The C entry row sits above the bucket list, outside any scroll container.
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.open }
    }

    private fun writes() = synchronized(requests) { requests.filter { it.method == "POST" || it.method == "PUT" || it.method == "DELETE" } }

    @After
    fun cleanup() {
        compose.runOnIdle {
            model?.let { m ->
                m.r2Browser.c.hide()
                m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect)
            }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle { owner.clear() }
    }

    @Test
    fun aStorageClassMigrationUsesTheReviewedPreviewAndConfirmsOnce() {
        install()
        openC("r2-c-jobs")
        compose.onNodeWithTag("rc-job-kind-migration").performScrollTo().performClick()
        compose.onNodeWithTag("rc-job-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        assertEquals("a preview must not send", 0, writes().size)
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.c.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals("exactly one create for the migration", 1, writes().size)
            assertEquals("storageClassMigration", lastMigration)
            assertNull(lastPrefix)
            assertEquals(TaskState.ACCEPTED, model!!.r2Browser.writeTasks.value.maxByOrNull { it.updatedAtMs }!!.state)
        }
    }

    @Test
    fun aPrefixDeleteNeedsAllThreeStagesAndSendsExactlyOnce() {
        install()
        openC("r2-c-jobs")
        compose.onNodeWithTag("rc-job-prefix").performScrollTo().performTextReplacement("logs/")
        compose.onNodeWithTag("rc-job-prefix").performImeAction()
        assertEquals("IME Done never submits", 0, writes().size)
        compose.onNodeWithTag("rc-job-mpu-declared").performScrollTo().performClick()
        compose.onNodeWithTag("rc-job-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        compose.onNodeWithTag("rc-preview-begin-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.confirmation.stage == com.flarego.ui.r2.R2CStage.RISK }
        captureDialog("prefix-risk", "rc-confirm-scope")
        assertEquals("stage 1 sends nothing", 0, writes().size)
        // Confirm the scope, then type a wrong name: still nothing.
        compose.onNodeWithTag("rc-confirm-risk-ack").performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.confirmation.stage == com.flarego.ui.r2.R2CStage.TYPED_NAME }
        compose.onNodeWithTag("rc-confirm-typed-name").performTextReplacement("My-Bucket")
        assertTrue(!model!!.r2Browser.c.state.value.confirmation.typedMatches)
        assertEquals("a wrong typed name sends nothing", 0, writes().size)
        compose.onNodeWithTag("rc-confirm-typed-name").performTextReplacement("my-bucket")
        compose.onNodeWithTag("rc-confirm-typed-name").performImeAction()
        assertEquals(com.flarego.ui.r2.R2CStage.TYPED_NAME, model!!.r2Browser.c.state.value.confirmation.stage)
        assertEquals("IME Done in stage 2 never submits", 0, writes().size)
        captureDialog("prefix-typed", "rc-confirm-scope")
        compose.onNodeWithTag("rc-confirm-open-final").performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.confirmation.stage == com.flarego.ui.r2.R2CStage.FINAL }
        captureDialog("prefix-final", "rc-confirm-final-scope")
        compose.onNodeWithTag("rc-confirm-final-submit").performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.c.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals("exactly one prefix-delete create", 1, writes().size)
            assertEquals("prefixDelete", lastMigration)
            assertEquals("logs/", lastPrefix)
            assertEquals(TaskState.ACCEPTED, model!!.r2Browser.writeTasks.value.maxByOrNull { it.updatedAtMs }!!.state)
        }
        // A repeated click after the accepted task cannot reach a second write.
        compose.onNodeWithTag("rc-confirm-final-submit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, writes().size) }
    }

    @Test
    fun cancellingTheStagesNeverSends() {
        install()
        openC("r2-c-jobs")
        compose.onNodeWithTag("rc-job-prefix").performScrollTo().performTextReplacement("logs/")
        compose.onNodeWithTag("rc-job-prefix").performImeAction()
        compose.onNodeWithTag("rc-job-mpu-declared").performScrollTo().performClick()
        for (stage in 1..3) {
            compose.onNodeWithTag("rc-job-preview").performScrollTo().performClick()
            compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
            compose.onNodeWithTag("rc-preview-begin-confirm").performScrollTo().performClick()
            if (stage >= 2) {
                compose.onNodeWithTag("rc-confirm-risk-ack").performClick()
            }
            if (stage == 3) {
                compose.onNodeWithTag("rc-confirm-typed-name").performTextReplacement("my-bucket")
                compose.onNodeWithTag("rc-confirm-typed-name").performImeAction()
                compose.onNodeWithTag("rc-confirm-open-final").performClick()
            }
            compose.onNodeWithTag("rc-confirm-cancel-$stage").performClick()
            compose.waitUntil(10000) { model!!.r2Browser.c.state.value.confirmation.stage == com.flarego.ui.r2.R2CStage.NONE }
            assertEquals("cancel at stage $stage sends nothing", 0, writes().size)
            compose.onNodeWithTag("rc-confirm-final-submit").assertDoesNotExist()
        }
    }

    @Test
    fun anEventNotificationAppendUsesTheReviewedPreviewAndSendsOnlyNewRules() {
        install()
        openC("r2-c-notifications")
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.queues.isNotEmpty() }
        compose.onNodeWithTag("rc-notif-action-PutObject").performScrollTo().performClick()
        compose.onNodeWithTag("rc-notif-prefix").performScrollTo().performTextReplacement("images/")
        diagnosticPhase = "ime-done"
        compose.onNodeWithTag("rc-notif-prefix").performImeAction()
        diagnosticPhase = "preview-scroll"
        compose.onNodeWithTag("rc-notif-preview").performScrollTo()
        diagnosticPhase = "preview-click"
        compose.onNodeWithTag("rc-notif-preview").performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        assertEquals("a preview must not send", 0, writes().size)
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.c.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals("exactly one PUT", 1, writes().size)
            assertEquals("PUT", writes().single().method)
            val body = (writes().single().payload as HttpBody.Text).value
            assertTrue("only the new rule is sent: $body", body.contains("images/") && !body.contains("rule-1"))
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            assertTrue(model!!.r2Browser.c.state.value.notice.orEmpty().contains("成功"))
            assertEquals(2, jobRules.size)
            assertTrue(jobRules.first().contains("rule-1"))
        }
    }

    @Test fun selectedNotificationDeletionUsesThreeStagesAndPreservesOtherRules() {
        jobRules += """{"ruleId":"rule-2","actions":["DeleteObject"],"description":"keep","createdAt":"2026-10-22T00:00:00Z"}"""
        install(); openC("r2-c-notifications")
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.queues.isNotEmpty() }
        compose.onNodeWithTag("rc-rule-select-rule-1").performScrollTo().performClick()
        completeNotificationDeletion("selected")
        compose.runOnIdle {
            val write = writes().single()
            assertEquals("DELETE", write.method)
            assertEquals("{\"ruleIds\":[\"rule-1\"]}", (write.payload as HttpBody.Text).value)
            assertEquals(1, jobRules.size)
            assertTrue(jobRules.single().contains("rule-2"))
        }
    }

    @Test fun clearingAllNotificationRulesUsesAnExplicitAllIntentAndNoBody() {
        install(); openC("r2-c-notifications")
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.queues.isNotEmpty() }
        compose.onNodeWithTag("rc-notif-clear-all").performScrollTo().performClick()
        completeNotificationDeletion("all")
        compose.runOnIdle {
            assertEquals("DELETE", writes().single().method)
            assertTrue(writes().single().payload !is HttpBody.Text)
            assertTrue(jobRules.isEmpty())
        }
    }

    private fun completeNotificationDeletion(kind: String) {
        compose.onNodeWithTag("rc-notif-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        compose.onNodeWithTag("rc-preview-begin-confirm").performScrollTo().performClick()
        captureDialog("notification-$kind-risk", "rc-confirm-scope")
        assertEquals(0, writes().size)
        compose.onNodeWithTag("rc-confirm-risk-ack").performClick()
        compose.onNodeWithTag("rc-confirm-typed-name").performTextReplacement("my-bucket")
        compose.onNodeWithTag("rc-confirm-typed-name").performImeAction()
        captureDialog("notification-$kind-typed", "rc-confirm-scope")
        assertEquals(0, writes().size)
        assertEquals(com.flarego.ui.r2.R2CStage.TYPED_NAME, model!!.r2Browser.c.state.value.confirmation.stage)
        compose.onNodeWithTag("rc-confirm-open-final").performClick()
        captureDialog("notification-$kind-final", "rc-confirm-final-scope")
        compose.onNodeWithTag("rc-confirm-final-submit").assertIsDisplayed().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.c.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(1, writes().size)
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            assertTrue(model!!.r2Browser.c.state.value.notice.orEmpty().contains("成功"))
        }
        compose.onNodeWithTag("rc-confirm-final-submit").assertDoesNotExist()
    }

    @Test fun longLiteralPrefixCanBeReviewedAndCancelledWithoutAnyWrite() {
        install(); openC("r2-c-jobs")
        val prefix = "旧照片 中文 space/".repeat(14)
        compose.onNodeWithTag("rc-job-prefix").performScrollTo().performTextReplacement(prefix)
        compose.onNodeWithTag("rc-job-prefix").performImeAction()
        compose.onNodeWithTag("rc-job-mpu-declared").performScrollTo().performClick()
        compose.onNodeWithTag("rc-job-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        compose.onNodeWithTag("rc-preview-begin-confirm").performScrollTo().performClick()
        captureDialog("long-prefix-risk", "rc-confirm-scope")
        compose.onNodeWithTag("rc-confirm-risk-ack").performClick()
        compose.onNodeWithTag("rc-confirm-typed-name").performScrollTo().performTextReplacement("my-bucket")
        compose.onNodeWithTag("rc-confirm-typed-name").performImeAction()
        captureDialog("long-prefix-typed", "rc-confirm-scope", scrollScope = true)
        compose.onNodeWithTag("rc-confirm-open-final").assertIsDisplayed().performClick()
        captureDialog("long-prefix-final-top", "rc-confirm-final-scope")
        compose.onNodeWithText("点击“确认执行删除”后立即接纳本次写入；作业只处理启动时已存在的对象，不能撤销，也不表示之后桶为空或分片已清理。")
            .performScrollTo().assertIsDisplayed()
        captureDialog("long-prefix-final-bottom", "rc-confirm-final-scope", scrollScope = false, requireScopeVisible = false)
        compose.onNodeWithTag("rc-confirm-cancel-3").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, writes().size) }
    }

    @Test fun wholeBucketObjectClearRequiresAnExplicitChoiceAndThreeStages() {
        // An existing notification rule correctly blocks whole-bucket clearing in Core. This
        // positive UI fixture deliberately has no such conflict; no guard is relaxed.
        jobRules.clear()
        install(); openC("r2-c-jobs")
        compose.onNodeWithTag("rc-job-whole-bucket").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("rc-job-mpu-declared").performScrollTo().performClick()
        compose.onNodeWithTag("rc-job-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.previewTitle != null }
        compose.onNodeWithTag("rc-preview-begin-confirm").performScrollTo().performClick()
        assertEquals(com.flarego.ui.r2.R2CDeleteKind.BUCKET_CLEAR, model!!.r2Browser.c.state.value.confirmation.kind)
        assertEquals(0, writes().size)
        compose.onNodeWithTag("rc-confirm-risk-ack").performClick()
        compose.onNodeWithTag("rc-confirm-typed-name").performScrollTo().performTextReplacement("my-bucket")
        compose.onNodeWithTag("rc-confirm-typed-name").performImeAction()
        assertEquals(0, writes().size)
        compose.onNodeWithTag("rc-confirm-open-final").performClick()
        captureDialog("bucket-clear-final", "rc-confirm-final-scope")
        compose.onNodeWithTag("rc-confirm-final-submit").performClick()
        compose.waitUntil(15000) { !model!!.r2Browser.c.state.value.writing && model!!.r2Browser.writeTasks.value.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, writes().size)
            assertEquals("", lastPrefix)
            assertEquals(TaskState.ACCEPTED, model!!.r2Browser.writeTasks.value.single().state)
        }
    }

    @Test fun accountMetricsRemainAccessibleWithoutAnyBucketAndNeverCarryARegion() {
        bucketNames = emptyList(); install()
        compose.onNodeWithTag("r2-c-jobs").assertIsNotEnabled()
        compose.onNodeWithTag("r2-c-metrics").assertIsEnabled()
        openC("r2-c-metrics")
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.metrics != null }
        compose.onNodeWithText("账号级统计（非当前桶、非账单、不可用于结算）").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val metric = requests.single { it.path.endsWith("/r2/metrics") }
            assertEquals("GET", metric.method)
            assertTrue(metric.headers.keys.none { it.equals("cf-r2-jurisdiction", true) })
            assertEquals(0, writes().size)
        }
    }

    @Test fun multipleBucketsRequireAnExplicitChoiceAndRegionChangesUseTheNewScope() {
        bucketNames = listOf("other-bucket", "my-bucket"); install()
        compose.onNodeWithTag("r2-c-notifications").assertIsNotEnabled()
        compose.onNodeWithTag("r2-c-bucket-choice").performClick()
        compose.onNodeWithTag("r2-c-bucket-my-bucket").performClick()
        openC("r2-c-notifications")
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.queues.isNotEmpty() }
        compose.onNodeWithTag("rc-region-eu").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.c.state.value.jurisdiction == "eu" && !model!!.r2Browser.c.state.value.loading }
        compose.runOnIdle {
            val read = requests.last { it.path.endsWith("/event_notifications/r2/my-bucket/configuration") }
            assertEquals("eu", read.headers.entries.single { it.key.equals("cf-r2-jurisdiction", true) }.value)
            assertTrue(requests.none { it.path.contains("event_notifications/r2/other-bucket") })
            assertEquals(0, writes().size)
        }
    }

    private fun captureDialog(name: String, scopeTag: String, scrollScope: Boolean = false, requireScopeVisible: Boolean = true) {
        val node = compose.onNodeWithTag(scopeTag)
        if (scrollScope) node.performScrollTo()
        if (requireScopeVisible) node.assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        val directory = "/sdcard/Pictures/flarego-r2-c-20261024"
        // Use the existing native-evidence shell pipe, with no permission grant/adoption. Unlike
        // filesDir these synthetic-only screenshots survive AGP's post-run uninstall for host pull.
        automation.executeShellCommand("mkdir -p $directory").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val pipes = automation.executeShellCommandRw("dd of=$directory/$name-font$scale.png bs=8192")
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
            println("T3C_PNG $name-font$scale.png size=${bitmap.width}x${bitmap.height}")
        } finally {
            bitmap.recycle()
            pipes.forEach { runCatching { it.close() } }
        }
    }
}
