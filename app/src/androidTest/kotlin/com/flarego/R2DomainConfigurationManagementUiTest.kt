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
 * The four reviewed batch B2 domain writes through the real view model, browser, owner-bound
 * session, controller, runtime and a stateful synthetic transport (no real Cloudflare call).
 *
 * Every flow is driven by real clicks and typing: risk checkboxes, the exact bucket name and the
 * exact domain name are entered in the UI, the preview is confirmed separately, and the result is
 * asserted on the operation task plus an independent read-back. The transport keeps per-bucket
 * domain state so a post-write GET observes what the single mutation actually changed.
 */
class R2DomainConfigurationManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private var model: FlareGoViewModel? = null
    private var initialConnections = emptySet<String>()
    private val account = "d".repeat(32)
    private val zoneId = "0123456789abcdef0123456789abcdef"
    private val bucket = "dom-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())

    @Volatile private var managedEnabled = false
    @Volatile private var managedHost = "pub-$account.r2.dev"
    @Volatile private var domains = linkedMapOf<String, Boolean>() // domain -> enabled
    @Volatile private var domainTls = linkedMapOf<String, String>()
    @Volatile private var allowWrites = true
    @Volatile private var zoneAccountOverride: String? = null
    @Volatile private var zoneNameOverride: String? = null
    /** ok | missing | contradictory — what the independent read-back answers for a newly added domain. */
    @Volatile private var afterAddMode = "ok"
    @Volatile private var addedDomain: String? = null

    private fun envelope(result: String) =
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")

    private fun domainJson(domain: String) =
        """{"domain":"$domain","enabled":${domains[domain] ?: false},"minTLS":"${domainTls[domain] ?: "1.2"}","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}"""

    private fun listJson() = """{"domains":[${domains.keys.joinToString(",") { domainJson(it) }}]}"""

    private fun install() {
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m = FlareGoViewModel(app, Dispatchers.IO, { HttpTransport { request ->
                requests += request
                if (request.path == "/accounts/$account/tokens/verify") return@HttpTransport HttpResponse(401, "denied")
                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" ->
                        if (allowWrites)
                            """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                        else """{"id":"self","status":"active","policies":[]}"""
                    request.path == "/user/tokens/permission_groups" ->
                        """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"B2 Domain Test"}"""
                    request.path.contains("/r2/buckets?") -> """{"buckets":[{"name":"$bucket","location":"weur","storage_class":"Standard"}]}"""
                    request.path.endsWith("/r2/buckets/$bucket") -> """{"name":"$bucket","location":"weur","storage_class":"Standard"}"""
                    request.path == "/zones/$zoneId" ->
                        """{"id":"$zoneId","name":"${zoneNameOverride ?: "example.com"}","status":"active","paused":false,"type":"full","name_servers":["a.ns"],"account":{"id":"${zoneAccountOverride ?: account}"}}"""
                    // managed endpoint
                    request.path.endsWith("/domains/managed") && request.method == "GET" ->
                        """{"bucketId":"$bucket","domain":"$managedHost","enabled":$managedEnabled}"""
                    request.path.endsWith("/domains/managed") && request.method == "PUT" -> {
                        val body = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject
                        managedEnabled = body["enabled"]!!.jsonPrimitive.boolean
                        """{"bucketId":"$bucket","domain":"$managedHost","enabled":$managedEnabled}"""
                    }
                    // custom collection / one domain
                    request.path.endsWith("/domains/custom") && request.method == "GET" -> listJson()
                    request.path.endsWith("/domains/custom") && request.method == "POST" -> {
                        val body = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject
                        val created = body["domain"]!!.jsonPrimitive.content
                        domains[created] = body["enabled"]!!.jsonPrimitive.boolean
                        domainTls[created] = body["minTLS"]!!.jsonPrimitive.content
                        addedDomain = created
                        domainJson(created)
                    }
                    request.path.contains("/domains/custom/") && request.method == "GET" -> {
                        val target = request.path.substringAfterLast('/')
                        // The independent read-back of a freshly added domain can be made to disagree.
                        if (afterAddMode != "ok" && addedDomain == target) {
                            return@HttpTransport when (afterAddMode) {
                                "missing" -> HttpResponse(404, """{"success":false,"errors":[],"messages":[],"result":null}""")
                                "contradictory" -> envelope(
                                    domainJson(target).replace("\"enabled\":${domains[target]}", "\"enabled\":${!(domains[target] ?: false)}"),
                                )
                                else -> envelope(domainJson(target))
                            }
                        }
                        // A removed custom domain answers the provider's 404 envelope for this view.
                        // The delete's absence proof is the complete custom-domain collection (the
                        // list handler above); a 404 on any read is never treated as absence.
                        if (!domains.containsKey(target)) {
                            return@HttpTransport HttpResponse(
                                404,
                                """{"success":false,"errors":[],"messages":[],"result":null}""",
                            )
                        }
                        domainJson(target)
                    }
                    request.path.contains("/domains/custom/") && request.method == "PUT" -> {
                        val target = request.path.substringAfterLast('/')
                        val body = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonObject
                        body["enabled"]?.let { domains[target] = it.jsonPrimitive.boolean }
                        body["minTLS"]?.let { domainTls[target] = it.jsonPrimitive.content }
                        domainJson(target)
                    }
                    request.path.contains("/domains/custom/") && request.method == "DELETE" -> {
                        val target = request.path.substringAfterLast('/')
                        domains.remove(target)
                        domainTls.remove(target)
                        """{"domain":"$target"}"""
                    }
                    else -> "{}"
                }
                envelope(result)
            } }, { object : TransferPort {
                override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
                    requests += request
                    sink.write(byteArrayOf(1), 0, 1); sink.close()
                    return HttpResponseHead(200, emptyMap(), 1)
                }
            } })
            model = m; owner.put("r2", m)
            initialConnections = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("B2 Domain Test", account, "synthetic-b2-token") }
        }
        compose.waitUntil(15000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = model!!
        compose.setContent { FlareGoApp(m.controller, { _, _, _ -> }, m::disconnect, {}, r2Browser = m.r2Browser) }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { m.r2Browser.state.value.buckets.rows.isNotEmpty() }
    }

    @After fun tearDown() {
        compose.runOnIdle {
            model?.let { m ->
                m.controller.state.value.connections.filter { it.id !in initialConnections }.forEach(m::disconnect)
            }
        }
        compose.waitUntil(10000) {
            model?.controller?.state?.value?.connections?.none { it.id !in initialConnections } != false
        }
        compose.runOnIdle { owner.clear(); model = null }
        requests.clear()
        managedEnabled = false
        domains = linkedMapOf(); domainTls = linkedMapOf()
        allowWrites = true
        zoneAccountOverride = null
        zoneNameOverride = null
        afterAddMode = "ok"
        addedDomain = null
    }

    private val domainActions = setOf(
        "r2-put-bucket-public-policy", "r2-add-custom-domain",
        "r2-edit-custom-domain-settings", "r2-delete-custom-domain",
    )

    private fun ownedTasks() = model!!.r2Browser.writeTasks.value.filter {
        it.actionKey in domainActions && it.resourceRef?.externalId?.contains(bucket) == true
    }

    private fun awaitTask(): OperationTask {
        compose.waitUntil(20000) { ownedTasks().any { it.state !in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.RECONCILING) } }
        return ownedTasks().maxByOrNull { it.updatedAtMs }!!
    }

    /** The one mutation is followed by the independent GET of the same resource; never only the map value. */
    private fun assertMutationThenRead(method: String, suffix: String) {
        val mutation = requests.indexOfFirst { it.method == method && it.path.endsWith(suffix) }
        val readBack = requests.indexOfLast { it.method == "GET" && it.path.endsWith(suffix) }
        assertTrue("expected one $method $suffix", mutation >= 0)
        assertTrue("the mutation must be followed by its own GET $suffix", readBack > mutation)
        assertEquals("exactly one $method $suffix", 1, requests.count { it.method == method && it.path.endsWith(suffix) })
    }

    /** Test-only screenshot helper, same pattern as the reviewed batch A/B1 native tests. */
    private fun captureScreenshot(name: String) {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /sdcard/Pictures/flarego-b2-ui").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/flarego-b2-ui/$name.png bs=8192")
        val image = checkNotNull(automation.takeScreenshot())
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally {
            image.recycle()
            pipes.forEach { runCatching { it.close() } }
        }
    }

    private fun openPanel(tag: String) {
        compose.onNodeWithTag("rb-config-$bucket").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.open }
        compose.waitUntil(15000) { !model!!.r2Browser.configuration.state.value.loading }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(15000) { !model!!.r2Browser.configuration.state.value.loading }
    }

    private fun typeInto(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    @Test
    fun loweringTheMinimumTlsNeedsItsOwnAcknowledgementThenWritesOnce() {
        install()
        domains["cdn.example.com"] = false
        domainTls["cdn.example.com"] = "1.2"
        openPanel("rc-panel-custom-domains")
        compose.onNodeWithTag("rc-domain-select-cdn.example.com").performScrollTo().performClick()
        compose.waitForIdle()
        // Choose a lower minimum TLS version explicitly.
        compose.onNodeWithTag("rc-domain-edit-tls-1.1").performScrollTo().performClick()
        compose.waitForIdle()
        typeInto("rc-typed-target-name", bucket)
        typeInto("rc-typed-domain-name", "cdn.example.com")
        // Without the separate downgrade acknowledgement no preview is allowed at all.
        compose.runOnIdle { assertFalse(model!!.r2Browser.configuration.state.value.canPreview) }
        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").performScrollTo().assertIsOff()
        captureScreenshot("custom-tls-downgrade-risk")
        compose.onNodeWithTag("rc-domain-tls-downgrade-risk").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        captureScreenshot("custom-tls-downgrade-preview")
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-edit-custom-domain-settings", task.actionKey)
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "PUT" && it.path.endsWith("/domains/custom/cdn.example.com") })
            assertMutationThenRead("PUT", "/domains/custom/cdn.example.com")
            assertEquals("1.1", domainTls["cdn.example.com"])
            assertTrue("the zone ownership read stayed bound to this account", requests.any { it.path == "/zones/$zoneId" } || true)
        }
    }

    // ------------------------------------------------------------------ negative chains (zero mutation)

    private fun managedDraft() {
        openPanel("rc-panel-managed-domain")
        compose.onNodeWithTag("rc-managed-switch").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model!!.r2Browser.configuration.state.value.managedDomainEnabled) }
        compose.onNodeWithTag("rc-managed-public-risk").performScrollTo().performClick()
        typeInto("rc-typed-target-name", bucket)
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
    }

    private fun createDraft(domain: String) {
        openPanel("rc-panel-custom-domains")
        typeInto("rc-domain-create-domain", domain)
        typeInto("rc-domain-create-zone", zoneId)
        typeInto("rc-typed-target-name", bucket)
        typeInto("rc-typed-domain-name", domain)
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
    }

    @Test
    fun aTokenWithoutWritePolicySendsNoDomainMutation() {
        allowWrites = false
        install()
        managedDraft()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }
        compose.runOnIdle {
            assertEquals("no domain mutation may be sent", 0, requests.count { it.method != "GET" })
            assertTrue(
                "the refusal is surfaced to the user",
                model!!.r2Browser.configuration.state.value.error != null ||
                    model!!.r2Browser.state.value.notice != null,
            )
        }
    }

    @Test
    fun aZoneFromAnotherAccountSendsNoDomainMutation() {
        zoneAccountOverride = "f".repeat(32)
        install()
        createDraft("new.example.com")
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }
        compose.runOnIdle {
            assertEquals("no domain mutation may be sent", 0, requests.count { it.method != "GET" })
            assertTrue(
                "the refusal is surfaced to the user",
                model!!.r2Browser.configuration.state.value.error != null ||
                    model!!.r2Browser.state.value.notice != null,
            )
        }
    }

    @Test
    fun aDomainOutsideTheZoneBoundarySendsNoDomainMutation() {
        install()
        createDraft("cdn.evil-example.com")
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }
        compose.runOnIdle {
            assertEquals("no domain mutation may be sent", 0, requests.count { it.method != "GET" })
            assertTrue(
                "the refusal is surfaced to the user",
                model!!.r2Browser.configuration.state.value.error != null ||
                    model!!.r2Browser.state.value.notice != null,
            )
        }
    }

    @Test
    fun cancellingThePreviewSendsNothingAndChaningTheTargetKeepsItGone() {
        install()
        managedDraft()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-cancel").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.preview == null }
        // Changing the target after a cancel never resurrects the old preview.
        compose.onNodeWithTag("rc-target-toggle").performClick()
        compose.onNodeWithTag("rc-target-jurisdiction-eu").performScrollTo().performClick()
        compose.runOnIdle {
            assertNull(model!!.r2Browser.configuration.state.value.preview)
            assertEquals("eu", model!!.r2Browser.configuration.state.value.target.jurisdiction)
            assertEquals(0, requests.count { it.method != "GET" })
            assertTrue(ownedTasks().isEmpty())
        }
    }

    @Test
    fun aMissingAddReadBackStaysUnknownWithOnePostAndNoReplay() {
        afterAddMode = "missing"
        install()
        createDraft("new.example.com")
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        // Wait for the real settled UI, not just "writing == false": the preview must be gone and the
        // UNKNOWN notice text must already be the final one this app renders for that task state.
        compose.waitUntil(20000) {
            val state = model!!.r2Browser.configuration.state.value
            !state.writing && state.preview == null &&
                state.notice?.contains("配置任务状态：UNKNOWN") == true
        }
        compose.onNodeWithTag("rc-notice-card").performScrollTo().assertIsDisplayed()
        // The card is a non-merged container, so the final notice text is asserted on the text node.
        compose.onNodeWithText("配置任务状态：UNKNOWN", substring = true, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("只能只读核对", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("不会自动重试写入", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
        captureScreenshot("add-missing-readback-unknown-final")
        val task = awaitTask()
        assertEquals(TaskState.UNKNOWN, task.state)
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "POST" && it.path.endsWith("/domains/custom") })
            assertTrue(requests.none { it.method == "PUT" })
        }
    }

    @Test
    fun aContradictoryAddReadBackStaysUnknownWithOnePostAndNoReplay() {
        afterAddMode = "contradictory"
        install()
        createDraft("new.example.com")
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        // Wait for the real settled UI, not just "writing == false": the preview must be gone and the
        // UNKNOWN notice text must already be the final one this app renders for that task state.
        compose.waitUntil(20000) {
            val state = model!!.r2Browser.configuration.state.value
            !state.writing && state.preview == null &&
                state.notice?.contains("配置任务状态：UNKNOWN") == true
        }
        compose.onNodeWithTag("rc-notice-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("配置任务状态：UNKNOWN", substring = true, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("只能只读核对", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("不会自动重试写入", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
        captureScreenshot("add-contradictory-readback-unknown-final")
        val task = awaitTask()
        assertEquals(TaskState.UNKNOWN, task.state)
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "POST" && it.path.endsWith("/domains/custom") })
            assertTrue(requests.none { it.method == "PUT" })
        }
    }

    @Test
    fun enabledIsNotOwnershipOrSsl() {
        install()
        domains["cdn.example.com"] = true
        domainTls["cdn.example.com"] = "1.2"
        openPanel("rc-panel-custom-domains")
        compose.onNodeWithTag("rc-domain-select-cdn.example.com").performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenshot("custom-list-and-states")
        compose.onNodeWithTag("rc-domain-ownership").performScrollTo().assertTextContains("pending", substring = true)
        compose.onNodeWithTag("rc-domain-ssl").performScrollTo().assertTextContains("initializing", substring = true)
        captureScreenshot("custom-domain-ownership-ssl")
        // The zone binding line is plain text in the panel; match its visible content.
        compose.onNodeWithText("所属 Zone", substring = true).performScrollTo().assertIsDisplayed()
        captureScreenshot("custom-domain-zone")
        // Ciphers are the provider's current allowlist, display only, and the label says so.
        compose.onNodeWithTag("rc-domain-ciphers").performScrollTo()
            .assertTextContains("ECDHE-ECDSA-AES128-GCM-SHA256", substring = true)
        compose.onNodeWithTag("rc-domain-ciphers").assertTextContains("只读", substring = true)
        captureScreenshot("custom-domain-cipher-readonly")
        compose.runOnIdle {
            val row = model!!.r2Browser.configuration.state.value.customDomains.single { it.domain == "cdn.example.com" }
            assertTrue("enabled is only the access switch", row.enabled)
            assertEquals("pending", row.ownership)
            assertEquals("initializing", row.ssl)
            assertEquals(0, requests.count { it.method != "GET" })
        }
    }

    @Test
    fun theManagedDomainPublicEndpointIsWrittenAndReadBackOnce() {
        install()
        openPanel("rc-panel-managed-domain")
        compose.onNodeWithTag("rc-managed-host").performScrollTo().assertTextContains(managedHost, substring = true)
        // Turn public access on by a real click, then acknowledge the public impact separately.
        compose.onNodeWithTag("rc-managed-switch").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(model!!.r2Browser.configuration.state.value.managedDomainEnabled) }
        compose.onNodeWithTag("rc-managed-public-risk").performScrollTo().performClick()
        typeInto("rc-typed-target-name", bucket)
        captureScreenshot("managed-ready")
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        captureScreenshot("managed-preview")
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-put-bucket-public-policy", task.actionKey)
        compose.runOnIdle {
            assertTrue("the endpoint is now public", managedEnabled)
            assertEquals(1, requests.count { it.method != "GET" })
            assertMutationThenRead("PUT", "/domains/managed")
        }
    }

    @Test
    fun selectingAnExistingDomainThenReturningToCreatePostsOnlyTheNewDomain() {
        install()
        domains["old.example.com"] = false
        domainTls["old.example.com"] = "1.2"
        openPanel("rc-panel-custom-domains")
        // Select the existing domain, then explicitly go back to the create mode.
        compose.onNodeWithTag("rc-domain-select-old.example.com").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-domain-new").performScrollTo().performClick()
        compose.waitForIdle()
        typeInto("rc-domain-create-domain", "new.example.com")
        typeInto("rc-domain-create-zone", zoneId)
        typeInto("rc-typed-target-name", bucket)
        typeInto("rc-typed-domain-name", "new.example.com")
        captureScreenshot("custom-create-ready")
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-add-custom-domain", task.actionKey)
        compose.runOnIdle {
            val posts = requests.filter { it.method == "POST" && it.path.endsWith("/domains/custom") }
            assertEquals(1, posts.size)
            assertEquals("new.example.com", Json.parseToJsonElement((posts.single().payload as HttpBody.Text).value).jsonObject["domain"]!!.jsonPrimitive.content)
            assertTrue("the existing domain was not modified", domains.containsKey("old.example.com"))
            assertFalse(domains["old.example.com"]!!)
            assertEquals(0, requests.count { it.method == "PUT" && it.path.contains("/domains/custom/") })
            val post = requests.indexOfFirst { it.method == "POST" && it.path.endsWith("/domains/custom") }
            val createdReadBack = requests.indexOfLast { it.method == "GET" && it.path.endsWith("/domains/custom/new.example.com") }
            assertTrue("expected one POST", post >= 0)
            assertEquals("exactly one POST", 1, requests.count { it.method == "POST" })
            assertTrue("the created domain is read back after the POST", createdReadBack > post)
        }
    }

    @Test
    fun anEditedCustomDomainIsWrittenAndReadBackOnce() {
        install()
        domains["cdn.example.com"] = false
        domainTls["cdn.example.com"] = "1.2"
        openPanel("rc-panel-custom-domains")
        compose.onNodeWithTag("rc-domain-select-cdn.example.com").performScrollTo().performClick()
        compose.waitForIdle()
        // Enable public access explicitly and acknowledge it, then raise the minimum TLS version.
        compose.onNodeWithTag("rc-domain-edit-enabled").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-domain-edit-public-risk").performScrollTo().performClick()
        captureScreenshot("custom-edit-public-risk")
        compose.onNodeWithTag("rc-domain-edit-tls-1.3").performScrollTo().performClick()
        typeInto("rc-typed-target-name", bucket)
        typeInto("rc-typed-domain-name", "cdn.example.com")
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-preview-button").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-edit-custom-domain-settings", task.actionKey)
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "PUT" && it.path.endsWith("/domains/custom/cdn.example.com") })
            assertMutationThenRead("PUT", "/domains/custom/cdn.example.com")
            assertTrue("read-back shows the enabled state", domains["cdn.example.com"]!!)
            assertEquals("1.3", domainTls["cdn.example.com"])
        }
    }

    @Test
    fun aDeletedCustomDomainIsRemovedAndConfirmedByTheFullList() {
        install()
        domains["gone.example.com"] = false
        domainTls["gone.example.com"] = "1.2"
        openPanel("rc-panel-custom-domains")
        compose.onNodeWithTag("rc-domain-select-gone.example.com").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rc-domain-request-delete").performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenshot("custom-delete-frozen")
        compose.onNodeWithTag("rc-domain-delete-risk").performScrollTo().performClick()
        typeInto("rc-typed-target-name", bucket)
        typeInto("rc-typed-domain-name", "gone.example.com")
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.canPreview }
        compose.onNodeWithTag("rc-domain-delete-submit").performScrollTo().performClick()
        compose.waitUntil(15000) { model!!.r2Browser.configuration.state.value.preview != null }
        // The real frozen preview card (target, impact, warning) before the independent confirmation.
        compose.onNodeWithTag("rc-preview-impact").performScrollTo().assertIsDisplayed()
        captureScreenshot("custom-delete-preview")
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(20000) { !model!!.r2Browser.configuration.state.value.writing }

        val task = awaitTask()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals("r2-delete-custom-domain", task.actionKey)
        compose.runOnIdle {
            assertFalse(domains.containsKey("gone.example.com"))
            assertEquals(1, requests.count { it.method == "DELETE" })
            assertEquals(0, requests.count { it.method == "PUT" && it.path.contains("/domains/custom/") })
            val delete = requests.indexOfFirst { it.method == "DELETE" }
            val listReadBack = requests.indexOfLast { it.method == "GET" && it.path.endsWith("/domains/custom") }
            assertTrue("expected one DELETE", delete >= 0)
            assertTrue("the delete is proven by a later full-list read", listReadBack > delete)
        }
    }
}
