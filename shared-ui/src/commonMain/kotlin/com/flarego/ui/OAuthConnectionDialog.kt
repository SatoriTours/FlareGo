package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-screen presentation; the existing OAuth owner still owns all authorization and persistence. */
@Composable
fun ConnectionDialog(onDismiss: () -> Unit, oauthConnection: OAuthConnection? = null) {
    val state = oauthConnection?.state?.collectAsState()?.value ?: OAuthConnectionState()
    val configured = oauthConnection?.configured == true
    val browser = LocalUriHandler.current
    var showDetails by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val dismiss = { oauthConnection?.cancel(); currentDismiss() }
    // Only successful persistence can close the screen. Saving cannot be cancelled by back.
    LaunchedEffect(state.connected) { if (state.connected) dismiss() }
    val openAuthorization = {
        try { oauthConnection?.begin()?.let(browser::openUri) }
        catch (_: Exception) { oauthConnection?.launchFailed() }
        Unit
    }
    Dialog(
        onDismissRequest = { if (!state.connecting) dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnBackPress = !state.connecting, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("oauth-screen"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.authorized && state.accounts.isNotEmpty() && !state.connecting) "选择账号" else "添加账号",
                        Modifier.weight(1f).testTag("oauth-title"), style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = !state.connecting, onClick = dismiss) { Text("取消") }
                }
                BoxWithConstraints(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                        .padding(vertical = 24.dp),
                        verticalArrangement = if (state.authorized && state.accounts.isNotEmpty() && !state.connecting) Arrangement.Top else Arrangement.Center) {
                        val choosing = state.authorized && state.accounts.isNotEmpty() && !state.connecting && !state.connected
                        if (!choosing) {
                            if (state.busy || state.connecting) Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("正在连接账号…",
                                    style = MaterialTheme.typography.headlineSmall)
                            } else Text(when {
                                state.waiting -> "等待 Cloudflare 授权"
                                state.error != null -> "连接未完成"
                                else -> "连接 Cloudflare"
                            }, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(10.dp))
                            if (state.error == null) Muted(when {
                                state.connecting -> "正在保存连接…"
                                state.busy -> "正在读取账号…"
                                state.waiting -> "完成授权后返回这里"
                                else -> "在浏览器中完成授权"
                            })
                        }
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("oauth-error"),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!configured) Notice("此版本暂不支持 Cloudflare 授权，请联系维护者获取已配置授权的版本。已有连接仍可使用。")
                        if (choosing) {
                            state.accounts.forEach { account ->
                                val duplicateName = state.accounts.count { it.name == account.name } > 1
                                TextButton(
                                    enabled = !state.busy && !state.reauthorizationRequired,
                                    onClick = { oauthConnection?.connect(account.id) },
                                    contentPadding = PaddingValues(vertical = 14.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("oauth-account-${account.id}"),
                                ) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                        if (duplicateName) Text("账号尾号 ${account.id.takeLast(6)}", style = MaterialTheme.typography.bodySmall)
                                        if (state.error != null && !state.reauthorizationRequired)
                                            Text("点击重试连接", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        if (state.authorized && !state.connecting && !state.connected) {
                            if ((state.discoveryFailed || state.hasMore) && !state.reauthorizationRequired) TextButton(
                                enabled = !state.busy, onClick = { oauthConnection?.loadMore() },
                                modifier = Modifier.fillMaxWidth().testTag("oauth-discover"),
                            ) { Text(when {
                                state.discoveryFailed -> "重新读取账号"
                                else -> "加载更多账号"
                            }) }
                            TextButton(enabled = !state.busy, onClick = openAuthorization,
                                modifier = Modifier.fillMaxWidth().testTag("oauth-authorize")) {
                                Text("重新前往 Cloudflare 授权")
                            }
                        } else if (!state.connected && !state.connecting && !state.busy) {
                            Spacer(Modifier.height(24.dp))
                            Button(enabled = configured && !state.busy, onClick = openAuthorization, shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("oauth-authorize")) {
                                Text(if (state.waiting) "继续前往 Cloudflare" else "前往 Cloudflare 授权")
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)) {
                    TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showDetails) "收起授权说明" else "权限与隐私说明", style = MaterialTheme.typography.bodySmall)
                    }
                    if (showDetails) Muted("仅访问本次授权允许的功能，连接成功不代表已开放所有资源的读写权限。授权凭据加密保存在本机。断开连接只删除本机凭据；你也可以在 Cloudflare 撤销授权。")
                }
            }
        }
    }
}
