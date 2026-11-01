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

@Composable
fun ServiceSetupScreen(connect: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
            .padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.Center) {
            ServiceSetupCard(connect)
        }
    }
}

/** Also usable inside the account list, without a second card or introductory checklist. */
@Composable
fun ServiceSetupCard(connect: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("添加你的云账号", style = MaterialTheme.typography.headlineSmall)
        Muted("连接 Cloudflare，管理你的域名与资源")
        Button(onClick = connect, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-connect")) {
            Text("连接 Cloudflare")
        }
        TextButton(onClick = {
            try {
                uriHandler.openUri("https://developers.cloudflare.com/fundamentals/oauth/")
                error = null
            } catch (_: Exception) {
                error = "无法打开浏览器，请安装或启用浏览器后重试"
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Cloudflare 授权帮助", style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
