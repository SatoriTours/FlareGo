package com.flarego

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.flarego.core.application.AppController
import com.flarego.core.application.dns.*
import com.flarego.core.application.zones.*
import com.flarego.core.application.management.CloudflareManagement
import com.flarego.core.demo.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.ui.*
import com.flarego.ui.dns.*
import com.flarego.ui.zones.*
import com.flarego.ui.management.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual App/host/leaf Compose. Public-port owner and platform/job counters are synthetic, not B2 acceptance. */
class ExtendedManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun click(tag:String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    private fun visible(tag:String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun install(h:UiOwner=UiOwner(), module:ManagementPanelModule=ManagementPanelModule.FILES, app:Boolean=false, overlap:Boolean=false, font:Float=1f):UiOwner {
        val mountId=++h.mountId
        compose.runOnUiThread { compose.activity.setContent { key(mountId) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    if(app) {
                        val coroutineScope=rememberCoroutineScope()
                        val controller=remember { AppController(coroutineScope,SessionFactory { DemoSession(it) },MemoryStore(),demoConnections) }
                        h.controller=controller
                        val appState by controller.state.collectAsState()
                        val actualContext=remember(appState,h.actual.value) { h.liveContext }
                        FlareGoApp(controller,{_,_,_->},{},{},management=h.management,panelPorts=h.ports,panelContext=actualContext)
                    } else if(overlap) {
                        var old by remember { mutableStateOf(true) }; var next by remember { mutableStateOf(false) }
                        Column {
                            Row {
                                Button({next=true},Modifier.testTag("mount-next")) { Text("新界面") }
                                Button({old=false},Modifier.testTag("dispose-old")) { Text("关闭旧界面") }
                            }
                            // Only latest host needs space; old remains actually mounted until explicit disposal.
                            if(old) key("old") { Box(Modifier.height(if(next) androidx.compose.ui.unit.Dp(1f) else androidx.compose.ui.unit.Dp(480f)).testTag("old")) {
                                ManagementPanelHost(h.actual.value,module,h.ports,{},onBound={h.bound+=it})
                            } }
                            if(next) key("new") { Box(Modifier.weight(1f).testTag("new")) {
                                ManagementPanelHost(h.actual.value,module,h.ports,{},onBound={h.bound+=it})
                            } }
                        }
                    } else ManagementPanelHost(h.actual.value,module,h.ports,{h.backs++},onBound={h.bound+=it})
                } }
            }
        } } };compose.waitForIdle();return h
    }
    private fun openZone(h:UiOwner) {
        compose.onNodeWithTag("nav-domains").performClick()
        click("zone-row-${h.scope.zoneId}")
    }
    private fun openDns(h:UiOwner) { openZone(h);compose.onNodeWithTag("zone-tab-dns").performClick() }
    private fun settingsPreview() {
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().performTextReplacement("601")
        click("dz-preview");visible("dz-preview-details")
    }

    @Test fun missingPortShowsSpecificPendingModuleAndReachableBack() {
        val h=UiOwner();h.ports=ManagementPanelPorts();install(h)
        visible("management-unavailable").assertTextContains("导入与导出",substring=true)
        compose.onNodeWithTag("management-back").assertIsDisplayed().performClick();assertEquals(1,h.backs)
    }
    @Test fun allFivePortsMountRealLeafAndForwardOwnedRefresh() {
        val h=UiOwner();val module=mutableStateOf(ManagementPanelModule.FILES)
        compose.runOnUiThread { compose.activity.setContent { FlareGoTheme {
            ManagementPanelHost(h.actual.value,module.value,h.ports,{})
        } } }
        val cases=listOf(ManagementPanelModule.FILES to "df-refresh",ManagementPanelModule.SCAN to "ds-refresh-review",
            ManagementPanelModule.DNS_SETTINGS to "dz-refresh",ManagementPanelModule.DNSSEC to "ds-refresh")
        for((m,tag) in cases) {
            compose.runOnIdle { module.value=m };click(tag)
            compose.runOnIdle { assertSame(h.active,h.calls.last().first) }
        }
        compose.runOnIdle { module.value=ManagementPanelModule.ZONE_SETTINGS }
        compose.onNodeWithText("只读刷新当前目标").performScrollTo().performClick()
        compose.runOnIdle { assertSame(h.active,h.calls.last().first);assertEquals(5,h.issued.size) }
    }
    @Test fun sameContextOldHostDisposeCannotClearNewBinding() {
        val h=install(overlap=true,module=ManagementPanelModule.DNS_SETTINGS)
        visible("dz-header");val old=h.active!!
        val oldClick=compose.onNodeWithTag("dz-refresh").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("mount-next").performClick();compose.waitForIdle();val current=h.active!!
        val attempts=h.attempts.size
        compose.runOnIdle { oldClick() }
        assertEquals("Old real button callback must stop at Host, before synthetic owner admission",attempts,h.attempts.size)
        assertNotSame(old,current);assertEquals(old.context,current.context)
        settingsPreview();compose.onNodeWithTag("dispose-old").performClick()
        compose.runOnIdle { assertSame(old,h.clears.last());assertSame(current,h.active);assertTrue("Compose dispose is not a user route exit",h.exits.isEmpty()) }
        visible("dz-preview-details");compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsEnabled()
        assertTrue(h.confirms().isEmpty())
    }
    @Test fun sameContextOldPublicationCannotExposeValuesErrorOrConfirm() {
        val h=install(module=ManagementPanelModule.DNS_SETTINGS);visible("dz-header")
        val old=h.settingsState.value
        compose.runOnIdle { h.settingsState.value=old.copy(lease=UiLease(h.actual.value)) }
        visible("management-waiting");compose.onNodeWithTag("dz-header").assertDoesNotExist()
        compose.runOnIdle { h.settingsState.value=old.copy(state=old.state.copy(errors=listOf(DnsTask7Error(h.scope,7,DnsTask7Section.SETTINGS,"OLD_ERROR"))),lease=UiLease(h.actual.value)) }
        compose.onNodeWithText("OLD_ERROR").assertDoesNotExist();assertTrue(h.confirms().isEmpty())
    }
    @Test fun independentOuterEpochRejectsEntireOldPublication() {
        val h=install(module=ManagementPanelModule.DNS_SETTINGS);visible("dz-header");val old=h.settingsState.value
        compose.runOnIdle { h.actual.value=h.actual.value.copy(currentEpoch=8) }
        compose.runOnIdle { h.settingsState.value=old }
        visible("management-waiting");compose.onNodeWithTag("dz-header").assertDoesNotExist()
    }
    @Test fun innerScopeAndInnerEpochCannotSelfValidate() {
        val h=install(module=ManagementPanelModule.FILES);visible("df-header");val p=h.filesState.value
        compose.runOnIdle { h.filesState.value=p.copy(state=p.state.copy(epoch=99)) }
        visible("management-waiting");compose.onNodeWithTag("df-header").assertDoesNotExist()
        compose.runOnIdle { h.filesState.value=p.copy(state=p.state.copy(scope=ZoneScope(h.scope.account,"other"))) }
        visible("management-waiting");compose.onNodeWithTag("df-header").assertDoesNotExist()
    }
    @Test fun recreatedHostRejectsTaskEightAndNineOldPublication() {
        val h=install(module=ManagementPanelModule.DNSSEC);visible("ds-status");val old=h.dnssecState.value
        install(h,ManagementPanelModule.DNSSEC)
        compose.runOnIdle { h.dnssecState.value=old }
        visible("management-waiting");compose.onNodeWithTag("ds-status").assertDoesNotExist()
        install(h,ManagementPanelModule.ZONE_SETTINGS);visible("zs-current-value");val settingOld=h.zoneSettingsState.value
        install(h,ManagementPanelModule.ZONE_SETTINGS)
        compose.runOnIdle { h.zoneSettingsState.value=settingOld }
        visible("management-waiting");compose.onNodeWithTag("zs-current-value").assertDoesNotExist()
    }
    @Test fun newLeaseDoesNotReviveUnconfirmedPlanAndRiskAcknowledgement() {
        val h=install(module=ManagementPanelModule.DNSSEC);click("ds-status-disabled");click("ds-preview")
        click("ds-risk-DISABLE");compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsEnabled()
        val old=h.dnssecState.value
        install(h,ManagementPanelModule.DNSSEC)
        val fresh=h.dnssecState.value
        compose.runOnIdle { h.dnssecState.value=old }
        visible("management-waiting");compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.runOnIdle { h.dnssecState.value=fresh }
        click("ds-status-disabled");click("ds-preview")
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled();assertTrue(h.confirms().isEmpty())
    }
    @Test fun explicitPreviewAndIndependentConfirmationKeepActualDomainAndRiskOnly() {
        val h=install(module=ManagementPanelModule.DNSSEC);click("ds-status-disabled");click("ds-preview")
        assertTrue(h.confirms().isEmpty());compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
        click("ds-risk-DISABLE");click("ds-confirm")
        val sent=h.confirms().single() as DnssecPanelIntent.Confirm
        assertEquals(setOf(DnssecRisk.DISABLE),sent.acknowledgedRisks)
        assertEquals("ui-plan",sent.planId);assertEquals(h.dnssecState.value.state.revision,sent.revision)
    }
    @Test fun backReleasesItsCapturedLeaseWithoutConfirmOrFileCancel() {
        val h=install();visible("df-header");val lease=h.active
        compose.onNodeWithTag("management-back").performClick()
        compose.runOnIdle { assertSame(lease,h.clears.last());assertSame(lease,h.exits.single());assertTrue(h.events.indexOf("exit-1")<h.events.indexOf("clear-1"));assertEquals(1,h.backs);assertTrue(h.calls.isEmpty());assertTrue(h.confirms().isEmpty()) }
    }
    @Test fun nativeAppEntriesUseActualOwnedZoneAndPreserveFiveTabsAndReturnTab() {
        val h=install(app=true);openDns(h)
        for((module,header) in listOf(ManagementPanelModule.FILES to "df-header",ManagementPanelModule.SCAN to "ds-header",ManagementPanelModule.DNS_SETTINGS to "dz-header")) {
            click("management-open-${module.name}");visible(header)
            listOf("overview","domains","resources","billing","accounts").forEach { compose.onNodeWithTag("nav-$it").assertExists() }
            compose.onNodeWithTag("management-back").performClick()
            compose.onNodeWithTag("zone-tab-dns").assertIsSelected()
        }
        compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        compose.onNodeWithTag("management-back").performClick();compose.onNodeWithTag("zone-tab-security").assertIsSelected()
        compose.onNodeWithTag("zone-tab-settings").performClick();click("management-open-ZONE_SETTINGS");visible("zs-current-value")
    }
    @Test fun appWithoutActualContextCannotNavigateFromPanelState() {
        val h=UiOwner();h.actual.value=h.actual.value.copy(scope=ZoneScope(h.scope.account,"different"));install(h,app=true);openDns(h)
        compose.onNodeWithTag("management-open-FILES").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("management-host").assertDoesNotExist()
    }
    @Test fun appCrossModuleAndBottomNavigationReleaseBeforeNextBinding() {
        val h=install(app=true);openZone(h);compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        val old=h.active!!;compose.onNodeWithTag("management-back").performClick()
        compose.onNodeWithTag("zone-tab-settings").performClick();click("management-open-ZONE_SETTINGS");visible("zs-current-value")
        compose.runOnIdle { assertTrue("A real exit callback must exist",h.events.contains("exit-1"));assertTrue(h.events.indexOf("exit-1") < h.events.indexOf("clear-1"));assertTrue(h.events.indexOf("clear-1") < h.events.indexOf("bind-2"));h.dnssec.clear(old) }
        visible("zs-current-value");compose.onNodeWithTag("nav-accounts").performClick()
        compose.onNodeWithTag("management-host").assertDoesNotExist();assertNull(h.active)
    }
    @Test fun realActivityRecreateIssuesNewLeaseButKeepsVmPickerAndClaimedJobMarkers() {
        lateinit var vm:PresentationTestModel
        compose.runOnUiThread { vm=ViewModelProvider(compose.activity)[PresentationTestModel::class.java] }
        val h=vm.owner;h.pickerPending=true;h.jobClaimed=true
        install(h);visible("df-header");val old=h.active
        compose.activityRule.scenario.recreate()
        lateinit var restored:PresentationTestModel
        compose.runOnUiThread { restored=ViewModelProvider(compose.activity)[PresentationTestModel::class.java] }
        assertSame(vm,restored);install(restored.owner);visible("df-header")
        assertNotSame(old,h.active);assertTrue(h.pickerPending);assertTrue(h.jobClaimed)
        assertEquals(0,h.jobCancel);assertEquals(0,h.grantRelease);assertTrue(h.confirms().isEmpty())
    }
    @Test fun ordinaryRecompositionAndRealImeDoNotRebindAndBodyRemainsScrollable() {
        val h=install(module=ManagementPanelModule.DNS_SETTINGS,font=2f);visible("dz-header")
        val lease=h.active;val count=h.issued.size
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().performClick().performTextReplacement("601")
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("management-body").fetchSemanticsNode().boundsInRoot.height>0)
        assertSame(lease,h.active);assertEquals(count,h.issued.size)
        click("dz-preview");visible("dz-preview-details");assertTrue(h.confirms().isEmpty());click("dz-back")
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().assertTextContains("601")
    }
    private fun attachNativeRetainedApp(activity:MainActivity,owner:UiOwner) {
        // One composition call site across Activity instances, matching the production onCreate root.
        activity.setContent { NativeRetainedFixtureApp(owner) }
    }

    @Test fun nativeAppRecreatePreservesOwnedZoneModuleAndIssuesNewLeaseWithoutOldRisk() {
        lateinit var model:PresentationTestModel
        compose.runOnUiThread { model=ViewModelProvider(compose.activity)[PresentationTestModel::class.java] }
        val h=model.owner
        val application=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity:Activity,savedInstanceState:Bundle?) {
                if(activity is MainActivity) {
                    val restored=ViewModelProvider(activity)[PresentationTestModel::class.java]
                    attachNativeRetainedApp(activity,restored.owner)
                }
            }
            override fun onActivityCreated(a:Activity,b:Bundle?)=Unit
            override fun onActivityStarted(a:Activity)=Unit
            override fun onActivityResumed(a:Activity)=Unit
            override fun onActivityPaused(a:Activity)=Unit
            override fun onActivityStopped(a:Activity)=Unit
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle)=Unit
            override fun onActivityDestroyed(a:Activity)=Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            compose.runOnUiThread { attachNativeRetainedApp(compose.activity,h) }
            openZone(h);compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC")
            click("ds-status-disabled");click("ds-preview");click("ds-risk-DISABLE")
            compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsEnabled()
            val old=h.active!!;val epoch=model.controller.operationEpoch
            assertEquals(Page.DOMAINS,model.controller.state.value.page);assertNull(model.controller.state.value.domain)
            assertEquals(h.scope,h.zones.state.value.selected?.ref)
            compose.activityRule.scenario.recreate();compose.waitForIdle()
            compose.runOnUiThread { assertSame(model,ViewModelProvider(compose.activity)[PresentationTestModel::class.java]) }
            assertEquals(epoch,model.controller.operationEpoch)
            assertTrue("Actual App configuration recreation must not notify user route exit",h.exits.isEmpty())
            assertEquals("Actual selected owned Zone must survive same-context App recreate",h.scope,h.zones.state.value.selected?.ref)
            visible("ds-status");assertNotSame(old,h.active);assertEquals(old.context,h.active!!.context)
            compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
            click("ds-status-disabled");click("ds-preview")
            compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
            compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled();assertTrue(h.confirms().isEmpty())
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    @Test fun actualAccountChangeAndReturnCannotReviveSavedModuleOrPlan() {
        val h=install(app=true);openZone(h)
        compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        val old=h.dnssecState.value
        compose.runOnIdle { h.controller!!.selectConnection(demoConnections[1]) }
        compose.onNodeWithTag("management-host").assertDoesNotExist()
        compose.runOnIdle { h.controller!!.selectConnection(demoConnections[0]);h.dnssecState.value=old }
        click("zone-row-${h.scope.zoneId}")
        compose.onNodeWithTag("management-host").assertDoesNotExist();assertTrue(h.confirms().isEmpty())
        compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        assertNotSame(old.lease,h.active);compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
    }

    @Test fun actualZoneAndPageEpochChangesCannotRestoreOldSavedModule() {
        val h=install(app=true);openZone(h)
        compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        val old=h.dnssecState.value
        val otherScope=ZoneScope(h.scope.account,"other-zone")
        val other=h.owner.copy(ref=otherScope,name="other.example.com",raw=JsonObject(h.owner.raw+mapOf("id" to JsonPrimitive(otherScope.zoneId),"name" to JsonPrimitive("other.example.com"))))
        compose.runOnIdle { h.actual.value=h.actual.value.copy(scope=otherScope);h.zonesState.value=h.zonesState.value.copy(selected=other) }
        compose.onNodeWithTag("management-host").assertDoesNotExist()
        compose.runOnIdle { h.actual.value=h.actual.value.copy(scope=h.scope);h.zonesState.value=h.zonesState.value.copy(selected=h.owner);h.dnssecState.value=old }
        compose.onNodeWithTag("management-host").assertDoesNotExist()
        compose.runOnIdle { h.controller!!.navigate(Page.RESOURCES) }
        compose.runOnIdle { h.controller!!.navigate(Page.DOMAINS) }
        click("zone-row-${h.scope.zoneId}")
        compose.onNodeWithTag("management-host").assertDoesNotExist();assertTrue(h.confirms().isEmpty())
        compose.onNodeWithTag("zone-tab-security").performClick();click("management-open-DNSSEC");visible("ds-status")
        assertNotSame(old.lease,h.active);compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"extended-ui-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun appFontTwoWithImeHasNonzeroIndependentPanelAndReachableConfirm() {
        val h=install(app=true,font=2f);openDns(h);click("management-open-DNS_SETTINGS")
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().performClick().performTextReplacement("601")
        assertTrue(compose.onNodeWithTag("management-body").fetchSemanticsNode().boundsInRoot.height>0)
        screenshot("native-font2-ime")
        click("dz-preview");visible("dz-preview-details");compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsDisplayed()
        screenshot("native-font2-confirm");click("dz-confirm")
        assertEquals(1,h.confirms().size)
    }
}

class PresentationTestModel:ViewModel() {
    val owner=UiOwner()
    val controller=AppController(viewModelScope,SessionFactory { DemoSession(it) },MemoryStore(),demoConnections).also { owner.controller=it }
}

/** Same root composition is restored before the Activity's first frame; the actual AppController is VM-held. */
@Composable
private fun NativeRetainedFixtureApp(h:UiOwner) {
    val controller=h.controller!!
    val appState by controller.state.collectAsState()
    val actual=remember(appState,h.actual.value) { h.liveContext }
    FlareGoApp(controller,{_,_,_->},{},{},management=h.management,panelPorts=h.ports,panelContext=actual)
}
private class UiLease(override val context:ManagementPanelContext):ManagementPanelLease

/** Deliberately synthetic UI owner. Its admission is not production adapter proof. */
class UiOwner {
    val scope=ZoneScope(AccountScope("demo-production","demo"),"zone-ui")
    val actual=mutableStateOf(ManagementPanelContext(scope,7))
    val owner=ZoneDetails(scope,"example.com","active",false,"full",emptyList(),buildJsonObject {
        put("id",scope.zoneId);put("name","example.com");put("type","full");putJsonObject("account"){put("id",scope.account.accountId)}
    })
    var active:ManagementPanelLease?=null
    val issued=mutableListOf<ManagementPanelLease>();val clears=mutableListOf<ManagementPanelLease>();val bound=mutableListOf<ManagementPanelLease>()
    val exits=mutableListOf<ManagementPanelLease>()
    val calls=mutableListOf<Pair<ManagementPanelLease,Any>>();val events=mutableListOf<String>();var backs=0
    var mountId=0
    val attempts=mutableListOf<Pair<ManagementPanelLease,Any>>()
    var pickerPending=false;var jobClaimed=false;var jobCancel=0;var grantRelease=0
    var controller:AppController?=null
    val liveContext:ManagementPanelContext get()=actual.value.copy(currentEpoch=controller?.operationEpoch?:actual.value.currentEpoch)
    private fun bind(context:ManagementPanelContext,initial:()->Unit):ManagementPanelLease {
        initial();val lease=UiLease(context);active=lease;issued+=lease;events+="bind-${issued.size}";return lease
    }
    private fun clear(lease:ManagementPanelLease) {
        clears+=lease;events+="clear-${issued.indexOfFirst { it===lease }+1}"
        if(active===lease) active=null
    }
    private fun accept(lease:ManagementPanelLease,intent:Any):Boolean {
        attempts+=lease to intent
        if(active!==lease || lease.context!=liveContext) return false
        calls+=lease to intent;return true
    }
    fun confirms()=calls.map { it.second }.filter { it is DnsFilesPanelIntent.Confirm || it is DnsScanPanelIntent.Confirm || it is DnsZoneSettingsPanelIntent.Confirm || it is DnssecPanelIntent.Confirm || it is ZoneSettingsPanelIntent.Confirm }
    val filesState=MutableStateFlow(PanelPublication< DnsFilesPanelState>(null,null,DnsFilesPanelState()))
    val files=object:DnsFilesPanelPort {
        override val state=filesState
        override fun bind(actualContext:ManagementPanelContext):ManagementPanelLease {
            val l=this@UiOwner.bind(actualContext){};state.value=PanelPublication(actualContext,l,DnsFilesPanelState(scope=scope,epoch=actualContext.currentEpoch,snapshot=DnsFileImportState(owner,null)));return l
        }
        override fun clear(lease:ManagementPanelLease)=this@UiOwner.clear(lease)
        override fun onIntent(lease:ManagementPanelLease,intent:DnsFilesPanelIntent) { accept(lease,intent) }
    }
    val scanState=MutableStateFlow(PanelPublication< DnsScanPanelState>(null,null,DnsScanPanelState()))
    val scan=object:DnsScanPanelPort {
        override val state=scanState
        override fun bind(actualContext:ManagementPanelContext):ManagementPanelLease {
            val l=this@UiOwner.bind(actualContext){};state.value=PanelPublication(actualContext,l,DnsScanPanelState(scope=scope,epoch=actualContext.currentEpoch,snapshot=DnsScanSnapshot(owner,emptyList(),null),reviewLoaded=true));return l
        }
        override fun clear(lease:ManagementPanelLease)=this@UiOwner.clear(lease)
        override fun onIntent(lease:ManagementPanelLease,intent:DnsScanPanelIntent) { accept(lease,intent) }
    }
    val settingsState=MutableStateFlow(PanelPublication< DnsZoneSettingsPanelState>(null,null,DnsZoneSettingsPanelState()))
    val settings=object:DnsZoneSettingsPanelPort {
        override val state=settingsState
        override fun bind(actualContext:ManagementPanelContext):ManagementPanelLease {
            val l=this@UiOwner.bind(actualContext){}
            val current=DnsZoneSettings(scope,Json.parseToJsonElement("""{"ns_ttl":300,"nameservers":{"type":"cloudflare.standard"},"zone_mode":"standard"}""").jsonObject)
            state.value=PanelPublication(actualContext,l,DnsZoneSettingsPanelState(scope=scope,epoch=actualContext.currentEpoch,
                snapshot=DnsZoneSettingsSnapshot(owner,current,true,DnsSettingsEligibility(fields=setOf(DnsZoneSettingKey.NS_TTL)))));return l
        }
        override fun clear(lease:ManagementPanelLease)=this@UiOwner.clear(lease)
        override fun onIntent(lease:ManagementPanelLease,intent:DnsZoneSettingsPanelIntent) {
            if(!accept(lease,intent))return
            val p=state.value;val s=p.state
            val next=when(intent) {
                is DnsZoneSettingsPanelIntent.DraftInvalidated -> s.copy(prepared=null,draft=null,revision=s.revision+1)
                is DnsZoneSettingsPanelIntent.DraftChanged -> s.copy(draft=intent.action)
                is DnsZoneSettingsPanelIntent.Preview -> {
                    val before=dnsZoneSettingsView(s.snapshot!!.settings);val delta=dnsZoneSettingsDelta(before,intent.action.changed)
                    s.copy(prepared=DnsSettingsPanelPreview("ui-plan",s.revision,s.epoch,intent.action,
                        DnsZoneSettingsPreview(scope,owner.name,delta,emptySet(),listOf("无CAS"),before,dnsZoneSettingsAfter(before,delta)),dnsZoneSettingsResourceRef(intent.action,before)))
                }
                is DnsZoneSettingsPanelIntent.BackToDraft,is DnsZoneSettingsPanelIntent.Cancel -> s.copy(prepared=null)
                else -> s
            };state.value=p.copy(state=next)
        }
    }
    val dnssecState=MutableStateFlow(PanelPublication< DnssecPanelState>(null,null,DnssecPanelState()))
    val dnssec=object:DnssecPanelPort {
        override val state=dnssecState
        override fun bind(actualContext:ManagementPanelContext):ManagementPanelLease {
            val l=this@UiOwner.bind(actualContext){}
            val d=DnssecDetails(scope,"active",null,null,null,null,false,false,false,Json.parseToJsonElement("""{"status":"active","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":false}""").jsonObject)
            state.value=PanelPublication(actualContext,l,DnssecPanelState(scope=scope,snapshotMutation=DnssecMutation.UPDATE,snapshot=DnssecSnapshot(owner,d,true)));return l
        }
        override fun clear(lease:ManagementPanelLease)=this@UiOwner.clear(lease)
        override fun onIntent(lease:ManagementPanelLease,intent:DnssecPanelIntent) {
            if(!accept(lease,intent))return
            val p=state.value;val s=p.state
            state.value=p.copy(state=when(intent) {
                is DnssecPanelIntent.DraftInvalidated -> s.copy(prepared=null,revision=s.revision+1)
                is DnssecPanelIntent.Preview -> {
                    val a=intent.action as DnssecAction.Update;val before=s.snapshot!!.details
                    s.copy(prepared=DnssecPanelPreview("ui-plan",s.revision,DnssecPreview(scope,owner.name,DnssecMutation.UPDATE,setOf(DnssecRisk.DISABLE),"外部DS未知","停止签名风险","无CAS"),a,ResourceRef(scope,"dnssec",scope.zoneId),before,before.copy(status=a.input.status?:before.status)))
                }
                is DnssecPanelIntent.BackToDraft,is DnssecPanelIntent.Cancel -> s.copy(prepared=null)
                else -> s
            })
        }
    }
    val zoneSettingsState=MutableStateFlow(PanelPublication< ZoneSettingsPanelState>(null,null,ZoneSettingsPanelState()))
    val zoneSettings=object:ZoneSettingsPanelPort {
        override val state=zoneSettingsState
        override fun bind(actualContext:ManagementPanelContext):ManagementPanelLease {
            val l=this@UiOwner.bind(actualContext){};val target=ZoneSettingsTarget.Setting(ZoneSettingKey.SSL)
            state.value=PanelPublication(actualContext,l,ZoneSettingsPanelState(scope=scope,snapshots=mapOf(target to ZoneSettingsSnapshot(owner,setting=ZoneSettingValue(scope,ZoneSettingKey.SSL,JsonPrimitive("strict"),true,JsonObject(emptyMap())),writeAllowed=null))));return l
        }
        override fun clear(lease:ManagementPanelLease)=this@UiOwner.clear(lease)
        override fun onIntent(lease:ManagementPanelLease,intent:ZoneSettingsPanelIntent) { accept(lease,intent) }
    }
    var ports=ManagementPanelPorts(files,scan,settings,dnssec,zoneSettings,onRouteExit={ lease ->
        exits+=lease;events+="exit-${issued.indexOfFirst { it===lease }+1}"
    })
    val zonesState=MutableStateFlow(ZonePageState(account=scope.account,zones=listOf(owner)))
    val zones=object:ZonePagePort {
        override val state=zonesState
        override fun onIntent(intent:ZoneIntent) {
            when(intent) {
                is ZoneIntent.Open -> state.value=state.value.copy(selected=owner.takeIf { it.ref==intent.scope })
                ZoneIntent.Clear -> state.value=state.value.copy(selected=null)
                is ZoneIntent.Load -> state.value=ZonePageState(account=intent.account,zones=listOf(owner).filter { it.ref.account==intent.account },selected=state.value.selected)
                else -> Unit
            }
        }
    }
    val dns=object:DnsPagePort {
        override val state=MutableStateFlow(DnsPageState())
        override fun onIntent(intent:DnsIntent) { if(intent is DnsIntent.Load) state.value=DnsPageState(scope=intent.scope,zoneName=owner.name) }
    }
    val management=CloudflareManagement(dns,zones)
}
