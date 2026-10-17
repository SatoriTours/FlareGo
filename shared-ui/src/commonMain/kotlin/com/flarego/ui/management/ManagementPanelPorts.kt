package com.flarego.ui.management

import androidx.compose.runtime.Composable
import com.flarego.core.model.ZoneScope
import com.flarego.core.model.ZoneDetails
import com.flarego.ui.dns.*
import com.flarego.ui.zones.*
import kotlinx.coroutines.flow.StateFlow

/** Independent live context supplied by the application owner, never read from Panel state. */
data class ManagementPanelContext(val scope: ZoneScope, val currentEpoch: Long)

/** Opaque presentation identity, issued and registered by the application adapter. Not write authority. */
interface ManagementPanelLease { val context: ManagementPanelContext }

data class PanelPublication<S>(val context: ManagementPanelContext?, val lease: ManagementPanelLease?, val state: S)

enum class ManagementPanelModule(val title: String) {
    FILES("导入与导出"), SCAN("扫描与记录用量"), DNS_SETTINGS("DNS 配置"),
    DNSSEC("DNSSEC 与签名信息"), ZONE_SETTINGS("域名设置、SSL 与缓存清理")
}

enum class ZoneManagementTab { DETAILS, DNS, SETTINGS, SECURITY }
class ZoneManagementSlots(
    val settings: @Composable (ZoneDetails) -> Unit,
    val security: @Composable (ZoneDetails) -> Unit,
    val selectedTab: ZoneManagementTab = ZoneManagementTab.DETAILS,
    val onTabChanged: (ZoneManagementTab) -> Unit = {},
)

interface DnsFilesPanelPort {
    val state: StateFlow<PanelPublication<DnsFilesPanelState>>
    fun bind(actualContext: ManagementPanelContext): ManagementPanelLease?
    fun clear(lease: ManagementPanelLease)
    fun onIntent(lease: ManagementPanelLease, intent: DnsFilesPanelIntent)
}

interface DnsScanPanelPort {
    val state: StateFlow<PanelPublication<DnsScanPanelState>>
    fun bind(actualContext: ManagementPanelContext): ManagementPanelLease?
    fun clear(lease: ManagementPanelLease)
    fun onIntent(lease: ManagementPanelLease, intent: DnsScanPanelIntent)
}

interface DnsZoneSettingsPanelPort {
    val state: StateFlow<PanelPublication<DnsZoneSettingsPanelState>>
    fun bind(actualContext: ManagementPanelContext): ManagementPanelLease?
    fun clear(lease: ManagementPanelLease)
    fun onIntent(lease: ManagementPanelLease, intent: DnsZoneSettingsPanelIntent)
}

interface DnssecPanelPort {
    val state: StateFlow<PanelPublication<DnssecPanelState>>
    fun bind(actualContext: ManagementPanelContext): ManagementPanelLease?
    fun clear(lease: ManagementPanelLease)
    fun onIntent(lease: ManagementPanelLease, intent: DnssecPanelIntent)
}

interface ZoneSettingsPanelPort {
    val state: StateFlow<PanelPublication<ZoneSettingsPanelState>>
    fun bind(actualContext: ManagementPanelContext): ManagementPanelLease?
    fun clear(lease: ManagementPanelLease)
    fun onIntent(lease: ManagementPanelLease, intent: ZoneSettingsPanelIntent)
}

data class ManagementPanelPorts(
    val files: DnsFilesPanelPort? = null,
    val scan: DnsScanPanelPort? = null,
    val dnsSettings: DnsZoneSettingsPanelPort? = null,
    val dnssec: DnssecPanelPort? = null,
    val zoneSettings: ZoneSettingsPanelPort? = null,
    /** Actual user route departure only. Dispose/recreation must never call this. */
    val onRouteExit: (ManagementPanelLease) -> Unit = {},
)
