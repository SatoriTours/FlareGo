package com.flarego

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flarego.core.application.AppController
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.FlareGoApp
import org.junit.Rule
import org.junit.Test

class HomeNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun onlyOpenedObjectsAppearAndBothBackActionsHonorTheSource() {
        val a = Connection("test-a", "来源测试甲", "account-a", true)
        val b = Connection("test-b", "来源测试乙", "account-b", true)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { connection ->
                    object : CloudSession by DemoSession(connection) {
                        override suspend fun inventory() = Inventory(
                            listOf(Domain("zone", "source.example", "active", connection.id)),
                            listOf(CloudResource("worker", "source-worker", ResourceKind.WORKER, "")),
                        )
                    }
                }, MemoryStore(), listOf(a, b))
            }
            FlareGoApp(controller, {}, {})
        }
        compose.onNodeWithTag("recent-zone-zone").assertDoesNotExist()
        compose.onNodeWithTag("recent-resource-WORKER-worker").assertDoesNotExist()
        compose.onNodeWithTag("nav-domains").performClick()
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("source")
        compose.onNodeWithText("source.example").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("source")
        compose.onNodeWithTag("nav-overview").performClick()
        compose.onNodeWithTag("recent-zone-zone").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("recent-zone-zone").assertIsDisplayed()
        compose.onNodeWithTag("overview-worker").performScrollTo().performClick()
        compose.onNodeWithText("source-worker").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("source-worker").assertIsDisplayed()
        compose.onNodeWithTag("nav-overview").performClick()
        val worker = compose.onNodeWithTag("recent-resource-WORKER-worker")
        val zone = compose.onNodeWithTag("recent-zone-zone")
        org.junit.Assert.assertTrue(worker.getUnclippedBoundsInRoot().top < zone.getUnclippedBoundsInRoot().top)
        worker.performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        worker.assertIsDisplayed()
        compose.onNodeWithContentDescription("切换云账号").performClick()
        compose.onNodeWithText("来源测试乙").performClick()
        worker.assertDoesNotExist()
        zone.assertDoesNotExist()
    }
}
