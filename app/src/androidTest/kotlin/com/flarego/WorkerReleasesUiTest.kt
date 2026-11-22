package com.flarego

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.operations.DnsOperationLifecycle
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.updates.*
import com.flarego.platform.AndroidFingerprint
import com.flarego.platform.LocalDatabase
import com.flarego.platform.TokenVault
import com.flarego.platform.CredentialVault
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestName
import java.io.File
import java.util.Collections

/** Real MainActivity, view model, directory, gateway and routes; Cloudflare transport is synthetic. */
@SdkSuppress(minSdkVersion = 29)
class WorkerReleasesUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val a = "a".repeat(32)
    private val b = "b".repeat(32)
    private val accountA = "M1 Account A"
    private val accountB = "M1 Account B"
    private val worker = "c".repeat(32)
    private val zone = "d".repeat(32)
    private val zoneFixture = """{"id":"$zone","name":"recent.example","status":"active","type":"full","paused":false,"account":{"id":"$a"}}"""
    private val dnsTaskId = "synthetic-dns-receipt"
    private val dnsConnectionId = "synthetic-dns-receipt-connection"
    private val dnsRecord = "e".repeat(32)
    private val dnsZonePath = "/zones/$zone"
    private val dnsRecordPath = "$dnsZonePath/dns_records/$dnsRecord"

    private fun seedDnsReceipt() = runBlocking {
        // Hash the expected writable A-record fields, not the provider envelope or its ID.
        val desiredRecord = """{"comment":null,"content":"192.0.2.8","name":"www.recent.example","private_routing":false,"proxied":false,"settings":{},"tags":[],"ttl":1,"type":"A"}"""
        val connection = Connection(dnsConnectionId, accountA, a)
        val scope = ZoneScope(AccountScope(connection.id, connection.accountId), zone)
        val task = OperationTask(dnsTaskId, connection.id, scope, DnsOperationLifecycle.ACTION_PATCH,
            ResourceRef(scope, "dns_record", dnsRecord), "0".repeat(64),
            AndroidFingerprint().sha256(desiredRecord.encodeToByteArray()), TaskState.UNKNOWN,
            OperationTaskPersistence.summaryDetailFor(TaskState.UNKNOWN), null, null, 1, 1)
        LocalDatabase.open(application).use { db ->
            assertTrue("Run only against an isolated app install", db.localStore.connections().isEmpty())
            val storage = TokenVault(application)
            CredentialVault(storage::get, storage::put, storage::remove).putToken(connection.id, "synthetic-m1-a")
            db.localStore.putConnection(connection)
            db.operationStore.putWithSummary(task, OperationTaskPersistence.summaryFor(task))
        }
    }
    private val deployed = "e26eba62-5560-4597-b834-8b7fd69f44d1"
    private val historic = "aa6eba62-5560-4597-b834-8b7fd69f44d2"
    private val v1 = "c2d711fc-626e-432e-a308-8f57a1ceb111"
    private val v2 = "d2d711fc-626e-432e-a308-8f57a1ceb222"
    private val new = "f2d711fc-626e-432e-a308-8f57a1ceb333"
    private val release = """{"id":"$deployed","source":"api","created_on":"2026-11-21T14:32:00Z","strategy":"percentage","versions":[{"version_id":"$v1","percentage":90},{"version_id":"$v2","percentage":10}]}"""
    private val oldRelease = """{"id":"$historic","source":"wrangler","created_on":"2026-11-20T09:15:00Z","strategy":"percentage","versions":[{"version_id":"$v1","percentage":100}]}"""
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    @Volatile private var status = 200
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val testAccounts = setOf(a, b)
    @Volatile private var billingDenied = false
    private fun transport(token: String) = HttpTransport { r ->
        requests += r
        check(r.method == "GET") { "Unexpected write in a read-only path" }
        val isB = "/accounts/$b/" in r.path
        val result = when {
            r.path == "/user/tokens/verify" -> {
                val id = if (token == "synthetic-m1-a") "a".repeat(32) else "e".repeat(32)
                """{"id":"$id","status":"active"}"""
            }
            r.path.startsWith("/accounts?") ->
                """[{"id":"$a","name":"$accountA"},{"id":"$b","name":"$accountB"}]"""
            r.path == "/accounts/$a" -> """{"id":"$a","name":"$accountA"}"""
            r.path == "/accounts/$b" -> """{"id":"$b","name":"$accountB"}"""
            r.path.startsWith("/zones?") -> if (billingDenied)
                return@HttpTransport HttpResponse(200, """{"success":true,"result":[],"result_info":{"total_count":0}}""")
            else "[$zoneFixture]"
            r.path == "/zones/$zone" -> zoneFixture
            r.path == dnsRecordPath -> {
                check(token == "synthetic-m1-a") { "DNS reconciliation must use the owning account credential" }
                """{"id":"$dnsRecord","zone_id":"$zone","type":"A","name":"www.recent.example","content":"192.0.2.8","ttl":1,"proxied":false}"""
            }
            r.path.startsWith("/zones/$zone/dns_records?") ->
                """[{"id":"sample-record","name":"www.recent.example","type":"A","content":"192.0.2.8","ttl":1}]"""
            "/r2/buckets?" in r.path -> """{"buckets":[{"name":"media-fixture","location":"weur","storage_class":"Standard"}]}"""
            "/r2/buckets/media-fixture/objects?" in r.path -> """[{"key":"images/illustration.png","size":120,"etag":"example"}]"""
            "/billing/history" in r.path -> when {
                billingDenied && token == "synthetic-m1-a" -> return@HttpTransport HttpResponse(403, "{}")
                billingDenied -> """[{"id":"new-bill","amount":1,"currency":"USD","description":"synthetic-restored-billing"}]"""
                else -> "[]"
            }
            "/storage/kv/namespaces?" in r.path || "/d1/database?" in r.path -> "[]"
            r.path.contains("/workers/workers?") -> """[{"id":"$worker","name":"edge-router"}]"""
            r.path.contains("/scripts-search?id=$worker&") -> """[{"id":"$worker","script_name":"mapped-script"}]"""
            r.path.contains("/mapped-script/deployments?") -> when {
                status != 200 -> return@HttpTransport HttpResponse(status, "{}")
                isB -> """{"deployments":[]}"""
                else -> """{"deployments":[$release,$oldRelease]}"""
            }
            r.path.endsWith("/mapped-script/deployments/$historic") -> oldRelease
            r.path.endsWith("/mapped-script/deployments/$deployed") -> release
            r.path.contains("/mapped-script/versions?") -> """{"items":[{"id":"$new","number":26},{"id":"$v2","number":25}]}"""
            r.path.endsWith("/mapped-script/versions/$new") -> """{"id":"$new","number":26,"metadata":{"created_on":"2026-11-21T15:00:00Z"}}"""
            billingDenied -> return@HttpTransport HttpResponse(403, "{}")
            else -> error("Unexpected fixture request")
        }
        val paging = if (r.path.contains("/workers/workers?") || r.path.startsWith("/zones") ||
            "/d1/database?" in r.path || "/storage/kv/namespaces?" in r.path)
            ""","result_info":{"page":1,"total_pages":1,"count":1,"total_count":1}""" else ""
        HttpResponse(200, """{"success":true,"result":$result,"errors":[],"messages":[]$paging}""")
    }
    private val notesHead = "合成版本说明：开头\n"
    private val notesEnd = "（说明截断前最后一行）"
    private val notesPastLimit = "（截断后不可显示）"
    private val shownNotes = (notesHead + "仅供本机测试的版本记录。\n".repeat(1000))
        .take(12000 - notesEnd.length - 1) + "\n" + notesEnd
    private var updateChecks = 0
    private var updateDownloads = 0
    private val versionFixture = object : UpdateService {
        override suspend fun check(channel: UpdateChannel): AppUpdate {
            updateChecks++
            return AppUpdate(channel, "v-fixture", Int.MAX_VALUE, "0".repeat(40),
                shownNotes + notesPastLimit, ReleaseAsset(1, "fixture.apk", 1048576), "0".repeat(64))
        }

        override suspend fun download(update: AppUpdate, progress: (Float) -> Unit): String {
            updateDownloads++
            error("Update notes must not download an APK")
        }
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity || model != null) return
            val transfers = object : TransferPort {
                override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead =
                    error("Unexpected transfer")
            }
            val m = FlareGoViewModel(application, Dispatchers.IO, ::transport, { transfers },
                updateService = versionFixture, updateStore = ManualUpdateFixtureStore())
            initial = m.controller.state.value.connections.map { it.id }.toSet()
            if (testName.methodName == "mainActivityReadOnlyCheckReconcilesPersistedDnsReceipt") initial -= dnsConnectionId
            model = m
            activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:com.flarego.FlareGoViewModel", m)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    @get:Rule(order = 0) val testName = TestName()
    @get:Rule(order = 1) val fixture = object : ExternalResource() {
        override fun before() {
            if (testName.methodName == "mainActivityReadOnlyCheckReconcilesPersistedDnsReceipt") seedDnsReceipt()
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        }
        override fun after() { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun install(releaseStatus: Int = 200, secondAccount: Boolean = false, captureDirectory: Boolean = false) {
        status = releaseStatus
        val m = checkNotNull(model)
        // Refuse to reuse an account or let production state become part of this fixture.
        assertTrue("Run only against an isolated app install", initial.isEmpty())
        if (secondAccount) {
            compose.runOnIdle { m.viewModelScope.launch { m.connect(accountB, b, "synthetic-m1-b") } }
            compose.waitUntil(12_000) { m.controller.state.value.selected?.accountId == b }
        }
        compose.runOnIdle { m.viewModelScope.launch { m.connect(accountA, a, "synthetic-m1-a") } }
        compose.waitUntil(12_000) { m.controller.state.value.selected?.accountId == a }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.waitUntil(12_000) { m.controller.state.value.inventory.resources.any { it.id == worker } }
        if (captureDirectory) capture("01-directory")
        compose.onNodeWithText("edge-router").performClick()
        compose.waitUntil(12_000) { m.controller.workerReleases?.state?.value?.deployments?.loading == false }
    }

    @After fun cleanup() {
        val m = model ?: return
        fun owned() = m.controller.state.value.connections.filter { it.id !in initial && it.accountId in testAccounts }
        compose.runOnIdle { owned().forEach(m::disconnect) }
        compose.waitUntil(12_000) { owned().isEmpty() }
    }

    private fun capture(name: String, wholeWindow: Boolean = false) {
        if (InstrumentationRegistry.getArguments().getString("captureM1") != "true") return
        compose.waitForIdle()
        val dir = File(application.cacheDir, "worker-m1-evidence").also { it.mkdirs() }
        val image = if (wholeWindow) checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(dir, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        val target = compose.onNodeWithTag(tag)
        if (!target.isDisplayed()) compose.onNodeWithTag("worker-screen").performScrollToNode(hasTestTag(tag))
        return target
    }

    @Test fun currentAndHistoryStaySeparateFromLatestCreatedVersion() {
        install(captureDirectory = true)
        node("worker-current").assert(hasAnyDescendant(hasText("90%", substring = true)))
        capture("02-current")
        node("worker-history").performClick()
        node("worker-current").assert(hasAnyDescendant(hasText("90%", substring = true)))
        node("worker-deployment-$deployed").assert(hasText("当前部署", substring = true))
        capture("03-history")
        node("worker-deployment-$historic").assert(hasText("历史记录", substring = true)).performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.deployment?.value?.id == historic }
        node("worker-historic-allocation").assert(hasAnyDescendant(hasText("100%", substring = true)))
        node("worker-current").assert(hasAnyDescendant(hasText("90%", substring = true)))
        capture("04-snapshot")
        Espresso.pressBack()
        node("worker-deployment-$historic").assertIsDisplayed()
        node("worker-versions").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.versions?.value != null }
        node("worker-version-$new").assert(hasText("未分配流量", substring = true))
        capture("18-versions")
        node("worker-version-$new").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.version?.value?.id == new }
        node("worker-version-allocation").assert(hasAnyDescendant(hasText("未分配流量", substring = true)))
        capture("05-version")
        repeat(2) { compose.onNodeWithContentDescription("返回").performClick() }
        compose.onNodeWithText("edge-router").assertIsDisplayed()
        assertTrue(requests.all { it.method == "GET" })
        assertTrue(requests.any { it.path == "/accounts/$a/workers/scripts/mapped-script/deployments/$historic" })
    }

    @Test fun releaseRowsToggleWithoutLeavingTheTabOrReplacingTheList() {
        install()
        compose.onNodeWithText("Workers · $accountA · 只读").assertDoesNotExist()
        node("worker-history").performClick()
        node("worker-deployment-$historic").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.deployment?.value?.id == historic }
        node("worker-historic-allocation").assertIsDisplayed()
        node("worker-history").assertIsSelected()
        node("worker-deployment-$deployed").assertExists()
        node("worker-deployment-$historic").performClick()
        compose.onNodeWithTag("worker-historic-allocation").assertDoesNotExist()
        node("worker-history").assertIsSelected()
        node("worker-versions").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.versions?.value != null }
        node("worker-version-$new").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.version?.value?.id == new }
        node("worker-version-allocation").assertIsDisplayed()
        node("worker-version-$v2").assertExists()
        node("worker-version-$new").performClick()
        compose.onNodeWithTag("worker-version-allocation").assertDoesNotExist()
        node("worker-versions").assertIsSelected()
        node("worker-current").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("edge-router").assertIsDisplayed()
    }

    @Test fun connectedSingleAccountHasOneIdentityAndNoSwitchButton() {
        install()
        compose.onNodeWithTag("nav-accounts").performClick()
        // Modal drawer content may still have off-screen semantics while closed.
        val identities = compose.onAllNodesWithText(accountA).fetchSemanticsNodes()
        assertTrue(identities.count { it.boundsInWindow.width > 0f } == 1)
        compose.onNodeWithContentDescription("切换云账号").assertDoesNotExist()
        capture("accounts-connected-single", wholeWindow = true)
        compose.onNodeWithText("断开连接").assertIsDisplayed()
        val identity = compose.onNode(hasText(accountA) and hasAnyAncestor(hasScrollToNodeAction()))
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val updates = compose.onNodeWithText("应用更新").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("已连接身份必须在应用更新之前", identity.bottom < updates.top)
    }

    @Test fun switchingAccountReturnsToDirectoryAndNeverShowsPreviousTraffic() {
        install(secondAccount = true)
        node("worker-current")
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText(accountB).performClick()
        compose.waitUntil(12_000) { model?.controller?.state?.value?.inventory?.resources?.any { it.id == worker } == true }
        compose.onNodeWithText("edge-router").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.deployments?.value != null }
        compose.onNodeWithText("尚无部署").assertIsDisplayed()
        compose.onNodeWithText("90%", substring = true).assertDoesNotExist()
    }

    @Test fun mainActivityHomeReopensOnlyVisitedObjectsAndReturnsHome() {
        install(secondAccount = true)
        compose.onNodeWithTag("nav-overview").performClick()
        val workerVisit = "recent-resource-WORKER-$worker"
        val zoneVisit = "recent-zone-$zone"
        val bucketVisit = "recent-resource-R2-media-fixture"
        compose.onNodeWithTag(workerVisit).assertIsDisplayed()
        compose.onNodeWithTag(zoneVisit).assertDoesNotExist()
        compose.onNodeWithTag(bucketVisit).assertDoesNotExist()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithTag("overview-refresh-all").performClick()
        compose.onNodeWithTag("nav-domains").performClick()
        compose.waitUntil(12_000) { model?.management?.zones?.state?.value?.zones?.any { it.ref.zoneId == zone } == true }
        compose.onNodeWithTag("zone-row-$zone").performClick()
        compose.waitUntil(12_000) {
            model?.management?.dns?.state?.value?.rows?.any { it.id == "sample-record" } == true
        }
        compose.onNodeWithTag("dns-record-sample-record").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("zone-row-$zone").assertIsDisplayed()
        compose.onNodeWithTag("nav-overview").performClick()
        compose.onNodeWithTag(zoneVisit).performClick()
        compose.waitUntil(12_000) {
            model?.management?.dns?.state?.value?.rows?.any { it.id == "sample-record" } == true
        }
        compose.onNodeWithTag("dns-record-sample-record").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag(zoneVisit).assertIsDisplayed()
        val requireFirstViewport = InstrumentationRegistry.getArguments().getString("requireFirstViewport") == "true"
        if (requireFirstViewport) {
            compose.onNodeWithTag("nav-resources").performClick()
            compose.onNodeWithTag("resource-type-selector").performClick()
            compose.onNodeWithTag("resource-tab-R2").performClick()
        } else {
            compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("overview-r2"))
            compose.onNodeWithTag("overview-r2").performClick()
        }
        compose.waitUntil(12_000) { model?.r2Browser?.state?.value?.buckets?.rows?.any { it.name == "media-fixture" } == true }
        compose.onNodeWithTag("rb-open-media-fixture").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(12_000) { model?.r2Browser?.state?.value?.objects?.bucket == "media-fixture" }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("nav-overview").performClick()
        if (!requireFirstViewport) compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag(bucketVisit))
        compose.onNodeWithTag(bucketVisit).assertIsDisplayed()
        capture("home-three-recent", wholeWindow = true)
        if (requireFirstViewport) {
            val viewport = compose.onNodeWithTag("overview-list").getUnclippedBoundsInRoot()
            val bottomBar = compose.onNodeWithTag("nav-overview").getUnclippedBoundsInRoot()
            fun assertFullyVisible(tag: String): SemanticsNodeInteraction {
                val node = compose.onNodeWithTag(tag).assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue("$tag 不得在折叠线下或被底栏遮挡：$bounds / $viewport",
                    bounds.top >= viewport.top && bounds.bottom <= minOf(viewport.bottom, bottomBar.top))
                assertTrue("$tag 点击区不得小于 48dp", (bounds.bottom - bounds.top).value >= 48f)
                return node
            }
            listOf(bucketVisit, zoneVisit, workerVisit).forEach { assertFullyVisible(it) }
            listOf(
                Triple("domains", "域名", "1"),
                Triple("worker", "Workers", "1"),
                Triple("r2", "R2 存储", "1+"),
                Triple("kv", "KV 存储", "0+"),
                Triple("d1", "D1 数据库", "0+"),
            ).forEach { (key, label, count) ->
                assertFullyVisible("overview-$key").assert(hasText(label) and hasText(count))
            }
        }
        for (tag in listOf("overview-domains", "overview-worker", "overview-r2", "overview-kv", "overview-d1")) {
            compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        capture("home-resource-grid", wholeWindow = true)
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag(bucketVisit))
        compose.onNodeWithTag(bucketVisit).performClick()
        compose.waitUntil(12_000) { model?.r2Browser?.state?.value?.objects?.rows?.isNotEmpty() == true }
        compose.onNodeWithTag("ro-folder-images/").performScrollTo().performClick()
        compose.waitUntil(12_000) { model?.r2Browser?.state?.value?.objects?.let { it.prefix == "images/" && !it.loading } == true }
        compose.onNodeWithTag("ro-row-images/illustration.png").performScrollTo().performClick()
        compose.onNodeWithTag("ro-download-images/illustration.png").performScrollTo().assertIsDisplayed()
        capture("r2-object-detail", wholeWindow = true)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("ro-download-images/illustration.png").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag(bucketVisit).assertIsDisplayed()
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag(workerVisit))
        compose.onNodeWithTag(workerVisit).performClick()
        node("worker-current").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag(bucketVisit).assertIsDisplayed()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText(accountB).performClick()
        compose.onNodeWithTag(bucketVisit).assertDoesNotExist()
        compose.onNodeWithTag(workerVisit).assertDoesNotExist()
        compose.onNodeWithTag(zoneVisit).assertDoesNotExist()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText(accountA).performClick()
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag(bucketVisit))
        compose.onNodeWithTag(bucketVisit).assertIsDisplayed()
        assertTrue(requests.all { it.method == "GET" })
    }

    private fun assertRootTitleFits(title: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("directory-page-title").assertIsDisplayed().assert(hasText(title))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
        assertTrue("页首不得截断：$title", layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
    }

    @Test fun mainActivityConnectsFromSetupAfterExplicitAccountChoice() {
        val m = checkNotNull(model)
        assertTrue(initial.isEmpty())
        compose.onNodeWithTag("setup-connect").performScrollTo().performClick()
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").performScrollTo().performTextInput("synthetic-m1-a")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("token-submit").performScrollTo().performClick()
        compose.waitUntil(12_000) { m.tokenConnection.state.value.accounts.size == 2 }
        compose.runOnIdle { assertTrue(m.controller.state.value.connections.isEmpty()) }
        compose.onNodeWithTag("token-manual-toggle").assertDoesNotExist()
        compose.onNodeWithTag("token-account-$a").performScrollTo().performClick()
        compose.waitUntil(12_000) { m.controller.state.value.selected?.accountId == a }
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        compose.onNodeWithText("账号已连接").assertIsDisplayed()
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun mainActivityOpensSyncDetailsWithoutInventingUnreadResults() {
        install()
        compose.onNodeWithTag("nav-overview").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithTag("overview-sync-details").performClick()
        compose.onNodeWithTag("sync-product-workers").assert(hasAnyDescendant(hasText("已读取 1 个")))
        compose.onNodeWithTag("sync-product-zones").assert(hasAnyDescendant(hasText("尚未读取")))
        capture("sync-details", wholeWindow = true)
        Espresso.pressBack()
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        compose.onNodeWithTag("sync-details").assertDoesNotExist()
    }

    @Test fun mainActivityConnectionDetailsExplainLocalCredentialWithoutExposingIt() {
        install()
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithTag("connection-details-toggle").performClick()
        compose.onNodeWithText("凭据已在本机加密保存").assertIsDisplayed()
        compose.onNodeWithText("synthetic-m1-a").assertDoesNotExist()
        capture("connection-details", wholeWindow = true)
        compose.onNodeWithTag("connection-details-toggle").performClick()
        compose.onNodeWithText("凭据已在本机加密保存").assertDoesNotExist()
    }

    @Test fun mainActivityCreateMenuOpensExistingNativeD1Form() {
        install()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("创建资源").performClick()
        compose.onNodeWithTag("resource-create-D1").performClick()
        val m = checkNotNull(model)
        compose.waitUntil(12_000) { m.d1Browser.state.value.editor == com.flarego.ui.d1.D1Editor.CREATE }
        assertTrue(m.d1Browser.content.state.value.secure)
        Espresso.pressBack()
        compose.waitUntil(12_000) { m.d1Browser.state.value.editor == null }
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun mainActivityCreateMenuOpensR2AndKvWithoutSending() {
        install()
        val m = checkNotNull(model)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("创建资源").performClick()
        compose.onNodeWithTag("resource-create-R2").performClick()
        compose.waitUntil(12_000) { compose.onNodeWithTag("rb-create-name").isDisplayed() }
        capture("resource-create-r2", wholeWindow = true)
        Espresso.pressBack()
        compose.onNodeWithTag("resource-type-selector").assertIsDisplayed()
        compose.onNodeWithContentDescription("创建资源").performClick()
        compose.onNodeWithTag("resource-create-KV").performClick()
        compose.waitUntil(12_000) { m.kvBrowser.state.value.editor == com.flarego.ui.kv.KvEditor.CREATE }
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun mainActivityUpdateNotesScrollToVisibleTruncationBoundaryAndClose() {
        assertTrue(initial.isEmpty())
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithText("设备").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("check-update").performScrollTo().performClick()
        compose.waitUntil(12_000) { model!!.updater.state.value.available?.versionName == "v-fixture" }
        compose.onNodeWithText("更新说明").performScrollTo().performClick()
        val notes = compose.onNodeWithText(notesHead, substring = true, useUnmergedTree = true).assertIsDisplayed()
        notes.assertTextEquals(shownNotes)
        compose.onNodeWithText(notesPastLimit, substring = true).assertDoesNotExist()

        val viewport = compose.onNode(hasScrollAction() and (hasText(notesHead, substring = true) or hasAnyDescendant(hasText(notesHead, substring = true))),
            useUnmergedTree = true)
        val scroll = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, scroll.value())
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        notes.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val end = layouts.single().getBoundingBox(shownNotes.lastIndex)
        assertTrue(end.bottom > viewport.fetchSemanticsNode().boundsInRoot.height)

        viewport.performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { scroll.value() > 0f }
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroll.maxValue()) }
        compose.waitUntil(5_000) { scroll.value() == scroll.maxValue() }
        val noteOrigin = notes.fetchSemanticsNode().positionInRoot
        val bounds = viewport.fetchSemanticsNode().boundsInRoot
        capture("update-notes-end", wholeWindow = true)
        val lastGlyph = noteOrigin + end.center
        assertTrue("截断前末字 $lastGlyph 须落在可见滚动区域 $bounds", bounds.contains(lastGlyph))
        compose.onNodeWithText("说明过长，仅显示前 12000 个字符").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("更新说明").assertIsDisplayed()
        assertEquals(1, updateChecks)
        assertEquals(0, updateDownloads)
        assertNull(model!!.updater.state.value.downloadedPath)
    }

    @Test fun mainActivityReadOnlyCheckReconcilesPersistedDnsReceipt() {
        val m = checkNotNull(model)
        val seed = checkNotNull(m.operationStore.get(dnsTaskId))
        assertEquals(TaskState.UNKNOWN, seed.state)
        assertEquals(a, m.controller.state.value.selected?.accountId)
        compose.waitUntil(12_000) { m.controller.state.value.tasks.singleOrNull() == seed }
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("操作记录").performClick()

        val row = hasTestTag("job-row-$dnsTaskId")
        val receipt = hasTestTag("job-detail-$dnsTaskId")
        compose.onNode(hasAnyAncestor(row) and hasClickAction() and hasText("待核对")).assertIsDisplayed().performClick()
        compose.onNode(hasText("目标：$dnsRecord") and hasAnyAncestor(receipt)).performScrollTo().assertIsDisplayed()
        val check = compose.onNode(hasText("只读核对") and hasAnyAncestor(receipt)).performScrollTo().assertIsDisplayed()
        fun requestSnapshot() = synchronized(requests) { requests.map { it.method to it.path } }
        val before = requestSnapshot()
        assertTrue(before.none { (_, path) -> path == dnsZonePath || path == dnsRecordPath })

        check.performTouchInput { click() }
        compose.waitUntil(12_000) { m.controller.state.value.tasks.singleOrNull()?.state == TaskState.SUCCEEDED }
        compose.onNode(hasAnyAncestor(row) and hasText("已确认完成")).assertIsDisplayed()
        compose.onNode(hasText("已确认完成") and hasAnyAncestor(receipt)).assertIsDisplayed()
        compose.onNode(hasText("只读核对") and hasAnyAncestor(receipt)).assertDoesNotExist()

        val persisted = LocalDatabase.open(application).use { it.operationStore.get(dnsTaskId) }
        assertNotNull(persisted)
        assertEquals(seed.copy(state = TaskState.SUCCEEDED,
            safeDetail = OperationTaskPersistence.summaryDetailFor(TaskState.SUCCEEDED),
            updatedAtMs = persisted!!.updatedAtMs), persisted)
        assertTrue(persisted.updatedAtMs > seed.updatedAtMs)
        val after = requestSnapshot().drop(before.size)
        // The runtime verifies the Zone; the record adapter verifies it again before reading the exact record.
        assertEquals(listOf("GET" to dnsZonePath, "GET" to dnsZonePath, "GET" to dnsRecordPath), after)
        instrumentation.addResults(Bundle().apply {
            putString("dns-receipt", "$dnsTaskId: UNKNOWN -> SUCCEEDED (SQLite and visible receipt)")
            putString("dns-readback", after.joinToString { (method, path) -> "$method $path" })
        })
    }

    @Test fun mainActivityOperationsExpandAndCollapseTheCurrentReceipt() {
        install()
        val m = checkNotNull(model)
        val current = m.controller.state.value.selected!!
        val owner = com.flarego.core.model.AccountScope(current.id, current.accountId)
        compose.runOnIdle {
            m.controller.publishTasks(listOf(com.flarego.core.model.OperationTask(
                "synthetic-receipt", current.id, owner, "r2-upload-object", null,
                "0".repeat(64), null, com.flarego.core.model.TaskState.UNKNOWN, "示例：传输结果待核对",
                null, null, 1, 1)))
        }
        compose.onNodeWithTag("nav-overview").performClick()
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("操作记录").performClick()
        val row = compose.onNodeWithTag("job-row-synthetic-receipt")
        val detail = compose.onNodeWithTag("job-detail-synthetic-receipt")
        detail.assertDoesNotExist()
        row.performClick()
        detail.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("来源：此设备").assertIsDisplayed()
        capture("operation-receipt", wholeWindow = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("操作记录").assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("操作记录标题必须完整渲染", layouts.isNotEmpty() && layouts.none {
            it.hasVisualOverflow || (0 until it.lineCount).any(it::isLineEllipsized)
        })
        listOf("返回", "搜索").forEach { label ->
            val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$label 触控高度必须 ≥48dp", (bounds.bottom - bounds.top).value >= 48f)
        }
        compose.onNodeWithText("只读核对").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消搜索").performClick()
        assertTrue(requests.all { it.method == "GET" })
        compose.onNodeWithContentDescription("返回").performClick()
        detail.assertDoesNotExist()
        row.assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        compose.onNodeWithContentDescription("更多").assertIsDisplayed()
    }

    @Test fun mainActivityDirectoriesKeepDesignGroupingAndReturnToTheFilteredList() {
        val m = checkNotNull(model)
        install()
        compose.onNodeWithContentDescription("返回").performClick()
        assertRootTitleFits("资源")
        compose.onNodeWithTag("resource-type-selector").assertIsDisplayed().assert(hasText("Workers"))
        compose.onNodeWithText("当前账号").assertIsDisplayed()
        compose.onNodeWithText("edge-router").assertIsDisplayed()
        capture("resources-directory", wholeWindow = true)
        compose.onNodeWithTag("nav-domains").performClick()
        val zoneRow = compose.onNodeWithTag("zone-row-$zone")
        compose.waitUntil(12_000) { m.management.zones.state.value.zones.any { it.ref.zoneId == zone } }
        assertRootTitleFits("域名")
        compose.onNodeWithText("已托管").assertIsDisplayed()
        zoneRow.assertIsDisplayed()
        capture("domains-directory", wholeWindow = true)
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("recent")
        compose.onNodeWithTag("search-field").performImeAction()
        zoneRow.performScrollTo().performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.rows.any { it.id == "sample-record" } }
        compose.onNodeWithTag("zone-tab-dns").assertIsSelected()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("search-field").assertTextContains("recent")
        zoneRow.assertIsDisplayed()
        compose.onNodeWithTag("nav-resources").performClick()
        val selector = compose.onNodeWithTag("resource-type-selector")
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("edge")
        compose.onNodeWithTag("search-field").performImeAction()
        selector.performClick()
        compose.onNodeWithTag("resource-tab-D1").performClick()
        compose.onNodeWithTag("search-field").assertDoesNotExist()
        compose.waitUntil(12_000) {
            val state = m.d1Browser.state.value
            state.observedAtMs != null && !state.loading
        }
        compose.onNodeWithText("暂无数据库").assertIsDisplayed()
        capture("resources-empty", wholeWindow = true)
        selector.performClick()
        compose.onNodeWithTag("resource-tab-WORKER").performClick()
        compose.onNodeWithTag("search-field").assertTextContains("edge").performImeAction()
        compose.onNodeWithText("edge-router").assertIsDisplayed()
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun mainActivityDnsReviewReturnsToSameOpenedRecord() {
        val m = checkNotNull(model)
        assertTrue(initial.isEmpty())
        compose.runOnIdle {
            val demo = com.flarego.core.demo.demoConnections.first()
            m.controller.reloadConnections(listOf(demo))
            m.onManagementResume()
        }
        compose.onNodeWithTag("nav-domains").performClick()
        compose.waitUntil(12_000) { m.management.zones.state.value.zones.isNotEmpty() }
        compose.onNodeWithTag("zone-row-zone-demo").performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.rows.any { it.type == "A" } }
        val record = m.management.dns.state.value.rows.first { it.type == "A" }
        compose.onNodeWithTag("dns-record-${record.id}").performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.detail?.id == record.id }
        compose.onNodeWithTag("dns-detail-patch").performScrollTo().performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.mode != null }
        compose.onNodeWithTag("dns-content").performScrollTo().performTextReplacement("192.0.2.44")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("dns-preview").performScrollTo().performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.preparedChange != null }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("dns-content").performScrollTo().assertTextContains("192.0.2.44")
        compose.onNodeWithTag("dns-preview").performScrollTo().performClick()
        compose.waitUntil(12_000) { m.management.dns.state.value.preparedChange != null }
        compose.onNodeWithTag("dns-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(12_000) {
            val state = m.management.dns.state.value
            state.mode == null && state.pendingTask?.state == com.flarego.core.model.TaskState.SUCCEEDED
        }
        compose.onNodeWithTag("zone-tab-dns").assertIsSelected()
        compose.onNodeWithTag("dns-record-${record.id}").assertIsDisplayed()
        compose.onNodeWithText("192.0.2.44").assertIsDisplayed()
        assertTrue(requests.isEmpty())
        capture("dns-edit-return", wholeWindow = true)
    }

    @Test fun mainActivityBillingUpgradeReturnsToAccountHistory() {
        billingDenied = true
        install()
        val m = checkNotNull(model)
        compose.onNodeWithTag("nav-billing").performClick()
        compose.waitUntil(12000) {
            m.controller.state.value.products[com.flarego.core.application.inventory.ProductKey.BILLING]?.error != null
        }
        assertRootTitleFits("账单")
        capture("billing-limited", wholeWindow = true)
        compose.onNodeWithText("账单历史").assertIsDisplayed()
        compose.onNodeWithText("无法读取账单历史").assertIsDisplayed()
        compose.onNodeWithTag("billing-reauthorize").performScrollTo().performClick()
        compose.waitUntil(12000) { m.tokenConnection.state.value.upgradePlan != null }
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").performScrollTo().performTextInput("synthetic-restored-billing-token")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("token-submit").performScrollTo().performClick()
        compose.waitUntil(12000) { m.tokenConnection.state.value.connected }
        compose.onNodeWithTag("old-token-skip").performScrollTo().performClick()
        compose.waitUntil(12000) {
            m.controller.state.value.products[com.flarego.core.application.inventory.ProductKey.BILLING]?.let {
                it.observedAtMs != null && it.error == null
            } == true
        }
        compose.onNodeWithTag("nav-billing").assertIsSelected()
        compose.onNodeWithTag("billing-permission-card").assertDoesNotExist()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
        assertTrue(requests.all { it.method == "GET" })
        compose.onNodeWithText("1 USD").assertIsDisplayed()
        compose.onNodeWithText("账单历史").assertIsDisplayed()
        capture("billing-upgrade-return", wholeWindow = true)
    }

    @Test fun forbiddenReadDoesNotLookLikeAnEmptyDeployment() {
        install(403)
        compose.onNodeWithText("尚无部署").assertDoesNotExist()
        compose.onNodeWithText("Cloudflare 拒绝读取", substring = true).assertIsDisplayed()
        capture("403-permission")
    }
}
