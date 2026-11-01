package com.flarego

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.flarego.ui.*
import org.junit.Rule
import org.junit.Test

class ConnectionHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingAuthorizationPortCannotFallBackToManualCredentials() {
        compose.setContent { FlareGoTheme { ConnectionDialog({}) } }
        compose.onNodeWithTag("oauth-authorize").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("取消").assertIsEnabled()
    }

    @Test fun privacyExplainsPermissionLimitsAndLocalDisconnection() {
        compose.setContent { FlareGoTheme { ConnectionDialog({}) } }
        compose.onNodeWithText("权限与隐私说明").performScrollTo().performClick()
        compose.onNodeWithText("仅访问本次授权允许的功能，连接成功不代表已开放所有资源的读写权限。授权凭据加密保存在本机。断开连接只删除本机凭据；你也可以在 Cloudflare 撤销授权。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("收起授权说明").performScrollTo().performClick()
        compose.onNodeWithText("权限与隐私说明").assertIsDisplayed()
    }
}
