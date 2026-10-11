package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun ServiceSetupScreen(connect: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ServiceSetupCard(connect) }
    }
}

@Composable
fun ServiceSetupCard(connect: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard {
        Text("添加你的第一个云服务", style = MaterialTheme.typography.headlineSmall)
        Text("还没有连接云服务。添加服务并绑定 API Token 后，即可查看你的域名、资源和账单。")
        Pill("Cloudflare")
        Text("1. 前往 Cloudflare 创建 API Token，按需授予读取权限。")
        Text("2. 添加服务，填写 Account ID 和 API Token，验证后保存。")
        Muted("目前支持 Cloudflare，其他云厂商正在规划中。密钥加密保存在本机。")
        OutlinedButton(
            onClick = {
                try {
                    uriHandler.openUri("https://dash.cloudflare.com/profile/api-tokens")
                    error = null
                } catch (_: Exception) {
                    error = "无法打开浏览器，请安装或启用浏览器后重试"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("前往 Cloudflare 配置密钥")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Text("添加服务并绑定密钥")
        }
    }
}
