package com.flarego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
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
fun Pill(
    text: String,
    warning: Boolean = false,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    contentColor: androidx.compose.ui.graphics.Color? = null,
) {
    val bg = containerColor ?: if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = contentColor ?: if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        text,
        Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
    )
}

fun ResourceKind.icon(): ImageVector =
    when (this) {
        ResourceKind.WORKER -> Icons.Outlined.Bolt
        ResourceKind.R2 -> Icons.Outlined.Inventory2
        ResourceKind.D1 -> Icons.Outlined.Storage
        ResourceKind.KV -> Icons.Outlined.Layers
    }

@Composable
fun ResourceRow(
    resource: CloudResource,
    demo: Boolean,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    // At large font scales the trailing usage column would squeeze the name to a few
    // characters, so the usage moves under the title instead of competing for width.
    val stackedValue = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)) {
        Row(
            // Minimum height, not a fixed one: at 2x font scale the two text lines must grow
            // instead of being clipped. The 1x visual height stays the same as before.
            Modifier.fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        resource.kind.icon(),
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    resource.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    resource.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stackedValue) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(resource.usage ?: "—", style = MaterialTheme.typography.labelLarge)
                        Muted(if (demo) "示例用量" else "已读取")
                    }
                }
            }
            if (!stackedValue) Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(resource.usage ?: "—", style = MaterialTheme.typography.labelLarge)
                Muted(if (demo) "示例用量" else "已读取")
            }
            Icon(
                Icons.Outlined.ChevronRight,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    SectionCard(modifier) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.padding(start = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
