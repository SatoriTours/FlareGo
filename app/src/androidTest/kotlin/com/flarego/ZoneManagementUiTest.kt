package com.flarego

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.flarego.core.model.*
import com.flarego.core.application.zones.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.zones.*
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Leaf callbacks are observed boundaries, not claims of API end-to-end writes. */
class ZoneManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = AccountScope("connection-fixture", "account-fixture")
    private val zone = ZoneDetails(ZoneScope(account, "zone-fixture"), "fresh.example.com", "pending", false, "full",
        listOf("ada.ns.cloudflare.com", "ben.ns.cloudflare.com"), JsonObject(emptyMap()))
    private val prepared = mutableListOf<ZoneAction>()
    private var submitted = 0
    private var cancelled = 0
    private var latest = ZoneFormDraft()
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun edit(tag: String, value: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    private fun install(mode: ZoneFormMode, target: ZoneDetails? = zone, initial: ZoneFormDraft = ZoneFormDraft(), fontScale: Float = 1f) {
        latest = initial
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    var draft by remember { mutableStateOf(initial) }
                    var action by remember { mutableStateOf<ZoneAction?>(null) }
                    if (action == null) ZoneForm(mode, account, target, draft, { latest = it; draft = it }, {
                        prepared += it; action = it
                    }, { cancelled++ })
                    else ZoneChangeConfirmation(action!!,
                        ZoneChangePreview(action!!.scope, if (action is ZoneAction.Create) (action as ZoneAction.Create).input.name else target!!.name,
                            "测试领域影响：可能中断服务；不会注销注册域名", "服务端未提供 CAS；仍有竞争窗口"),
                        { submitted++ }, { action = null }, { cancelled++ })
                } }
            }
        }
    }

    @Test fun deleteRequiresExactFreshProviderFullNameBeforePreviewAndSeparateConfirm() {
        install(ZoneFormMode.DELETE)
        compose.onNodeWithTag("zone-form-target").assertTextContains("fresh.example.com", substring = true)
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        listOf("", "example.com", "FRESH.example.com", "fresh.example.com ", "stale.example.com").forEach {
            edit("zone-delete-name", it)
            compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { assertTrue(prepared.isEmpty()); assertEquals(0, submitted) }
        }
        edit("zone-delete-name", "fresh.example.com"); click("zone-form-preview")
        compose.runOnIdle {
            assertEquals(ZoneAction.Delete(zone.ref, zone.name, zone.name), prepared.single())
            assertEquals(0, submitted)
        }
        compose.onNodeWithTag("zone-confirm-target").performScrollTo().assertTextContains("fresh.example.com", substring = true)
        compose.onNodeWithTag("zone-confirm-impact").performScrollTo().assertTextContains("中断", substring = true)
        click("zone-confirm-submit")
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun deletePreviewBackRetainsTypedDraftAndCancelNeverConfirms() {
        install(ZoneFormMode.DELETE)
        edit("zone-delete-name", zone.name); click("zone-form-preview"); click("zone-confirm-back")
        compose.onNodeWithTag("zone-delete-name").performScrollTo().assertTextContains(zone.name)
        click("zone-form-preview"); click("zone-confirm-cancel")
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(1, cancelled) }
    }

    @Test fun refreshedDifferentProviderNameInvalidatesOldTypedName() {
        install(ZoneFormMode.DELETE, target = zone.copy(name = "changed.example.com"), initial = ZoneFormDraft(typedName = zone.name))
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(prepared.isEmpty()); assertEquals(0, submitted) }
    }

    @Test fun createIsAZoneAndOnlyPreparesBeforeIndependentConfirm() {
        install(ZoneFormMode.CREATE, target = null)
        compose.onNodeWithTag("zone-registration-note").assertTextContains("不会购买", substring = true)
        edit("zone-create-name", "new.example.com"); click("zone-type-full"); click("zone-form-preview")
        compose.runOnIdle { assertEquals(ZoneAction.Create(account, ZoneCreateInput("new.example.com")), prepared.single()); assertEquals(0, submitted) }
        click("zone-confirm-submit")
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun invalidCreateNameDoesNotPrepareAndCancelNeverConfirms() {
        install(ZoneFormMode.CREATE, target = null)
        edit("zone-create-name", "invalid name"); click("zone-form-preview")
        compose.onNodeWithTag("zone-form-error").performScrollTo().assertIsDisplayed()
        click("zone-form-cancel")
        compose.runOnIdle { assertTrue(prepared.isEmpty()); assertEquals(0, submitted); assertEquals(1, cancelled) }
    }

    @Test fun zonePatchContainsExactlyOneSelectedField() {
        install(ZoneFormMode.EDIT)
        click("zone-edit-paused"); click("zone-paused"); click("zone-form-preview")
        compose.runOnIdle { assertEquals(ZoneEditInput(paused = true), (prepared.last() as ZoneAction.Edit).input); assertEquals(0, submitted) }
        click("zone-confirm-back")
        click("zone-edit-type"); click("zone-type-partial"); click("zone-form-preview")
        compose.runOnIdle { assertEquals(ZoneEditInput(type = "partial"), (prepared.last() as ZoneAction.Edit).input) }
        click("zone-confirm-back")
        click("zone-edit-vanity"); edit("zone-vanity-nameservers", "ns1.example.com\nns2.example.com"); click("zone-form-preview")
        compose.runOnIdle { assertEquals(ZoneEditInput(vanityNameservers = listOf("ns1.example.com", "ns2.example.com")), (prepared.last() as ZoneAction.Edit).input) }
    }

    @Test fun onlyPendingZoneCanPrepareActivationAndRequestIsNotActivationSuccess() {
        install(ZoneFormMode.ACTIVATION)
        compose.onNodeWithTag("zone-activation-note").assertTextContains("不代表已激活", substring = true)
        click("zone-form-preview")
        compose.runOnIdle { assertEquals(ZoneAction.ActivationCheck(zone.ref), prepared.single()); assertEquals(0, submitted) }
        click("zone-confirm-cancel")
        compose.runOnIdle { assertEquals(0, submitted) }
    }

    @Test fun activeZoneCannotPrepareActivation() {
        install(ZoneFormMode.ACTIVATION, target = zone.copy(status = "active"))
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(prepared.isEmpty()); assertEquals(0, submitted) }
    }

    @Test fun foreignAccountSnapshotCannotBeUsedForDelete() {
        install(ZoneFormMode.DELETE, target = zone.copy(ref = ZoneScope(AccountScope("other", "foreign"), "zone-fixture")),
            initial = ZoneFormDraft(typedName = zone.name))
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(prepared.isEmpty()); assertEquals(0, submitted) }
    }

    @Test fun narrowLargeFontDeleteFormAndPreviewKeepActionsReachable() {
        install(ZoneFormMode.DELETE, fontScale = 2f)
        edit("zone-delete-name", zone.name)
        compose.onNodeWithTag("zone-form-cancel").performScrollTo().assertIsDisplayed()
        click("zone-form-preview")
        compose.onNodeWithTag("zone-confirm-submit").performScrollTo().assertIsDisplayed()
        click("zone-confirm-cancel")
        compose.runOnIdle { assertEquals(0, submitted); assertEquals(1, cancelled) }
    }

    @Test fun zonePageShowsProviderDetailsAndHonestSettingsSecurityPlaceholders() {
        val state = ZonePageState(account = account, selected = zone, demo = true)
        val intents = mutableListOf<ZoneIntent>()
        compose.setContent { FlareGoTheme { ZonePage(state, { intents += it }) } }
        compose.onNodeWithTag("zone-status").performScrollTo().assertTextContains("pending", substring = true)
        compose.onNodeWithTag("zone-paused-value").performScrollTo().assertTextContains("false", substring = true)
        compose.onNodeWithTag("zone-type-value").performScrollTo().assertTextContains("full", substring = true)
        compose.onNodeWithTag("zone-nameservers").performScrollTo().assertTextContains("ada.ns.cloudflare.com", substring = true)
        click("zone-activation-check")
        compose.runOnIdle { assertEquals(ZoneIntent.ActivationCheck(zone.ref), intents.last()); assertTrue(intents.none { it is ZoneIntent.Confirm }) }
        compose.onNodeWithTag("zone-tab-settings").performClick()
        compose.onNodeWithTag("zone-module-pending").assertTextContains("待接入", substring = true)
        compose.onNodeWithTag("zone-tab-security").performClick()
        compose.onNodeWithTag("zone-module-pending").assertTextContains("DNSSEC", substring = true)
    }

    @Test fun zonePageDeleteUsesRefreshedTargetAndConfirmsUserTypedNameOnly() {
        val port = TestZonePagePort(ZonePageState(account = account, selected = zone))
        compose.setContent { FlareGoTheme { val state by port.state.collectAsState(); ZonePage(state, port::onIntent) } }
        click("zone-detail-delete")
        compose.onNodeWithTag("zone-delete-name").performScrollTo().assertTextContains("")
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        edit("zone-delete-name", "fresh.example.com"); click("zone-form-preview")
        val prepared = port.state.value.preparedChange!!
        compose.runOnIdle { assertTrue(port.intents.none { it is ZoneIntent.Confirm }) }
        click("zone-confirm-back")
        compose.onNodeWithTag("zone-delete-name").performScrollTo().assertTextContains("fresh.example.com")
        click("zone-form-preview"); click("zone-confirm-submit")
        compose.runOnIdle { assertEquals(ZoneIntent.Confirm(prepared.id, prepared.revision, "fresh.example.com"), port.intents.last()) }
    }

    @Test fun zonePageFreshTargetReplacementClearsOldTypedNameAndConfirmation() {
        val port = TestZonePagePort(ZonePageState(account = account, selected = zone))
        compose.setContent { FlareGoTheme { val state by port.state.collectAsState(); ZonePage(state, port::onIntent) } }
        click("zone-detail-delete"); edit("zone-delete-name", zone.name); click("zone-form-preview")
        compose.runOnIdle { port.mutable.value = port.state.value.copy(editorTarget = zone.copy(name = "changed.example.com"),
            preparedChange = null, draft = null, editorRevision = port.state.value.editorRevision + 1) }
        compose.onNodeWithTag("zone-confirm-submit").assertDoesNotExist()
        compose.onNodeWithTag("zone-delete-name").performScrollTo().assertTextContains("")
        compose.onNodeWithTag("zone-form-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(port.intents.none { it is ZoneIntent.Confirm }) }
    }
}

private class TestZonePagePort(initial: ZonePageState) : ZonePagePort {
    val mutable = MutableStateFlow(initial)
    override val state: StateFlow<ZonePageState> = mutable
    val intents = mutableListOf<ZoneIntent>()
    override fun onIntent(intent: ZoneIntent) {
        intents += intent
        val s = mutable.value
        when (intent) {
            is ZoneIntent.BeginDelete -> mutable.value = s.copy(editorMode = ZoneEditorMode.DELETE, editorTarget = s.selected,
                draft = null, preparedChange = null, editorRevision = s.editorRevision + 1)
            ZoneIntent.DraftInvalidated -> mutable.value = s.copy(revision = s.revision + 1, preparedChange = null)
            is ZoneIntent.Preview -> mutable.value = s.copy(draft = intent.action,
                preparedChange = ZonePreparedPreview("zone-ui-plan", s.revision,
                    ZoneChangePreview(intent.action.scope, s.editorTarget!!.name, "删除整个 Zone 可能中断服务", "无 CAS"), intent.action, s.editorTarget))
            ZoneIntent.BackToDraft -> mutable.value = s.copy(preparedChange = null)
            ZoneIntent.Cancel -> mutable.value = s.copy(editorMode = null, editorTarget = null, draft = null, preparedChange = null)
            else -> Unit
        }
    }
}
