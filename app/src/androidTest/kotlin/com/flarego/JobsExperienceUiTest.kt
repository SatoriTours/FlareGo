package com.flarego

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.JobsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class JobsExperienceUiTest {
    @get:Rule val compose = createComposeRule()
    private val connection = Connection("jobs-current", "当前账号", "a".repeat(32))
    private fun task(id: String = "one", connectionId: String = connection.id) = OperationTask(
        id, connectionId, AccountScope(connectionId, connection.accountId), "kv-put-value", null,
        "0".repeat(64), null, TaskState.UNKNOWN, "写入结果未知，请只读核对", null, null, 1, 1,
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").also { it.mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun summaryTitleSearchKeepsCurrentTaskAndReconcileAction() {
        var checked: String? = null
        val state = AppState(listOf(connection), connection,
            tasks = listOf(task()), operations = listOf(Operation("one", connection.id, "更新应用设置", OperationStatus.UNKNOWN, "旧摘要")))
        compose.setContent { FlareGoTheme { JobsScreen(state, " 设置 ") { checked = it } } }
        compose.onNodeWithText("更新应用设置").assertIsDisplayed()
        compose.onNodeWithText("写入结果未知，请只读核对").assertIsDisplayed()
        compose.onNodeWithText("只读核对").performClick()
        compose.runOnIdle { assertEquals("one", checked) }
        compose.onNodeWithText("DNS 任务").assertDoesNotExist()
        compose.onNodeWithText("旧摘要").assertDoesNotExist()
        capture("jobs-matching-task")
    }

    @Test fun summaryDetailSearchKeepsItsJoinedTaskAndReconcileAction() {
        var checked: String? = null
        val state = AppState(listOf(connection), connection, tasks = listOf(task()),
            operations = listOf(Operation("one", connection.id, "更新应用设置", OperationStatus.UNKNOWN, "唯一摘要关键词")))
        compose.setContent { FlareGoTheme { JobsScreen(state, "唯一摘要关键词") { checked = it } } }
        compose.onNodeWithText("更新应用设置").assertIsDisplayed()
        compose.onNodeWithText("只读核对").performClick()
        compose.runOnIdle { assertEquals("one", checked) }
        compose.onNodeWithText("没有匹配的操作记录").assertDoesNotExist()
    }

    @Test fun anotherAccountsTaskCannotHideCurrentReceipt() {
        val state = AppState(listOf(connection), connection, tasks = listOf(task(connectionId = "other")),
            operations = listOf(Operation("one", connection.id, "当前账号操作", OperationStatus.SUCCESS, "当前结果")))
        compose.setContent { FlareGoTheme { JobsScreen(state, "") } }
        compose.onNodeWithText("当前账号操作").assertIsDisplayed()
        compose.onNodeWithText("写入结果未知，请只读核对").assertDoesNotExist()
    }

    @Test fun detailSearchFindsOrphanTaskWithoutDnsLabel() {
        val state = AppState(listOf(connection), connection, tasks = listOf(task()))
        compose.setContent { FlareGoTheme { JobsScreen(state, "写入结果未知") } }
        compose.onNodeWithText("云资源任务").assertIsDisplayed()
        compose.onNodeWithText("写入结果未知，请只读核对").assertIsDisplayed()
    }

    @Test fun noMatchShowsSearchRecoveryInsteadOfClaimingNoHistory() {
        val state = AppState(listOf(connection), connection, tasks = listOf(task()))
        compose.setContent { FlareGoTheme { JobsScreen(state, "不存在的操作") } }
        compose.onNodeWithText("没有匹配的操作记录").assertIsDisplayed()
        compose.onNodeWithText("尝试其他关键词或清空搜索").assertIsDisplayed()
        compose.onNodeWithText("暂无操作记录").assertDoesNotExist()
        capture("jobs-search-empty")
    }
}
