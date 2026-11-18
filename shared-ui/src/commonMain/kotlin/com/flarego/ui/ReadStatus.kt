package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal expect fun observationTime(epochMs: Long): String
internal fun readStatusText(fromCache: Boolean, loading: Boolean, time: String) =
    "${if(loading) "后台更新中" else if(fromCache) "本地缓存" else "已同步"} · 更新于 $time"

@Composable
fun ReadStatus(observedAtMs: Long?, fromCache: Boolean, loading: Boolean, modifier: Modifier = Modifier) {
    if (observedAtMs == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (loading) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    CircleShape,
                )
        )
        Text(
            readStatusText(fromCache, loading, observationTime(observedAtMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
