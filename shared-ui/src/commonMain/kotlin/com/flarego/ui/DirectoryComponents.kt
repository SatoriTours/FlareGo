package com.flarego.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.flarego.core.application.inventory.ProductLoadState

@Composable
internal fun directoryBackground(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFF6F6F1)
    else MaterialTheme.colorScheme.background

internal fun zoneStatusLabel(status: String): String = when (status) {
    "active" -> "已激活"
    "pending" -> "待激活"
    else -> status
}

@Composable
internal fun DirectoryReadFeedback(
    state: ProductLoadState?,
    refresh: () -> Unit,
    more: () -> Unit,
    showReadState: Boolean = true,
    showPagination: Boolean = true,
) {
    if (showReadState) {
        if (state?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        state?.error?.let { Notice(it.publicMessage, refresh) }
    }
    if (showPagination && state?.observedAtMs != null && !state.allPagesLoaded) {
        DirectoryHeading("当前只显示已加载的结果")
        if (state.next != null) TextButton(onClick = more, enabled = !state.loading) { Text("加载更多") }
    }
}

@Composable
internal fun DirectoryHeading(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun DirectoryGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

@Composable
internal fun DirectoryItem(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = if (index == 0) 14.dp else 0.dp
    val bottom = if (index == count - 1) 14.dp else 0.dp
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(top, top, bottom, bottom)) {
        Column {
            content()
            if (index < count - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun DirectoryPicker(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
    optionPrefix: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DirectoryHeading(label)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, value) ->
                    DropdownMenuItem(
                        text = { Text(value) },
                        onClick = { expanded = false; onSelect(key) },
                        modifier = Modifier.testTag("$optionPrefix-$key"),
                    )
                }
            }
        }
    }
}
