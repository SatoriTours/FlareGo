package com.flarego.ui.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flarego.ui.dns.*
import com.flarego.ui.zones.ZoneSettingsPanel
import kotlinx.coroutines.flow.StateFlow

/** Independent context; adapter owns leases and all runtime/platform behavior. */
@Composable
fun ManagementPanelHost(
    actualContext: ManagementPanelContext,
    module: ManagementPanelModule,
    ports: ManagementPanelPorts,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBound: (ManagementPanelLease) -> Unit = {},
) {
    when(module) {
        ManagementPanelModule.FILES -> ports.files?.let { p ->
            BoundPanel(actualContext,module,p,p.state,p::bind,p::clear,ports.onRouteExit,onBack,onBound,modifier,
                { it.scope==actualContext.scope && it.epoch==actualContext.currentEpoch }) { s,l,admit ->
                DnsFilesPanel(actualContext.scope,actualContext.currentEpoch,s,{ if(admit())p.onIntent(l,it) })
            }
        } ?: Unavailable(module,onBack,modifier)
        ManagementPanelModule.SCAN -> ports.scan?.let { p ->
            BoundPanel(actualContext,module,p,p.state,p::bind,p::clear,ports.onRouteExit,onBack,onBound,modifier,
                { it.scope==actualContext.scope && it.epoch==actualContext.currentEpoch }) { s,l,admit ->
                DnsScanPanel(actualContext.scope,actualContext.currentEpoch,s,{ if(admit())p.onIntent(l,it) })
            }
        } ?: Unavailable(module,onBack,modifier)
        ManagementPanelModule.DNS_SETTINGS -> ports.dnsSettings?.let { p ->
            BoundPanel(actualContext,module,p,p.state,p::bind,p::clear,ports.onRouteExit,onBack,onBound,modifier,
                { it.scope==actualContext.scope && it.epoch==actualContext.currentEpoch }) { s,l,admit ->
                DnsZoneSettingsPanel(actualContext.scope,actualContext.currentEpoch,s,{ if(admit())p.onIntent(l,it) })
            }
        } ?: Unavailable(module,onBack,modifier)
        ManagementPanelModule.DNSSEC -> ports.dnssec?.let { p ->
            BoundPanel(actualContext,module,p,p.state,p::bind,p::clear,ports.onRouteExit,onBack,onBound,modifier,
                { it.scope==actualContext.scope }) { s,l,admit ->
                DnssecPanel(actualContext.scope,s,{ if(admit())p.onIntent(l,it) })
            }
        } ?: Unavailable(module,onBack,modifier)
        ManagementPanelModule.ZONE_SETTINGS -> ports.zoneSettings?.let { p ->
            BoundPanel(actualContext,module,p,p.state,p::bind,p::clear,ports.onRouteExit,onBack,onBound,modifier,
                { it.scope==actualContext.scope }) { s,l,admit ->
                ZoneSettingsPanel(actualContext.scope,s,{ if(admit())p.onIntent(l,it) })
            }
        } ?: Unavailable(module,onBack,modifier)
    }
}

/** Only presentation lifetime is shared here. No authority, payload or app execution lives in this helper. */
@Composable
private fun <S> BoundPanel(
    context:ManagementPanelContext,module:ManagementPanelModule,port:Any,
    state:StateFlow<PanelPublication<S>>,bind:(ManagementPanelContext)->ManagementPanelLease?,
    clear:(ManagementPanelLease)->Unit,onRouteExit:(ManagementPanelLease)->Unit,onBack:()->Unit,onBound:(ManagementPanelLease)->Unit,
    modifier:Modifier,validState:(S)->Boolean,
    content:@Composable (S,ManagementPanelLease,()->Boolean)->Unit,
) {
    var lease by remember(port,context,module) { mutableStateOf<ManagementPanelLease?>(null) }
    var mounted by remember(port,context,module) { mutableStateOf(false) }
    val publication by state.collectAsState()
    val latestContext by rememberUpdatedState(context)
    val latestPublication by rememberUpdatedState(publication)
    val latestValidState by rememberUpdatedState(validState)
    val latestBound by rememberUpdatedState(onBound)
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    DisposableEffect(port,context,module) {
        val ownedLease=bind(context)
        lease=ownedLease;mounted=true
        if(ownedLease!=null) latestBound(ownedLease)
        onDispose {
            mounted=false
            // Never read a latest lease or publication here: a newer host may already be bound.
            if(ownedLease!=null) clear(ownedLease)
        }
    }
    fun matches(l:ManagementPanelLease?):Boolean = mounted && l!=null && l.context==latestContext &&
        latestPublication.context==latestContext && latestPublication.lease===l && latestValidState(latestPublication.state)
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("management-host")) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick={ keyboard?.hide();focus.clearFocus();lease?.let { onRouteExit(it);clear(it) };onBack() },Modifier.testTag("management-back")) { Text("返回域名") }
            Text(module.title,Modifier.weight(1f).padding(top=12.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().weight(1f).testTag("management-body")) {
            val ownedLease=lease
            if(matches(ownedLease)) key(port,context,module,ownedLease) {
                content(publication.state,ownedLease!!,{ matches(ownedLease) })
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("账号、域名或页面已变化，正在等待当前对象的读取。",Modifier.testTag("management-waiting"))
            }
        }
    }
}

@Composable
private fun Unavailable(module:ManagementPanelModule,onBack:()->Unit,modifier:Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("management-host")) {
        Text("${module.title}尚待接入当前账号。",Modifier.testTag("management-unavailable"))
        TextButton(onClick=onBack,modifier=Modifier.testTag("management-back")) { Text("返回域名") }
    }
}
