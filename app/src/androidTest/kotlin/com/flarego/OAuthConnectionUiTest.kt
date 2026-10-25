package com.flarego

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.core.ports.AccountSummary
import com.flarego.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual dialog interactions. The external authorization/browser is a port, not simulated cloud success. */
class OAuthConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private class Authorization(override val configured: Boolean) : OAuthConnection {
        val mutable = MutableStateFlow(OAuthConnectionState())
        override val state: StateFlow<OAuthConnectionState> = mutable
        var saved: Pair<String, String>? = null
        override fun begin(): String { mutable.value = OAuthConnectionState(waiting = true); return "https://dash.cloudflare.com/oauth2/auth?client_id=fixture" }
        override fun cancel() { mutable.value = OAuthConnectionState() }
        override fun launchFailed() { mutable.value = OAuthConnectionState(error = "无法打开浏览器") }
        override fun loadMore() = Unit
        override suspend fun connect(name: String, accountId: String) { saved = name to accountId }
    }
    @Test fun unconfiguredBuildDisablesAuthorizationButPreservesTokenFallback() {
        val auth = Authorization(false)
        val draft = ConnectionDraft()
        compose.setContent { FlareGoTheme { ConnectionDialog({}, { _, _, _ -> error("must not submit") }, draft, oauthConnection = auth) } }
        compose.onNodeWithTag("oauth-authorize").assertIsNotEnabled()
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo().performClick()
        compose.onNodeWithText("API Token").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("获取 API Token").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(draft.manualToken); assertNull(auth.saved) }
    }
    @Test fun browserFailureIsRecoverableWithoutSubmittingConnection() {
        val auth = Authorization(true)
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw IllegalStateException("no browser") }
            }) { FlareGoTheme { ConnectionDialog({}, { _, _, _ -> error("must not submit") }, oauthConnection = auth) } }
        }
        compose.onNodeWithTag("oauth-authorize").performClick()
        compose.onNodeWithTag("oauth-error").assertIsDisplayed()
        compose.onNodeWithText("验证并连接").assertIsNotEnabled()
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertNull(auth.saved) }
    }
    @Test fun discoveredAccountRequiresExplicitConnectAndNeverDisplaysTokenInput() {
        val id = "a".repeat(32)
        val auth = Authorization(true)
        auth.mutable.value = OAuthConnectionState(authorized = true, accounts = listOf(AccountSummary(id, "测试账号")))
        var dismissed = false
        compose.setContent { FlareGoTheme { ConnectionDialog({ dismissed = true }, { _, _, _ -> error("must use OAuth") }, oauthConnection = auth) } }
        compose.onNodeWithText("API Token").assertDoesNotExist()
        compose.onNodeWithTag("oauth-account-$id").performScrollTo().performClick()
        compose.runOnIdle { assertNull(auth.saved); assertFalse(dismissed) }
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertEquals("测试账号" to id, auth.saved); assertTrue(dismissed) }
    }
}
