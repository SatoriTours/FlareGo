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
        Text("还没有连接云服务。连接 Cloudflare 后，可查看授权范围内的域名、资源和账单。")
        Pill("Cloudflare")
        Text("1. 添加服务，在官方页面确认 OAuth 授权。")
        Text("2. 返回 App 选择账号，验证后保存连接。")
        Muted("OAuth 需此构建已配置客户端；未配置时可选择高级 API Token 方式。")
        Muted("目前支持 Cloudflare，其他云厂商正在规划中。密钥加密保存在本机。")
        OutlinedButton(
            onClick = {
                try {
                    uriHandler.openUri("https://developers.cloudflare.com/fundamentals/oauth/")
                    error = null
                } catch (_: Exception) {
                    error = "无法打开浏览器，请安装或启用浏览器后重试"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("Cloudflare 授权帮助")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = connect, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Text("添加 Cloudflare 服务")
        }
    }
}
