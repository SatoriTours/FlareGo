package com.flarego

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
