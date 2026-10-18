package com.flarego

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real MainActivity/registry/OS picker/provider; only the external Cloudflare boundary is synthetic. */
@SdkSuppress(minSdkVersion = 29)
class R2DocumentPickerIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val automation get() = instrumentation.uiAutomation
    private val account = "c".repeat(32)
    private var model: FlareGoViewModel? = null
    private var initialConnections = emptySet<String>()
    private val initialGrants = mutableSetOf<String>()
    private var initialStaging = emptySet<String>()
    private val ownedFiles = mutableListOf<String>()
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val activityCreates = AtomicInteger()
    private var uploadLength = -1L
    private var uploadSha256: String? = null
    private var downloadCalls = 0
    private var pickerResolvedByUser = false
    private var failDownloadAfterFirstChunk = false
    private val byteCount = 5L * 1024 * 1024
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun zeroDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        repeat((byteCount / 8192).toInt()) { digest.update(ByteArray(8192)) }
        return hex(digest.digest())
    }
    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request; downloadCalls++
            var length = 0L
            try {
                val bytes = ByteArray(8192)
                while (length < byteCount) {
                    sink.write(bytes, 0, bytes.size); length += bytes.size
                    if (failDownloadAfterFirstChunk) throw java.io.IOException("synthetic-private-download-marker")
                }
                return HttpResponseHead(200, mapOf("Content-Length" to listOf(byteCount.toString())), length)
            } finally { sink.close() }
        }
    }
    private fun transport() = HttpTransport { request ->
        requests += request
        if (request.path == "/accounts/$account/tokens/verify") return@HttpTransport HttpResponse(401, "denied")
        val result = when {
            request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Picker Fixture"}"""
            request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
            request.path == "/user/tokens/self" -> """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
            request.path == "/user/tokens/permission_groups" -> """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
            request.method == "PUT" && request.path.endsWith("/objects/dir/new.bin") -> {
                val stream = request.payload as HttpBody.Stream
                val digest = MessageDigest.getInstance("SHA-256"); var length = 0L
                try {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.source.read(buffer)
                        if (count < 0) break
                        check(count > 0)
                        digest.update(buffer, 0, count); length += count
                    }
                } finally { stream.source.close() }
                uploadLength = length; uploadSha256 = hex(digest.digest())
                """{"key":"dir/new.bin","size":"$length","etag":"synthetic","uploaded":"2026-10-18T00:00:00Z"}"""
            }
            request.path.contains("/objects?") -> """[{"key":"dir/remote.bin","size":$byteCount,"etag":"synthetic"}]"""
            request.path.endsWith("/r2/buckets/my-bucket") -> """{"name":"my-bucket","storage_class":"Standard"}"""
            request.path.contains("/r2/buckets?") -> """{"buckets":[{"name":"my-bucket","storage_class":"Standard"}]}"""
            else -> "[]"
        }
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
            if (activity !is MainActivity) return
            activityCreates.incrementAndGet()
            if (model != null) return // Real recreation must retain the original store, not reinject a new VM.
            val m = FlareGoViewModel(application, Dispatchers.IO, { transport() }, { transfers })
            initialConnections = m.controller.state.value.connections.map { it.id }.toSet()
            initialGrants += application.contentResolver.persistedUriPermissions.map { it.uri.toString() }
            initialStaging = stagingFiles()
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
    @get:Rule(order = 0) val installFixture = object : ExternalResource() {
        override fun before() { instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) } }
        override fun after() { instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun openR2(): FlareGoViewModel {
        val m = checkNotNull(model)
        compose.runOnIdle {
            assertSame("Actual Activity must use the fixture VM through its real ViewModelStore", m, ViewModelProvider(compose.activity)[FlareGoViewModel::class.java])
            m.viewModelScope.launch { m.connect("R2 Picker Fixture", account, "synthetic-picker-token") }
        }
        compose.waitUntil(10000) { m.controller.state.value.selected?.accountId == account }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.buckets.rows.isNotEmpty() }
        compose.onNodeWithTag("rb-open-my-bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.objects?.rows?.isNotEmpty() == true }
        return m
    }
    private fun node(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) node(root.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun clickSystem(label: String): Boolean {
        var current = node(automation.rootInActiveWindow) { it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true } ?: return false
        while (!current.isClickable) current = current.parent ?: return false
        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK).also { clicked ->
            if (clicked && (label == "Save" || label in ownedFiles)) pickerResolvedByUser = true
        }
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) { if (condition()) return; Thread.sleep(100) }
        assertTrue(message, condition())
    }
    private fun pickerVisible() = automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true
    private fun waitPicker() { waitFor("The actual system DocumentsUI activity must be visible", ::pickerVisible); pickerResolvedByUser = false }
    private fun cancelSystem() {
        pickerResolvedByUser = true
        automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor("Cancelled picker must actually close before another back or teardown") { !pickerVisible() }
    }
    private fun downloads() {
        val toolbar = node(automation.rootInActiveWindow) { it.viewIdResourceName?.endsWith("/toolbar") == true }
        if (node(toolbar) { it.text?.toString() == "Downloads" } != null) return
        clickSystem("Show roots")
        waitFor("System Downloads root must be selectable") { clickSystem("Downloads") }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun shell(command: String): String {
        val pipes = automation.executeShellCommandRw("sh")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\nexit\n").toByteArray()) }
            return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes().decodeToString() }
        } finally { pipes.forEach { runCatching { it.close() } } }
    }
    private fun stagingFiles() = application.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("flarego-r2-upload-") || it.name.startsWith("flarego-dns-export-") }.map { it.name }.toSet()
    private fun captureEvidence(name: String) {
        val current = checkNotNull(model).controller.state.value
        check(current.selected?.accountId == account && current.page == Page.RESOURCES && current.resourceKind == ResourceKind.R2)
        check(name.matches(Regex("[a-z-]+")))
        // AGP uninstalls the app after connected tests. Keep only synthetic-UI images
        // in an explicitly owned shell artifact directory so the parent can pull them.
        check(shell("mkdir -p /sdcard/Pictures/flarego-r2-picker-evidence && printf ready").trim() == "ready")
        // UiAutomation tokenizes the command (not shell quoting); launch dd directly.
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/flarego-r2-picker-evidence/$name.png bs=8192")
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { bitmap.recycle(); pipes.forEach { runCatching { it.close() } } }
    }
    private fun ownedName(kind: String) = "flarego-r2-picker-$kind-${UUID.randomUUID()}.bin".also { ownedFiles += it }
    private fun uploadPicker() {
        compose.onNodeWithTag("ro-upload-key").performScrollTo().performTextInput("dir/new.bin")
        compose.onNodeWithTag("ro-upload-btn").performScrollTo().performClick()
        waitPicker()
    }
    private fun saveSystem(name: String) {
        downloads()
        val edit = node(automation.rootInActiveWindow) { it.isEditable && it.className?.toString() == "android.widget.EditText" }
        assertNotNull("System CreateDocument filename field", edit)
        assertTrue(edit!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        assertTrue("User explicitly saves the new document", clickSystem("Save"))
    }
    private fun recreateUnderPicker(): MainActivity {
        val original = compose.activity
        val prior = activityCreates.get()
        instrumentation.runOnMainSync { original.recreate() }
        waitFor("Real replacement MainActivity must be created under the system picker") { activityCreates.get() == prior + 1 }
        waitPicker()
        return original
    }
    @After fun cleanup() {
        if (pickerVisible()) {
            if (!pickerResolvedByUser) cancelSystem()
            else waitFor("Resolved picker must return without sending a second back to MainActivity") { !pickerVisible() }
        }
        compose.waitForIdle()
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initialConnections }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initialConnections } != false }
        waitFor("All private staging owned by this test must be released") { stagingFiles() == initialStaging }
        ownedFiles.forEach { shell("rm -f /sdcard/Download/${quote(it)}") }
    }
    @Test fun nativeOpenDocumentCancellationReturnsToTheSameVmAndCanOpenAgain() {
        val m = openR2(); uploadPicker()
        assertEquals(1, activityCreates.get())
        cancelSystem()
        compose.waitUntil(10000) { m.r2Browser.state.value.objects?.notice?.contains("取消") == true }
        compose.runOnIdle {
            assertSame(m, ViewModelProvider(compose.activity)[FlareGoViewModel::class.java])
            assertNull(m.r2Browser.state.value.stagedUpload)
            assertTrue(requests.all { it.method == "GET" })
        }
        compose.onNodeWithTag("ro-upload-btn").performScrollTo().performClick(); waitPicker()
        cancelSystem()
        compose.waitUntil(10000) { m.r2Browser.state.value.objects?.notice?.contains("取消") == true }
    }
    @Test fun nativeOpenDocumentStagesExactBytesAndOnlyExplicitConfirmationUploads() {
        val name = ownedName("source")
        shell("dd if=/dev/zero of=/sdcard/Download/${quote(name)} bs=8192 count=640 2>/dev/null")
        val m = openR2(); captureEvidence("object-list"); uploadPicker(); downloads()
        waitFor("The owned source must be selected through the OS picker") { clickSystem(name) }
        compose.waitUntil(15000) { m.r2Browser.state.value.stagedUpload != null }
        compose.runOnIdle {
            val staged = checkNotNull(m.r2Browser.state.value.stagedUpload)
            assertEquals(byteCount, staged.length); assertEquals(zeroDigest(), staged.sha256)
            assertTrue(requests.all { it.method == "GET" })
        }
        compose.onNodeWithTag("ro-upload-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.preview != null }
        compose.runOnIdle { assertTrue(requests.all { it.method == "GET" }) }
        compose.onNodeWithText("目标：dir/new.bin").assertIsDisplayed()
        captureEvidence("upload-preview")
        compose.onNodeWithTag("rp-confirm").performScrollTo().assertIsDisplayed()
        captureEvidence("upload-preview-confirm")
        compose.onNodeWithTag("rp-confirm").performClick()
        compose.waitUntil(15000) { m.r2Browser.writeTasks.value.any { it.state == TaskState.SUCCEEDED } && !m.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "PUT" })
            assertEquals(byteCount, uploadLength); assertEquals(zeroDigest(), uploadSha256)
            assertNull(m.r2Browser.state.value.stagedUpload)
        }
        captureEvidence("upload-settled")
        assertEquals(zeroDigest(), shell("sha256sum /sdcard/Download/${quote(name)}").trim().substringBefore(' '))
        waitFor("Only pre-existing persisted grants may remain") {
            application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() == initialGrants
        }
    }
    @Test fun nativeOpenDocumentDeliversToRetainedVmAfterActualActivityRecreation() {
        val name = ownedName("recreate-source")
        shell("dd if=/dev/zero of=/sdcard/Download/${quote(name)} bs=8192 count=640 2>/dev/null")
        val m = openR2(); uploadPicker()
        val original = recreateUnderPicker()
        downloads(); waitFor("Owned source must remain selectable after recreation") { clickSystem(name) }
        compose.waitUntil(15000) { m.r2Browser.state.value.stagedUpload != null }
        compose.runOnIdle {
            assertNotSame(original, compose.activity)
            assertSame(m, ViewModelProvider(compose.activity)[FlareGoViewModel::class.java])
            assertEquals(2, activityCreates.get())
            assertEquals(byteCount, m.r2Browser.state.value.stagedUpload!!.length)
            assertEquals(zeroDigest(), m.r2Browser.state.value.stagedUpload!!.sha256)
            assertNull(m.r2Browser.state.value.preview)
            assertTrue(m.r2Browser.writeTasks.value.isEmpty())
            assertTrue(requests.all { it.method == "GET" })
        }
        compose.onNodeWithTag("ro-upload-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertNull(m.r2Browser.state.value.stagedUpload) }
    }
    @Test fun nativeCreateDocumentDeliversToRetainedVmAndDoesNotReplayAfterRecreation() {
        val name = ownedName("recreate-download")
        val m = openR2()
        compose.onNodeWithTag("ro-download-dir/remote.bin").performScrollTo().performClick(); waitPicker()
        val original = recreateUnderPicker(); saveSystem(name)
        compose.waitUntil(15000) { m.r2Browser.state.value.objects?.notice?.contains("已保存") == true }
        compose.runOnIdle {
            assertNotSame(original, compose.activity)
            assertSame(m, ViewModelProvider(compose.activity)[FlareGoViewModel::class.java])
            assertEquals(2, activityCreates.get()); assertEquals(1, downloadCalls)
            assertTrue(m.r2Browser.writeTasks.value.isEmpty())
            assertTrue(requests.all { it.method == "GET" })
        }
        assertEquals(zeroDigest(), shell("sha256sum /sdcard/Download/${quote(name)}").trim().substringBefore(' '))
        assertFalse(pickerVisible())
    }
    @Test fun nativePickedUploadAfterLeavingR2CannotStageOrSendIntoAnotherPage() {
        val name = ownedName("stale-source")
        shell("dd if=/dev/zero of=/sdcard/Download/${quote(name)} bs=8192 count=640 2>/dev/null")
        val m = openR2(); uploadPicker()
        instrumentation.runOnMainSync { m.controller.navigate(Page.OVERVIEW) }
        downloads(); waitFor("Owned stale result must return via the OS picker") { clickSystem(name) }
        waitFor("Picker must finish delivering the stale result") { !pickerVisible() }
        compose.waitUntil(10000) { m.r2Browser.state.value.context == null }
        compose.runOnIdle {
            assertNull(m.r2Browser.state.value.stagedUpload); assertNull(m.r2Browser.state.value.preview)
            assertEquals(Page.OVERVIEW, m.controller.state.value.page)
            assertTrue(m.r2Browser.writeTasks.value.isEmpty()); assertTrue(requests.all { it.method == "GET" })
        }
        assertEquals(zeroDigest(), shell("sha256sum /sdcard/Download/${quote(name)}").trim().substringBefore(' '))
        assertEquals(initialGrants, application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet())
    }
    @Test fun nativeCreateDocumentCancellationDoesNotDownloadOrCreateATask() {
        val m = openR2()
        compose.onNodeWithTag("ro-download-dir/remote.bin").performScrollTo().performClick(); waitPicker()
        cancelSystem()
        compose.waitUntil(10000) { m.r2Browser.state.value.objects?.notice?.contains("取消") == true }
        assertEquals(0, downloadCalls)
        assertTrue(m.r2Browser.writeTasks.value.isEmpty())
        assertTrue(requests.all { it.method == "GET" })
        assertEquals(initialGrants, application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet())
    }
    @Test fun nativeDownloadIoFailureDoesNotPublishOrKeepTheOwnedPartialDocument() {
        val name = ownedName("failed-download")
        failDownloadAfterFirstChunk = true
        val m = openR2()
        compose.onNodeWithTag("ro-download-dir/remote.bin").performScrollTo().performClick(); waitPicker(); saveSystem(name)
        compose.waitUntil(15000) { m.r2Browser.state.value.objects?.notice?.contains("未完成") == true }
        assertEquals(1, downloadCalls)
        // Bridge publishes the failure notice before its NonCancellable IO finally.
        // The newly persisted grant is released only after abort has completed.
        waitFor("Failure cleanup must finish before checking owned-file deletion") {
            application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() == initialGrants
        }
        assertEquals("absent", shell("if [ -e /sdcard/Download/${quote(name)} ]; then printf present; else printf absent; fi").trim())
        assertFalse(m.r2Browser.state.value.objects!!.notice!!.contains("synthetic-private-download-marker"))
        assertTrue(requests.all { it.method == "GET" })
        waitFor("Failure must release newly persisted document grants") {
            application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() == initialGrants
        }
    }
    @Test fun nativeCreateDocumentPublishesExactStreamedBytes() {
        val name = ownedName("download")
        val m = openR2()
        compose.onNodeWithTag("ro-download-dir/remote.bin").performScrollTo().performClick(); waitPicker(); saveSystem(name)
        compose.waitUntil(15000) { m.r2Browser.state.value.objects?.notice?.contains("已保存") == true }
        assertEquals(1, downloadCalls)
        assertEquals(byteCount.toString(), shell("stat -c %s /sdcard/Download/${quote(name)}").trim())
        assertEquals(zeroDigest(), shell("sha256sum /sdcard/Download/${quote(name)}").trim().substringBefore(' '))
        assertTrue(requests.all { it.method == "GET" })
        waitFor("Download grants added by this operation must be released") {
            application.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet() == initialGrants
        }
    }
}
