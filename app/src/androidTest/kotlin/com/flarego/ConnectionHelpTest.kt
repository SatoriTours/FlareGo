package com.flarego

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.flarego.core.model.CloudException
import com.flarego.ui.ConnectionDialog
import com.flarego.ui.ConnectionDraft
import com.flarego.ui.FlareGoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConnectionHelpTest {
    @get:Rule val compose = createComposeRule()

    private val accountId = "a".repeat(32)
    private val token = "fixture-token-not-a-real-credential"

    private fun fillConnection() {
        compose.onNode(hasSetTextAction() and hasText("账号名称"))
            .performScrollTo().performTextInput("测试账号")
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .performScrollTo().performTextInput(accountId)
        compose.onNode(hasSetTextAction() and hasText("API Token"))
            .performScrollTo().performTextInput(token)
    }

    @Test
    fun tokenHelpOpensOfficialPageWithoutSubmittingOrLosingCredentials() {
        val opened = mutableListOf<String>()
        var submitted: List<String>? = null
        var dismissed = false
        val draft = ConnectionDraft()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened.add(uri) }
            }) {
                FlareGoTheme {
                    ConnectionDialog(
                        onDismiss = { dismissed = true },
                        connect = { name, account, secret -> submitted = listOf(name, account, secret) },
                        draft = draft,
                    )
                }
            }
        }
        fillConnection()
        compose.onNodeWithText("获取 API Token").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://dash.cloudflare.com/profile/api-tokens"), opened)
            assertNull(submitted)
            assertFalse(dismissed)
        }
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle {
            assertEquals(listOf("测试账号", accountId, token), submitted)
            assertTrue(dismissed)
            assertEquals("", draft.token)
            assertEquals("", draft.accountId)
            assertEquals("", draft.name)
        }
    }

    @Test
    fun accountHelpOpensOfficialInstructionsWithoutClosingDialog() {
        val opened = mutableListOf<String>()
        var dismissed = false
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened.add(uri) }
            }) {
                FlareGoTheme {
                    ConnectionDialog(
                        onDismiss = { dismissed = true },
                        connect = { _, _, _ -> fail("Help must not submit credentials") },
                    )
                }
            }
        }
        compose.onNodeWithText("查找 Account ID").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("https://developers.cloudflare.com/fundamentals/account/find-account-and-zone-ids/"),
                opened,
            )
            assertFalse(dismissed)
        }
        compose.onNodeWithText("连接 Cloudflare").assertIsDisplayed()
        compose.onNodeWithText("验证并连接").assertIsNotEnabled()
    }

    @Test
    fun verificationFailureIsVisibleWithoutScrollingToTheBottom() {
        compose.setContent {
            FlareGoTheme {
                ConnectionDialog(
                    onDismiss = {},
                    connect = { _, _, _ -> throw CloudException("账号验证无访问权限") },
                )
            }
        }
        fillConnection()
        compose.onNodeWithText("获取 API Token").performScrollTo()
        compose.onNodeWithText("验证并连接").performClick()
        compose.onNodeWithText("账号验证无访问权限").assertIsDisplayed()
    }

    @Test
    fun invalidAccountIdIsMarkedBeforeAnyConnectionAttempt() {
        var submitted = false
        compose.setContent {
            FlareGoTheme {
                ConnectionDialog(
                    onDismiss = {},
                    connect = { _, _, _ -> submitted = true },
                )
            }
        }
        fillConnection()
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .performScrollTo().performTextReplacement("not-an-account-id")
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertFalse("Invalid Account ID must not be submitted", submitted) }
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText("Account ID 必须为 32 位十六进制字符").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Account ID"))
            .performScrollTo().performTextReplacement("  ${accountId.uppercase()}  ")
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertTrue(submitted) }
    }

    @Test
    fun unavailableBrowserShowsRecoverableErrorAndKeepsCredentials() {
        var submitted: List<String>? = null
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    throw IllegalArgumentException("No browser handler")
                }
            }) {
                FlareGoTheme {
                    ConnectionDialog(
                        onDismiss = {},
                        connect = { name, account, secret -> submitted = listOf(name, account, secret) },
                    )
                }
            }
        }
        fillConnection()
        compose.onNodeWithText("获取 API Token").performScrollTo().performClick()
        compose.onNodeWithText("无法打开浏览器，请安装或启用浏览器后重试")
            .assertIsDisplayed()
        compose.onNodeWithText("验证并连接").performClick()
        compose.runOnIdle { assertEquals(listOf("测试账号", accountId, token), submitted) }
    }
}
