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
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.application.connection.ReadTarget
import com.flarego.core.application.connection.TokenConnectionStatus
import com.flarego.core.application.connection.TokenPhase
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
    private val token = FakeTokenConnection()
    private val upgrades = mutableListOf<Pair<Connection, Set<ReadTarget>>>()
    private val deniedTargets = setOf(
        ReadTarget(ReadFeature.WORKERS), ReadTarget(ReadFeature.R2), ReadTarget(ReadFeature.KV),
        ReadTarget(ReadFeature.D1), ReadTarget(ReadFeature.BILLING),
    )
    private fun install(dark: Boolean = false, large: Boolean = false, readProfile: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), listOf(connection),
                    // readProfile stands for a legacy OAuth connection: it may request nothing.
                    availableProducts = { if (readProfile) emptySet() else ProductKey.entries.toSet() },
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
                    Surface(Modifier.fillMaxSize()) {
                        FlareGoApp(controller, {}, {}, tokenConnection = token, upgradeToken = { selected, targets ->
                            upgrades += selected to targets
                            token.mutable.value = TokenConnectionStatus(upgrading = true, phase = TokenPhase.VERIFYING, busy = true)
                        })
                    }
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
    private fun assertRefreshSummary(message: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(message))
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    private fun refreshAll() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("overview-refresh-all"))
        compose.onNodeWithTag("overview-refresh-all").performClick()
    }

    @Test fun aLegacyOauthConnectionRequestsNothingAndExplainsTheTokenMigration() {
        install(readProfile = true)
        refreshAll()
        // OAuth is retired: the old record keeps its row but sends no request at all.
        compose.runOnIdle { assertTrue("legacy OAuth must not read any product", calls.isEmpty()) }
        compose.onNodeWithTag("overview-d1").performScrollTo().assert(hasAnyDescendant(hasText("当前授权方案未支持")))
        compose.onNodeWithTag("overview-d1").performClick()
        compose.onNodeWithText("当前授权方案未支持 D1 数据库").assertIsDisplayed()
        capture("unsupported-d1")
        compose.onNodeWithContentDescription("创建资源").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新").assertDoesNotExist()
        compose.onNodeWithTag("nav-billing").performClick()
        // The legacy OAuth record explains the migration: reconfigure with a Token, keep local data.
        compose.onNodeWithText("该连接需要配置 API Token").assertIsDisplayed()
        compose.onNodeWithText("旧版 OAuth 授权已停用", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("billing-open-console").assertIsDisplayed()
        compose.onNodeWithTag("billing-reauthorize").assertDoesNotExist()
        compose.onNodeWithTag("toolbar-refresh").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls.size) }
        capture("unsupported-billing")
    }

    @Test fun toolbarRefreshActuallyReadsAndReportsCompletion() {
        install()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithContentDescription("刷新").performClick()
        assertRefreshSummary("读取完成：6 项成功")
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
        assertRefreshSummary("读取完成：6 项成功")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("overview-refresh-all"))
        compose.onNodeWithTag("overview-refresh-all").assertIsEnabled()
        refreshAll()
        compose.runOnIdle { assertEquals(12, calls.size) }
    }

    @Test fun permissionFailuresAreExplainedAndOfferReauthorization() {
        forbidden = true
        install()
        refreshAll()
        assertRefreshSummary("读取完成：1 项成功，5 项失败")
        // The failure list is a lazy list; scroll to the last entry instead of assuming it is
        // already composed. The exact text is still asserted.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("账单：当前授权缺少账单读取权限"))
        compose.onNodeWithText("账单：当前授权缺少账单读取权限").assertIsDisplayed()
        assertPermissionUpgrade()
    }

    @Test fun failedRefreshKeepsPreviousCountButMarksItStaleAndCanRecover() {
        install()
        refreshAll()
        compose.runOnIdle { unavailable = true }
        refreshAll()
        assertRefreshSummary("读取完成：5 项成功，1 项失败")
        compose.onNodeWithTag("overview-domains").performScrollTo().assert(hasAnyDescendant(hasText("1+ · 更新失败")))
        val networkError = "域名：暂时无法加载，请稍后重试"
        val overview = compose.onNode(hasScrollToNodeAction())
        overview.performScrollToNode(hasText(networkError))
        compose.onNodeWithText(networkError).assertIsDisplayed()
        // The upgrade action, when present, follows the last failure in the lazy list.
        overview.performTouchInput { swipeUp() }
        compose.onNodeWithTag("overview-permission-upgrade").assertDoesNotExist()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
        compose.runOnIdle { assertTrue(upgrades.isEmpty()) }
        capture("stale-error")
        compose.runOnIdle { unavailable = false }
        refreshAll()
        assertRefreshSummary("读取完成：6 项成功")
        compose.onNodeWithTag("overview-domains").performScrollTo().assert(hasAnyDescendant(hasText("1")))
    }

    @Test fun failureFeedbackIsReachableInDarkThemeWithLargeText() {
        forbidden = true
        // The same refresh has a network error for Zones; only explicit denials may be requested.
        unavailable = true
        install(dark = true, large = true)
        refreshAll()
        assertRefreshSummary("读取完成：0 项成功，6 项失败")
        assertPermissionUpgrade(captureName = "dark-large-errors")
    }

    private fun assertPermissionUpgrade(captureName: String? = null) {
        val tag = "overview-permission-upgrade"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        val entry = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
        captureName?.let(::capture)
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
        compose.runOnIdle { assertTrue(upgrades.isEmpty()) }
        entry.performClick()
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.onNodeWithTag("token-title").assertTextEquals("补充读取权限")
        compose.onNodeWithTag("token-create").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(connection to deniedTargets), upgrades) }
    }
}
