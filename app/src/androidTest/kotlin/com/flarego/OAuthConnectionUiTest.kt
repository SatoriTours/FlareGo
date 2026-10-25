package com.flarego

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.ports.AccountSummary
import com.flarego.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import android.graphics.Bitmap
import java.io.File

/** Actual dialog interactions. The external authorization/browser is a port, not simulated cloud success. */
class OAuthConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("oauthScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Window enter/scroll animations continue after Compose becomes idle.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "oauth-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private class Authorization(override val configured: Boolean) : OAuthConnection {
        val mutable = MutableStateFlow(OAuthConnectionState())
        override val state: StateFlow<OAuthConnectionState> = mutable
        var saved: Pair<String, String>? = null
        var discoveries = 0
        override fun begin(): String { mutable.value = OAuthConnectionState(waiting = true); return "https://dash.cloudflare.com/oauth2/auth?client_id=fixture" }
        override fun cancel() { mutable.value = OAuthConnectionState() }
        override fun launchFailed() { mutable.value = OAuthConnectionState(error = "无法打开浏览器，请安装或启用浏览器后重试") }
        override fun loadMore() { discoveries++ }
        override suspend fun connect(name: String, accountId: String) { saved = name to accountId }
    }
    @Test fun unconfiguredBuildDisablesAuthorizationButPreservesTokenFallback() {
        val auth = Authorization(false)
        val draft = ConnectionDraft()
        compose.setContent { FlareGoTheme { ConnectionDialog({}, { _, _, _ -> error("must not submit") }, draft, oauthConnection = auth) } }
        compose.onNodeWithTag("oauth-authorize").assertIsNotEnabled()
        screenshot("unconfigured")
        compose.onNodeWithText("此构建尚未配置 OAuth 客户端，暂不能授权。可使用下方 API Token 备用方式；已有连接不受影响。").performScrollTo()
        screenshot("unconfigured-help")
        compose.onNodeWithTag("oauth-authorize").performScrollTo()
        screenshot("unconfigured-authorize")
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo()
        screenshot("unconfigured-fallback")
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo().performClick()
        compose.onNodeWithText("API Token").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("API Token")).performScrollTo()
        screenshot("token-input")
        compose.onNodeWithText("获取 API Token").performScrollTo().assertIsDisplayed()
        screenshot("token-fallback")
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
        screenshot("browser-failure")
        compose.onNodeWithText("验证并连接").assertIsNotEnabled()
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo().assertIsEnabled()
        screenshot("browser-failure-fallback")
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
        screenshot("account-selection")
        compose.onNode(hasSetTextAction() and hasText("账号名称（可选）")).performScrollTo()
        screenshot("account-name")
        compose.onNode(hasSetTextAction() and hasText("Account ID")).performScrollTo()
        screenshot("account-id")
        compose.runOnIdle { assertNull(auth.saved); assertFalse(dismissed) }
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertEquals("测试账号" to id, auth.saved); assertTrue(dismissed) }
    }

    @Test fun discoveryFailureOffersRetryAndKeepsManualAccountSelection() {
        val auth = Authorization(true)
        val draft = ConnectionDraft().apply { accountId = "a".repeat(32); name = "手动账号" }
        auth.mutable.value = OAuthConnectionState(authorized = true, discoveryFailed = true,
            error = "无法发现账号，可手填 Account ID 验证；请核对账号读取权限")
        compose.setContent { FlareGoTheme { ConnectionDialog({}, { _, _, _ -> error("must use OAuth") }, draft, oauthConnection = auth) } }
        compose.onNodeWithTag("oauth-discover").performScrollTo().assertTextEquals("重试发现账号").performClick()
        screenshot("discovery-retry")
        compose.onNode(hasSetTextAction() and hasText("账号名称（可选）")).performScrollTo()
        screenshot("retry-name")
        compose.onNode(hasSetTextAction() and hasText("Account ID")).performScrollTo()
        screenshot("retry-account-id")
        compose.runOnIdle {
            assertEquals(1, auth.discoveries)
            assertEquals("a".repeat(32), draft.accountId)
            assertEquals("手动账号", draft.name)
            assertNull(auth.saved)
            auth.mutable.value = auth.mutable.value.copy(busy = true)
        }
        compose.onNodeWithTag("oauth-discover").assertIsNotEnabled()
        compose.onNodeWithText("验证并连接").assertIsNotEnabled()
    }

    @Test fun freshAuthorizationClearsPreviouslySelectedAccount() {
        val auth = Authorization(true)
        val draft = ConnectionDraft().apply { accountId = "a".repeat(32) }
        auth.mutable.value = OAuthConnectionState(authorized = true)
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { FlareGoTheme { ConnectionDialog({}, { _, _, _ -> error("must use OAuth") }, draft, oauthConnection = auth) } }
        }
        compose.onNodeWithTag("oauth-authorize").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", draft.accountId); assertEquals(1, opened.size); assertNull(auth.saved) }
        compose.onNodeWithText("验证并连接").assertIsNotEnabled()
        screenshot("waiting")
        compose.onNodeWithText("正在等待浏览器授权。完成后将返回 App；若进程已退出，请重新授权。").performScrollTo()
        screenshot("waiting-help")
        compose.onNodeWithTag("oauth-token-fallback").performScrollTo()
        screenshot("waiting-fallback")
    }
}
