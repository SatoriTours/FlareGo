package com.flarego

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ClipData
import android.net.Uri
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import org.json.JSONArray
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.core.application.connection.TokenPhase
import com.flarego.core.application.AppController
import com.flarego.core.ports.SessionFactory
import com.flarego.core.application.connection.TokenProblem
import com.flarego.core.application.connection.RetiredToken
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.application.connection.TokenUpgradePlan
import com.flarego.core.ports.TokenKind
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.MemoryStore
import com.flarego.ui.FlareGoApp
import com.flarego.core.ports.AccountSummary
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.TokenConnection
import com.flarego.ui.TokenConnectionDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fake credential boundary only: these tests never hold or print a real token. */
class TokenConnectionUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeToken(initial: TokenConnectionStatus = TokenConnectionStatus()) : TokenConnection {
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<TokenConnectionStatus> = mutable.asStateFlow()
        val submitted = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val manual = mutableListOf<String>()
        var retries = 0
        var loads = 0
        var cancels = 0
        override fun submit(token: String) { submitted += token }
        override fun retry() { retries++ }
        override fun loadMore() { loads++ }
        override fun selectAccount(accountId: String) { selected += accountId }
        override fun useAccountId(accountId: String) { manual += accountId }
        override fun cancel() { cancels++ }
    }

    private fun dialog(fake: FakeToken, large: Boolean = false, existing: Boolean = true, onDismiss: () -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) { TokenConnectionDialog(onDismiss, fake) } }
            }
        }
        if (existing && fake.state.value.phase == TokenPhase.IDLE) openExisting()
    }

    private fun openExisting() {
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
    }

    private fun masked(): Boolean =
        compose.onNodeWithTag("token-field").fetchSemanticsNode().config.contains(SemanticsProperties.Password)

    /** What the field actually renders: bullets while masked, the raw value only when revealed. */
    private fun fieldDisplay(): String =
        compose.onNodeWithTag("token-field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    private fun fieldText(): String =
        compose.onNodeWithTag("token-field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @Test fun browserTemplatePrefillsAllReadPermissionsAndReturnsToPaste() {
        val fake = FakeToken()
        val opened = mutableListOf<String>()
        installJourney(fake, open = { opened += it })
        compose.onNodeWithTag("token-field").assertDoesNotExist()
        compose.onNodeWithTag("token-create").assertIsEnabled()
        compose.onNodeWithTag("token-create").performScrollTo().performClick()
        compose.runOnIdle {
            val uri = Uri.parse(opened.single())
            assertEquals("https", uri.scheme)
            assertEquals("dash.cloudflare.com", uri.host)
            assertEquals("/profile/api-tokens", uri.path)
            assertEquals(setOf("permissionGroupKeys", "accountId", "zoneId", "name"), uri.queryParameterNames)
            assertEquals("*", uri.getQueryParameter("accountId"))
            assertEquals("all", uri.getQueryParameter("zoneId"))
            val group = JSONArray(uri.getQueryParameter("permissionGroupKeys"))
            val keys = (0 until group.length()).map {
                val permission = group.getJSONObject(it)
                assertEquals(setOf("key", "type"), permission.keys().asSequence().toSet())
                assertEquals("read", permission.getString("type"))
                permission.getString("key")
            }
            assertEquals(setOf("account_settings", "zone", "dns", "zone_settings", "ssl_and_certificates",
                "workers_scripts", "workers_kv_storage", "workers_r2", "d1", "billing"), keys.toSet())
            assertEquals(keys.size, keys.distinct().size)
            assertTrue(fake.submitted.isEmpty())
        }
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        compose.onNodeWithTag("token-submit").assertIsNotEnabled()
        compose.onNodeWithTag("token-create-back").performScrollTo().performClick()
    }

    @Test fun failedBrowserLaunchKeepsCreationScreenAndCanRetry() {
        var calls = 0
        installJourney(FakeToken(), open = { if (++calls == 1) throw IllegalArgumentException("fake no browser") })
        compose.onNodeWithTag("token-create").performScrollTo().performClick()
        compose.onNodeWithTag("token-browser-error").assertExists()
        compose.onNodeWithTag("token-field").assertDoesNotExist()
        compose.onNodeWithTag("token-create").performScrollTo().performClick()
        compose.onNodeWithTag("token-browser-error").assertDoesNotExist()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun pasteReadsOnlyOnClickAndRejectsEmptyAndNonText() {
        val fake = FakeToken()
        var reads = 0
        var clip: ClipEntry? = null
        installJourney(fake, read = { reads++; clip })
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithTag("token-paste").performScrollTo().performClick()
        compose.onNodeWithTag("token-paste-error").assertExists()
        assertEquals("", fieldText())
        compose.runOnIdle { clip = ClipEntry(ClipData.newRawUri("fake", Uri.parse("content://fake/nontext"))) }
        compose.onNodeWithTag("token-paste").performScrollTo().performClick()
        compose.onNodeWithTag("token-paste-error").assertExists()
        assertEquals("", fieldText())
        compose.runOnIdle { clip = ClipEntry(ClipData.newPlainText("fake", "  fake-token-not-real  ")) }
        compose.onNodeWithTag("token-paste").performScrollTo().performClick()
        compose.onNodeWithTag("token-paste-error").assertDoesNotExist()
        assertEquals("•".repeat("fake-token-not-real".length), fieldDisplay())
        compose.runOnIdle { assertEquals(3, reads); assertTrue(fake.submitted.isEmpty()) }
        compose.onNodeWithTag("token-submit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("fake-token-not-real"), fake.submitted) }
    }

    private fun installJourney(fake: FakeToken, open: (String) -> Unit = { error("unexpected launch") }, read: () -> ClipEntry? = { null }) {
        compose.setContent {
            val platform = LocalClipboard.current
            val clipboard = remember(platform) {
                object : Clipboard by platform {
                    override suspend fun getClipEntry(): ClipEntry? = read()
                }
            }
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) = open(uri) },
                LocalClipboard provides clipboard,
            ) {
                FlareGoTheme { TokenConnectionDialog({}, fake) }
            }
        }
    }

    @Test fun tokenInputIsMaskedAndRevealIsExplicit() {
        dialog(FakeToken())
        val raw = "fake-token-not-real"
        compose.onNodeWithTag("token-field").performTextInput(raw)
        // Hidden state: the field is marked as a password input and renders only bullets.
        assertTrue(masked())
        assertEquals("•".repeat(raw.length), fieldDisplay())
        compose.onNodeWithContentDescription("显示 Token").assertExists()
        compose.onNode(hasStateDescription("Token 已隐藏")).assertExists()
        compose.onNodeWithTag("token-reveal").performClick()
        // Revealed state is explicit in both the icon affordance and the field's state text.
        compose.onNodeWithContentDescription("隐藏 Token").assertExists()
        compose.onNode(hasStateDescription("Token 已显示")).assertExists()
        assertEquals(raw, fieldDisplay())
        compose.onNodeWithTag("token-reveal").performClick()
        compose.onNodeWithContentDescription("显示 Token").assertExists()
        compose.onNode(hasStateDescription("Token 已隐藏")).assertExists()
        assertEquals("•".repeat(raw.length), fieldDisplay())
    }

    @Test fun emptyOrBlankTokenCannotSubmit() {
        val fake = FakeToken()
        dialog(fake)
        compose.onNodeWithTag("token-submit").assertIsNotEnabled()
        compose.onNodeWithTag("token-field").performTextInput("   ")
        compose.onNodeWithTag("token-submit").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fake.submitted.isEmpty()) }
    }

    @Test fun submitClearsPlaintextAndBlocksDuplicateWhileBusy() {
        val fake = FakeToken()
        dialog(fake)
        compose.onNodeWithTag("token-field").performTextInput("fake-token-not-real")
        compose.onNodeWithTag("token-submit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("fake-token-not-real"), fake.submitted) }
        compose.onNodeWithTag("token-submit").assertIsNotEnabled()
        assertEquals("", fieldText())
        compose.runOnIdle { fake.mutable.value = fake.mutable.value.copy(phase = TokenPhase.VERIFYING, busy = true, tokenHeld = true) }
        compose.onNodeWithTag("token-progress").assertExists()
        compose.onNodeWithTag("token-submit").assertIsNotEnabled()
    }

    @Test fun discoveredAccountsAreSelectableAndConnectDismisses() {
        val fake = FakeToken()
        var dismissed = 0
        dialog(fake, onDismiss = { dismissed++ })
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(
                phase = TokenPhase.CHOOSING, tokenHeld = true,
                accounts = listOf(AccountSummary("acct-one", "主账号"), AccountSummary("acct-two", "备用账号")),
            )
        }
        compose.onNodeWithTag("token-account-acct-one").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("acct-one"), fake.selected) }
        compose.onNodeWithText("备用账号").assertIsDisplayed()
        compose.runOnIdle { fake.mutable.value = fake.mutable.value.copy(phase = TokenPhase.CONNECTED, connected = true, busy = false) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun loadMoreOnlyWhenAnotherPageExists() {
        val fake = FakeToken()
        dialog(fake)
        compose.onNodeWithTag("token-load-more").assertDoesNotExist()
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.CHOOSING, tokenHeld = true, hasMore = true,
                accounts = listOf(AccountSummary("acct-one", "主账号")))
        }
        compose.onNodeWithTag("token-load-more").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fake.loads) }
    }

    @Test fun restrictedTokenNeedsFullManualAccountId() {
        val fake = FakeToken()
        dialog(fake)
        compose.runOnIdle { fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.CHOOSING, tokenHeld = true, needsAccountId = true) }
        compose.onNodeWithTag("token-account-id").assertDoesNotExist()
        compose.onNodeWithTag("token-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("token-manual-submit").assertIsNotEnabled()
        compose.onNodeWithTag("token-account-id").performScrollTo().performTextInput("abc123")
        compose.onNodeWithTag("token-manual-submit").assertIsNotEnabled()
        compose.onNodeWithTag("token-account-id").performScrollTo().performTextReplacement("0123456789abcdef0123456789abcdef")
        compose.onNodeWithTag("token-manual-submit").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("0123456789abcdef0123456789abcdef"), fake.manual) }
    }

    @Test fun permissionAndNetworkErrorsAreDistinctAndRetryable() {
        val fake = FakeToken()
        dialog(fake)
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.IDLE, tokenHeld = true, canRetry = true,
                problem = TokenProblem.PERMISSION, error = "该 Token 缺少读取账号的权限")
        }
        compose.onNodeWithText("该 Token 缺少读取账号的权限").assertExists()
        compose.onNodeWithText("该 Token 权限不足：请在官方页面补充 Account 读取权限后重试。").assertExists()
        compose.onNodeWithTag("token-retry").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, fake.retries) }
        compose.runOnIdle {
            fake.mutable.value = fake.mutable.value.copy(problem = TokenProblem.NETWORK, error = "网络不可用，请稍后重试")
        }
        compose.onNodeWithText("网络不可用，请稍后重试").assertExists()
        compose.onNodeWithText("网络不可用，请检查连接后重试。").assertExists()
        compose.onNodeWithTag("token-retry").assertIsDisplayed()
    }

    @Test fun cancelForgetsTokenAndReopeningStartsEmpty() {
        val fake = FakeToken()
        var dismissed = 0
        var open by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            FlareGoTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (open) TokenConnectionDialog({ dismissed++; open = false }, fake)
                }
            }
        }
        openExisting()
        compose.onNodeWithTag("token-field").performTextInput("fake-token-not-real")
        compose.onNodeWithTag("token-cancel").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, fake.cancels); assertEquals(1, dismissed) }
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("token-field").assertDoesNotExist()
        openExisting()
        assertEquals("", fieldText())
    }

    @Test fun noOAuthEntryExistsAnywhereInTheTokenFlow() {
        dialog(FakeToken(), existing = false)
        compose.onNodeWithTag("token-use-oauth").assertDoesNotExist()
        compose.onNodeWithText("OAuth", substring = true).assertDoesNotExist()
        // Only the API Token paths are supported, whether newly created or already held.
        compose.onNodeWithTag("token-create").performScrollTo().assertIsDisplayed()
        openExisting()
        compose.onNodeWithTag("token-use-oauth").assertDoesNotExist()
    }

    @Test fun screenshotLargeFontHidesTokenValue() {
        val fake = FakeToken()
        dialog(fake)
        compose.onNodeWithTag("token-field").performTextInput("fake-token-not-real")
        // Large font scrolls: capture the primary action after bringing it into view.
        compose.onNodeWithTag("token-submit").performScrollTo()
        compose.waitForIdle()
        assertEquals("•".repeat("fake-token-not-real".length), fieldDisplay())
        shoot("token-font2")
    }

    @Test fun defaultAddAccountUsesOnlyTheTokenFlow() {
        val connection = Connection("token-ui", "测试账号", "token-ui-account")
        val fake = FakeToken()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), listOf(connection))
            }
            FlareGoTheme { FlareGoApp(controller, {}, {}, tokenConnection = fake) }
        }
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithText("连接 Cloudflare 账号").performClick()
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.onNodeWithTag("token-field").assertDoesNotExist()
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
    }

    @Test fun screenshotDefaultFontHidesTokenValue() {
        val fake = FakeToken()
        installJourney(fake, open = {})
        shoot("01-purposes")
        compose.onNodeWithTag("token-create").performScrollTo().performClick()
        compose.onNodeWithTag("token-field").performTextInput("fake-token-not-real")
        assertEquals("•".repeat("fake-token-not-real".length), fieldDisplay())
        shoot("02-token")
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(
                phase = TokenPhase.CHOOSING, tokenHeld = true,
                accounts = listOf(
                    AccountSummary("2fc10000000000000000000000004d8b", "个人工作区"),
                    AccountSummary("76ab000000000000000000000000c921", "团队工作区"),
                ),
            )
        }
        compose.onNodeWithText("团队工作区").assertIsDisplayed()
        shoot("03-accounts")
    }

    @Test fun screenshotScopedUpgradeAndOptionalOldTokenManagement() {
        val account = "1".repeat(32)
        val oldId = "a".repeat(32)
        val fake = FakeToken(TokenConnectionStatus(upgrading = true, upgradePlan =
            TokenUpgradePlan(account, "示例团队", setOf(ReadFeature.ACCOUNT, ReadFeature.ZONES,
                ReadFeature.DNS, ReadFeature.ZONE_SETTINGS, ReadFeature.SSL), "2".repeat(32))))
        dialog(fake, existing = false)
        compose.onNodeWithTag("token-create").assertExists()
        compose.onNodeWithText("已预填当前账号及单个域名", substring = true).performScrollTo().assertIsDisplayed()
        shoot("04-scoped-upgrade")
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.CONNECTED, connected = true, upgrading = true,
                accounts = listOf(AccountSummary(account, "示例团队")), retiredToken = RetiredToken(oldId, TokenKind.USER_OWNED))
        }
        compose.onNodeWithTag("old-token-id").assertTextContains(oldId, substring = true)
        compose.onNodeWithTag("old-token-manage").assertExists()
        compose.onNodeWithTag("old-token-skip").performScrollTo().assertIsDisplayed()
        shoot("05-old-token")
    }

    private fun shoot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("token-screen").captureToImage().asAndroidBitmap()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /sdcard/Pictures/token-ui")).use { it.readBytes() }
        val pipes = automation.executeShellCommandRw("dd of=/sdcard/Pictures/token-ui/$name.png bs=8192")
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { bitmap.recycle(); pipes.forEach { runCatching { it.close() } } }
    }

    @Test fun permissionDetailsCanBeOpenedAndClosedWithoutLeavingAddAccount() {
        dialog(FakeToken(), large = true, existing = false)
        val toggle = compose.onNodeWithText("权限明细")
        val permissions = compose.onNodeWithText("账号、域名、DNS、站点设置、通用 SSL、Workers、KV、R2、D1、账单历史")
        val exclusion = compose.onNodeWithText("Registrar 不在预填范围内。")

        permissions.assertDoesNotExist()
        exclusion.assertDoesNotExist()
        toggle.performScrollTo().performClick()
        permissions.performScrollTo().assertIsDisplayed()
        exclusion.performScrollTo().assertIsDisplayed()
        toggle.performScrollTo().performClick()
        permissions.assertDoesNotExist()
        exclusion.assertDoesNotExist()
        compose.onNodeWithTag("token-field").assertDoesNotExist()
    }

    @Test fun primaryActionStaysReachableByScrolling() {
        val fake = FakeToken()
        dialog(fake, large = true, existing = false)
        compose.onNodeWithTag("token-create").performScrollTo().assertIsDisplayed()
        openExisting()
        compose.onNodeWithTag("token-submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("token-field").performScrollTo().assertIsDisplayed()
    }

    @Test fun verifyingPhaseAllowsSafeCancel() {
        val fake = FakeToken()
        var dismissed = 0
        dialog(fake, onDismiss = { dismissed++ })
        // Verifying is a network phase: leaving must be possible and must forget the token.
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.VERIFYING, busy = true, tokenHeld = true)
        }
        compose.onNodeWithTag("token-cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, fake.cancels); assertEquals(1, dismissed) }
    }

    @Test fun persistingPhaseBlocksCancel() {
        val persisting = FakeToken(TokenConnectionStatus(phase = TokenPhase.CONNECTING, busy = true, tokenHeld = true))
        var dismissed = 0
        dialog(persisting, onDismiss = { dismissed++ })
        compose.onNodeWithTag("token-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, persisting.cancels); assertEquals(0, dismissed) }
    }

    @Test fun savedConnectionIsDismissedWithoutCancellingIt() {
        val fake = FakeToken()
        var dismissed = 0
        dialog(fake, onDismiss = { dismissed++ })
        compose.runOnIdle {
            fake.mutable.value = TokenConnectionStatus(phase = TokenPhase.CONNECTED, connected = true, busy = false)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(0, fake.cancels) }
    }
}
