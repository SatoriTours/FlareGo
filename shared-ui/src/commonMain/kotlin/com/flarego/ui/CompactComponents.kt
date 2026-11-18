package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Content can grow with font scale; touch target has defined height baseline. */
@Composable
fun CompactListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = if (subtitle == null) 56.dp else 68.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Text(
                    value,
                    Modifier.widthIn(max = 120.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                trailing()
            }
            if (onClick != null) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
fun CompactTabs(
    labels: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "tab",
) {
    val textStyle = when {
        labels.size >= 5 -> MaterialTheme.typography.labelSmall
        labels.size == 4 -> MaterialTheme.typography.labelMedium
        else -> MaterialTheme.typography.labelLarge
    }

    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 40.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
        .padding(3.dp)
        .selectableGroup()

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEach { (key, label) ->
            val active = key == selected
            val itemModifier = Modifier
                .weight(1f)
                .heightIn(min = 34.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(9.dp),
                )
                .selectable(
                    selected = active,
                    role = Role.Tab,
                    onClick = { onSelect(key) },
                )
                .testTag("$tagPrefix-$key")

            Box(
                modifier = itemModifier.padding(horizontal = 2.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = textStyle.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
