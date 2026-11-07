package com.flarego

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.flarego.core.application.AppController
import com.flarego.core.demo.DemoSession
import com.flarego.core.demo.demoConnections
import com.flarego.core.ports.MemoryStore
import com.flarego.core.ports.SessionFactory
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
import java.io.File

/** Real dialog interactions; browser and authorization are controlled boundaries, not live cloud evidence. */
class OAuthConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val first = "a".repeat(32)
    private val second = "b".repeat(32)
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("oauthScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "oauth-screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private class Authorization(override val configured: Boolean = true) : OAuthConnection {
        val mutable = MutableStateFlow(OAuthConnectionState())
        override val state: StateFlow<OAuthConnectionState> = mutable
        var selected: String? = null
        var discoveries = 0
        override fun begin(): String { mutable.value = OAuthConnectionState(waiting = true); return "https://dash.cloudflare.com/oauth2/auth?client_id=fixture" }
        override fun cancel() { mutable.value = OAuthConnectionState() }
        override fun launchFailed() { mutable.value = OAuthConnectionState(error = "无法打开浏览器，请安装或启用浏览器后重试") }
        override fun loadMore() { discoveries++ }
        override fun connect(accountId: String) {
            selected = accountId
            mutable.value = mutable.value.copy(busy = true, connecting = true)
        }
    }
    private fun assertNoManualEntry() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Account ID").assertDoesNotExist()
        compose.onNodeWithTag("oauth-token-fallback").assertDoesNotExist()
        compose.onNodeWithText("验证并连接").assertDoesNotExist()
    }
    @Test fun lostCallbackSessionOpensRecoveryOverExistingAccountAndCanBeDismissed() {
        val auth = Authorization()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), demoConnections)
            }
            FlareGoApp(controller, {}, {}, oauthConnection = auth)
        }
        compose.onNodeWithTag("oauth-screen").assertDoesNotExist()
        // A callback can arrive after the app has already composed its old account overview.
        compose.runOnIdle {
            auth.mutable.value = OAuthConnectionState(reauthorizationRequired = true,
                error = "授权会话已丢失，请重新开始授权。已有账号连接未更改。")
        }
        compose.onNodeWithTag("oauth-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("oauth-authorize").assertIsEnabled()
        assertNoManualEntry()
        screenshot("lost-session")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("oauth-screen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(OAuthConnectionState(), auth.mutable.value) }
    }

    @Test fun unconfiguredBuildExplainsUnavailableAuthorizationWithoutManualFallback() {
        val auth = Authorization(false)
        compose.setContent { FlareGoTheme { ConnectionDialog({}, auth) } }
        compose.onNodeWithTag("oauth-authorize").assertIsNotEnabled()
        assertNoManualEntry()
        screenshot("unconfigured")
        compose.onNodeWithText("此版本暂不支持 Cloudflare 授权，请联系维护者获取已配置授权的版本。已有连接仍可使用。")
            .performScrollTo().assertIsDisplayed()
        screenshot("unconfigured-help")
        compose.onNodeWithText("取消").assertIsEnabled()
    }
    @Test fun browserFailureIsRecoverableWithoutSubmittingConnection() {
        val auth = Authorization()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { throw IllegalStateException("no browser") }
            }) { FlareGoTheme { ConnectionDialog({}, auth) } }
        }
        compose.onNodeWithTag("oauth-authorize").performClick()
        compose.onNodeWithTag("oauth-error").performScrollTo().assertIsDisplayed()
        screenshot("browser-failure")
        compose.onNodeWithTag("oauth-authorize").assertIsEnabled()
        assertNoManualEntry()
        compose.runOnIdle { assertNull(auth.selected) }
    }
    @Test fun selectingAccountStartsConnectionImmediatelyAndOnlySuccessDismisses() {
        val auth = Authorization()
        auth.mutable.value = OAuthConnectionState(authorized = true, accounts = listOf(
            AccountSummary(first, "个人账号"), AccountSummary(second, "团队账号")))
        var dismissed = false
        compose.setContent { FlareGoTheme { ConnectionDialog({ dismissed = true }, auth) } }
        assertNoManualEntry()
        compose.onNodeWithTag("oauth-account-$second").performScrollTo().assertTextContains("团队账号")
        screenshot("account-selection")
        compose.onNodeWithTag("oauth-account-$second").performClick()
        compose.runOnIdle { assertEquals(second, auth.selected); assertFalse(dismissed) }
        compose.onNodeWithText("正在连接账号…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        screenshot("connecting")
        compose.runOnIdle { auth.mutable.value = OAuthConnectionState(connected = true) }
        compose.waitUntil { dismissed }
    }
    @Test fun discoveryFailureOffersRetryAndNeverRequestsIdentifiers() {
        val auth = Authorization()
        auth.mutable.value = OAuthConnectionState(authorized = true, discoveryFailed = true,
            error = "读取账号未完成，请检查网络后重试。不会保存未验证的连接。")
        compose.setContent { FlareGoTheme { ConnectionDialog({}, auth) } }
        compose.onNodeWithTag("oauth-error").performScrollTo().assertIsDisplayed()
        screenshot("discovery-failure")
        compose.onNodeWithTag("oauth-discover").performScrollTo().performClick()
        screenshot("discovery-retry")
        assertNoManualEntry()
        compose.runOnIdle { assertEquals(1, auth.discoveries); auth.mutable.value = auth.mutable.value.copy(busy = true) }
        compose.onNodeWithTag("oauth-discover").assertIsNotEnabled()
    }
    @Test fun duplicateAccountNamesAreDistinguishedWithoutShowingFullIdentifiers() {
        val auth = Authorization()
        auth.mutable.value = OAuthConnectionState(authorized = true, accounts = listOf(
            AccountSummary(first, "同名账号"), AccountSummary(second, "同名账号")))
        compose.setContent { FlareGoTheme { ConnectionDialog({}, auth) } }
        compose.onNodeWithTag("oauth-account-$first").performScrollTo().assertTextContains("账号尾号 aaaaaa")
        compose.onNodeWithTag("oauth-account-$second").performScrollTo().assertTextContains("账号尾号 bbbbbb")
        compose.onNodeWithText(first).assertDoesNotExist()
        screenshot("duplicate-names")
    }
    @Test fun waitingCanReopenBrowserAndCancelWithoutConnecting() {
        val auth = Authorization()
        val opened = mutableListOf<String>()
        var dismissed = false
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { FlareGoTheme { ConnectionDialog({ dismissed = true }, auth) } }
        }
        screenshot("ready")
        compose.onNodeWithTag("oauth-authorize").performClick()
        compose.onNodeWithText("等待 Cloudflare 授权").performScrollTo().assertIsDisplayed()
        screenshot("waiting")
        compose.onNodeWithTag("oauth-authorize").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(2, opened.size); assertNull(auth.selected); assertTrue(dismissed) }
    }
    @Test fun expiredGrantOffersAuthorizationAndDisablesAccountSelection() {
        val auth = Authorization()
        auth.mutable.value = OAuthConnectionState(authorized = true, reauthorizationRequired = true,
            accounts = listOf(AccountSummary(first, "个人账号")), error = "授权已失效，请重新前往 Cloudflare 授权。")
        compose.setContent { FlareGoTheme { ConnectionDialog({}, auth) } }
        compose.onNodeWithTag("oauth-account-$first").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("点击重试连接").assertDoesNotExist()
        compose.onNodeWithTag("oauth-discover").assertDoesNotExist()
        compose.onNodeWithTag("oauth-authorize").performScrollTo().assertIsEnabled()
        screenshot("expired")
    }
    @Test fun emptyAccountResultStillOffersRetryAndReauthorization() {
        val auth = Authorization()
        auth.mutable.value = OAuthConnectionState(authorized = true, discoveryFailed = true,
            error = "授权已完成，但没有找到可连接的账号。请重新读取，或返回 Cloudflare 确认授权的账号。")
        compose.setContent { FlareGoTheme { ConnectionDialog({}, auth) } }
        compose.onNodeWithTag("oauth-error").performScrollTo().assertIsDisplayed()
        screenshot("empty")
        compose.onNodeWithTag("oauth-discover").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("oauth-authorize").performScrollTo().assertIsEnabled()
        assertNoManualEntry()
    }
}
