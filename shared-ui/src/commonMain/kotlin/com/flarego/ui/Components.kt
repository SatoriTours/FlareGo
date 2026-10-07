package com.flarego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.core.model.*

@Composable
fun SectionCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun Pill(text: String, warning: Boolean = false) {
    Text(
        text,
        Modifier.background(
                if (warning) Orange.copy(alpha = .12f)
                else MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = if (warning) Orange else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
    )
}

fun ResourceKind.icon(): ImageVector =
    when (this) {
        ResourceKind.WORKER -> Icons.Default.Bolt
        ResourceKind.R2 -> Icons.Default.Inventory2
        ResourceKind.D1 -> Icons.Default.Storage
        ResourceKind.KV -> Icons.Default.Layers
    }

@Composable
fun ResourceRow(resource: CloudResource, demo: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.heightIn(min = 78.dp).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                resource.kind.icon(),
                null,
                Modifier.size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(11.dp),
                    )
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    resource.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Muted(resource.detail, Modifier.fillMaxWidth())
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(resource.usage ?: "—", style = MaterialTheme.typography.titleSmall)
                Muted(if (demo) "示例用量" else "已读取")
            }
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
    SectionCard(Modifier.fillMaxWidth()) {
        Icon(
            Icons.Default.CloudQueue,
            null,
            Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Muted(subtitle)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Notice(text: String, onRetry: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (onRetry != null)
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("重试") }
        }
    }
}

@Composable
fun DemoChart(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(110.dp)) {
        repeat(4) {
            drawLine(
                grid,
                Offset(0f, size.height * it / 3),
                Offset(size.width, size.height * it / 3),
                strokeWidth = 1f,
            )
        }
        val values =
            listOf(.72f, .61f, .65f, .55f, .57f, .41f, .5f, .48f, .3f, .36f, .22f, .28f, .12f, .2f)
        val path =
            Path().apply {
                values.forEachIndexed { i, v ->
                    val x = size.width * i / (values.size - 1)
                    if (i == 0) moveTo(x, size.height * v) else lineTo(x, size.height * v)
                }
            }
        drawPath(
            path,
            line,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
        )
    }
}
