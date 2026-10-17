package com.flarego

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.model.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.zones.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Panel, synthetic session snapshots/callbacks only. A fixture true is NOT production authority. */
class ZoneSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = ZoneScope(AccountScope("ui-settings-connection", "ui-settings-account"), "ui-settings-zone")
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun edit(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    private fun select(key: ZoneSettingKey) = click("zs-key-${key.route}")
    private fun previews(h: Harness) = h.intents.filterIsInstance<ZoneSettingsPanelIntent.Preview>()
    private fun confirms(h: Harness) = h.intents.filterIsInstance<ZoneSettingsPanelIntent.Confirm>()

    private fun install(h: Harness = Harness(scope), fontScale: Float = 1f): Harness {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    val state by h.state.collectAsState()
                    ZoneSettingsPanel(h.expectedScope.value, state, h::onIntent)
                } }
            }
        }
        return h
    }

    @Test fun only20ReviewedKeysAreSelectableAndAuditEntriesStayReadonly() {
        val h = install()
        ZoneSettingKey.entries.forEach { compose.onNodeWithTag("zs-key-${it.route}").assertExists() }
        click("zs-audit-toggle")
        compose.onNodeWithTag("zs-unavailable-mirage").performScrollTo().assertTextContains("deprecated", substring = true)
        compose.onNodeWithTag("zs-unavailable-0rtt").performScrollTo().assertTextContains("needs_review", substring = true)
        compose.onNodeWithTag("zs-key-mirage").assertDoesNotExist()
        compose.onNodeWithTag("zs-key-0rtt").assertDoesNotExist()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun eightNativeGenericInputsProduceTypedReviewedValues() {
        val h = install()
        val choices = linkedMapOf(ZoneSettingKey.SSL to "full", ZoneSettingKey.ALWAYS_USE_HTTPS to "on",
            ZoneSettingKey.MIN_TLS_VERSION to "1.2", ZoneSettingKey.TLS_1_3 to "zrt",
            ZoneSettingKey.HTTP2 to "on", ZoneSettingKey.HTTP3 to "on", ZoneSettingKey.DEVELOPMENT_MODE to "on")
        choices.forEach { (key, value) ->
            select(key); click("zs-value-$value"); click("zs-preview")
            compose.runOnIdle {
                val action = previews(h).last().action as ZoneSettingsAction.Update
                assertEquals(key, action.key); assertEquals(JsonPrimitive(value), action.input["value"])
                assertTrue(confirms(h).isEmpty())
            }
            click("zs-cancel")
        }
        select(ZoneSettingKey.BROWSER_CACHE_TTL); edit("zs-value", "120"); click("zs-preview")
        compose.runOnIdle { assertEquals(JsonPrimitive(120), (previews(h).last().action as ZoneSettingsAction.Update).input["value"]) }
    }

    @Test fun dedicatedInputsUseTheirSpecificValueSchemas() {
        val h = install()
        val values = linkedMapOf(
            ZoneSettingKey.AEGIS to """{"enabled":true,"pool_id":"pool-new"}""",
            ZoneSettingKey.AUTOMATIC_PLATFORM_OPTIMIZATION to """{"cf":true,"enabled":true,"wordpress":false,"wp_plugin":true}""",
            ZoneSettingKey.BINARY_AST to "\"latest\"", ZoneSettingKey.FONTS to "\"on\"",
            ZoneSettingKey.H2_PRIORITIZATION to "\"custom\"", ZoneSettingKey.IMAGE_RESIZING to "\"open\"",
            ZoneSettingKey.ORIGIN_H2_MAX_STREAMS to "1000", ZoneSettingKey.ORIGIN_MAX_HTTP_VERSION to "\"2\"",
            ZoneSettingKey.SPEED_BRAIN to "\"on\"", ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS to "\"example.com,static.example.com\"",
            ZoneSettingKey.TRANSFORMATIONS_C2PA to "\"on\"", ZoneSettingKey.TRANSFORMATIONS_CONFIG to """{"c2pa":"on"}""",
        )
        values.forEach { (key, text) ->
            select(key)
            val value = json(text)
            when {
                value is JsonObject -> {
                    if (key == ZoneSettingKey.AUTOMATIC_PLATFORM_OPTIMIZATION) click("zs-branch-object")
                    click("zs-json-toggle"); edit("zs-value-json", text)
                }
                key in setOf(ZoneSettingKey.ORIGIN_H2_MAX_STREAMS, ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS) -> edit("zs-value", value.jsonPrimitive.content)
                else -> click("zs-value-${value.jsonPrimitive.content}")
            }
            click("zs-preview")
            compose.runOnIdle {
                val action = previews(h).last().action as ZoneSettingsAction.Update
                assertEquals(key, action.key); assertEquals(value, action.input["value"])
                assertEquals(setOf("value"), action.input.keys); assertTrue(confirms(h).isEmpty())
            }
            click("zs-cancel")
        }
    }

    @Test fun structuredAegisAndPartialTransformationsNeverWriteUnknownFields() {
        val h = install()
        select(ZoneSettingKey.AEGIS)
        click("zs-field-value-enabled"); edit("zs-field-value-pool_id", "pool-new"); click("zs-preview")
        compose.runOnIdle { assertEquals(json("""{"value":{"enabled":true,"pool_id":"pool-new"}}"""), (previews(h).last().action as ZoneSettingsAction.Update).input) }
        click("zs-cancel"); select(ZoneSettingKey.TRANSFORMATIONS_CONFIG)
        click("zs-field-value-c2pa-on"); click("zs-preview")
        compose.runOnIdle { assertEquals(json("""{"value":{"c2pa":"on"}}"""), (previews(h).last().action as ZoneSettingsAction.Update).input) }
    }

    @Test fun unknownWritePermissionStaysReadonlyDespiteEditableTrue() {
        val h = Harness(scope, writeAllowed = null); install(h)
        compose.onNodeWithTag("zs-readonly-reason").performScrollTo().assertTextContains("未验证", substring = true)
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun editableFalseCannotBeOverriddenByWriteAllowedTrue() {
        val h = Harness(scope, editable = false); install(h)
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        select(ZoneSettingKey.HTTP3)
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun nullableEditableIsShownAndDoesNotInferPermission() {
        val h = Harness(scope, writeAllowed = null, editable = null); install(h)
        compose.onNodeWithTag("zs-editable").performScrollTo().assertTextContains("未提供", substring = true)
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun invalidNumericTextIsRetainedAndNeverBecomesZero() {
        val h = install(); select(ZoneSettingKey.BROWSER_CACHE_TTL)
        listOf("oops", "", "01", "-1", "1.5", "31536001").forEach { value ->
            edit("zs-value", value); click("zs-preview")
            compose.onNodeWithTag("zs-input-error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("zs-value").performScrollTo().assertTextContains(value)
            compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
        }
        edit("zs-value", "60"); click("zs-preview")
        compose.runOnIdle { assertEquals(JsonPrimitive(60), (previews(h).single().action as ZoneSettingsAction.Update).input["value"]) }
    }

    @Test fun invalidJsonAndUnreviewedValueFieldsAreNotDefaultedOrSent() {
        val h = install(); select(ZoneSettingKey.AEGIS); click("zs-json-toggle")
        listOf("{", """{"enabled":"off"}""", """{"url":"https://example.com","method":"POST"}""").forEach { value ->
            edit("zs-value-json", value); click("zs-preview")
            compose.onNodeWithTag("zs-value-json").performScrollTo().assertTextContains(value)
            compose.onNodeWithTag("zs-input-error").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
        }
    }

    @Test fun sslDowngradeHasItsOwnRiskDecisionBeforeOrdinaryConfirmation() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        click("zs-value-flexible"); click("zs-preview")
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
        click("zs-risk-SSL_DOWNGRADE")
        val p = h.state.value.prepared!!
        click("zs-confirm")
        compose.runOnIdle { assertEquals(ZoneSettingsPanelIntent.Confirm(p.planId, p.revision, ZoneSettingsRisk.SSL_DOWNGRADE), confirms(h).single()) }
    }

    @Test fun minimumTlsDowngradeNeedsExplicitSslRiskDecision() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        select(ZoneSettingKey.MIN_TLS_VERSION); click("zs-value-1.0"); click("zs-preview")
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        click("zs-risk-SSL_DOWNGRADE"); click("zs-confirm")
        compose.runOnIdle { assertEquals(ZoneSettingsRisk.SSL_DOWNGRADE, confirms(h).single().acknowledgedRisk) }
    }

    @Test fun disableUniversalSslIsAnIndependentRiskAndCancelNeverSubmits() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.DISABLE_UNIVERSAL_SSL
        click("zs-tab-universal"); click("zs-universal-enabled"); click("zs-preview")
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("zs-risk-SSL_DOWNGRADE").assertDoesNotExist()
        click("zs-risk-DISABLE_UNIVERSAL_SSL"); click("zs-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun purgeAllIsExplicitAndNeedsItsOwnRiskThenOrdinaryConfirm() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.PURGE_ALL
        click("zs-tab-cache"); click("zs-purge-all"); click("zs-preview")
        compose.runOnIdle { assertEquals(CachePurgeInput.All, (previews(h).single().action as ZoneSettingsAction.Purge).input); assertTrue(confirms(h).isEmpty()) }
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        click("zs-risk-PURGE_ALL"); click("zs-confirm")
        compose.runOnIdle { assertEquals(ZoneSettingsRisk.PURGE_ALL, confirms(h).single().acknowledgedRisk) }
    }

    @Test fun allFivePurgeModesUseTypedBodiesAndInvalidInputNeverFallsBackToAll() {
        val h = install(); click("zs-tab-cache")
        click("zs-preview")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
        listOf("files" to "https://static.example.com/a", "hosts" to "static.example.com", "tags" to "asset:v1", "prefixes" to "static.example.com/images").forEach { (mode, text) ->
            click("zs-purge-$mode"); edit("zs-purge-entries", text)
            if (mode == "files") edit("zs-purge-headers", """{"https://static.example.com/a":{"CF-Device-Type":"mobile"}}""")
            click("zs-preview")
            compose.runOnIdle {
                val value = (previews(h).last().action as ZoneSettingsAction.Purge).input
                val expected: CachePurgeInput = when (mode) {
                    "files" -> CachePurgeInput.Files(listOf(CachePurgeFile(text, mapOf("CF-Device-Type" to "mobile"))))
                    "hosts" -> CachePurgeInput.Hosts(listOf(text))
                    "tags" -> CachePurgeInput.Tags(listOf(text))
                    else -> CachePurgeInput.Prefixes(listOf(text))
                }
                assertEquals(expected, value); assertTrue(confirms(h).isEmpty())
            }
            click("zs-cancel")
        }
        click("zs-purge-files"); edit("zs-purge-entries", "https://user:secret@example.com/a"); click("zs-preview")
        compose.runOnIdle { assertEquals(4, previews(h).size); assertTrue(previews(h).none { (it.action as ZoneSettingsAction.Purge).input == CachePurgeInput.All }) }
    }

    @Test fun purgeCountUsesSessionTierAndUnknownTierStaysAt100() {
        val h = install(); click("zs-tab-cache")
        val many = (1..101).joinToString("\n") { "https://example.com/$it" }
        edit("zs-purge-entries", many); click("zs-preview")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
        compose.onNodeWithTag("zs-purge-limits").performScrollTo().assertTextContains("100", substring = true)
        compose.runOnIdle {
            val target = ZoneSettingsTarget.Cache
            h.mutable.value = h.state.value.copy(snapshots = h.state.value.snapshots + (target to h.state.value.snapshots.getValue(target).copy(purgeTier = CachePurgeTier.ENTERPRISE)))
        }
        click("zs-preview")
        compose.runOnIdle { assertEquals(101, ((previews(h).single().action as ZoneSettingsAction.Purge).input as CachePurgeInput.Files).files.size) }
    }

    @Test fun acceptedPurgeOnlySaysAcceptedAndAllowsANewExplicitRequest() {
        val h = Harness(scope); h.addCacheTask(TaskState.ACCEPTED); install(h); click("zs-tab-cache")
        compose.onNodeWithTag("zs-task-cache-task").performScrollTo().assertTextContains("仅已受理", substring = true)
        edit("zs-purge-entries", "https://example.com/a"); click("zs-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun unknownPurgeBlocksResendAndOffersOnlyReadOnlyReconcile() {
        val h = Harness(scope); h.addCacheTask(TaskState.UNKNOWN); install(h); click("zs-tab-cache")
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        click("zs-reconcile-cache-task")
        compose.runOnIdle { assertEquals(ZoneSettingsPanelIntent.Reconcile(scope, "cache-task"), h.intents.last()); assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
        select(ZoneSettingKey.HTTP3); click("zs-value-on"); click("zs-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size) }
    }

    @Test fun backRetainsDraftButEditingClearsOldRiskDecision() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        click("zs-value-flexible"); click("zs-preview"); click("zs-risk-SSL_DOWNGRADE"); click("zs-back")
        compose.onNodeWithTag("zs-value-flexible").performScrollTo().assertIsSelected()
        click("zs-value-full"); click("zs-preview")
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsNotEnabled()
        click("zs-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun oneSetting403DoesNotDisableAnotherSetting() {
        val h = Harness(scope)
        h.mutable.value = h.state.value.copy(errors = listOf(ZoneSettingsPanelError(scope, ZoneSettingsTarget.Setting(ZoneSettingKey.SSL), "SSL 403 仅此项失败")))
        install(h)
        compose.onNodeWithTag("zs-target-error").performScrollTo().assertTextContains("403", substring = true)
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        select(ZoneSettingKey.HTTP3)
        compose.onNodeWithTag("zs-target-error").assertDoesNotExist()
        click("zs-value-on"); click("zs-preview")
        compose.runOnIdle { assertEquals(ZoneSettingKey.HTTP3, (previews(h).single().action as ZoneSettingsAction.Update).key) }
    }

    @Test fun wrongScopeOrSnapshotReferenceCannotShowOldValuesErrorsOrConfirmation() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        click("zs-value-full"); click("zs-preview"); click("zs-risk-SSL_DOWNGRADE")
        compose.runOnIdle { h.expectedScope.value = ZoneScope(AccountScope("new", "new-account"), "new-zone") }
        compose.onNodeWithTag("zs-confirm").assertDoesNotExist()
        compose.onNodeWithTag("zs-current-value").assertDoesNotExist()
        compose.onNodeWithTag("zs-target-error").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun mismatchedSnapshotOwnerOrPlanRefIsReadonlyAndDoesNotConfirm() {
        val h = Harness(scope)
        val target = h.state.value.target
        val old = h.state.value.snapshots.getValue(target)
        h.mutable.value = h.state.value.copy(snapshots = h.state.value.snapshots + (target to old.copy(owner = old.owner.copy(ref = ZoneScope(scope.account, "other-zone")))))
        install(h)
        compose.onNodeWithTag("zs-current-value").assertDoesNotExist()
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun unknownPurgeWithUnidentifiableReferenceStillBlocksTheCacheTarget() {
        val h = Harness(scope); h.addCacheTask(TaskState.UNKNOWN)
        h.mutable.value = h.state.value.copy(pendingTasks = h.state.value.pendingTasks.map {
            it.copy(resourceRef = ResourceRef(scope, "cache_purge", "unknown-cache-id"))
        })
        install(h); click("zs-tab-cache")
        compose.onNodeWithTag("zs-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun authorityLossInvalidatesRiskAndOldPlanEvenIfPermissionReturns() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        click("zs-value-full"); click("zs-preview"); click("zs-risk-SSL_DOWNGRADE")
        val target = h.state.value.target
        val snapshot = h.state.value.snapshots.getValue(target)
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshots = h.state.value.snapshots + (target to snapshot.copy(writeAllowed = null))) }
        compose.onNodeWithTag("zs-confirm").assertDoesNotExist()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshots = h.state.value.snapshots + (target to snapshot)) }
        compose.onNodeWithTag("zs-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun changedPreparedResourceReferenceCannotReuseAcknowledgedRisk() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.SSL_DOWNGRADE
        click("zs-value-full"); click("zs-preview"); click("zs-risk-SSL_DOWNGRADE")
        compose.runOnIdle { h.mutable.value = h.state.value.copy(prepared = h.state.value.prepared!!.copy(resourceRef = ResourceRef(scope, "zone_setting", "HTTP3"))) }
        compose.onNodeWithTag("zs-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun changingPurgeModeClearsTargetsAndOldRiskWithoutImplicitAll() {
        val h = install(); h.nextRisk = ZoneSettingsRisk.PURGE_ALL
        click("zs-tab-cache"); click("zs-purge-all"); click("zs-preview"); click("zs-risk-PURGE_ALL"); click("zs-back")
        click("zs-purge-files")
        compose.onNodeWithTag("zs-purge-entries").performScrollTo().assertTextContains("")
        click("zs-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun narrowLargeFontRiskPreviewAndLongPurgeInputCanReachBottomActions() {
        val h = install(fontScale = 2f); click("zs-tab-cache")
        val text = "https://example.com/" + "long-path-".repeat(60)
        edit("zs-purge-entries", text)
        compose.onNodeWithTag("zs-cancel").performScrollTo().assertIsDisplayed()
        click("zs-preview")
        compose.onNodeWithTag("zs-preview-cas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsDisplayed()
        screenshot("narrow-files-preview")
        click("zs-back"); compose.onNodeWithTag("zs-purge-entries").performScrollTo().assertTextContains(text)
        click("zs-purge-all"); h.nextRisk = ZoneSettingsRisk.PURGE_ALL; click("zs-preview")
        click("zs-risk-PURGE_ALL")
        compose.onNodeWithTag("zs-confirm").performScrollTo().assertIsDisplayed()
        screenshot("narrow-purge-all-risk")
        click("zs-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "task9-panel-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}

private class Harness(val scope: ZoneScope, writeAllowed: Boolean? = true, editable: Boolean? = true) {
    val expectedScope = mutableStateOf(scope)
    val intents = mutableListOf<ZoneSettingsPanelIntent>()
    var nextRisk: ZoneSettingsRisk? = null
    private var nextId = 0
    private val owner = ZoneDetails(scope, "example.com", "active", false, "full", emptyList(), JsonObject(emptyMap()))
    private fun json(s: String) = Json.parseToJsonElement(s)
    private val values = mapOf(
        ZoneSettingKey.AEGIS to json("""{"enabled":false,"pool_id":"pool-old"}"""),
        ZoneSettingKey.AUTOMATIC_PLATFORM_OPTIMIZATION to JsonPrimitive("off"),
        ZoneSettingKey.ORIGIN_H2_MAX_STREAMS to JsonPrimitive(10),
        ZoneSettingKey.ORIGIN_MAX_HTTP_VERSION to JsonPrimitive("1"),
        ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS to JsonPrimitive("example.com"),
        ZoneSettingKey.TRANSFORMATIONS_CONFIG to json("""{"transformations":"off","c2pa":"off","allowed_origins":"example.com"}"""),
        ZoneSettingKey.SSL to JsonPrimitive("strict"), ZoneSettingKey.MIN_TLS_VERSION to JsonPrimitive("1.3"),
        ZoneSettingKey.BROWSER_CACHE_TTL to JsonPrimitive(3600),
    )
    val mutable = MutableStateFlow(ZoneSettingsPanelState(scope = scope, snapshots =
        ZoneSettingKey.entries.associate { key -> ZoneSettingsTarget.Setting(key) to ZoneSettingsSnapshot(owner,
            setting = ZoneSettingValue(scope, key, values[key] ?: JsonPrimitive("off"), editable, buildJsonObject { put("fixture_only", true) }), writeAllowed = writeAllowed) } +
            mapOf(ZoneSettingsTarget.UniversalSsl to ZoneSettingsSnapshot(owner, universal = UniversalSslSetting(scope, true, editable, JsonObject(emptyMap())), writeAllowed = writeAllowed),
                ZoneSettingsTarget.Cache to ZoneSettingsSnapshot(owner, writeAllowed = writeAllowed))))
    val state: StateFlow<ZoneSettingsPanelState> = mutable
    fun addCacheTask(status: TaskState) {
        mutable.value = state.value.copy(pendingTasks = listOf(OperationTask("cache-task", scope.connectionId, scope, "zone-purge",
            ResourceRef(scope, "cache_purge", scope.zoneId), "a".repeat(64), "b".repeat(64), status, "缓存请求状态", null, null, 1, 1)))
    }
    fun onIntent(intent: ZoneSettingsPanelIntent) {
        intents += intent
        val s = state.value
        when (intent) {
            is ZoneSettingsPanelIntent.Select -> mutable.value = s.copy(target = intent.target, prepared = null, revision = s.revision + 1, editorRevision = s.editorRevision + 1)
            is ZoneSettingsPanelIntent.DraftInvalidated -> mutable.value = s.copy(prepared = null, revision = s.revision + 1)
            is ZoneSettingsPanelIntent.Preview -> {
                val action = intent.action
                val target: ZoneSettingsTarget = when (action) { is ZoneSettingsAction.Update -> ZoneSettingsTarget.Setting(action.key); is ZoneSettingsAction.UniversalSsl -> ZoneSettingsTarget.UniversalSsl; is ZoneSettingsAction.Purge -> ZoneSettingsTarget.Cache }
                val snap = s.snapshots.getValue(target)
                val before = snap.setting?.value ?: snap.universal?.let { JsonPrimitive(it.enabled) }
                val after = when (action) { is ZoneSettingsAction.Update -> action.input["value"]; is ZoneSettingsAction.UniversalSsl -> JsonPrimitive(action.enabled); is ZoneSettingsAction.Purge -> cachePurgeBody(action.input, snap.purgeTier) }
                val ref = when (target) { is ZoneSettingsTarget.Setting -> ResourceRef(scope, "zone_setting", target.key.name); ZoneSettingsTarget.UniversalSsl -> ResourceRef(scope, "universal_ssl", "universal_ssl"); ZoneSettingsTarget.Cache -> ResourceRef(scope, "cache_purge", scope.zoneId) }
                mutable.value = s.copy(prepared = ZoneSettingsPanelPreview("panel-plan-${++nextId}", s.revision,
                    ZoneSettingsPreview(scope, owner.name, target, nextRisk, "测试领域影响：配置变化可能影响服务", "服务端未提供 CAS，仍有竞争窗口"), action, ref, before, after))
            }
            is ZoneSettingsPanelIntent.BackToDraft -> mutable.value = s.copy(prepared = null)
            is ZoneSettingsPanelIntent.Cancel -> mutable.value = s.copy(prepared = null, editorRevision = s.editorRevision + 1)
            else -> Unit
        }
    }
}
