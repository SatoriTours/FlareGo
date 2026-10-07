package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PurchaseScreen(
    keyword: String,
    searched: Boolean,
    demo: Boolean,
    choose: (String, String) -> Unit,
    openConsole: () -> Unit,
) {
    val word =
        keyword
            .trim()
            .lowercase()
            .substringBefore('.')
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .take(50)
            .ifBlank { "flarego" }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Notice(if (demo) "域名与价格均为示例，可体验购买确认，无真实注册或扣款。" else "真实域名查询与注册首版在 Cloudflare 控制台完成。")
        }
        if (demo && searched) {
            item { Text("“$word” 的示例结果", style = MaterialTheme.typography.titleMedium) }
            listOf("com" to "10.44", "dev" to "12.00", "app" to "14.00").forEach { (suffix, price)
                ->
                item {
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("$word.$suffix", style = MaterialTheme.typography.titleMedium)
                                Muted("示例可注册 · 年费 USD")
                            }
                            Text("$$price", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { choose("$word.$suffix", price) }) { Text("选择") }
                        }
                    }
                }
            }
        } else if (demo) item { EmptyState("搜索一个域名", "输入标题栏的搜索框，提交后查看示例结果") }
        if (!demo)
            item {
                Button(onClick = openConsole, modifier = Modifier.fillMaxWidth()) {
                    Text("在官方控制台搜索并注册")
                }
            }
    }
}

@Composable
fun PurchaseDialog(name: String, price: String, onDismiss: () -> Unit, confirm: () -> Unit) {
    var autoRenew by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模拟购买 $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("注册 1 年 · 示例价格 $$price USD")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("自动续费 · 演示选项")
                    Switch(autoRenew, { autoRenew = it })
                }
                Notice("演示流程不会购买真实域名，也不会保存续费设置或扣款。真实注册需要在官方控制台确认联系人、协议和付款。")
            }
        },
        confirmButton = { TextButton(onClick = confirm) { Text("确认模拟购买") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
