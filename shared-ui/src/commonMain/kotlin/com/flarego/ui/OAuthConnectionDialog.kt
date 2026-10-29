package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    var showDetails by remember { mutableStateOf(false) }
    val dismiss = {
        oauth.cancel()
        draft.clear()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!saving) dismiss() },
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        icon = {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("CF", modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        title = { Text("连接 Cloudflare") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("在浏览器中完成官方授权，无需复制密钥。",
                    style = MaterialTheme.typography.bodyLarge)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.authorized) "✓  已完成官方授权" else "1  在浏览器确认授权",
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (state.authorized) "2  选择账号，验证并连接" else "2  返回 App，选择要连接的账号",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                (error ?: state.error)?.let {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(14.dp).testTag("oauth-error"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!oauth.configured) Notice("此构建尚未配置 OAuth 客户端，暂不能授权。可使用下方 API Token 备用方式；已有连接不受影响。")
                Button(
                    enabled = oauth.configured && !state.busy && !saving,
                    onClick = {
                        error = null
                        if (!state.waiting) draft.accountId = ""
                        try { browser.openUri(oauth.begin()) }
                        catch (_: Exception) { oauth.launchFailed() }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("oauth-authorize"),
                ) { Text(if (state.waiting) "返回浏览器继续授权" else if (state.authorized) "重新授权" else "前往 Cloudflare 授权") }
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
                    if (state.discoveryFailed || state.hasMore) TextButton(
                        enabled = !state.busy && !saving, onClick = oauth::loadMore,
                        modifier = Modifier.testTag("oauth-discover"),
                    ) { Text(if (state.discoveryFailed) "重试发现账号" else "发现更多账号") }
                    OutlinedTextField(draft.name, { draft.name = it }, label = { Text("账号名称（可选）") },
                        enabled = !state.busy && !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.accountId, { draft.accountId = it; error = null }, label = { Text("Account ID") },
                        enabled = !state.busy && !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Muted("也可手填 32 位 Account ID。连接前会验证该账号是否在授权范围内。凭据仅加密保存在本机。")
                }
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (showDetails) "收起授权说明" else "权限与隐私说明")
                }
                if (showDetails) Muted("仅请求此构建配置的权限，不代表已开通全部功能。凭据仅加密保存在本机，不会显示在此页。")
                HorizontalDivider()
                TextButton(
                    enabled = !saving,
                    onClick = { oauth.cancel(); draft.accountId = ""; draft.manualToken = true },
                    modifier = Modifier.fillMaxWidth().testTag("oauth-token-fallback"),
                ) { Text("改用 API Token 连接") }
            }
        },
        confirmButton = {
            if (state.authorized) {
                Button(
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
