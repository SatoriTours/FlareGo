package com.flarego.ui.r2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.ui.SectionCard

@Composable
fun R2CorsConfigurationPanel(
    state: R2ConfigurationUiState,
    onIntent: (R2ConfigurationIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.editable && !state.writing && !state.loading
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "CORS 跨域资源共享配置",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "CORS 是浏览器跨域规则，不是访问授权或将对象自动公开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rc-cors-notice"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onIntent(R2ConfigurationIntent.AddCorsRule) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("rc-cors-add-rule"),
                ) {
                    Text("添加 CORS 规则")
                }
                OutlinedButton(
                    onClick = { onIntent(R2ConfigurationIntent.RequestDeleteConfiguration) },
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).testTag("rc-cors-request-delete"),
                ) {
                    Text("删除整个配置 (DELETE)")
                }
            }
        }

        if (state.cors.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth().testTag("rc-cors-empty")) {
                Text(
                    text = "暂无 CORS 规则 (PUT 保存将清空所有规则)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.cors.forEachIndexed { index, rule ->
                SectionCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "CORS 规则 #${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            onClick = { onIntent(R2ConfigurationIntent.RemoveCorsRule(index)) },
                            enabled = enabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("rc-cors-remove-$index"),
                        ) {
                            Text("删除规则")
                        }
                    }

                    OutlinedTextField(
                        value = rule.id,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsIdChanged(index, it)) },
                        label = { Text("规则 ID（可选）") },
                        placeholder = { Text("例如 allow-my-app") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-id-$index"),
                    )

                    OutlinedTextField(
                        value = rule.methods,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsMethodsChanged(index, it)) },
                        label = { Text("允许的 HTTP 方法 (Allowed Methods)") },
                        placeholder = { Text("GET, PUT, POST, DELETE, HEAD") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-methods-$index"),
                    )

                    OutlinedTextField(
                        value = rule.origins,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsOriginsChanged(index, it)) },
                        label = { Text("允许的来源 (Allowed Origins)") },
                        placeholder = { Text("例如 https://example.com, https://app.example.com") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-origins-$index"),
                    )

                    OutlinedTextField(
                        value = rule.allowedHeaders,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsAllowedHeadersChanged(index, it)) },
                        label = { Text("允许的请求头 (Allowed Headers)") },
                        placeholder = { Text("例如 Content-Type, Authorization") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-allowed-headers-$index"),
                    )

                    OutlinedTextField(
                        value = rule.exposeHeaders,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsExposeHeadersChanged(index, it)) },
                        label = { Text("暴露的响应头 (Expose Headers)") },
                        placeholder = { Text("例如 ETag, Content-Length") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-expose-headers-$index"),
                    )

                    OutlinedTextField(
                        value = rule.maxAgeSeconds,
                        onValueChange = { onIntent(R2ConfigurationIntent.CorsMaxAgeChanged(index, it)) },
                        label = { Text("缓存时间（秒，Max Age Seconds，可选）") },
                        placeholder = { Text("例如 3600") },
                        enabled = enabled,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rc-cors-max-age-$index"),
                    )
                }
            }
        }
    }
}
