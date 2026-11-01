package com.flarego

import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.AppController
import com.flarego.core.application.inventory.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real app entry and product loader; no cloud credentials or external requests. */
class RefreshFeedbackUiTest {
    @get:Rule val compose = createComposeRule()
    private val connection = Connection("refresh", "刷新测试", "synthetic-account")
    private var gate: CompletableDeferred<Unit>? = null
    private var forbidden = false
    private var unavailable = false
    private val calls = mutableListOf<ProductKey>()
    private fun install(dark: Boolean = false, large: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), listOf(connection),
                    productFactory = ProductSessionFactory {
                        ProductServices(object : ProductDirectoryPort {
                            override suspend fun zones(account: AccountScope, request: PageRequest): CloudPage<Domain> {
                                calls += ProductKey.ZONES; gate?.await()
                                if (unavailable) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法加载，请稍后重试")
                                return CloudPage(listOf(Domain("z", "example.test", "active", connection.id)), null, 1)
                            }
                            override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest): CloudPage<CloudResource> {
                                calls += kind.product(); gate?.await()
                                if (forbidden) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "当前授权缺少产品读取权限")
                                return CloudPage(emptyList(), null, 0)
                            }
                        }, object : ScopedBillingPort {
                            override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> {
                                calls += ProductKey.BILLING; gate?.await()
                                if (forbidden) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "当前授权缺少账单读取权限")
                                return CloudPage(emptyList(), null, 0)
                            }
                        }, object : CapabilityPort {
                            override suspend fun status(scope: CloudScope, actionKey: String) = CapabilityStatus(actionKey, scope)
                        })
                    })
            }
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                FlareGoTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) { FlareGoApp(controller, {}, {}) }
                }
            }
        }
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "refresh-feedback").also { it.mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun refreshAll() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("overview-refresh-all"))
        compose.onNodeWithTag("overview-refresh-all").performClick()
    }

    @Test fun toolbarRefreshActuallyReadsAndReportsCompletion() {
        install()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.onNodeWithText("读取完成：6 项成功").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(6, calls.size) }
        capture("success")
    }

    @Test fun allProductsRefreshShowsProgressAndPreventsRepeatedClicks() {
        gate = CompletableDeferred()
        install()
        refreshAll()
        compose.onNodeWithTag("overview-refresh-all").assertIsNotEnabled()
        compose.onNodeWithContentDescription("正在刷新").assertIsDisplayed()
        compose.onNodeWithTag("toolbar-refresh").assertIsNotEnabled()
        compose.onNodeWithText("正在刷新产品…").assertIsDisplayed()
        capture("loading")
        compose.runOnIdle { assertEquals(6, calls.size); gate!!.complete(Unit) }
        compose.onNodeWithText("读取完成：6 项成功").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("overview-refresh-all").assertIsEnabled()
        refreshAll()
        compose.runOnIdle { assertEquals(12, calls.size) }
    }

    @Test fun permissionFailuresAreExplainedAndOfferReauthorization() {
        forbidden = true
        install()
        refreshAll()
        compose.onNodeWithText("读取完成：1 项成功，5 项失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("账单：当前授权缺少账单读取权限").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("重新授权"))
        compose.onNodeWithText("重新授权").performClick()
        compose.onNodeWithTag("oauth-screen").assertIsDisplayed()
    }

    @Test fun failedRefreshKeepsPreviousCountButMarksItStaleAndCanRecover() {
        install()
        refreshAll()
        compose.runOnIdle { unavailable = true }
        refreshAll()
        compose.onNodeWithText("读取完成：5 项成功，1 项失败").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("overview-domains").performScrollTo().assert(hasAnyDescendant(hasText("1+ · 更新失败")))
        compose.onNodeWithText("域名：暂时无法加载，请稍后重试").performScrollTo().assertIsDisplayed()
        capture("stale-error")
        compose.runOnIdle { unavailable = false }
        refreshAll()
        compose.onNodeWithText("读取完成：6 项成功").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("overview-domains").performScrollTo().assert(hasAnyDescendant(hasText("1")))
    }

    @Test fun failureFeedbackIsReachableInDarkThemeWithLargeText() {
        forbidden = true
        install(dark = true, large = true)
        refreshAll()
        compose.onNodeWithText("读取完成：1 项成功，5 项失败").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("重新授权"))
        compose.onNodeWithText("重新授权").assertIsDisplayed()
        capture("dark-large-errors")
    }
}
