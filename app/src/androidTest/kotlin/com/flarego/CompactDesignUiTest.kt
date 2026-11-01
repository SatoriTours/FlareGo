package com.flarego

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.zones.*
import com.flarego.core.model.*
import com.flarego.ui.*
import com.flarego.ui.zones.ZonePage
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Compose interactions; fixtures never contact a cloud account. */
class CompactDesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val connection = Connection("compact", "测试账号", "test-account")
    private val account = AccountScope(connection.id, connection.accountId)
    private fun zone(id: String, status: String, scope: AccountScope = account) =
        ZoneDetails(ZoneScope(scope, id), "$id.example.com", status, false, "full",
            emptyList(), JsonObject(emptyMap()))

    private fun content(dark: Boolean = false, large: Boolean = false, body: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                FlareGoTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { body() } }
            }
        }
    }
    private fun capture(name: String, tag: String? = null) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "native-design").also { it.mkdirs() }
        val bitmap = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun connectionUsesAnIndependentScreenAndNeverShowsCredentials() {
        var cancelled = 0
        content { ConnectionDialog({ cancelled++ }) }
        compose.onNodeWithTag("oauth-screen").assertWidthIsAtLeast(300.dp).assertHeightIsAtLeast(500.dp)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("CF").assertDoesNotExist()
        capture("connection", "oauth-screen")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun firstLaunchKeepsOnlyTheConnectionActionAndOptionalHelp() {
        var connected = false
        content { ServiceSetupScreen { connected = true } }
        compose.onNodeWithTag("setup-connect").performClick()
        compose.runOnIdle { assertTrue(connected) }
        compose.onNodeWithText("1. 添加服务，在官方页面确认 OAuth 授权。").assertDoesNotExist()
        capture("first-launch")
    }

    @Test fun overviewOffersEachLoadedProductWithoutInventingFees() {
        var destination: Page? = null
        content {
            OverviewScreen(AppState(listOf(connection), connection, productLoading = true),
                { destination = it }, {})
        }
        // Removing a product entry or sending it to billing breaks this contract.
        listOf("domains" to Page.DOMAINS, "worker" to Page.RESOURCES, "r2" to Page.RESOURCES,
            "kv" to Page.RESOURCES, "d1" to Page.RESOURCES).forEach { (tag, page) ->
            compose.onNodeWithTag("overview-$tag").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(page, destination) }
        }
        compose.onAllNodesWithText("未加载", useUnmergedTree = true).assertCountEquals(5)
        compose.onNodeWithText("本月费用预估").assertDoesNotExist()
        compose.onNodeWithText("$0").assertDoesNotExist()
        capture("overview")
    }

    @Test fun zoneFiltersCombineWithSearchAndNeverExposeAnotherAccount() {
        val active = zone("active", "active")
        val pending = zone("pending", "pending")
        val foreign = zone("foreign", "active", AccountScope("other", "other"))
        var opened: ZoneScope? = null
        val query = mutableStateOf("")
        content {
            ZonePage(ZonePageState(account = account, zones = listOf(active, pending, foreign)),
                { if (it is ZoneIntent.Open) opened = it.scope }, query = query.value)
        }
        compose.onNodeWithTag("zone-filter-active").performClick()
        compose.onNodeWithTag("zone-row-active").assertIsDisplayed()
        compose.onNodeWithTag("zone-row-pending").assertDoesNotExist()
        compose.onNodeWithTag("zone-row-foreign").assertDoesNotExist()
        compose.runOnIdle { query.value = "pending" }
        compose.onNodeWithTag("zone-row-active").assertDoesNotExist()
        compose.onNodeWithTag("zone-row-pending").assertDoesNotExist()
        compose.onNodeWithTag("zone-filter-pending").performClick()
        compose.onNodeWithTag("zone-row-pending").performClick()
        compose.runOnIdle { assertEquals(pending.ref, opened) }
        capture("domains-filtered")
    }

    @Test fun failedZoneLoadDoesNotClaimTheAccountHasNoZones() {
        content { ZonePage(ZonePageState(account = account, error = "读取失败"), {}) }
        compose.onNodeWithText("当前账号尚无 Zone；可刷新或检查 Zone Read 权限。").assertDoesNotExist()
        compose.onNodeWithTag("zone-refresh").performScrollTo().assertIsEnabled()
        capture("domains-error")
    }

    @Test fun zoneFilterResetsForAnotherAccountAndRefreshKeepsLoadedRows() {
        val current = mutableStateOf(ZonePageState(account = account,
            zones = listOf(zone("active", "active"), zone("pending", "pending")), loading = true))
        content(dark = true, large = true) { ZonePage(current.value, {}) }
        compose.onNodeWithTag("zone-filter-active").performClick()
        compose.onNodeWithTag("zone-row-active").performScrollTo().assertIsDisplayed()
        val other = AccountScope("second", "second-account")
        compose.runOnIdle { current.value = ZonePageState(account = other, zones = listOf(zone("second", "pending", other))) }
        compose.onNodeWithTag("zone-row-second").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("zone-row-active").assertDoesNotExist()
        capture("domains-large")
    }
}
