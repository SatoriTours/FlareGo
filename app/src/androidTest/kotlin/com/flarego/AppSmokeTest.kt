package com.flarego

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.AppController
import com.flarego.core.application.registrar.RegistrarController
import com.flarego.core.model.AppState
import com.flarego.core.model.Connection
import com.flarego.core.demo.DemoSession
import com.flarego.core.demo.demoConnections
import com.flarego.core.model.RegistrarCandidate
import com.flarego.core.ports.MemoryStore
import com.flarego.core.ports.SessionFactory
import com.flarego.ui.FlareGoApp
import com.flarego.ui.FlareGoTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aLegacyOAuthConnectionIsKeptAndMarkedAsNeedingAToken() {
        val legacy = Connection("legacy-oauth", "旧账号", "a".repeat(32))
        // The real Activity already owns a ComposeView; render into it instead of the test rule.
        compose.runOnUiThread {
            compose.activity.setContent {
                FlareGoTheme {
                    com.flarego.ui.AccountsScreen(
                        AppState(listOf(legacy), legacy),
                        connect = {},
                        remove = {},
                        needsToken = { true },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("旧账号").assertIsDisplayed()
        compose.onNodeWithText("需要 Token").assertIsDisplayed()
        compose.onNodeWithTag("account-needs-token-legacy-oauth").assertIsDisplayed()
        // The record is kept: disconnecting stays an explicit user action.
        compose.onNodeWithText("断开连接").assertIsDisplayed()
    }

    @Test
    fun aLegacyOAuthCallbackIntentHasNoSideEffect() {
        // OAuth is retired: a stale App Link callback must not open a dialog, swap accounts or
        // touch stored data. The build no longer registers the callback host at all.
        compose.runOnUiThread {
            val activity = compose.activity
            val launchIntent = activity.intent
            val callback = Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = android.net.Uri.parse(
                    "https://oauth-unconfigured.invalid/cloudflare/callback?code=synthetic&state=lost-session",
                )
            }
            try {
                InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, callback)
            } finally {
                activity.intent = launchIntent
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
        compose.onNodeWithTag("token-error").assertDoesNotExist()
        compose.onNodeWithText("添加你的云账号").assertIsDisplayed()
    }

    // Demo interactions are explicit test fixtures; production startup never seeds them.
    private fun installDemoFixture() {
        compose.runOnUiThread {
            compose.activity.setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), demoConnections)
                }
                // Mirrors the ViewModel demo branch: three clearly marked sample candidates and
                // no network. The real wiring lives in FlareGoViewModel.registrarSearch.
                val tokenConnection = remember { FakeTokenConnection() }
                val registrar = remember {
                    RegistrarController(scope, { _, keyword, limit ->
                        listOf(".com" to "8.57", ".dev" to "10.11", ".app" to "11.00").take(limit)
                            .map { (suffix, cost) ->
                                RegistrarCandidate(
                                    name = if (keyword.endsWith(suffix)) keyword else keyword + suffix,
                                    registrable = true, tier = "standard", currency = "USD",
                                    registrationCost = "示例 $cost", renewalCost = "示例 $cost",
                                    reason = "示例数据 · 未调用云 API",
                                )
                            }
                    }, { true }, { 0L })
                }
                FlareGoApp(controller, {}, {}, registrar = registrar, tokenConnection = tokenConnection)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun freshInstallGuidesOfficialAuthorizationAcrossTabs() {
        compose.onNodeWithText("添加你的云账号").assertIsDisplayed()
        screenshot("first-launch")
        compose.onNodeWithTag("setup-connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("api-gateway").assertDoesNotExist()
        listOf("domains", "resources", "billing", "accounts").forEach { tab ->
            compose.onNodeWithTag("nav-$tab").performClick()
            compose.onNodeWithTag("setup-connect").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("setup-connect").performClick()
        // API Token is the only add-account flow.
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.onNodeWithTag("token-purpose-billing").assertIsOn()
        enterExistingToken()
        assertTrue("Token 输入框应为空且掩码", emptyMaskedTokenField())
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText("Production · 演示").assertDoesNotExist()
        compose.onNodeWithText("Dev sandbox · 演示").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder =
            File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply {
                mkdirs()
            }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "native-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun submittedSearchStaysVisibleAndCanBeCleared() {
        installDemoFixture()
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput(" media ")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        compose.onNodeWithText("media-assets").assertIsDisplayed()
        compose.onNodeWithText("api-gateway").assertDoesNotExist()
        screenshot("search-submitted")
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.onNodeWithTag("search-field").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        // Restoring the full list retains its scroll offset, including on compact screens.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("api-gateway"))
        compose.onNodeWithText("api-gateway").assertIsDisplayed()
    }

    @Test
    fun dnsNoMatchesExplainsFilterAndClearRestoresRecords() {
        installDemoFixture()
        compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithText("flarego.dev").performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("no-such-record")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithText("没有匹配的解析记录").assertIsDisplayed()
        compose.onNodeWithContentDescription("清空搜索").performClick()
        // Clear keeps search editable; restored rows must be reachable within the resized viewport.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("192.0.2.10"))
        compose.onNodeWithText("192.0.2.10").assertIsDisplayed()
        compose.onNodeWithTag("search-field").performTextInput(" 192.0.2.10 ")
        compose.onNodeWithText("192.0.2.10").assertIsDisplayed()
    }

    @Test
    fun searchIsRetainedAcrossTabs() {
        installDemoFixture()
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("media")
        compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithTag("search-field").assertTextContains("media")
        compose.onNodeWithText("api-gateway").assertDoesNotExist()
    }

    @Test
    fun systemBackFromRootTabReturnsHomeInsteadOfFinishingActivity() {
        installDemoFixture()
        compose.onNodeWithTag("nav-resources").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("nav-overview").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
        screenshot("back-home")
    }

    @Test
    fun systemBackClosesDrawerThenSearchWithoutLeavingResources() {
        installDemoFixture()
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("media")
        compose.onNodeWithTag("search-field").performImeAction()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("search-field").assertIsDisplayed()
        compose.onNodeWithText("media-assets").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("search-field").assertDoesNotExist()
        // Clearing search preserves the list scroll position; the restored item
        // may be outside a 320dp viewport and must remain reachable.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("api-gateway"))
        compose.onNodeWithText("api-gateway").assertIsDisplayed()
    }

    @Test
    fun accountTabOffersOnlyTheTokenEntry() {
        installDemoFixture()
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithContentDescription("连接账号").performClick()
        enterExistingToken()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        compose.onNodeWithTag("token-use-oauth").assertDoesNotExist()
        compose.onNodeWithTag("token-cancel").performClick()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
    }

    @Test
    fun compactNavigationSearchAndDrawer() {
        installDemoFixture()
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("api-gateway").assertIsDisplayed()
        screenshot("resources")
        compose.onNodeWithTag("search-field").assertDoesNotExist()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNodeWithTag("search-field").performTextInput("media")
        compose.onNodeWithText("media-assets").assertIsDisplayed()
        compose.onNodeWithText("api-gateway").assertDoesNotExist()
        screenshot("search")
        compose.onNodeWithContentDescription("取消搜索").performClick()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        screenshot("drawer")
        compose.onNodeWithText("Dev sandbox · 演示").performClick()
        compose.onNodeWithText("暂无云资源").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText("Production · 演示").performClick()
        compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithText("flarego.dev").performClick()
        compose.onNodeWithText("192.0.2.10").assertIsDisplayed()
        screenshot("dns")
        compose.onNodeWithContentDescription("编辑记录 record-a").performClick()
        compose.onNodeWithTag("dns-content").performTextReplacement("192.0.2.99")
        compose.onNodeWithText("预览变更").performClick()
        screenshot("dns-confirm")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("dns-content").assertTextContains("192.0.2.99")
        compose.onNodeWithText("预览变更").performClick()
        compose.onNodeWithText("确认提交").performClick()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("192.0.2.99").assertIsDisplayed()
    }

    @Test
    fun authorizationContainsNoSecretFieldsAndAllowsScreenshots() {
        compose.onNodeWithContentDescription("连接账号").performClick()
        // 用途页不显示凭据；已有 Token 页只有一个空的掩码输入框。
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        enterExistingToken()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        assertTrue("Token 输入框应为空且掩码", emptyMaskedTokenField())
        compose.runOnIdle {
            assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onNodeWithTag("token-cancel").performClick()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
    }

    @Test
    fun authorizationDialogSurvivesActivityRecreationAndCancelClosesIt() {
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithContentDescription("连接账号").performClick()
        compose.onNodeWithTag("token-purpose-domains").performClick()
        enterExistingToken()
        compose.onNodeWithTag("token-field").performTextInput("fake-token-never-save")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("token-title").assertIsDisplayed()
        assertTrue("Token 输入框应为空且掩码", emptyMaskedTokenField())
        compose.onNodeWithTag("token-purpose-back").performScrollTo().performClick()
        compose.onNodeWithTag("token-purpose-domains").assertIsOff()
        compose.onNodeWithContentDescription("取消").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("token-title").assertDoesNotExist()
        compose.onNodeWithContentDescription("连接账号").performClick()
        // The same Token entry comes back, with no OAuth alternative anywhere in the flow.
        enterExistingToken()
        compose.onNodeWithTag("token-field").assertIsDisplayed()
        compose.onNodeWithTag("token-use-oauth").assertDoesNotExist()
    }

    @Test
    fun connectionHelpLaunchesBrowserIntentsFromTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = mutableListOf<String>()
        // Intercept only the external browser boundary; exercise the real app and URI handler.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.add(intent.dataString.orEmpty())
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("如何创建 API Token").performClick()
            compose.onNodeWithTag("setup-connect").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nav-accounts").performClick()
            compose.onNodeWithContentDescription("连接账号").performClick()
            compose.runOnIdle {
                assertEquals(
                    listOf("https://developers.cloudflare.com/fundamentals/api/get-started/create-token/"),
                    opened,
                )
            }
            compose.onNodeWithTag("token-screen").assertIsDisplayed()
            compose.onNodeWithTag("token-use-oauth").assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithTag("token-create").performScrollTo().performClick()
            compose.runOnIdle {
                val uri = android.net.Uri.parse(opened[1])
                assertEquals("https://dash.cloudflare.com/profile/api-tokens", uri.buildUpon().clearQuery().build().toString())
                val groups = org.json.JSONArray(uri.getQueryParameter("permissionGroupKeys"))
                val keys = (0 until groups.length()).map { index ->
                    val group = groups.getJSONObject(index)
                    assertEquals("read", group.getString("type"))
                    group.getString("key")
                }
                assertEquals(listOf("account_settings", "zone", "dns", "billing"), keys)
            }
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            assertTrue(emptyMaskedTokenField())
            compose.onNodeWithTag("token-submit").assertIsNotEnabled()
            compose.onNodeWithContentDescription("取消").performClick()
            compose.onNodeWithText("获取 API Token").assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun domainSearchIsReadOnlyAndShowsDemoCandidates() {
        installDemoFixture()
        compose.onNodeWithTag("nav-domains").performClick()
        // The demo connection keeps the toolbar "+" entry that opens the registrar page.
        compose.onNodeWithContentDescription("购买域名").performClick()
        compose.onNodeWithTag("registrar-search-field").assertIsDisplayed()
        compose.onNodeWithTag("registrar-search-field").performTextInput("my-cloud")
        compose.onNodeWithTag("registrar-search-submit").performClick()
        compose.onNodeWithTag("registrar-result-my-cloud.com").assertIsDisplayed()
        // Every candidate offers the same read-only "open console" action, so more than one
        // node is expected; the first visible card must expose it.
        compose.onAllNodesWithTag("registrar-open-console").onFirst().assertIsDisplayed()
        compose.onNodeWithText("示例 · 未调用云 API").assertIsDisplayed()
        // Search only suggests candidates: the removed demo purchase dialog must be gone.
        compose.onNodeWithText("确认模拟购买").assertDoesNotExist()
        screenshot("domain-search")
    }

    private fun enterExistingToken() {
        compose.onNodeWithTag("token-existing").performScrollTo().performClick()
    }

    /** The Token field must start empty and be masked before anything is typed. */
    private fun emptyMaskedTokenField(): Boolean {
        val config = compose.onNodeWithTag("token-field").fetchSemanticsNode().config
        val text = config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        return text.isEmpty() && config.contains(androidx.compose.ui.semantics.SemanticsProperties.Password)
    }
}