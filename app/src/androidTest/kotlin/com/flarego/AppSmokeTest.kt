package com.flarego

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
    fun compactNavigationSearchAndDrawer() {
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
    fun connectionDraftSurvivesActivityRecreationAndIsClearedOnCancel() {
        val secret = "fixture-recreation-token-not-real"
        compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithContentDescription("连接账号").performClick()
        compose.onNode(hasSetTextAction() and hasText("账号名称"))
            .performScrollTo().performTextInput("恢复测试")
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .performScrollTo().performTextInput("a".repeat(32))
        compose.onNode(hasSetTextAction() and hasText("API Token"))
            .performScrollTo().performTextInput(secret)

        compose.activityRule.scenario.recreate()

        compose.onNode(hasSetTextAction() and hasText("账号名称"))
            .performScrollTo().assertTextContains("恢复测试")
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .performScrollTo().assertTextContains("a".repeat(32))
        val tokenField = compose.onNode(hasSetTextAction() and hasText("API Token"))
            .performScrollTo()
        assertEquals(
            "•".repeat(secret.length),
            tokenField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[FlareGoViewModel::class.java]
            assertEquals(secret, model.connectionDraft.token)
            assertTrue(
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertFalse(
                compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
        }
        compose.onNodeWithContentDescription("连接账号").performClick()
        listOf("账号名称", "Account ID", "API Token").forEach { label ->
            val field = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo()
            assertEquals("", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
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
            compose.onNodeWithTag("nav-accounts").performClick()
            compose.onNodeWithContentDescription("连接账号").performClick()
            compose.onNodeWithText("获取 API Token").performScrollTo().performClick()
            compose.onNodeWithText("查找 Account ID").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(
                    listOf(
                        "https://dash.cloudflare.com/profile/api-tokens",
                        "https://developers.cloudflare.com/fundamentals/account/find-account-and-zone-ids/",
                    ),
                    opened,
                )
            }
            compose.onNodeWithText("验证并连接").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("获取 API Token").assertDoesNotExist()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun purchaseIsClearlyDemoAndRequiresConfirmation() {
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
