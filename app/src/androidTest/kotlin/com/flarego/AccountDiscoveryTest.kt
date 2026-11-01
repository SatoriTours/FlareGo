package com.flarego

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.CloudflareIdentityClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.platform.*
import com.flarego.ui.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real OAuth owner and dialog together. Only external HTTP and persistence are fixtures. */
class AccountDiscoveryTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val config = OAuthConfig("fixture", "https://login.example.org/cloudflare/callback", listOf("account-settings.read"))
    private val account = "a".repeat(32)
    private val second = "b".repeat(32)
    private val paths = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private var dismissed by mutableStateOf(false)

    @After fun cleanup() { scope.cancel() }

    private fun install(accounts: List<String>, denied: Boolean = false): AndroidOAuthConnection {
        val endpoint = OAuthTokenEndpoint(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"fixture-access","refresh_token":"fixture-refresh","token_type":"bearer","expires_in":900}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val owner = AndroidOAuthConnection(scope, config, {
            CloudflareIdentityClient(CloudflareGateway(HttpTransport { request ->
                paths += request.path
                if (denied) HttpResponse(403, "fixture-private-body")
                else if (request.path.startsWith("/accounts?")) {
                    val rows = accounts.joinToString(",") { """{"id":"$it","name":"账号 ${it.first()}"}""" }
                    HttpResponse(200, """{"success":true,"result":[$rows],"result_info":{"page":1,"per_page":50,"count":${accounts.size},"total_count":${accounts.size}}}""")
                } else {
                    val id = request.path.substringAfterLast('/')
                    HttpResponse(200, """{"success":true,"result":{"id":"$id","name":"已验证账号"}}""")
                }
            }))
        }, { _, id, _ -> saved += id }, endpoint)
        compose.setContent { FlareGoTheme { if (!dismissed) ConnectionDialog({ dismissed = true }, owner) } }
        compose.runOnIdle {
            val auth = owner.begin().toHttpUrl()
            owner.acceptCallback(config.redirectUri + "?code=fixture&state=" + auth.queryParameter("state"))
        }
        return owner
    }

    @Test fun singleAccountCallbackCompletesDialogWithoutAnyInputOrClick() {
        install(listOf(account))
        compose.waitUntil(10_000) { dismissed }
        compose.onNodeWithTag("oauth-title").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(account), saved)
            assertEquals(listOf("/accounts?page=1&per_page=50", "/accounts/$account"), paths)
        }
    }

    @Test fun multiAccountCallbackWaitsForNamedChoiceThenCompletes() {
        val owner = install(listOf(account, second))
        compose.waitUntil(10_000) { owner.state.value.accounts.size == 2 }
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertFalse(dismissed) }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("oauth-account-$second").performScrollTo().performClick()
        compose.waitUntil(10_000) { dismissed }
        compose.runOnIdle { assertEquals(listOf(second), saved); assertEquals("/accounts/$second", paths.last()) }
    }

    @Test fun rejectedDiscoveryKeepsDialogOpenWithRecoveryAndNeverSaves() {
        val owner = install(listOf(account), denied = true)
        compose.waitUntil(10_000) { owner.state.value.discoveryFailed }
        compose.onNodeWithTag("oauth-error").performScrollTo().assertTextContains("权限", substring = true)
        compose.onNodeWithTag("oauth-discover").performScrollTo().assertIsEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertFalse(dismissed) }
    }
}
