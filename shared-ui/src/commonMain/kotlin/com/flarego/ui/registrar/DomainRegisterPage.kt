package com.flarego.ui.registrar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.model.RegistrarCandidate
import com.flarego.core.model.RegistrarIntent
import com.flarego.core.model.RegistrarSearch
import com.flarego.ui.EmptyState
import com.flarego.ui.Notice
import com.flarego.ui.Pill
import com.flarego.ui.SectionCard

@Composable
fun DomainRegisterPage(
    state: RegistrarSearch,
    onIntent: (RegistrarIntent) -> Unit,
    onOpenConsole: () -> Unit,
    demo: Boolean = false,
) {
    var queryText by rememberSaveable(state.keyword) { mutableStateOf(state.keyword) }

    Column(Modifier.fillMaxSize()) {
        if (demo) {
            Text(
                "示例 · 未调用云 API",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        // 顶部搜索区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("registrar-search-field"),
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text(
                        "输入想注册的域名，如 example.com",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val text = queryText.trim()
                        if (text.isNotBlank()) {
                            onIntent(RegistrarIntent.Query(text))
                        }
                    }
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val text = queryText.trim()
                            if (text.isNotBlank()) {
                                onIntent(RegistrarIntent.Query(text))
                            }
                        },
                        modifier = Modifier.testTag("registrar-search-submit"),
                    ) {
                        Icon(Icons.Default.Search, "搜索", modifier = Modifier.size(20.dp))
                    }
                },
            )
        }

        if (state.searching) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        state.error?.let { err ->
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Notice(err, onRetry = { onIntent(RegistrarIntent.Retry) })
            }
        }

        when {
            state.unsupported -> {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    EmptyState(
                        title = "域名搜索需要具备 Registrar 读取权限",
                        subtitle = "当前连接未启用域名搜索：API Token 需勾选 Registrar Domains 读取权限，或先在 Cloudflare 控制台搜索与注册",
                        action = "打开控制台",
                        onAction = onOpenConsole,
                    )
                }
            }
            !state.searching && state.keyword.isNotBlank() && state.results.isEmpty() && state.error == null -> {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    EmptyState(
                        title = "没有匹配结果",
                        subtitle = "换个关键词，或到官方控制台搜索",
                        modifier = Modifier.testTag("registrar-empty"),
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.results, key = { it.name }) { candidate ->
                        RegistrarResultCard(candidate, onOpenConsole)
                    }
                    if (state.results.isNotEmpty()) {
                        item {
                            Text(
                                "注册会产生真实费用且不可退款；App 内暂不提交注册。",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistrarResultCard(
    candidate: RegistrarCandidate,
    onOpenConsole: () -> Unit,
) {
    SectionCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registrar-result-${candidate.name}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                candidate.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when (candidate.registrable) {
                true -> Pill("可注册", warning = false)
                false -> Pill("已被注册", warning = true)
                null -> Pill(
                    "待确认",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val priceText = when {
            candidate.registrationCost != null || candidate.renewalCost != null -> {
                val first = candidate.registrationCost ?: "未知"
                val renew = candidate.renewalCost ?: "未知"
                val currency = candidate.currency ?: "USD"
                "首年 $first · 续费 $renew · $currency"
            }
            else -> "价格未知"
        }
        Text(
            priceText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val noteText = candidate.reason ?: "搜索结果为缓存数据，注册前需在官方控制台确认"
        Text(
            noteText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenConsole,
                modifier = Modifier.testTag("registrar-open-console"),
            ) {
                Text("去注册")
            }
        }
    }
}
