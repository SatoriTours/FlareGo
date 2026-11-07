package com.flarego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal expect fun observationTime(epochMs: Long): String
internal fun readStatusText(fromCache: Boolean, loading: Boolean, time: String) =
    "${if(loading) "后台更新中" else if(fromCache) "本地缓存" else "已同步"} · 更新于 $time"

@Composable
fun ReadStatus(observedAtMs: Long?, fromCache: Boolean, loading: Boolean) {
    if(observedAtMs==null)return
    Text(readStatusText(fromCache,loading,observationTime(observedAtMs)),
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
