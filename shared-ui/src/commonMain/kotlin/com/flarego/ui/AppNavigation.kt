package com.flarego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.flarego.core.model.Page

@Composable
internal fun AppNavigation(selected: Page, navigate: (Page) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp)
            .selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple(Page.OVERVIEW, Icons.Outlined.GridView, "overview"),
                Triple(Page.DOMAINS, Icons.Outlined.Language, "domains"),
                Triple(Page.RESOURCES, Icons.Outlined.Inventory2, "resources"),
                Triple(Page.BILLING, Icons.Outlined.CreditCard, "billing"),
                Triple(Page.ACCOUNTS, Icons.Outlined.CloudQueue, "accounts"),
            ).forEach { (page, icon, tag) ->
                val active = page == selected
                val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { navigate(page) })
                    .heightIn(min = 58.dp).padding(vertical = 6.dp).testTag("nav-$tag"),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.width(16.dp).height(2.dp).background(if (active) color else Color.Transparent))
                    Spacer(Modifier.height(6.dp))
                    Icon(icon, null, Modifier.size(21.dp), tint = color)
                    Spacer(Modifier.height(5.dp))
                    Text(when (page) {
                        Page.BILLING -> "账单"
                        Page.RESOURCES -> "资源"
                        Page.ACCOUNTS -> "账号"
                        else -> page.title
                    }, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}
