package com.flarego

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.*
import com.flarego.platform.LocalDatabase
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccountDiscoveryTest {
    @get:Rule val compose = createComposeRule()
    private val accountA = "a".repeat(32)
    private val accountB = "b".repeat(32)
    private val secret = "synthetic-discovery-token-not-real"
    private val owner = ViewModelStore()
    private var model: FlareGoViewModel? = null
    private var initialIds = emptySet<String>()

    private fun ok(result: String, info: String = "{}") = HttpResponse(200,
        """{"success":true,"result":$result,"result_info":$info}""")

    private fun createModel(send: suspend (HttpRequest) -> HttpResponse): FlareGoViewModel {
        lateinit var created: FlareGoViewModel
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            created = FlareGoViewModel(app) { HttpTransport(send) }
            owner.put("discovery-fixture", created)
            model = created
            val initial = created.controller.state.value.connections
            initialIds = initial.map { it.id }.toSet()
            assertTrue("fixture IDs must not overwrite an existing connection",
                initial.none { it.accountId == accountA || it.accountId == accountB })
        }
        return created
    }

    private fun installModel(model: FlareGoViewModel, dismiss: () -> Unit = {}) {
        compose.setContent {
            FlareGoTheme {
                ConnectionDialog(dismiss, model::connect, model.connectionDraft,
                    model::discoverAccounts, model::verifyDraftToken)
            }
        }
    }

    @After fun closeModel() {
        compose.runOnIdle {
            model?.let { current ->
                current.controller.state.value.connections.filter { it.id !in initialIds }.forEach(current::disconnect)
            }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initialIds } != false }
        compose.runOnIdle { owner.clear() }
    }

    @Test fun discoveryIsExplicitAndPagedSelectionKeepsTheExistingName() {
        val requests = mutableListOf<PageRequest>()
        val draft = ConnectionDraft().apply { name="自定义名称";token=secret }
        var submitted: List<String>? = null
        compose.setContent {
            FlareGoTheme {
                ConnectionDialog({}, { name,account,token -> submitted=listOf(name,account,token) },draft,
                    discoverAccounts={ token,request ->
                        assertEquals(secret,token);requests+=request
                        if(request.token==null) CloudPage(listOf(AccountSummary(accountA,"First account")),PageToken.Number(2),2)
                        else CloudPage(listOf(AccountSummary(accountB,"Second account")),null,2)
                    })
            }
        }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithText("发现账号").performScrollTo().performClick()
        compose.onNodeWithText("发现更多账号").performScrollTo().performClick()
        compose.onNodeWithTag("discovered-account-$accountB").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PageRequest(),PageRequest(token=PageToken.Number(2))),requests)
            assertEquals(accountB,draft.accountId)
            assertEquals("自定义名称",draft.name)
        }
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertEquals(listOf("自定义名称",accountB,secret),submitted);assertEquals("",draft.token) }
    }

    @Test fun deniedDiscoveryKeepsTheDraftAndManualConnectUsesRealAccountVerification() {
        val paths=mutableListOf<String>()
        val current=createModel { r ->
            paths+=r.path
            when {
                r.path.startsWith("/accounts?") -> HttpResponse(403,"synthetic-provider-private-marker")
                r.path=="/accounts/$accountA" -> ok("""{"id":"$accountA","name":"Manual","type":"standard"}""")
                r.path.contains("r2/buckets") -> ok("""{"buckets":[]}""")
                else -> ok("[]")
            }
        }
        compose.runOnIdle { current.connectionDraft.apply { name="Manual";accountId=accountA;token=secret } }
        var dismissed=false
        installModel(current) { dismissed=true }
        compose.onNodeWithText("发现账号").performScrollTo().performClick()
        compose.onNodeWithTag("connection-error").assertTextContains("无访问权限", substring = true)
        compose.runOnIdle { assertEquals(secret,current.connectionDraft.token);assertEquals(accountA,current.connectionDraft.accountId) }
        compose.onNodeWithText("验证并连接").performClick()
        compose.waitUntil(10_000) { dismissed }
        compose.runOnIdle {
            assertTrue(paths.contains("/accounts/$accountA"))
            assertEquals(accountA,current.controller.state.value.selected?.accountId)
            assertEquals("",current.connectionDraft.token)
        }
    }

    @Test fun validTokenWithDeniedAccountNeverSavesAConnectionOrClearsTheDraft() {
        val paths=mutableListOf<String>()
        val current=createModel { r ->
            paths+=r.path
            if(r.path=="/user/tokens/verify") ok("""{"id":"synthetic-token-id","status":"active"}""")
            else HttpResponse(403,"synthetic-private-body")
        }
        compose.runOnIdle { current.connectionDraft.apply { name="Denied";accountId=accountA;token=secret } }
        installModel(current)
        compose.onNodeWithText("用户 Token").performScrollTo().performClick()
        compose.onNodeWithText("验证 Token（可选）").performScrollTo().performClick()
        compose.onNodeWithText("Token 当前有效；这不表示拥有全部权限，连接前仍需验证指定账号").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("验证并连接").performClick()
        compose.onNodeWithTag("connection-error").assertTextContains("无访问权限", substring = true)
        compose.runOnIdle {
            assertEquals(initialIds,current.controller.state.value.connections.map{it.id}.toSet())
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            LocalDatabase.open(app).use { database ->
                assertEquals("denied access must not leave a persisted connection",
                    initialIds, database.localStore.connections().map { it.id }.toSet())
            }
            assertEquals(secret,current.connectionDraft.token)
            assertEquals(listOf("/user/tokens/verify","/accounts/$accountA"),paths)
        }
    }

    @Test fun accountTokenVerificationDoesNotUseTheUserEndpoint() {
        val paths=mutableListOf<String>()
        val current=createModel { r -> paths+=r.path;ok("""{"id":"synthetic-token-id","status":"active"}""") }
        compose.runOnIdle { current.connectionDraft.apply { accountId=accountA;token=secret } }
        installModel(current)
        compose.onNodeWithText("账号 Token").performScrollTo().performClick()
        compose.onNodeWithText("验证 Token（可选）").performScrollTo().performClick()
        compose.onNodeWithText("Token 当前有效；这不表示拥有全部权限，连接前仍需验证指定账号").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("/accounts/$accountA/tokens/verify"),paths) }
    }

    @Test fun changedAndRestoredTokenDiscardsAnOldDiscoveryResponse() {
        val gate=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        val draft=ConnectionDraft().apply { token=secret }
        compose.setContent {
            FlareGoTheme {
                ConnectionDialog({}, {_,_,_->},draft,discoverAccounts={_,_->
                    entered.complete(Unit);gate.await();CloudPage(listOf(AccountSummary(accountA,"Old account")),null,1)
                })
            }
        }
        try {
            compose.onNodeWithText("发现账号").performScrollTo().performClick()
            compose.waitUntil(5_000) { entered.isCompleted }
            val field=compose.onNode(hasSetTextAction() and hasText("API Token")).performScrollTo()
            field.performTextReplacement("changed-synthetic-token")
            field.performTextReplacement(secret)
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithTag("discovered-account-$accountA").assertDoesNotExist()
            compose.runOnIdle { assertEquals("",draft.accountId);assertEquals(secret,draft.token) }
        } finally { gate.complete(Unit) }
    }

    @Test fun remountKeepsDiscoveryAndKindButCancelClearsBoth() {
        val draft=ConnectionDraft().apply { token=secret }
        var generation by mutableStateOf(0)
        compose.setContent {
            key(generation) {
                FlareGoTheme {
                    ConnectionDialog({}, {_,_,_->},draft,discoverAccounts={_,_->
                        CloudPage(listOf(AccountSummary(accountA,"Retained account")),null,1)
                    })
                }
            }
        }
        compose.onNodeWithText("账号 Token").performScrollTo().performClick()
        compose.onNodeWithText("发现账号").performScrollTo().performClick()
        compose.onNodeWithTag("discovered-account-$accountA").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { generation++ }
        compose.onNodeWithText("账号 Token").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("discovered-account-$accountA").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("",draft.token);generation++ }
        compose.onNodeWithTag("discovered-account-$accountA").assertDoesNotExist()
        compose.onNodeWithText("类型未知").performScrollTo().assertIsSelected()
    }

    @Test fun missingTokenCannotStartDiscoveryAndDoesNotRequestAnyEndpoint() {
        var calls=0
        compose.setContent { FlareGoTheme {
            ConnectionDialog({}, {_,_,_->},discoverAccounts={_,_->calls++;CloudPage(emptyList(),null,null)})
        } }
        compose.onNodeWithText("发现账号").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0,calls) }
    }
}
