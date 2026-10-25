package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.safeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OAuthConnectionDialog(onDismiss: () -> Unit, draft: ConnectionDraft, oauth: OAuthConnection) {
    val state by oauth.state.collectAsState()
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dismiss = {
        oauth.cancel()
        draft.clear()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        title = { Text("连接 Cloudflare") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("在 Cloudflare 官方页面确认权限，无需手动创建或复制 API Token。")
                Muted("只申请此构建已配置的权限；授权不代表拥有全部功能，未获授权的操作仍会被拒绝。")
                if (!oauth.configured) Notice("此构建尚未配置 OAuth 客户端，暂不能授权。可使用下方 API Token 备用方式；已有连接不受影响。")
                Button(
                    enabled = oauth.configured && !state.busy && !saving,
                    onClick = {
                        error = null
                        try { browser.openUri(oauth.begin()) }
                        catch (_: Exception) { oauth.launchFailed() }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("oauth-authorize"),
                ) { Text(if (state.waiting) "重新打开授权页面" else if (state.authorized) "重新授权" else "使用 Cloudflare 官方授权") }
                if (state.waiting) Muted("正在等待浏览器授权。完成后将返回 App；若进程已退出，请重新授权。")
                if (state.busy || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.authorized) {
                    Text("选择本次连接的账号", style = MaterialTheme.typography.titleSmall)
                    state.accounts.forEach { account ->
                        OutlinedButton(
                            enabled = !state.busy && !saving,
                            onClick = {
                                draft.accountId = account.id
                                if (draft.name.isBlank()) draft.name = account.name
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("oauth-account-${account.id}"),
                        ) { Text("${account.name}\n${account.id}") }
                    }
                    if (state.hasMore) TextButton(enabled = !state.busy && !saving, onClick = oauth::loadMore) { Text("发现更多账号") }
                    OutlinedTextField(draft.name, { draft.name = it }, label = { Text("账号名称（可选）") },
                        enabled = !state.busy && !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.accountId, { draft.accountId = it; error = null }, label = { Text("Account ID") },
                        enabled = !state.busy && !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Muted("也可手填 32 位 Account ID。连接前会验证该账号是否在授权范围内。凭据仅加密保存在本机。")
                }
                TextButton(
                    enabled = !saving,
                    onClick = { oauth.cancel(); draft.accountId = ""; draft.manualToken = true },
                    modifier = Modifier.fillMaxWidth().testTag("oauth-token-fallback"),
                ) { Text("高级：使用 API Token 连接") }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("oauth-error"), style = MaterialTheme.typography.bodySmall) }
                TextButton(
                    enabled = state.authorized && !state.busy && !saving && draft.accountId.isNotBlank(),
                    onClick = {
                        saving = true; error = null
                        scope.launch {
                            try { oauth.connect(draft.name, draft.accountId); dismiss() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = safeMessage(failure) }
                            finally { saving = false }
                        }
                    },
                ) { Text(if (saving) "验证中…" else "验证并连接") }
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("取消") } },
    )
}
