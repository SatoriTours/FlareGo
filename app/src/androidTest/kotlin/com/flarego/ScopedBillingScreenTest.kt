package com.flarego

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.core.model.*
import com.flarego.ui.BillingScreen
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.ScopedBillingScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScopedBillingScreenTest {
    @get:Rule val compose=createComposeRule()
    private val connection=Connection("local","Cloudflare","account")
    private val account=AccountScope("local","account")
    private fun entry(scope: CloudScope=account,amount: String?=null)=
        BillingEntry("charge",scope,"2026-10-01T00:00:00Z",amount,"USD","Scoped charge",null,null,null)

    @Test fun accountHistorySaysAccountAndKeepsMissingAmountUnknown() {
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,account,CloudPage(listOf(entry()),null,null),{})
        } }
        compose.onNodeWithText("当前账号账单历史").assertIsDisplayed()
        compose.onNodeWithText("未知 USD").assertIsDisplayed()
        compose.onNodeWithText("用户级账单历史 · 旧接口").assertDoesNotExist()
        compose.onNodeWithText("0 USD").assertDoesNotExist()
        compose.onNodeWithText("本月预估").assertDoesNotExist()
    }

    @Test fun explicitUserHistoryKeepsTheOldUserLabelAndConsoleAction() {
        val user=UserScope("local","explicit-user")
        var opened=0
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,user,CloudPage(listOf(entry(user,"20.99")),null,null),{opened++})
        } }
        compose.onNodeWithText("用户级账单历史 · 旧接口").assertIsDisplayed()
        compose.onNodeWithText("当前账号账单历史").assertDoesNotExist()
        compose.onNodeWithText("20.99 USD").assertIsDisplayed()
        compose.onNodeWithText("在官方控制台查看费用与发票").performClick()
        compose.runOnIdle { assertEquals(1,opened) }
    }

    @Test fun foreignAccountHistoryIsHiddenInsteadOfRelabeledAsTheCurrentAccount() {
        val other=AccountScope("local","other-account")
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,other,CloudPage(listOf(entry(other)),null,null),{})
        } }
        compose.onNodeWithText("账单范围与当前连接不一致，请重新加载").assertIsDisplayed()
        compose.onNodeWithText("Scoped charge").assertDoesNotExist()
        compose.onNodeWithText("当前账号账单历史").assertDoesNotExist()
    }

    @Test fun mixedScopeRowsAreNotPublishedUnderAnAccountTitle() {
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,account,CloudPage(listOf(entry(UserScope("local","other-user"))),null,null),{})
        } }
        compose.onNodeWithText("账单数据与所选范围不一致，请重新加载").assertIsDisplayed()
        compose.onNodeWithText("Scoped charge").assertDoesNotExist()
    }

    @Test fun legacyScreenAlsoUsesUnknownForMissingAmountsAndLabelsItsScope() {
        val state=AppState(listOf(connection),connection,billing=Billing(listOf(Invoice("legacy","2026-10-01",null,"USD","Legacy charge"))))
        compose.setContent { FlareGoTheme { BillingScreen(state,{}) } }
        compose.onNodeWithText("用户级账单历史 · 旧接口").assertIsDisplayed()
        compose.onNodeWithText("未知 USD").assertIsDisplayed()
    }

    @Test fun anActualZeroAmountRemainsZero() {
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,account,CloudPage(listOf(entry(amount="0")),null,null),{})
        } }
        compose.onNodeWithText("0 USD").assertIsDisplayed()
        compose.onNodeWithText("未知 USD").assertDoesNotExist()
    }

    @Test fun unsupportedScopeDoesNotRenderAHistory() {
        compose.setContent { FlareGoTheme {
            ScopedBillingScreen(connection,ZoneScope(account,"zone"),CloudPage(emptyList(),null,null),{})
        } }
        compose.onNodeWithText("不支持此账单范围，请选择账号或用户").assertIsDisplayed()
        compose.onNodeWithText("当前账号账单历史").assertDoesNotExist()
    }
}
