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
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
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
    private val transport = HttpTransport { r ->
        requests += r
        check(r.method == "GET") { "Unexpected write in a read-only path" }
        val isB = "/accounts/$b/" in r.path
        val result = when {
            r.path == "/accounts/$a" -> """{"id":"$a","name":"$accountA"}"""
            r.path == "/accounts/$b" -> """{"id":"$b","name":"$accountB"}"""
            r.path.contains("/workers/workers?") -> """[{"id":"$worker","name":"edge-router"}]"""
            r.path.contains("/scripts-search?id=$worker&") -> """[{"id":"$worker","script_name":"mapped-script"}]"""
            r.path.contains("/mapped-script/deployments?") -> when {
                status != 200 -> return@HttpTransport HttpResponse(status, "{}")
                isB -> """{"deployments":[]}"""
                else -> """{"deployments":[$release,$oldRelease]}"""
            }
            r.path.endsWith("/mapped-script/deployments/$historic") -> oldRelease
            r.path.contains("/mapped-script/versions?") -> """{"items":[{"id":"$new","number":26},{"id":"$v2","number":25}]}"""
            r.path.endsWith("/mapped-script/versions/$new") -> """{"id":"$new","number":26,"metadata":{"created_on":"2026-11-21T15:00:00Z"}}"""
            else -> error("Unexpected fixture request")
        }
        val paging = if (r.path.contains("/workers/workers?")) ""","result_info":{"page":1,"total_pages":1}""" else ""
        HttpResponse(200, """{"success":true,"result":$result,"errors":[],"messages":[]$paging}""")
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity || model != null) return
            val transfers = object : TransferPort {
                override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead =
                    error("Unexpected transfer")
            }
            val m = FlareGoViewModel(application, Dispatchers.IO, { transport }, { transfers })
            initial = m.controller.state.value.connections.map { it.id }.toSet()
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
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() { instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) } }
        override fun after() { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

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

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureM1") != "true") return
        compose.waitForIdle()
        val dir = File(application.cacheDir, "worker-m1-evidence").also { it.mkdirs() }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
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
        node("worker-deployment-$deployed").assert(hasText("当前部署", substring = true))
        capture("03-history")
        node("worker-deployment-$historic").assert(hasText("历史记录", substring = true)).performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.deployment?.value?.id == historic }
        node("worker-historic-allocation").assert(hasAnyDescendant(hasText("100%", substring = true)))
        compose.onNodeWithText("90%", substring = true).assertDoesNotExist()
        capture("04-snapshot")
        Espresso.pressBack()
        node("worker-deployment-$historic").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        node("worker-versions").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.versions?.value != null }
        node("worker-version-$new").assert(hasText("未分配流量", substring = true))
        capture("18-versions")
        node("worker-version-$new").performClick()
        compose.waitUntil(12_000) { model?.controller?.workerReleases?.state?.value?.version?.value?.id == new }
        node("worker-version-allocation").assert(hasAnyDescendant(hasText("未分配流量", substring = true)))
        capture("05-version")
        repeat(3) { compose.onNodeWithContentDescription("返回").performClick() }
        compose.onNodeWithText("edge-router").assertIsDisplayed()
        assertTrue(requests.all { it.method == "GET" })
        assertTrue(requests.any { it.path == "/accounts/$a/workers/scripts/mapped-script/deployments/$historic" })
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

    @Test fun forbiddenReadDoesNotLookLikeAnEmptyDeployment() {
        install(403)
        compose.onNodeWithText("尚无部署").assertDoesNotExist()
        compose.onNodeWithText("Cloudflare 拒绝读取", substring = true).assertIsDisplayed()
        capture("403-permission")
    }
}
