package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Content can grow with font scale; only the touch target has a minimum height. */
@Composable
fun CompactListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = if (subtitle == null) 48.dp else 62.dp)
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value != null) Text(value, Modifier.widthIn(max = 100.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            labels.forEach { (key, label) ->
                val active = key == selected
                Column(Modifier.selectable(active, role = Role.Tab, onClick = { onSelect(key) })
                    .testTag("$tagPrefix-$key").heightIn(min = 48.dp).widthIn(min = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.heightIn(min = 46.dp).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Box(Modifier.width(16.dp).height(2.dp).background(
                        if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
