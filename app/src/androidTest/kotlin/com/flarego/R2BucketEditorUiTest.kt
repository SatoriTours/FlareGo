package com.flarego

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.ui.r2.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 纯 Compose UI 契约测试，仅测试 R2BucketList 界面发出的 intent 契约及 UI 元素。
 * 不涉及真实网络或真实 VM 业务写入逻辑。
 */
class R2BucketEditorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createBucketPostAndPutModeToggleAndHints() {
        val intents = mutableListOf<R2BucketIntent>()
        var state by mutableStateOf(
            R2BucketListState(
                createMode = R2BucketCreateMode.POST,
                draftName = "new-bucket",
                draftLocationHint = "apac",
            )
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 1. 验证 POST 模式下元素
        compose.onNodeWithTag("rb-create-mode-post").assertIsDisplayed()
        compose.onNodeWithTag("rb-create-mode-put").assertIsDisplayed()
        compose.onNodeWithTag("rb-location-hint-none").assertIsDisplayed()
        compose.onNodeWithTag("rb-location-hint-apac").assertIsDisplayed()
        compose.onNodeWithTag("rb-create-put-hint").assertDoesNotExist()

        // 点击切换到 PUT 模式
        compose.onNodeWithTag("rb-create-mode-put").performScrollTo().performClick()
        assertEquals(listOf(R2BucketIntent.CreateModeChanged(R2BucketCreateMode.PUT)), intents)

        // 2. 更新状态为 PUT 模式
        state = state.copy(
            createMode = R2BucketCreateMode.PUT,
            draftLocationHint = null,
        )

        // 验证 PUT 模式下 location hints 隐藏，且显示说明
        compose.onNodeWithTag("rb-create-put-hint").assertIsDisplayed()
        compose.onNodeWithTag("rb-location-hint-none").assertDoesNotExist()
        compose.onNodeWithTag("rb-location-hint-apac").assertDoesNotExist()

        // 验证 SubmitCreate 点击
        compose.onNodeWithTag("rb-create-submit").performScrollTo().performClick()
        assertTrue(intents.contains(R2BucketIntent.SubmitCreate))
    }

    @Test
    fun editStorageClassEntryAndFormInteraction() {
        val intents = mutableListOf<R2BucketIntent>()
        var state by mutableStateOf(
            R2BucketListState(
                rows = listOf(
                    R2BucketRow("target-bucket", "weur", "Standard", "2026-10-18"),
                ),
                editingBucketName = null,
            )
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 1. 点击编辑入口
        compose.onNodeWithTag("rb-edit-target-bucket").performScrollTo().performClick()
        assertEquals(listOf(R2BucketIntent.RequestStorageClassEdit("target-bucket")), intents)

        // 2. 打开编辑卡片
        state = state.copy(
            editingBucketName = "target-bucket",
            editingOriginalStorageClass = "Standard",
            editingStorageClass = "Standard",
        )

        compose.onNodeWithTag("rb-edit-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rb-edit-target").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rb-edit-original").performScrollTo().assertIsDisplayed()

        // 未做修改前（与原类型相同），提交按钮应禁用
        compose.onNodeWithTag("rb-edit-submit").assertIsNotEnabled()

        // 点击选择 InfrequentAccess
        compose.onNodeWithTag("rb-edit-class-InfrequentAccess").performScrollTo().performClick()
        assertTrue(intents.contains(R2BucketIntent.EditStorageClassChanged("InfrequentAccess")))

        // 状态更新为选择了新类型
        state = state.copy(editingStorageClass = "InfrequentAccess")

        // 此时提交按钮启用，点击提交
        compose.onNodeWithTag("rb-edit-submit").assertIsEnabled().performClick()
        assertTrue(intents.contains(R2BucketIntent.SubmitStorageClassEdit))

        // 点击取消
        compose.onNodeWithTag("rb-edit-cancel").performScrollTo().performClick()
        assertTrue(intents.contains(R2BucketIntent.CancelStorageClassEdit))
    }

    @Test
    fun unknownStorageClassRowIsReadOnlyAndShowsNotice() {
        val intents = mutableListOf<R2BucketIntent>()
        val state = R2BucketListState(
            rows = listOf(
                R2BucketRow("known-bucket", "weur", "Standard", "2026-10-18"),
                R2BucketRow("unknown-bucket", "apac", "GlacierCold", "2026-10-18"),
                R2BucketRow("missing-bucket", "enam", null, "2026-10-18"),
            ),
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = { intents += it },
            )
        }

        // known bucket 应该有启用的编辑按钮
        compose.onNodeWithTag("rb-edit-known-bucket").performScrollTo().assertIsDisplayed().assertIsEnabled()

        // unknown/missing bucket 编辑入口应该被禁用或者不作为可编辑入口，且文字显示未知类型，不能默认为 Standard
        compose.onNodeWithText("类型：GlacierCold", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("类型：未知", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rb-edit-unknown-bucket").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rb-edit-missing-bucket").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun editFormCanSubmitDisabledWhenOriginalClassInvalidOrRefreshing() {
        val intents = mutableListOf<R2BucketIntent>()
        var state by mutableStateOf(
            // 情况 1: editingOriginalStorageClass 未知（不在 storageClasses 中，且与 editingStorageClass 不同）
            R2BucketListState(
                editingBucketName = "unknown-orig-bucket",
                editingOriginalStorageClass = "CustomUnknown",
                editingStorageClass = "Standard",
                loading = false,
                refreshing = false,
            )
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 此时虽然 editingStorageClass("Standard") != editingOriginalStorageClass("CustomUnknown") 且在 storageClasses 中，
        // 但由于原类型未知，提交按钮应禁用以防止产生误导或非法修改
        compose.onNodeWithTag("rb-edit-submit").performScrollTo().assertIsNotEnabled()

        // 情况 2: editingOriginalStorageClass 为 null
        state = state.copy(
            editingOriginalStorageClass = null,
            editingStorageClass = "Standard",
        )
        compose.onNodeWithTag("rb-edit-submit").performScrollTo().assertIsNotEnabled()

        // 情况 3: 原类型已知 (Standard)，新类型已知 (InfrequentAccess)，但处于 refreshing 状态
        state = state.copy(
            editingBucketName = "normal-bucket",
            editingOriginalStorageClass = "Standard",
            editingStorageClass = "InfrequentAccess",
            refreshing = true,
        )
        compose.onNodeWithTag("rb-edit-submit").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun rowEditEntryDisabledWhenRefreshing() {
        val intents = mutableListOf<R2BucketIntent>()
        val state = R2BucketListState(
            rows = listOf(
                R2BucketRow("my-bucket", "weur", "Standard", "2026-10-18"),
            ),
            refreshing = true,
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = { intents += it },
            )
        }

        // refreshing 为 true 时，编辑入口按钮应禁用
        compose.onNodeWithTag("rb-edit-my-bucket").performScrollTo().assertIsNotEnabled()
    }
}
