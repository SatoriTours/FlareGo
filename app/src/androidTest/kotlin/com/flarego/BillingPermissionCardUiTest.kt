package com.flarego

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.core.application.AppController
import com.flarego.core.application.connection.*
import com.flarego.core.ports.TokenKind
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.flarego.core.application.inventory.*
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.FlareGoApp
import com.flarego.ui.FlareGoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Billing permission failure is an actionable state, not a silent error line:
 * a base-scope authorization must be able to reach the reauthorization and console entries.
 */
class BillingPermissionCardUiTest {
    @get:Rule val compose = createComposeRule()
    private val connection = Connection("billing-permission", "账单测试", "synthetic-account")
    private val error = "当前授权缺少账单读取权限（fixture）"
    private var consolePage: Page? = null
    private val token = FakeTokenConnection()
    private val requested = mutableListOf<Set<ReadTarget>>()
    private val opened = mutableListOf<String>()

    private fun install(readProfile: Boolean = false, code: CloudFailureCode = CloudFailureCode.PERMISSION_REQUIRED) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                AppController(scope, SessionFactory { DemoSession(it) }, MemoryStore(), listOf(connection),
                    availableProducts = { if (readProfile) emptySet() else ProductKey.entries.toSet() },
                    productFactory = ProductSessionFactory {
                        ProductServices(object : ProductDirectoryPort {
                            override suspend fun zones(account: AccountScope, request: PageRequest) =
                                CloudPage(emptyList<Domain>(), null, 0)
                            override suspend fun resources(account: AccountScope, kind: ResourceKind, request: PageRequest) =
                                CloudPage(emptyList<CloudResource>(), null, 0)
                        }, object : ScopedBillingPort {
                            override suspend fun history(scope: CloudScope, request: PageRequest): CloudPage<BillingEntry> =
                                throw CloudFailure(code, error)
                        }, object : CapabilityPort {
                            override suspend fun status(scope: CloudScope, actionKey: String) = CapabilityStatus(actionKey, scope)
                        })
                    }).also { it.navigate(Page.BILLING) }
            }
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) {
                FlareGoTheme {
                    FlareGoApp(controller, {}, { page -> consolePage = page }, tokenConnection = token,
                        upgradeToken = { _, targets ->
                            requested += targets
                            token.mutable.value = TokenConnectionStatus(upgrading = true, upgradePlan =
                                TokenUpgradePlan("1".repeat(32), "账单测试", setOf(ReadFeature.ACCOUNT, ReadFeature.BILLING), null))
                        })
                }
            }
        }
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("billing-permission-card").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(error, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun permissionFailureOffersReauthorizationAndKeepsTheProviderError() {
        install()
        compose.onNodeWithTag("billing-permission-card").assertIsDisplayed()
        compose.onNodeWithText("无法读取账单历史").assertIsDisplayed()
        // The provider message is never swallowed; it stays readable inside the card.
        compose.onNodeWithText(error, substring = true).assertIsDisplayed()

        // Reauthorization opens the real connection dialog (the only observable of openConnection).
        compose.onNodeWithTag("billing-reauthorize").performClick()
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.onNodeWithText("补充读取权限").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(setOf(ReadTarget(ReadFeature.BILLING))), requested) }
        compose.onNodeWithTag("token-cancel").performClick()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()

        compose.onNodeWithTag("billing-open-console").performClick()
        compose.runOnIdle { assertEquals(Page.BILLING, consolePage) }
    }

    @Test fun networkFailureDoesNotOfferToUpgrade() {
        install(code = CloudFailureCode.UNAVAILABLE)
        compose.onNodeWithTag("billing-reauthorize").assertDoesNotExist()
    }

    @Test fun successfulReplacementOffersOnlyAnOptionalManagementPage() {
        install()
        compose.onNodeWithTag("billing-reauthorize").performClick()
        val old = "f".repeat(32)
        compose.runOnIdle {
            token.mutable.value = TokenConnectionStatus(phase = TokenPhase.CONNECTED, connected = true,
                upgrading = true, retiredToken = RetiredToken(old, TokenKind.USER_OWNED))
        }
        compose.onNodeWithTag("old-token-id").assertTextContains(old, substring = true)
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
        compose.onNodeWithTag("old-token-manage").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://dash.cloudflare.com/profile/api-tokens"), opened) }
        compose.onNodeWithTag("old-token-skip").performScrollTo().performClick()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()
    }

    @Test fun aLegacyOauthConnectionIsExplainedWithoutInvitingAScopeHuntOrAReload() {
        // Legacy OAuth: no product is requested at all, so the card explains the migration to a
        // Token instead of naming an unverified permission or looping re-authorization.
        install(readProfile = true)
        compose.onNodeWithTag("billing-permission-card").assertIsDisplayed()
        compose.onNodeWithText("该连接需要配置 API Token").assertIsDisplayed()
        compose.onNodeWithText("旧版 OAuth 授权已停用", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Billing", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("billing-reauthorize").assertDoesNotExist()
        compose.onNodeWithTag("billing-open-console").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(Page.BILLING, consolePage) }
    }
}
