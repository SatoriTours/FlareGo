package com.flarego

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.FlareGoApp
import com.flarego.ui.r2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 完整端到端架构集成测试：真实 FlareGoViewModel / Compose / R2BrowserHost / 真实 HTTP dispatch。
 * 验证 R2 存储桶配置从 Bucket List 按钮点击 -> 绑定目标 -> 加载真实规则 -> 增改删 -> 风险门禁 -> Preview -> Confirm 执行 -> 结果通知。
 * 覆盖：
 * 1. CORS PUT 编辑与整组替换
 * 2. CORS 真实 DELETE 模式、破坏性确认及精确桶名
 * 3. Lifecycle Date 条件 RFC 3339 毫秒保留与空前缀破坏性风险门禁
 * 4. Lock Indefinite 规则添加与无期限锁定风险说明
 * 5. Local Uploads 显式关闭 (false) 的真实 PUT 请求
 * 6. 非 default jurisdiction 对 Local Uploads 的前端保护与零请求拒绝
 * 7. Token 权限不足时写门禁的有效拦截与零写入
 * 8. 远端配置漂移后的安全拒绝 (Conflict)
 */
class R2ConfigurationManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private var model: FlareGoViewModel? = null
    private var initialConnections = emptySet<String>()
    private val account = "e".repeat(32)

    /**
     * Each test instance owns its own synthetic bucket identity.
     *
     * The operation store is application-scoped and persists tasks across view models, so a
     * fixed bucket name would let one run's UNKNOWN task block every later run through the
     * controller's unsettled-task gate. A distinct legal bucket name keeps this test's
     * records isolated without deleting anything the app owns globally.
     */
    private val bucket = "cfg-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())

    // 模拟远端存储桶的配置状态 (遵循 Cloudflare API 规范)
    @Volatile private var corsRulesJson: String = """[{"id":"initial-rule","allowed":{"methods":["GET"],"origins":["https://initial.com"],"headers":["*"]},"exposeHeaders":["ETag"],"maxAgeSeconds":1800}]"""
    @Volatile private var corsDeleted = false

    @Volatile private var lifecycleRulesJson: String = """[]"""
    @Volatile private var lockRulesJson: String = """[]"""
    @Volatile private var lockDeleted = false

    @Volatile private var localUploadsEnabled = true

    /** Number of CORS PUTs seen; the read-back probes only apply after a write actually happened. */
    @Volatile private var corsPutCount = 0

    /** When set (after a PUT), the independent CORS read-back answers this status instead of 200. */
    @Volatile private var corsReadBackStatus: Int = 200

    /** When set (after a PUT), the independent CORS read-back answers this rule document. */
    @Volatile private var corsReadBackRules: String? = null

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request; sink.write(byteArrayOf(1, 2, 3), 0, 3); sink.close()
            return HttpResponseHead(200, emptyMap(), 3)
        }
    }

    private fun install(
        allowWrites: Boolean = true,
        bucketLocation: String = "weur",
    ) {
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m = FlareGoViewModel(app, Dispatchers.IO, { HttpTransport { request ->
                requests += request
                if (request.path == "/accounts/$account/tokens/verify") return@HttpTransport HttpResponse(401, "denied")
                // A post-write read-back probe: the PUT already happened, the observation now fails
                // or contradicts it. The pre-send snapshot keeps answering the real state.
                if (request.method == "GET" && request.path.endsWith("/cors") && corsPutCount > 0 && corsReadBackStatus != 200) {
                    return@HttpTransport HttpResponse(corsReadBackStatus, """{"success":false,"errors":[],"messages":[],"result":null}""")
                }

                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" -> if (allowWrites)
                        """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                    else """{"id":"self","status":"active","policies":[]}"""
                    request.path == "/user/tokens/permission_groups" -> """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Config Test"}"""
                    request.path.contains("/r2/buckets?") -> {
                        """{"buckets":[{"name":"$bucket","location":"$bucketLocation","storage_class":"Standard"}]}"""
                    }
                    request.path.endsWith("/r2/buckets/$bucket") -> {
                        """{"name":"$bucket","location":"$bucketLocation","storage_class":"Standard"}"""
                    }
                    // CORS API: GET, PUT, DELETE
                    request.method == "GET" && request.path.endsWith("/cors") -> {
                        when {
                            corsPutCount > 0 && corsReadBackRules != null -> """{"rules":${corsReadBackRules!!}}"""
                            corsDeleted -> """{"rules":[]}"""
                            else -> """{"rules":$corsRulesJson}"""
                        }
                    }
                    request.method == "PUT" && request.path.endsWith("/cors") -> {
                        corsRulesJson = rulesArrayOf(request)
                        corsPutCount++
                        corsDeleted = false
                        """{"rules":$corsRulesJson}"""
                    }
                    request.method == "DELETE" && request.path.endsWith("/cors") -> {
                        corsDeleted = true
                        corsRulesJson = "[]"
                        """{}"""
                    }
                    // Lifecycle API: GET, PUT
                    request.method == "GET" && request.path.endsWith("/lifecycle") -> {
                        """{"rules":$lifecycleRulesJson}"""
                    }
                    request.method == "PUT" && request.path.endsWith("/lifecycle") -> {
                        lifecycleRulesJson = rulesArrayOf(request)
                        """{"rules":$lifecycleRulesJson}"""
                    }
                    // Lock API: GET, PUT, DELETE
                    request.method == "GET" && request.path.endsWith("/lock") -> {
                        if (lockDeleted) """{"rules":[]}""" else """{"rules":$lockRulesJson}"""
                    }
                    request.method == "PUT" && request.path.endsWith("/lock") -> {
                        lockRulesJson = rulesArrayOf(request)
                        lockDeleted = false
                        """{"rules":$lockRulesJson}"""
                    }
                    request.method == "DELETE" && request.path.endsWith("/lock") -> {
                        lockDeleted = true
                        lockRulesJson = "[]"
                        """{}"""
                    }
                    // Local uploads: GET, PUT (/local-uploads)
                    request.method == "GET" && request.path.endsWith("/local-uploads") -> {
                        """{"enabled":$localUploadsEnabled}"""
                    }
                    request.method == "PUT" && request.path.endsWith("/local-uploads") -> {
                        val bodyText = (request.payload as HttpBody.Text).value
                        val json = Json.parseToJsonElement(bodyText).jsonObject
                        localUploadsEnabled = json["enabled"]!!.jsonPrimitive.boolean
                        """{"enabled":$localUploadsEnabled}"""
                    }
                    else -> "{}"
                }
                HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")
            } }, { transfers })
            model = m; owner.put("r2", m)
            initialConnections = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("R2 Config Test", account, "synthetic-r2-token") }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = model!!
        compose.setContent { FlareGoApp(m.controller, { _, _, _ -> }, m::disconnect, {}, r2Browser = m.r2Browser) }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.buckets.rows.isNotEmpty() }
    }

    /** The exact provider rule array of one PUT body; the fake never re-wraps the request body. */
    private fun rulesArrayOf(request: HttpRequest): String {
        val body = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject
        return body.getValue("rules").jsonArray.toString()
    }

    private val configActions = setOf(
        "r2-put-bucket-cors-policy", "r2-delete-bucket-cors-policy",
        "r2-put-bucket-lifecycle-configuration",
        "r2-put-bucket-local-uploads-configuration",
        "r2-put-bucket-lock-configuration", "r2-delete-bucket-lock-configuration",
    )

    /** Only the tasks this test instance's synthetic bucket created; other rows are never touched. */
    private fun ownedTasks(): List<OperationTask> =
        model!!.r2Browser.writeTasks.value.filter {
            it.actionKey in configActions && it.resourceRef?.externalId?.contains(bucket) == true
        }

    /**
     * Waits for this test's own task to settle and returns it. A receipt alone is not success:
     * the controller only reports SUCCEEDED after its same-lock independent read-back matched.
     */
    private fun awaitOwnedTask(): OperationTask {
        compose.waitUntil(15000) {
            ownedTasks().any { it.state !in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.RECONCILING) }
        }
        return ownedTasks().maxByOrNull { it.updatedAtMs }!!
    }

    /** The independent read-back GET must be observed after the single write was sent. */
    private fun assertReadBackAfterWrite(method: String, suffix: String) {
        val writeIndex = requests.indexOfFirst { it.method == method && it.path.endsWith(suffix) }
        val readIndex = requests.indexOfLast { it.method == "GET" && it.path.endsWith(suffix) }
        assertTrue("expected one $method $suffix", writeIndex >= 0)
        assertTrue("expected an independent GET $suffix after the write", readIndex > writeIndex)
        assertEquals(1, requests.count { it.method == method && it.path.endsWith(suffix) })
    }

    private fun captureScreenshot(name: String) {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /sdcard/Pictures/flarego-r2-config-evidence").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/flarego-r2-config-evidence/$name.png bs=8192")
        val image = checkNotNull(automation.takeScreenshot())
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { image.recycle(); pipes.forEach { runCatching { it.close() } } }
    }

    @After fun tearDown() {
        compose.runOnIdle {
            model?.let { m ->
                m.controller.state.value.connections
                    .filter { it.id !in initialConnections }
                    .forEach(m::disconnect)
            }
        }
        compose.waitUntil(10000) {
            model?.controller?.state?.value?.connections?.none { it.id !in initialConnections } != false
        }
        compose.runOnIdle {
            owner.clear()
            model = null
        }
        requests.clear()
        corsPutCount = 0
        corsReadBackStatus = 200
        corsReadBackRules = null
        corsRulesJson = """[{"id":"initial-rule","allowed":{"methods":["GET"],"origins":["https://initial.com"],"headers":["*"]},"exposeHeaders":["ETag"],"maxAgeSeconds":1800}]"""
        corsDeleted = false
        lifecycleRulesJson = """[]"""
        lockRulesJson = """[]"""
        lockDeleted = false
        localUploadsEnabled = true
    }

    @Test
    fun realFlowOpenBucketConfigurationLoadsCorsAndAllowsAddingRuleAndPreviewAndConfirm() {
        install(allowWrites = true)

        // 1. 点击存储桶的「桶设置」按钮
        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().assertIsDisplayed().performClick()

        // 2. 等待配置加载完成，并显示当前配置的目标和地区
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-target-display").assertIsDisplayed().assertTextContains(bucket, substring = true)

        // 验证加载了远端初始 CORS 规则
        compose.onNodeWithTag("rc-cors-id-0").performScrollTo().assertTextContains("initial-rule", substring = true)
        compose.onNodeWithTag("rc-cors-methods-0").performScrollTo().assertTextContains("GET", substring = true)

        // 3. 增加一条新规则
        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-methods-1").performScrollTo().performTextReplacement("PUT,POST")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-origins-1").performScrollTo().performTextReplacement("https://app.example.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        // 4. 整组替换风险确认勾选（通过真实 UI 点击触发）
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(model!!.r2Browser.configuration.state.value.risks.fullReplacementAcknowledged)

        // 截图记录配置与风险确认状态
        captureScreenshot("cors-edit-ready")

        // 5. 点击生成写入预览
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()

        // 6. 等待预览呈现
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-preview-impact").performScrollTo().assertTextContains("CORS 规则集", substring = true)

        captureScreenshot("cors-preview-card")

        // 7. 确认执行写入
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()

        // 8. 等待写入完成
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        // 9. 回执不是成功：任务必须在同锁独立回读匹配后才是 SUCCEEDED
        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-put-bucket-cors-policy", task.actionKey)
        compose.runOnIdle {
            val putRequest = requests.single { it.method == "PUT" && it.path.endsWith("/cors") }
            val body = (putRequest.payload as HttpBody.Text).value
            assertTrue(body.contains("initial-rule"))
            assertTrue(body.contains("app.example.com"))
            assertReadBackAfterWrite("PUT", "/cors")
            assertNull(model!!.r2Browser.configuration.state.value.preview)
            assertNull(model!!.r2Browser.configuration.state.value.error)
        }
        // The panel shows the freshly read-back document, never the stale draft snapshot.
        compose.waitUntil(10000) {
            model!!.r2Browser.configuration.state.value.cors.any { it.origins.contains("app.example.com") }
        }

        // 10. 关闭配置界面，返回 Bucket 列表
        compose.onNodeWithTag("rc-close").performClick()
        compose.waitUntil(5000) { !model!!.r2Browser.configuration.state.value.open }
        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().assertExists()
    }

    @Test
    fun realFlowDeleteCorsConfigurationExecutesRealDeleteMethodWithDestructiveGate() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 点击「删除整个配置 (DELETE)」进入明确 DELETE模式
        compose.onNodeWithTag("rc-cors-request-delete").performScrollTo().performClick()
        compose.onNodeWithTag("rc-delete-mode-card").performScrollTo().assertIsDisplayed()

        // 确认破坏性风险
        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(model!!.r2Browser.configuration.state.value.risks.destructiveImpactAcknowledged)

        // 输入匹配的目标桶名
        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextReplacement(bucket)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        captureScreenshot("cors-delete-ready")

        // 点击生成删除预览
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()

        // 等待删除预览卡片（文案包含 删除该桶的全部 CORS 规则）
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-impact").performScrollTo().assertTextContains("删除该桶的全部 CORS 规则", substring = true)

        captureScreenshot("cors-delete-preview")

        // 确认删除
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        // 删除必须是真的 DELETE，且独立回读证明规则已不存在
        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-delete-bucket-cors-policy", task.actionKey)
        compose.runOnIdle {
            assertTrue(corsDeleted)
            assertReadBackAfterWrite("DELETE", "/cors")
            assertTrue(
                "DELETE must never be disguised as a PUT with an empty rule list",
                requests.none { it.method == "PUT" && it.path.endsWith("/cors") },
            )
        }
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.cors.isEmpty() }
    }

    @Test
    fun realFlowLifecycleRuleWithDateAndEmptyPrefixRequiresDestructiveRisk() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 切换到 Lifecycle 面板
        compose.onNodeWithTag("rc-panel-lifecycle").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-lifecycle-notice").performScrollTo().assertIsDisplayed()

        // 添加一条生命周期规则（初始 prefix 为空）
        compose.onNodeWithTag("rc-lifecycle-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-lifecycle-id-0").performScrollTo().performTextReplacement("log-expiry")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        // 验证空前缀警告展示
        compose.onNodeWithTag("rc-lifecycle-rule-prefix-warning-0").performScrollTo().assertIsDisplayed()

        // 选择 Date 模式并填入带毫秒的 RFC 3339 字符串
        compose.onNodeWithTag("rc-lifecycle-del-mode-0-DATE").performScrollTo().performClick()
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.lifecycle.firstOrNull()?.deleteMode == R2LifecycleDeleteMode.DATE }
        val rfc3339Date = "2026-12-31T23:59:59.123456789Z"
        compose.onNodeWithTag("rc-lifecycle-del-date-0").performScrollTo().performTextReplacement(rfc3339Date)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        // 勾选整组替换风险
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        // 空前缀规则为破坏性规则，未勾选破坏性风险或未输入桶名时不能预览
        compose.runOnIdle {
            assertFalse(model!!.r2Browser.configuration.state.value.canPreview)
        }

        // 勾选破坏性风险并输入桶名
        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextReplacement(bucket)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        captureScreenshot("lifecycle-edit-ready")

        // 可以预览
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()

        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        captureScreenshot("lifecycle-preview-card")

        // 确认写入
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        // 小数秒必须原样写入（不 trim、不截断、不重编码），且回读一致后才 SUCCEEDED
        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-put-bucket-lifecycle-configuration", task.actionKey)
        compose.runOnIdle {
            val putRequest = requests.single { it.method == "PUT" && it.path.endsWith("/lifecycle") }
            val body = (putRequest.payload as HttpBody.Text).value
            assertTrue("the exact provider date text must reach the wire: $body", body.contains("\"date\":\"$rfc3339Date\""))
            assertReadBackAfterWrite("PUT", "/lifecycle")
        }
    }

    @Test
    fun realFlowLockConfigurationPanelAddsIndefiniteRule() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 切换到 Lock 面板
        compose.onNodeWithTag("rc-panel-lock").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-lock-notice").performScrollTo().assertIsDisplayed()

        // 添加锁定规则
        compose.onNodeWithTag("rc-lock-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-lock-id-0").performScrollTo().performTextReplacement("immutability-lock")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-lock-prefix-0").performScrollTo().performTextReplacement("archive/")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        // 选择 Indefinite
        compose.onNodeWithTag("rc-lock-cond-0-INDEFINITE").performScrollTo().performClick()

        // 勾选整组替换风险（Lock PUT 是破坏性操作，必须确认破坏性并输入桶名）
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextReplacement(bucket)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        captureScreenshot("lock-edit-ready")

        // 预览与确认
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()

        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        captureScreenshot("lock-preview-card")

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-put-bucket-lock-configuration", task.actionKey)
        compose.runOnIdle {
            val putRequest = requests.single { it.method == "PUT" && it.path.endsWith("/lock") }
            val body = (putRequest.payload as HttpBody.Text).value
            assertTrue(body.contains("immutability-lock"))
            assertTrue(body.contains("archive/"))
            assertTrue(body.contains("\"type\":\"Indefinite\""))
            assertReadBackAfterWrite("PUT", "/lock")
        }
    }

    @Test
    fun realFlowLocalUploadsToggleSendsExplicitFalsePut() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 切换到 Local Uploads 面板
        compose.onNodeWithTag("rc-panel-local-uploads").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-local-uploads-notice").performScrollTo().assertIsDisplayed()

        // 初始状态为 true，切换开关为 false
        compose.onNodeWithTag("rc-local-uploads-switch").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(model!!.r2Browser.configuration.state.value.localUploadsEnabled)
        }

        // Local Uploads PUT 仍需整组替换确认
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        captureScreenshot("local-uploads-ready")

        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        captureScreenshot("local-uploads-preview")

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        // 验证真实 HTTP PUT 发出了 {"enabled":false}，且回读一致后才 SUCCEEDED
        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-put-bucket-local-uploads-configuration", task.actionKey)
        compose.runOnIdle {
            val putRequest = requests.single { it.method == "PUT" && it.path.endsWith("/local-uploads") }
            val json = Json.parseToJsonElement((putRequest.payload as HttpBody.Text).value).jsonObject
            assertEquals(false, json["enabled"]!!.jsonPrimitive.boolean)
            assertReadBackAfterWrite("PUT", "/local-uploads")
        }
    }

    @Test
    fun nonDefaultJurisdictionRejectsLocalUploadsWithoutNetworkCalls() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 展开目标设置，切换地区为 eu
        compose.onNodeWithTag("rc-target-toggle").performClick()
        compose.onNodeWithTag("rc-target-jurisdiction-eu").performScrollTo().performClick()
        compose.onNodeWithTag("rc-refresh").performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 切换到 Local Uploads 面板
        compose.onNodeWithTag("rc-panel-local-uploads").performScrollTo().performClick()

        // 提示该地区不支持，且 switch 不存在
        compose.onNodeWithTag("rc-local-uploads-unsupported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-local-uploads-switch").assertDoesNotExist()

        captureScreenshot("local-uploads-non-default-unsupported")

        // 验证完全没有任何关于 /local-uploads 且 jurisdiction 为 eu 的请求
        compose.runOnIdle {
            val localRequests = requests.filter { it.path.endsWith("/local-uploads") }
            assertTrue(localRequests.isEmpty())
        }
    }

    @Test
    fun permissionDeniedTokenPreventsWritesWithZeroMutations() {
        // 安装无写权限的 Token
        install(allowWrites = false)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 增加规则并勾选风险
        val initialCorsCount = model!!.r2Browser.configuration.state.value.cors.size
        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-methods-$initialCorsCount").performScrollTo().performTextReplacement("GET")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-origins-$initialCorsCount").performScrollTo().performTextReplacement("https://test.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        // 尝试生成预览与确认
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()

        // 等待写操作尝试结束
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        // 验证任务被安全拦截失败并呈现通知，且没有实际的 PUT /cors 请求发出
        captureScreenshot("permission-denied-error")
        compose.runOnIdle {
            val configWrite = Regex("/r2/buckets/[^/]+/(cors|lifecycle|local-uploads|lock)$")
            assertTrue(
                "无写权限 Token 绝不应发出任何配置写请求",
                requests.none { it.method != "GET" && configWrite.containsMatchIn(it.path) },
            )
            val task = ownedTasks().singleOrNull()
            assertNotNull(task)
            assertEquals(TaskState.FAILED, task!!.state)
            assertTrue(model!!.r2Browser.state.value.notice?.contains("配置写入未发送") == true)
        }
    }

    @Test
    fun remoteConfigurationDriftPreventsWriteOnStaleBaseline() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 增加规则并勾选风险
        val initialCorsCount = model!!.r2Browser.configuration.state.value.cors.size
        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-methods-$initialCorsCount").performScrollTo().performTextReplacement("GET")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-cors-origins-$initialCorsCount").performScrollTo().performTextReplacement("https://test.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()

        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }

        // 在确认前，模拟远端配置被其他人改动 (Drift)
        corsRulesJson = """[{"id":"drifted-rule-from-another-client","allowed":{"methods":["POST"],"origins":["https://other.com"],"headers":["*"]}}]"""

        // 执行确认
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        captureScreenshot("drift-detected-error")

        // 验证由于快照哈希不一致，写操作安全拒绝并记录 FAILED 任务，未完成整组覆盖
        compose.runOnIdle {
            val task = ownedTasks().singleOrNull()
            assertNotNull(task)
            assertEquals(TaskState.FAILED, task!!.state)
            assertTrue(model!!.r2Browser.state.value.notice?.contains("配置写入未发送") == true)
            assertTrue(
                "远端漂移必须在发送前拒绝，不能产生任何 PUT 或重放",
                requests.none { it.method == "PUT" && it.path.endsWith("/cors") },
            )
            assertTrue(
                "drift is refused before the single send, so only the preview reads happened",
                requests.count { it.method == "GET" && it.path.endsWith("/cors") } >= 1,
            )
        }
    }

    @Test
    fun realFlowDeleteLockConfigurationExecutesRealDeleteMethodWithDestructiveGate() {
        install(allowWrites = true)
        lockRulesJson = """[{"id":"existing-lock","enabled":true,"prefix":"archive/","condition":{"type":"Indefinite"}}]"""

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        compose.onNodeWithTag("rc-panel-lock").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-lock-id-0").performScrollTo().assertTextContains("existing-lock", substring = true)

        // 明确的 DELETE 模式：普通规则表单被移除，必须勾破坏性风险并输入精确桶名
        compose.onNodeWithTag("rc-lock-request-delete").performScrollTo().performClick()
        compose.onNodeWithTag("rc-delete-mode-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lock-add-rule").assertDoesNotExist()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model!!.r2Browser.configuration.state.value.risks.destructiveImpactAcknowledged) }
        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextReplacement(bucket)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        captureScreenshot("lock-delete-ready")

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-impact").performScrollTo()
            .assertTextContains("删除该桶的全部锁规则", substring = true)
        captureScreenshot("lock-delete-preview")

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-delete-bucket-lock-configuration", task.actionKey)
        compose.runOnIdle {
            assertTrue(lockDeleted)
            assertTrue(
                "the lock DELETE mode must send DELETE, never an empty PUT",
                requests.none { it.method == "PUT" && it.path.endsWith("/lock") },
            )
            assertReadBackAfterWrite("DELETE", "/lock")
        }
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.lock.isEmpty() }
    }

    @Test
    fun cancelPreviewDropsTheFrozenPreviewAndNoWriteIsSent() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-cors-origins-1").performScrollTo().performTextReplacement("https://cancel.example.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }

        // 冻结预览期间不能借面板切换偷偷修改草稿
        compose.onNodeWithTag("rc-panel-lifecycle").assertIsNotEnabled()
        compose.onNodeWithTag("rc-panel-lock").assertIsNotEnabled()
        captureScreenshot("preview-cancel-state")

        compose.onNodeWithTag("rc-preview-cancel").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview == null }
        assertFalse(model!!.r2Browser.configuration.state.value.writing)

        // 取消后可以改地区，旧预览不会复活
        compose.onNodeWithTag("rc-target-toggle").performClick()
        compose.onNodeWithTag("rc-target-jurisdiction-eu").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("eu", model!!.r2Browser.configuration.state.value.target.jurisdiction)
            assertNull(model!!.r2Browser.configuration.state.value.preview)
        }

        // 修改草稿同样使旧预览失效
        compose.onNodeWithTag("rc-target-jurisdiction-default").performScrollTo().performClick()
        compose.onNodeWithTag("rc-refresh").performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.cors.isNotEmpty() }
        compose.onNodeWithTag("rc-cors-origins-0").performScrollTo().performTextReplacement("https://edited.example.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.runOnIdle { assertNull(model!!.r2Browser.configuration.state.value.preview) }

        // 全程零写入、零重放
        compose.runOnIdle {
            assertTrue(
                                "a cancelled preview must not send anything",
                                requests.none { it.method == "PUT" || it.method == "DELETE" },
            )
            assertTrue(ownedTasks().none { it.state in setOf(TaskState.SUCCEEDED, TaskState.UNKNOWN, TaskState.PARTIAL) })
        }
    }

    @Test
    fun contradictingReadBackReportsUnknownWithoutASecondWrite() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-cors-origins-1").performScrollTo().performTextReplacement("https://unknown.example.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }

        // 写之后的独立回读与目标矛盾（PUT 已发生，但回读看不到新规则）
        corsReadBackRules = "[]"

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitOwnedTask()
        assertEquals(TaskState.UNKNOWN, task.state)
        captureScreenshot("readback-unknown")
        compose.runOnIdle {
            assertEquals(
                "an inconclusive read-back is never repaired by replaying the write",
                1,
                requests.count { it.method == "PUT" && it.path.endsWith("/cors") },
            )
            assertReadBackAfterWrite("PUT", "/cors")
        }
    }

    @Test
    fun missingReadBackAfterTheWriteReportsUnknownWithoutASecondWrite() {
        install(allowWrites = true)

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-cors-origins-1").performScrollTo().performTextReplacement("https://missing.example.com")
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.waitForIdle()

        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }

        // PUT 已发送，但写后的独立回读 404：绝不能把 404 当作“没有规则”，也不能重放写入
        corsReadBackStatus = 404

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitOwnedTask()
        assertEquals(TaskState.UNKNOWN, task.state)
        captureScreenshot("readback-404-unknown")
        compose.runOnIdle {
            assertEquals(
                "a missing read-back is never repaired by replaying the write",
                1,
                requests.count { it.method == "PUT" && it.path.endsWith("/cors") },
            )
            assertReadBackAfterWrite("PUT", "/cors")
        }
    }

    @Test
    fun lifecycleStorageClassTransitionKeepsTheProviderDateVerbatim() {
        install(allowWrites = true)
        val providerDate = "2027-05-04T03:02:01.123456789Z"
        // 规则 id 与 prefix 也必须逐字保留：不得被 trim 改写成另一个规则
        val providerId = " tier-rule "
        val providerPrefix = " logs/ "
        lifecycleRulesJson =
            """[{"id":"$providerId","enabled":true,"conditions":{"prefix":"$providerPrefix"},"storageClassTransitions":[{"condition":{"type":"Date","date":"$providerDate"},"storageClass":"InfrequentAccess"}]}]"""

        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag("rc-panel-lifecycle").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.loading }

        // 未编辑的迁移日期必须逐字保留（含 9 位小数），且不会被改造成 Age 或其他类型
        compose.onNodeWithTag("rc-lifecycle-trans-date-0-0").performScrollTo()
            .assertTextContains(providerDate, substring = true)
        compose.runOnIdle {
            val rule = model!!.r2Browser.configuration.state.value.lifecycle.single()
            assertEquals(providerId, rule.id)
            assertEquals(providerPrefix, rule.prefix)
            assertEquals(R2LifecycleDeleteMode.NONE, rule.deleteMode)
            assertEquals(R2LifecycleConditionType.DATE, rule.transitions.single().conditionType)
            assertEquals(providerDate, rule.transitions.single().utcDate)
        }

        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().performClick()
        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextReplacement(bucket)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.waitUntil(5000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitOwnedTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        compose.runOnIdle {
            val body = (requests.single { it.method == "PUT" && it.path.endsWith("/lifecycle") }.payload as HttpBody.Text).value
            assertTrue("the unedited provider date must be written verbatim: $body", body.contains("\"date\":\"$providerDate\""))
            assertTrue("the rule id must not be trimmed: $body", body.contains("\"id\":\"$providerId\""))
            assertTrue("the prefix must not be trimmed: $body", body.contains("\"prefix\":\"$providerPrefix\""))
            assertTrue(body.contains("\"storageClass\":\"InfrequentAccess\""))
        }
    }
}
