package com.flarego

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.ui.r2.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * 纯 Compose UI 契约测试：验证 R2 存储桶配置界面（B1 四面板、只读/超限、风险勾选、DELETE 模式、Date 无损展示及预览）。
 * 严格 TDD，不依赖真实网络或全局测试 hook。
 */
class R2ConfigurationUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun entryPointInBucketListTriggersOpenConfigurationIntent() {
        var openedBucket: String? = null
        val state = R2BucketListState(
            rows = listOf(
                R2BucketRow("my-test-bucket", "weur", "Standard", "2026-10-19"),
            ),
        )

        compose.setContent {
            R2BucketList(
                state = state,
                onIntent = {},
                onOpenConfiguration = { openedBucket = it },
            )
        }

        compose.onNodeWithTag("rb-config-my-test-bucket").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("my-test-bucket", openedBucket)
    }

    @Test
    fun screenHeaderShowsTargetAndJurisdictionAndHandlesCloseAndRefresh() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            target = R2ConfigurationDraftTarget(bucket = "target-bucket", jurisdiction = "eu"),
            editable = true,
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证目标与地区展示
        compose.onNodeWithTag("rc-target-display").assertIsDisplayed().assertTextContains("eu", substring = true)
        compose.onNodeWithTag("rc-target-display").assertTextContains("target-bucket", substring = true)

        // 验证关闭与刷新按钮
        compose.onNodeWithTag("rc-close").performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.Close))

        compose.onNodeWithTag("rc-refresh").performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.Refresh))

        // 点击展开目标编辑区
        compose.onNodeWithTag("rc-target-toggle").performClick()

        // 验证桶名修改
        compose.onNodeWithTag("rc-target-bucket").performTextInput("-modified")
        assertTrue(intents.any { it is R2ConfigurationIntent.BucketChanged })

        // 全部 5 个 pinned 地区必须由 state 选项驱动呈现（不是界面里硬编码的子集）
        assertEquals(
            listOf("default", "eu", "fedramp", "fedramp-high", "us"),
            state.target.jurisdictions,
        )
        state.target.jurisdictions.forEach { jur ->
            compose.onNodeWithTag("rc-target-jurisdiction-$jur").assertIsDisplayed()
        }

        // 验证地区切换
        compose.onNodeWithTag("rc-target-jurisdiction-fedramp-high").performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.JurisdictionChanged("fedramp-high")))
    }

    @Test
    fun panelNavigationTogglesFourPanels() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // The tab row is scrollable: at a large font the last tabs sit outside the 320dp width,
        // so reachability is proven by an actual scroll gesture inside that row, never by a tag lookup.
        compose.onNodeWithTag("rc-panel-lifecycle").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LIFECYCLE)))

        compose.onNodeWithTag("rc-panel-local-uploads").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LOCAL_UPLOADS)))

        compose.onNodeWithTag("rc-panel-lock").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.LOCK)))

        compose.onNodeWithTag("rc-panel-cors").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.CORS)))
    }

    @Test
    fun corsPanelRendersNoticeAndFieldsAndEmitsTypedIntents() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            editable = true,
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
            cors = listOf(
                R2CorsDraft(
                    id = "rule-1",
                    methods = "GET,PUT",
                    origins = "https://example.com",
                    allowedHeaders = "Content-Type",
                    exposeHeaders = "ETag",
                    maxAgeSeconds = "3600",
                ),
            ),
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证 CORS 规则说明（浏览器跨域规则，非访问授权）
        compose.onNodeWithTag("rc-cors-notice").performScrollTo().assertIsDisplayed()

        // 验证字段值展示与编辑
        compose.onNodeWithTag("rc-cors-id-0").performScrollTo().assertTextContains("rule-1", substring = true)
        compose.onNodeWithTag("rc-cors-methods-0").performScrollTo().assertTextContains("GET,PUT", substring = true)
        compose.onNodeWithTag("rc-cors-origins-0").performScrollTo().assertTextContains("https://example.com", substring = true)
        compose.onNodeWithTag("rc-cors-allowed-headers-0").performScrollTo().assertTextContains("Content-Type", substring = true)
        compose.onNodeWithTag("rc-cors-expose-headers-0").performScrollTo().assertTextContains("ETag", substring = true)
        compose.onNodeWithTag("rc-cors-max-age-0").performScrollTo().assertTextContains("3600", substring = true)

        compose.onNodeWithTag("rc-cors-methods-0").performScrollTo().performTextReplacement("DELETE")
        assertTrue(intents.any { it is R2ConfigurationIntent.CorsMethodsChanged && it.value == "DELETE" })

        // 验证添加规则与删除配置入口
        compose.onNodeWithTag("rc-cors-add-rule").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.AddCorsRule))

        compose.onNodeWithTag("rc-cors-request-delete").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.RequestDeleteConfiguration))
    }

    @Test
    fun lifecyclePanelSupportsDateAndWarnsOnEmptyPrefix() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.LIFECYCLE,
            editable = true,
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
            lifecycle = listOf(
                R2LifecycleDraft(
                    id = "rule-lc",
                    enabled = true,
                    prefix = "", // 空前缀全桶规则
                    deleteMode = R2LifecycleDeleteMode.DATE,
                    deleteDate = "2026-12-31T23:59:59.123456789Z",
                    abortAgeSeconds = "86400",
                    transitions = listOf(
                        R2LifecycleTransitionDraft(
                            conditionType = R2LifecycleConditionType.DATE,
                            utcDate = "2026-11-01T00:00:00Z",
                            storageClass = "InfrequentAccess",
                        ),
                    ),
                ),
            ),
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证全桶与空前缀提示
        compose.onNodeWithTag("rc-lifecycle-empty-prefix-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lifecycle-rule-prefix-warning-0").performScrollTo().assertIsDisplayed()

        // 验证无损 Date 原文展示（含小数秒）
        compose.onNodeWithTag("rc-lifecycle-del-date-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lifecycle-del-date-0").assertTextContains("2026-12-31T23:59:59.123456789Z", substring = true)

        // 验证 abortMultipart 年龄字段
        compose.onNodeWithTag("rc-lifecycle-abort-age-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lifecycle-abort-age-0").assertTextContains("86400", substring = true)

        // 验证分层迁移中的 Date 原文与存储类型固定 InfrequentAccess
        compose.onNodeWithTag("rc-lifecycle-trans-date-0-0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lifecycle-trans-date-0-0").assertTextContains("2026-11-01T00:00:00Z", substring = true)
        compose.onNodeWithTag("rc-lifecycle-trans-class-0-0").performScrollTo().assertIsDisplayed()

        // 验证切换为 AGE 条件
        compose.onNodeWithTag("rc-lifecycle-del-mode-0-AGE").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.LifecycleDeleteModeChanged(0, R2LifecycleDeleteMode.AGE)))
    }

    @Test
    fun lockPanelRendersConditionTypesAndWarnsOnIndefiniteLock() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.LOCK,
            editable = true,
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
            lock = listOf(
                R2LockDraft(
                    id = "rule-lock-1",
                    enabled = true,
                    prefix = "",
                    conditionType = R2LockConditionType.INDEFINITE,
                ),
            ),
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证锁影响提示与全桶规则提示
        compose.onNodeWithTag("rc-lock-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-lock-rule-prefix-warning-0").performScrollTo().assertIsDisplayed()

        // 验证 INDEFINITE 永久锁警告
        compose.onNodeWithTag("rc-lock-indefinite-warning-0").performScrollTo().assertIsDisplayed()

        // 切换回 AGE_SECONDS 条件
        compose.onNodeWithTag("rc-lock-cond-0-AGE_SECONDS").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.LockConditionTypeChanged(0, R2LockConditionType.AGE_SECONDS)))

        // 验证删除锁定配置入口
        compose.onNodeWithTag("rc-lock-request-delete").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.RequestDeleteConfiguration))
    }

    @Test
    fun localUploadsPanelDistinguishesDefaultFromNonDefault() {
        val intents = mutableListOf<R2ConfigurationIntent>()

        // 1. 非 default 地区：明确提示不支持且零请求
        var state by mutableStateOf(
            R2ConfigurationUiState(
                open = true,
                panel = R2ConfigurationPanel.LOCAL_UPLOADS,
                target = R2ConfigurationDraftTarget("test-bucket", "eu"),
                editable = true,
            )
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        compose.onNodeWithTag("rc-local-uploads-unsupported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-local-uploads-switch").assertDoesNotExist()

        // 2. default 地区：显示说明与开关
        state = state.copy(
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
            localUploadsEnabled = false,
        )

        compose.onNodeWithTag("rc-local-uploads-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-local-uploads-switch").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("rc-local-uploads-switch").performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.LocalUploadsEnabledChanged(true)))
    }

    @Test
    fun deleteRequestedModeRendersExplicitDeleteConfirmationAndSuppressesRuleForms() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            target = R2ConfigurationDraftTarget("my-bucket", "default"),
            editable = true,
            deleteRequested = true,
            risks = R2ConfigurationRisks(destructiveImpactAcknowledged = false),
            typedTargetName = "",
            canPreview = false,
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证删除模式卡片展示，普通表单隐藏
        compose.onNodeWithTag("rc-delete-mode-card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-cors-add-rule").assertDoesNotExist()

        // 验证取消删除模式
        compose.onNodeWithTag("rc-cancel-delete-mode").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.CancelDeleteConfiguration))

        // 验证破坏性风险勾选
        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().performClick()
        assertTrue(intents.any { it is R2ConfigurationIntent.RisksChanged && it.risks.destructiveImpactAcknowledged })

        // 验证精确桶名输入
        compose.onNodeWithTag("rc-typed-target-name").performScrollTo().performTextInput("my-bucket")
        assertTrue(intents.any { it is R2ConfigurationIntent.TypedTargetNameChanged && it.value == "my-bucket" })

        // canPreview 为 false 时预览按钮禁用
        compose.onNodeWithTag("rc-preview-button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun frozenPreviewDisablesPanelTabsAndTheTargetEditor() {
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            target = R2ConfigurationDraftTarget("frozen-bucket", "default"),
            editable = true,
            preview = R2ConfigurationPreviewUi(
                target = "default/frozen-bucket",
                impact = "PUT CORS 规则集：将覆盖全量 1 条规则",
                concurrencyWarning = "并发提示：请核对目标地区与桶名",
                requiresTypedTargetName = false,
            ),
        )

        compose.setContent {
            R2ConfigurationScreen(state = state, onIntent = {})
        }

        // 冻结预览期间不允许切面板或改目标/地区，避免用旧预览冒充新草稿
        compose.onNodeWithTag("rc-panel-cors").assertIsNotEnabled()
        compose.onNodeWithTag("rc-panel-lifecycle").assertIsNotEnabled()
        compose.onNodeWithTag("rc-panel-local-uploads").assertIsNotEnabled()
        compose.onNodeWithTag("rc-panel-lock").assertIsNotEnabled()
        compose.onNodeWithTag("rc-target-toggle").assertDoesNotExist()
        compose.onNodeWithTag("rc-target-bucket").assertDoesNotExist()
        compose.onNodeWithTag("rc-cors-add-rule").assertDoesNotExist()

        // 预览卡片仍然可取消/确认
        compose.onNodeWithTag("rc-preview-cancel").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().assertIsEnabled()
    }

    @Test
    fun readOnlyAndUnknownOrOversizeDocumentPreservesRawText() {
        val longJsonBuilder = StringBuilder("""{"rules":[""")
        for (i in 0 until 50) {
            longJsonBuilder.append("""{"id":"rule-$i","unknown_field":"value-$i"},""")
        }
        longJsonBuilder.append("""{"id":"last"}]}""")
        val rawJson = longJsonBuilder.toString()
        assertTrue(rawJson.length > 1000)

        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            target = R2ConfigurationDraftTarget("readonly-bucket", "default"),
            editable = false,
            readOnlyReason = "文档包含无法安全写入的未知字段，已保留为只读",
            rawDocumentText = rawJson,
            canPreview = false,
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = {},
            )
        }

        // 验证只读原因与原文展示
        compose.onNodeWithTag("rc-readonly-reason").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-readonly-reason").assertTextContains("未知字段", substring = true)

        // 第一页展示前 1000 字符
        compose.onNodeWithTag("rc-raw-document").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-raw-document").assertTextContains("rule-0", substring = true)

        // 分页翻页：点击下一页进入第 2 页
        compose.onNodeWithTag("rc-raw-next-page").performScrollTo().performClick()
        compose.onNodeWithTag("rc-raw-document").performScrollTo().assertTextContains("rule-25", substring = true)

        // 点击上一页返回第 1 页
        compose.onNodeWithTag("rc-raw-prev-page").performScrollTo().performClick()
        compose.onNodeWithTag("rc-raw-document").performScrollTo().assertTextContains("rule-0", substring = true)

        // 验证只读模式下编辑与风险勾选禁用、预览按钮禁用
        compose.onNodeWithTag("rc-risk-full-replacement").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-risk-destructive").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("rc-preview-button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun frozenPreviewCardRendersImpactAndRequiresExplicitConfirmation() {
        val intents = mutableListOf<R2ConfigurationIntent>()
        val state = R2ConfigurationUiState(
            open = true,
            panel = R2ConfigurationPanel.CORS,
            target = R2ConfigurationDraftTarget("test-bucket", "default"),
            preview = R2ConfigurationPreviewUi(
                target = "default/test-bucket",
                impact = "PUT CORS 规则集：将覆盖全量 1 条规则",
                concurrencyWarning = "并发提示：请核对目标地区与桶名",
                requiresTypedTargetName = false,
            ),
        )

        compose.setContent {
            R2ConfigurationScreen(
                state = state,
                onIntent = { intents += it },
            )
        }

        // 验证冻结预览卡片内容
        compose.onNodeWithTag("rc-preview-title").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-preview-target").performScrollTo().assertTextContains("default/test-bucket", substring = true)
        compose.onNodeWithTag("rc-preview-impact").performScrollTo().assertTextContains("PUT CORS", substring = true)
        compose.onNodeWithTag("rc-preview-concurrency").performScrollTo().assertTextContains("并发提示", substring = true)

        // 验证取消预览与确认执行按钮
        compose.onNodeWithTag("rc-preview-cancel").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.CancelPreview))

        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        assertTrue(intents.contains(R2ConfigurationIntent.Confirm))
    }
}
