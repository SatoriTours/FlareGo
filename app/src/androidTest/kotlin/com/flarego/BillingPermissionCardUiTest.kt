package com.flarego

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.core.application.AppController
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

    private fun install(readProfile: Boolean = false) {
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
                                throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, error)
                        }, object : CapabilityPort {
                            override suspend fun status(scope: CloudScope, actionKey: String) = CapabilityStatus(actionKey, scope)
                        })
                    }).also { it.navigate(Page.BILLING) }
            }
            FlareGoTheme {
                FlareGoApp(controller, {}, { page -> consolePage = page },
                    tokenConnection = FakeTokenConnection())
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("billing-permission-card").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun permissionFailureOffersReauthorizationAndKeepsTheProviderError() {
        install()
        compose.onNodeWithTag("billing-permission-card").assertIsDisplayed()
        compose.onNodeWithText("账单读取尚未启用").assertIsDisplayed()
        // The provider message is never swallowed; it stays readable inside the card.
        compose.onNodeWithText(error, substring = true).assertIsDisplayed()

        // Reauthorization opens the real connection dialog (the only observable of openConnection).
        compose.onNodeWithTag("billing-reauthorize").performClick()
        compose.onNodeWithTag("token-screen").assertIsDisplayed()
        compose.onNodeWithTag("token-purpose-billing").assertIsOn()
        compose.onNodeWithTag("token-cancel").performClick()
        compose.onNodeWithTag("token-screen").assertDoesNotExist()

        compose.onNodeWithTag("billing-open-console").performClick()
        compose.runOnIdle { assertEquals(Page.BILLING, consolePage) }
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
