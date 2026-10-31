package com.flarego

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.inventory.*
import com.flarego.core.model.*
import com.flarego.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ResourceExperienceUiTest {
    @get:Rule val compose = createComposeRule()
    private val connection = Connection("resource-experience", "当前账号", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val row = CloudResource("one", "已有 Worker", ResourceKind.WORKER, "已加载")
    private fun load(loading: Boolean = false, error: CloudFailure? = null, observed: Long? = null,
                     rows: List<CloudResource> = emptyList(), next: PageToken? = null) =
        ProductLoadState(ProductContent.Resources(rows), next, null, loading, error, account, 0, observed, next == null)
    private fun install(initial: ProductLoadState?, query: String = "", font: Float = 1f): MutableState<ProductLoadState?> {
        val current = mutableStateOf(initial)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    val state = current.value
                    ResourcesScreen(AppState(listOf(connection), connection, page = Page.RESOURCES,
                        productLoading = true, products = state?.let { mapOf(ProductKey.WORKERS to it) }.orEmpty(),
                        inventory = Inventory(emptyList(), (state?.content as? ProductContent.Resources)?.items.orEmpty())),
                        query, {}, refresh = { current.value = load(observed = 1) })
                } }
            }
        }
        return current
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        val dir = File(i.targetContext.getExternalFilesDir(null), "round2-root-screenshots").also { it.mkdirs() }
        // Synchronize with the actual rendered Compose surface, not an unrelated
        // top-level instrumentation window that can still be blank.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun loadingDoesNotClaimEmptyAndReportsProgress() {
        install(load(loading = true))
        compose.onNodeWithText("暂无云资源").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithText("刷新当前产品").assertIsNotEnabled()
        capture("resources-loading")
    }

    @Test fun failedReadOffersRetryWithoutClaimingNoResources() {
        install(load(error = CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "需要 Workers 读取权限")), font = 2f)
        compose.onNodeWithText("暂无云资源").assertDoesNotExist()
        compose.onNodeWithText("需要 Workers 读取权限").assertIsDisplayed()
        capture("resources-error-font2")
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("暂无云资源").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("需要 Workers 读取权限").assertDoesNotExist()
    }

    @Test fun notLoadedDoesNotLookLikeSuccessfulEmptyRead() {
        install(null)
        compose.onNodeWithText("暂无云资源").assertDoesNotExist()
        compose.onNodeWithText("尚未读取资源").assertIsDisplayed()
        compose.onNodeWithText("刷新当前产品").performClick()
        compose.onNodeWithText("暂无云资源").assertIsDisplayed()
    }

    @Test fun refreshingRetainsAlreadyLoadedRowsAndCount() {
        install(load(loading = true, observed = 1, rows = listOf(row)))
        compose.onNodeWithText("已有 Worker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已加载 1 · 全部").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test fun incompleteEmptyPageDoesNotClaimTheAccountHasNoResources() {
        install(load(observed = 1, next = PageToken.Number(2)))
        compose.onNodeWithText("暂无云资源").assertDoesNotExist()
        compose.onNodeWithText("当前页面没有资源").assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertIsDisplayed()
    }

    @Test fun searchMissOnlyDescribesLoadedRangeAndPaginationStaysReachable() {
        install(load(observed = 1, rows = listOf(row), next = PageToken.Number(2)), query = "missing")
        compose.onNodeWithText("没有匹配资源").assertIsDisplayed()
        compose.onNodeWithText("仅搜索已加载资源；可清空搜索或加载更多").assertIsDisplayed()
        compose.onNodeWithText("加载更多").assertIsDisplayed()
        capture("resources-search-partial")
    }
}
