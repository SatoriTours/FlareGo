package com.flarego

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.FlareGoApp
import com.flarego.ui.r2.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real VM/vault/database/Compose, synthetic provider transport; no Cloudflare network traffic. */
class R2ManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val account = "d".repeat(32)
    private val requests = java.util.Collections.synchronizedList(mutableListOf<HttpRequest>())
    @Volatile private var bucketStorageClass = "Standard"
    private var putCreated = false
    private var putStorageClass = "Standard"
    private var honorPutStorageClass = true
    private var honorBulkDelete = true
    private val objectKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add("dir/sub/file.txt") }
    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request; sink.write(byteArrayOf(1,2,3),0,3); sink.close()
            return HttpResponseHead(200, emptyMap(),3)
        }
    }
    private fun install(allowWrites: Boolean = false) {
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m = FlareGoViewModel(app, Dispatchers.IO, { HttpTransport { request ->
                requests += request
                if (request.path == "/accounts/$account/tokens/verify") return@HttpTransport HttpResponse(401, "denied")
                if (request.method == "GET" && request.path.endsWith("/r2/buckets/put-bucket") && !putCreated)
                    return@HttpTransport HttpResponse(404, "absent")
                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" -> if (allowWrites)
                        """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                        else """{"id":"self","status":"active","policies":[]}"""
                    request.path == "/user/tokens/permission_groups" -> """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.method == "DELETE" && request.path.endsWith("/objects/dir/sub/file.txt") -> {
                        objectKeys.remove("dir/sub/file.txt")
                        """{"key":"dir/sub/file.txt"}"""
                    }
                    request.method == "DELETE" && request.path.endsWith("/objects") -> {
                        val keys = Json.parseToJsonElement((request.payload as HttpBody.Text).value).jsonArray.map { it.jsonPrimitive.content }
                        if (honorBulkDelete) objectKeys.removeAll(keys.toSet())
                        JsonArray(keys.map { key -> buildJsonObject { put("key", key) } }).toString()
                    }
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Test"}"""
                    request.path.contains("/objects?") -> JsonArray(objectKeys.map { key ->
                        buildJsonObject { put("key", key); put("size", 3); put("etag", "e") }
                    }).toString()
                    request.method == "GET" && request.path.endsWith("/r2/buckets/put-bucket") ->
                        """{"name":"put-bucket","storage_class":"$putStorageClass"}"""
                    request.method == "PUT" && request.path.endsWith("/r2/buckets/put-bucket") -> {
                        putCreated = true
                        putStorageClass = if (honorPutStorageClass) checkNotNull(request.headers["cf-r2-storage-class"]) else "Standard"
                        """{"name":"put-bucket","storage_class":"${request.headers["cf-r2-storage-class"]}"}"""
                    }
                    request.method == "PATCH" && request.path.endsWith("/r2/buckets/my-bucket") -> {
                        bucketStorageClass = checkNotNull(request.headers["cf-r2-storage-class"])
                        """{"name":"my-bucket","location":"weur","storage_class":"$bucketStorageClass"}"""
                    }
                    request.path.endsWith("/r2/buckets/my-bucket") -> """{"name":"my-bucket","location":"weur","storage_class":"$bucketStorageClass"}"""
                    request.path.contains("/r2/buckets?") -> {
                        val rows = mutableListOf("""{"name":"my-bucket","location":"weur","storage_class":"$bucketStorageClass"}""")
                        if (putCreated) rows += """{"name":"put-bucket","storage_class":"$putStorageClass"}"""
                        """{"buckets":[${rows.joinToString(",") }]}"""
                    }
                    else -> "[]"
                }
                HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")
            } }, { transfers })
            model = m; owner.put("r2", m)
            initial = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("R2 Test", account, "synthetic-r2-token") }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = model!!
        compose.setContent { FlareGoApp(m.controller, {_,_,_->}, m::disconnect, {}, r2Browser = m.r2Browser) }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.buckets.rows.isNotEmpty() }
    }
    private fun captureBucketEvidence(name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val current = model!!.controller.state.value
        check(current.selected?.accountId == account && current.page == Page.RESOURCES && current.resourceKind == ResourceKind.R2)
        check(name.matches(Regex("[a-z-]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("mkdir -p /sdcard/Pictures/flarego-r2-bucket-editor-evidence").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/flarego-r2-bucket-editor-evidence/$name.png bs=8192")
        val image = checkNotNull(automation.takeScreenshot())
        try {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { image.recycle(); pipes.forEach { runCatching { it.close() } } }
    }
    private fun previewPutBucket() {
        compose.onNodeWithTag("rb-create-mode-put").performScrollTo().performClick()
        compose.onNodeWithTag("rb-location-hint-none").assertDoesNotExist()
        compose.onNodeWithTag("rb-create-name").performScrollTo().performTextInput("put-bucket")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("rb-storage-class-InfrequentAccess").performScrollTo().performClick()
        compose.onNodeWithTag("rb-storage-class-InfrequentAccess").assertIsSelected()
        compose.onNodeWithTag("rb-create-mode-put").performScrollTo()
        captureBucketEvidence("put-mode")
        compose.onNodeWithTag("rb-create-submit").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.preview != null }
        compose.runOnIdle {
            val preview = model!!.r2Browser.state.value.preview!!
            assertTrue(preview.detail.impact.contains("PUT"))
            assertTrue(preview.detail.impact.contains("InfrequentAccess"))
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    private fun previewStorageClassPatch() {
        compose.onNodeWithTag("rb-edit-my-bucket").performScrollTo().performClick()
        compose.onNodeWithTag("rb-edit-original").performScrollTo().assertTextContains("Standard", substring = true)
        compose.onNodeWithTag("rb-edit-submit").assertIsNotEnabled()
        compose.onNodeWithTag("rb-edit-class-InfrequentAccess").performScrollTo().performClick()
        captureBucketEvidence("patch-editor")
        compose.onNodeWithTag("rb-edit-submit").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.preview != null }
        compose.runOnIdle {
            val impact = model!!.r2Browser.state.value.preview!!.detail.impact
            assertTrue(impact.contains("Standard") && impact.contains("InfrequentAccess") && impact.contains("已有对象"))
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    private fun confirmAndWait() {
        compose.onNodeWithTag("rp-confirm").performScrollTo().assertIsDisplayed()
        if (model!!.r2Browser.liveCommand() is com.flarego.core.application.r2.R2Command.Bucket)
            captureBucketEvidence(if (model!!.r2Browser.state.value.buckets.editingBucketName == null) "put-preview" else "patch-preview")
        compose.onNodeWithTag("rp-confirm").performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing && model!!.r2Browser.writeTasks.value.isNotEmpty() }
    }
    private fun previewBulkDelete() {
        compose.onNodeWithTag("ro-checkbox-dir/sub/file.txt").performScrollTo().performClick()
        compose.onNodeWithTag("ro-bulk-delete-btn").performScrollTo().performClick()
        compose.onNodeWithTag("ro-bulk-delete-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ro-bulk-delete-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.preview != null }
        compose.runOnIdle {
            assertEquals(listOf("dir/sub/file.txt"), model!!.r2Browser.state.value.preview!!.targets)
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    private fun assertNoMutationWithRunningTask() {
        compose.runOnIdle {
            assertTrue(requests.all { it.method == "GET" })
            assertTrue(requests.any { it.path == "/user/tokens/self" })
            assertEquals(TaskState.FAILED, model!!.r2Browser.writeTasks.value.single().state)
        }
        captureBucketEvidence("pre-dispatch-refusal")
    }
    private fun openBucket() {
        compose.onNodeWithTag("rb-open-my-bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.objects?.rows?.isNotEmpty() == true }
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle { owner.clear() }
    }
    @Test fun actualVmPutBucketCreateUsesTheReviewedHeaderAndEmptyBodyOnlyAfterConfirmation() {
        install(allowWrites = true); previewPutBucket(); confirmAndWait()
        compose.runOnIdle {
            val write = requests.single { it.method != "GET" }
            assertEquals("PUT", write.method)
            assertEquals("/accounts/$account/r2/buckets/put-bucket", write.path)
            assertEquals(HttpBody.Empty, write.payload)
            assertEquals("InfrequentAccess", write.headers["cf-r2-storage-class"])
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            assertTrue(requests.any { it.path == "/user/tokens/self" })
        }
    }
    @Test fun actualVmPatchDefaultStorageClassUsesFreshAuthorityAndNoJsonBody() {
        install(allowWrites = true); previewStorageClassPatch(); confirmAndWait()
        compose.runOnIdle {
            val write = requests.single { it.method != "GET" }
            assertEquals("PATCH", write.method)
            assertEquals("/accounts/$account/r2/buckets/my-bucket", write.path)
            assertEquals(HttpBody.Empty, write.payload)
            assertEquals("InfrequentAccess", write.headers["cf-r2-storage-class"])
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            assertTrue(requests.any { it.path == "/user/tokens/self" })
        }
    }
    @Test fun missingAccountWriteGrantCannotPutABucketAfterSuccessfulPreviewReads() {
        install(); previewPutBucket(); confirmAndWait(); assertNoMutationWithRunningTask()
    }
    @Test fun missingAccountWriteGrantCannotPatchAfterSuccessfulPreviewReads() {
        install(); previewStorageClassPatch(); confirmAndWait(); assertNoMutationWithRunningTask()
    }
    @Test fun unknownAuthoritativeClassAfterPreviewRejectsPatchDespiteGrantedWriteAuthority() {
        install(allowWrites = true); previewStorageClassPatch()
        compose.runOnIdle { bucketStorageClass = "FutureArchive" }
        confirmAndWait(); assertNoMutationWithRunningTask()
    }
    @Test fun cancellingPutPreviewThenSwitchingToPostDoesNotSendOrRestoreAnOldHint() {
        install(allowWrites = true); previewPutBucket()
        compose.onNodeWithTag("rp-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("rb-create-mode-post").performScrollTo().performClick()
        compose.onNodeWithTag("rb-location-hint-none").performScrollTo().assertIsSelected()
        compose.runOnIdle {
            assertNull(model!!.r2Browser.state.value.preview)
            assertTrue(model!!.r2Browser.writeTasks.value.isEmpty())
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    @Test fun actualVmPutReceiptCannotClaimAnUnchangedDefaultClassWasApplied() {
        honorPutStorageClass = false
        install(allowWrites = true)
        previewPutBucket()
        confirmAndWait()
        compose.runOnIdle {
            assertEquals(TaskState.UNKNOWN, model!!.r2Browser.writeTasks.value.single().state)
            assertEquals(1, requests.count { it.method == "PUT" })
            val writeIndex = requests.indexOfFirst { it.method == "PUT" }
            assertTrue(requests.drop(writeIndex + 1).any { it.method == "GET" && it.path.endsWith("/r2/buckets/put-bucket") })
        }
    }
    @Test fun unknownProviderBucketClassStaysVisibleButCannotOpenTheEditor() {
        bucketStorageClass = "FutureArchive"
        install(allowWrites = true)
        compose.onNodeWithTag("rb-edit-my-bucket").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        captureBucketEvidence("unknown-class")
        compose.runOnIdle {
            model!!.r2Browser.onBucketIntent(R2BucketIntent.RequestStorageClassEdit("my-bucket"))
            assertNull(model!!.r2Browser.state.value.buckets.editingBucketName)
            assertNull(model!!.r2Browser.state.value.preview)
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    @Test fun systemBackFromObjectListReturnsToBucketsWithoutCloudMutation() {
        install(); openBucket()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("rb-open-my-bucket").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertNull(model!!.r2Browser.state.value.objects)
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    @Test fun resourceR2TabMountsRealBrowserAndLongSlashKey() {
        install(); openBucket()
        compose.onNodeWithText("dir/sub/file.txt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("r2-read-only-notice").assertIsDisplayed()
        compose.runOnIdle { assertTrue(requests.all { it.method == "GET" }) }
    }
    @Test fun typedBucketDeletePreparesButNeverSendsWithoutTheFinalConfirmation() {
        install()
        compose.onNodeWithTag("rb-delete-my-bucket").performScrollTo().performClick()
        compose.onNodeWithTag("rb-delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("rb-delete-input").performTextInput("my-bucket")
        compose.runOnIdle { assertEquals("my-bucket", model!!.r2Browser.state.value.buckets.typedDeleteName) }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        // The real IME changes the viewport; scroll the target into view before tapping.
        compose.onNodeWithTag("rb-delete-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.preview != null }
        compose.runOnIdle {
            assertEquals("my-bucket", model!!.r2Browser.state.value.preview!!.detail.bucket)
            assertTrue(requests.all { it.method == "GET" })
        }
        compose.onNodeWithTag("rp-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertNull(model!!.r2Browser.state.value.preview) }
    }
    private fun previewObjectDelete() {
        compose.onNodeWithTag("ro-delete-dir/sub/file.txt").performScrollTo().performClick()
        compose.onNodeWithTag("ro-delete-input").performTextInput("dir/sub/file.txt")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("ro-delete-confirm").performScrollTo().performClick()
        compose.waitUntil(5000) { model!!.r2Browser.state.value.preview != null }
        compose.runOnIdle { assertTrue(requests.all { it.method == "GET" }) }
    }
    @Test fun actualVmRequiresPreviewThenExplicitConfirmationAndFreshAccountAuthority() {
        install(allowWrites = true); openBucket(); previewObjectDelete()
        compose.onNodeWithTag("rp-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertEquals(1, requests.count { it.method == "DELETE" })
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            assertTrue(requests.any { it.path == "/user/tokens/self" })
        }
    }
    @Test fun missingWriteGrantDoesNotSendAndReadOnlyRecoveryDoesNotRetry() {
        install(); openBucket(); previewObjectDelete()
        compose.onNodeWithTag("rp-confirm").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(TaskState.FAILED, model!!.r2Browser.writeTasks.value.single().state)
            model!!.r2Browser.reconcileTask(model!!.r2Browser.writeTasks.value.single().id)
        }
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(TaskState.FAILED, model!!.r2Browser.writeTasks.value.single().state)
        }
    }
    @Test fun actualVmBulkDeleteReadsBackBeforeClaimingSuccess() {
        install(allowWrites = true); openBucket(); previewBulkDelete(); confirmAndWait()
        compose.runOnIdle {
            assertEquals(TaskState.SUCCEEDED, model!!.r2Browser.writeTasks.value.single().state)
            val index = requests.indexOfFirst { it.method == "DELETE" }
            assertTrue(index >= 0)
            assertTrue(requests.drop(index + 1).any { it.method == "GET" && it.path.contains("/objects?") })
            assertEquals(1, requests.count { it.method == "DELETE" })
        }
    }
    @Test fun actualVmBulkReceiptWithAStillPresentKeyRemainsUnknownAndNeverReplays() {
        honorBulkDelete = false
        install(allowWrites = true); openBucket(); previewBulkDelete(); confirmAndWait()
        compose.runOnIdle {
            assertEquals(TaskState.UNKNOWN, model!!.r2Browser.writeTasks.value.single().state)
            assertEquals(1, requests.count { it.method == "DELETE" })
            model!!.r2Browser.reconcileTask(model!!.r2Browser.writeTasks.value.single().id)
        }
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertEquals(TaskState.ACTION_REQUIRED, model!!.r2Browser.writeTasks.value.single().state)
            assertEquals(1, requests.count { it.method == "DELETE" })
        }
    }
    @Test fun uploadChooserCancellationPreservesDraftAndDoesNotUpload() {
        install(); openBucket()
        compose.onNodeWithTag("ro-upload-key").performScrollTo().performTextInput("dir/new.txt")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("ro-upload-btn").performScrollTo().performClick()
        compose.runOnIdle {
            val m = model!!
            val request = m.r2Browser.documentRequests.value!!
            val launch = m.takeR2DocumentLaunch(request.id)!!
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.second.action)
            // Uses the actual VM result handler; cancellation must not stage or upload anything.
            m.onR2DocumentResult(R2DocumentKind.UPLOAD, Activity.RESULT_CANCELED, null)
            assertEquals("dir/new.txt", m.r2Browser.state.value.objects!!.uploadKey)
            assertNull(m.r2Browser.state.value.stagedUpload)
            assertTrue(requests.all { it.method == "GET" })
        }
    }
    @Test fun switchingAwayWhileFilePickerIsPendingRejectsItsResult() {
        install(); openBucket()
        compose.onNodeWithTag("ro-upload-key").performScrollTo().performTextInput("stale.txt")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("ro-upload-btn").performScrollTo().performClick()
        compose.runOnIdle {
            val m = model!!
            val request = m.r2Browser.documentRequests.value!!
            m.takeR2DocumentLaunch(request.id)
            m.controller.navigate(Page.OVERVIEW)
            assertFalse(m.r2Browser.isDocumentCurrent(request.id))
            m.onR2DocumentResult(R2DocumentKind.UPLOAD, Activity.RESULT_OK, Intent())
            assertNull(m.r2Browser.state.value.stagedUpload)
        }
    }
}
