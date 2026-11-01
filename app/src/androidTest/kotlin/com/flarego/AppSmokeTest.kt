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
import com.flarego.core.demo.DemoSession
import com.flarego.core.demo.demoConnections
import com.flarego.core.ports.MemoryStore
import com.flarego.core.ports.SessionFactory
import com.flarego.ui.FlareGoApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    // Demo interactions are explicit test fixtures; production startup never seeds them.
    private fun installDemoFixture() {
        compose.runOnUiThread {
            compose.activity.setContent {
                val scope = rememberCoroutineScope()
                val controller = remember {
                    AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), demoConnections)
                }
                FlareGoApp(controller, {}, {})
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
        compose.onNodeWithTag("oauth-authorize").assertIsDisplayed()
        compose.onNodeWithTag("oauth-token-fallback").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("取消").performClick()
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
    fun accountTabOffersOnlyOfficialAuthorization() {
        installDemoFixture()
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithContentDescription("连接账号").performClick()
        compose.onNodeWithTag("oauth-authorize").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithTag("oauth-token-fallback").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
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
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        compose.onNodeWithText("取消").performClick()
    }

    @Test
    fun authorizationDialogSurvivesActivityRecreationAndCancelClosesIt() {
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithContentDescription("连接账号").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("oauth-title").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("取消").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("oauth-title").assertDoesNotExist()
        compose.onNodeWithContentDescription("连接账号").performClick()
        compose.onNodeWithTag("oauth-authorize").assertIsDisplayed()
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
            compose.onNodeWithText("Cloudflare 授权帮助").performClick()
            compose.onNodeWithTag("setup-connect").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nav-accounts").performClick()
            compose.onNodeWithContentDescription("连接账号").performClick()
            compose.runOnIdle {
                assertEquals(listOf("https://developers.cloudflare.com/fundamentals/oauth/"), opened)
            }
            compose.onNodeWithTag("oauth-authorize").assertIsDisplayed()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("获取 API Token").assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun purchaseIsClearlyDemoAndRequiresConfirmation() {
        installDemoFixture()
        compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithContentDescription("购买域名").performClick()
        compose.onNodeWithTag("search-field").performTextInput("my-cloud")
        compose.onNodeWithContentDescription("提交搜索").performClick()
        compose.onAllNodesWithText("选择")[0].performClick()
        compose.onNodeWithText("确认模拟购买").assertIsDisplayed()
        screenshot("purchase-confirm")
        compose.onNodeWithText("确认模拟购买").performClick()
        compose.onNodeWithText("演示操作，无真实注册或扣款").assertIsDisplayed()
    }
}
