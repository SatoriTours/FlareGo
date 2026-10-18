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
                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" -> if (allowWrites)
                        """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                        else """{"id":"self","status":"active","policies":[]}"""
                    request.path == "/user/tokens/permission_groups" -> """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.method == "DELETE" && request.path.endsWith("/objects/dir/sub/file.txt") -> """{"key":"dir/sub/file.txt"}"""
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Test"}"""
                    request.path.contains("/objects?") -> """[{"key":"dir/sub/file.txt","size":3,"etag":"e"}]"""
                    request.path.endsWith("/r2/buckets/my-bucket") -> """{"name":"my-bucket","location":"weur"}"""
                    request.path.contains("/r2/buckets?") -> """{"buckets":[{"name":"my-bucket","location":"weur"}]}"""
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
    private fun openBucket() {
        compose.onNodeWithTag("rb-open-my-bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.state.value.objects?.rows?.isNotEmpty() == true }
    }
    @After fun cleanup() {
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle { owner.clear() }
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
            assertEquals(TaskState.RUNNING, model!!.r2Browser.writeTasks.value.single().state)
            model!!.r2Browser.reconcileTask(model!!.r2Browser.writeTasks.value.single().id)
        }
        compose.waitUntil(10000) { !model!!.r2Browser.state.value.writing }
        compose.runOnIdle {
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(TaskState.UNKNOWN, model!!.r2Browser.writeTasks.value.single().state)
        }
    }
    @Test fun uploadChooserCancellationPreservesDraftAndDoesNotUpload() {
        install(); openBucket()
        compose.onNodeWithTag("ro-upload-key").performScrollTo().performTextInput("dir/new.txt")
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
