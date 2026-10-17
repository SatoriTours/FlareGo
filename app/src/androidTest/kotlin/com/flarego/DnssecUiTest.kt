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
import com.flarego.ui.dns.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Panel and native interactions. Synthetic snapshots/preview are NOT production authority. */
class DnssecUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = ZoneScope(AccountScope("dnssec-ui-connection", "dnssec-ui-account"), "dnssec-ui-zone")
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun previews(h: DnssecHarness) = h.intents.filterIsInstance<DnssecPanelIntent.Preview>()
    private fun confirms(h: DnssecHarness) = h.intents.filterIsInstance<DnssecPanelIntent.Confirm>()
    private fun install(h: DnssecHarness = DnssecHarness(scope), font: Float = 1f): DnssecHarness {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    val state by h.state.collectAsState()
                    DnssecPanel(h.expectedScope.value, state, h::onIntent)
                } }
            }
        }
        return h
    }

    @Test fun actualStatusesStayDistinctAndFutureIsReadonly() {
        val h = install()
        listOf("active", "pending", "pending-disabled", "error", "future-signing-state").forEach { status ->
            compose.runOnIdle { h.replaceDetails(h.state.value.snapshot!!.details.copy(status = status,
                raw = JsonObject(h.state.value.snapshot!!.details.raw + ("status" to JsonPrimitive(status))))) }
            compose.onNodeWithTag("ds-status").performScrollTo().assertTextContains(status, substring = true)
            if (status != "active") compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun missingDsDigestAlgorithmAndKeyTagNeverBorrowExamples() {
        val h = install()
        listOf("ds-ds", "ds-digest", "ds-algorithm", "ds-key-tag").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertTextContains("未提供", substring = true)
        }
        compose.onNodeWithTag("ds-ds").assertTextContains("DS：未提供")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun actualPublicDsAndZskFieldsAreShownWithoutSecretRawOrPagination() {
        val h = DnssecHarness(scope)
        h.replaceDetails(h.state.value.snapshot!!.details.copy(ds = "sample-public-ds", digest = "public-digest", algorithm = "13", keyTag = 123.5))
        h.mutable.value = h.state.value.copy(keys = listOf(DnssecKey(scope, "key-name", "public-location", "actual-tag",
            DnssecDnskey(13, 256, 3, "public-dnskey", DnssecKeyHeader("example.com", 1, 48, 3600, 64)), "public-signing-key",
            JsonObject(mapOf("SigningKey" to JsonObject(mapOf("privkey" to JsonPrimitive("TEST_SECRET_NEVER_RENDER"), "kek" to JsonPrimitive("TEST_KEK_NEVER_RENDER"))))))))
        install(h)
        compose.onNodeWithTag("ds-ds").performScrollTo().assertTextContains("sample-public-ds", substring = true)
        compose.onNodeWithTag("ds-key-tag").performScrollTo().assertTextContains("123.5", substring = true)
        compose.onNodeWithTag("ds-zsk-0").performScrollTo().assertTextContains("public-dnskey", substring = true)
            .assertTextContains("public-signing-key", substring = true).assertTextContains("actual-tag", substring = true)
            .assertTextContains("3600", substring = true)
        compose.onAllNodesWithText("TEST_SECRET_NEVER_RENDER", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("TEST_KEK_NEVER_RENDER", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("ds-next-page").assertDoesNotExist()
        click("ds-refresh-zsk")
        compose.runOnIdle { assertEquals(DnssecPanelIntent.Refresh(scope, DnssecPanelSource.ZSK), h.intents.last()); assertTrue(previews(h).isEmpty()) }
    }

    @Test fun unknownAndDeniedWritePermissionRemainReadonlyDespiteSuccessfulRead() {
        val h = install(DnssecHarness(scope, allowed = null))
        compose.onNodeWithTag("ds-readonly-reason").performScrollTo().assertTextContains("未验证", substring = true)
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = h.state.value.snapshot!!.copy(writeAllowed = false)) }
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        click("ds-select-delete")
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun wrongAccountScopeHidesOldDsKeysErrorsAndConfirmation() {
        val h = DnssecHarness(scope); h.replaceDetails(h.state.value.snapshot!!.details.copy(ds = "OLD_ZONE_DS"))
        h.mutable.value = h.state.value.copy(keys = listOf(DnssecKey(scope, "OLD_KEY", null, null, null, null, JsonObject(emptyMap()))),
            errors = listOf(DnssecPanelError(scope, DnssecPanelSource.ZSK, "OLD_ERROR")))
        install(h); click("ds-status-disabled"); click("ds-preview")
        compose.runOnIdle { h.expectedScope.value = ZoneScope(AccountScope("new-connection", "new-account"), "new-zone") }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.onAllNodesWithText("OLD_ZONE_DS", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("OLD_KEY", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("OLD_ERROR", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun ownerRawAccountOrDetailsScopeMismatchCannotAuthorizeOrDisplay() {
        val h = DnssecHarness(scope)
        val snap = h.state.value.snapshot!!
        h.mutable.value = h.state.value.copy(snapshot = snap.copy(owner = snap.owner.copy(raw = JsonObject(snap.owner.raw +
            ("account" to buildJsonObject { put("id", "other-account") })))))
        install(h)
        compose.onNodeWithTag("ds-status").assertDoesNotExist()
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = snap.copy(details = snap.details.copy(scope = ZoneScope(scope.account, "other-zone")))) }
        compose.onNodeWithTag("ds-status").assertDoesNotExist()
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
    }

    @Test fun updatePermissionCannotBeReusedForDelete() {
        val h = install()
        click("ds-select-delete")
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshotMutation = DnssecMutation.UPDATE) }
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun fourFieldUpdateProducesOnlyRealChangesAndKeepsFalseDistinctFromOmit() {
        val h = DnssecHarness(scope, nsec3 = true, type = "secondary")
        h.replaceDetails(h.state.value.snapshot!!.details.copy(multiSigner = true, presigned = true, useNsec3 = true))
        install(h)
        click("ds-status-disabled"); click("ds-multi-false"); click("ds-presigned-false"); click("ds-nsec3-false"); click("ds-preview")
        compose.runOnIdle {
            val action = previews(h).single().action as DnssecAction.Update
            assertEquals(DnssecUpdateInput("disabled", false, false, false), action.input)
            assertEquals(Json.parseToJsonElement("""{"status":"disabled","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":false}"""), action.input.fields())
            assertTrue(confirms(h).isEmpty())
        }
    }

    @Test fun singleFieldUpdateOmitsUntouchedBooleansAndNeverBecomesDelete() {
        val h = install(); click("ds-status-disabled"); click("ds-preview")
        compose.runOnIdle {
            assertEquals(DnssecAction.Update(scope, DnssecUpdateInput(status = "disabled")), previews(h).single().action)
            assertEquals(setOf("status"), (previews(h).single().action as DnssecAction.Update).input.fields().keys)
            assertTrue(confirms(h).isEmpty())
        }
        click("ds-cancel"); click("ds-select-delete"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecAction.Delete(scope), previews(h).last().action); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun enableUsesPatchActiveWithoutDisableOrDeleteRisk() {
        val h = DnssecHarness(scope); h.replaceDetails(h.state.value.snapshot!!.details.copy(status = "disabled")); install(h)
        click("ds-status-active"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecAction.Update(scope, DnssecUpdateInput(status = "active")), previews(h).single().action); assertTrue(confirms(h).isEmpty()) }
        compose.onNodeWithTag("ds-risk-DELETE").assertDoesNotExist()
        compose.onNodeWithTag("ds-risk-DISABLE").assertDoesNotExist()
        click("ds-confirm")
        compose.runOnIdle { assertEquals(emptySet<DnssecRisk>(), confirms(h).single().acknowledgedRisks) }
    }

    @Test fun unchangedDraftAndUnknownOldFlagCannotGenerateDefaults() {
        val h = DnssecHarness(scope); h.replaceDetails(h.state.value.snapshot!!.details.copy(multiSigner = null)); install(h)
        click("ds-preview")
        compose.onNodeWithTag("ds-input-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds-multi-true").performScrollTo().assertIsNotEnabled()
        click("ds-status-active"); click("ds-preview")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
        click("ds-status-disabled"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecUpdateInput(status = "disabled"), (previews(h).single().action as DnssecAction.Update).input) }
    }

    @Test fun nsec3EnableNeedsVerifiedQualificationButDisableDoesNot() {
        val h = install(DnssecHarness(scope, nsec3 = null))
        compose.onNodeWithTag("ds-nsec3-true").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = h.state.value.snapshot!!.copy(nsec3Available = false)) }
        compose.onNodeWithTag("ds-nsec3-true").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.replaceDetails(h.state.value.snapshot!!.details.copy(useNsec3 = true)) }
        click("ds-nsec3-false"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecUpdateInput(useNsec3 = false), (previews(h).single().action as DnssecAction.Update).input); assertTrue(confirms(h).isEmpty()) }
        click("ds-risk-CHANGE_NSEC3"); click("ds-confirm")
        compose.runOnIdle { assertEquals(setOf(DnssecRisk.CHANGE_NSEC3), confirms(h).single().acknowledgedRisks) }
    }

    @Test fun nsec3VerifiedEnableUsesOnlyExplicitFlagAndOwnRisk() {
        val h = install(DnssecHarness(scope, nsec3 = true)); click("ds-nsec3-true"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecUpdateInput(useNsec3 = true), (previews(h).single().action as DnssecAction.Update).input); assertTrue(confirms(h).isEmpty()) }
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
        click("ds-risk-CHANGE_NSEC3"); click("ds-confirm")
        compose.runOnIdle { assertEquals(setOf(DnssecRisk.CHANGE_NSEC3), confirms(h).single().acknowledgedRisks) }
    }

    @Test fun presignedEnableNeedsBothFreshSecondaryFieldsButFalseRemainsPossible() {
        val h = install(DnssecHarness(scope, type = "full"))
        compose.onNodeWithTag("ds-presigned-true").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { val snap = h.state.value.snapshot!!; h.mutable.value = h.state.value.copy(snapshot = snap.copy(owner = snap.owner.copy(type = "secondary"))) }
        compose.onNodeWithTag("ds-presigned-true").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.replaceDetails(h.state.value.snapshot!!.details.copy(presigned = true)) }
        click("ds-presigned-false"); click("ds-preview")
        compose.runOnIdle { assertEquals(DnssecUpdateInput(presigned = false), (previews(h).single().action as DnssecAction.Update).input); assertTrue(confirms(h).isEmpty()) }
        compose.onNodeWithTag("ds-impact").performScrollTo().assertTextContains("不能保证代理", substring = true)
    }

    @Test fun secondaryPresignedPreviewShowsRegistrarAndTransferResponsibility() {
        val h = install(DnssecHarness(scope, type = "secondary")); click("ds-presigned-true"); click("ds-preview")
        compose.onNodeWithTag("ds-impact").performScrollTo().assertTextContains("DNS-only", substring = true)
            .assertTextContains("Secondary DNS Overrides", substring = true).assertTextContains("Load Balancing", substring = true)
            .assertTextContains("DNSKEY", substring = true).assertTextContains("RRSIG", substring = true)
            .assertTextContains("NSEC", substring = true).assertTextContains("primary signer", substring = true)
            .assertTextContains("AXFR", substring = true).assertTextContains("未探测", substring = true)
        compose.onNodeWithTag("ds-registrar-warning").performScrollTo().assertTextContains("DS 状态未知", substring = true)
        click("ds-risk-CHANGE_PRESIGNED"); click("ds-confirm")
        compose.runOnIdle { assertEquals(setOf(DnssecRisk.CHANGE_PRESIGNED), confirms(h).single().acknowledgedRisks) }
    }

    @Test fun disableAndDeleteUseSeparateRiskDecisionsAndConfirmIdsOnly() {
        val h = install(); click("ds-status-disabled"); click("ds-preview")
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ds-risk-DELETE").assertDoesNotExist()
        click("ds-risk-DISABLE")
        val first = h.state.value.prepared!!; click("ds-confirm")
        compose.runOnIdle { assertEquals(DnssecPanelIntent.Confirm(first.planId, first.revision, setOf(DnssecRisk.DISABLE)), confirms(h).single()) }
        click("ds-cancel"); click("ds-select-delete"); click("ds-preview")
        compose.onNodeWithTag("ds-risk-DISABLE").assertDoesNotExist()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
        click("ds-risk-DELETE"); val second = h.state.value.prepared!!; click("ds-confirm")
        compose.runOnIdle { assertEquals(DnssecPanelIntent.Confirm(second.planId, second.revision, setOf(DnssecRisk.DELETE)), confirms(h).last()) }
    }

    @Test fun multiRiskUpdateNeedsEveryIndependentChoiceBeforeConfirm() {
        val h = install(DnssecHarness(scope, nsec3 = true, type = "secondary"))
        click("ds-status-disabled"); click("ds-multi-true"); click("ds-presigned-true"); click("ds-nsec3-true"); click("ds-preview")
        val risks = listOf(DnssecRisk.DISABLE, DnssecRisk.CHANGE_MULTI_SIGNER, DnssecRisk.CHANGE_PRESIGNED, DnssecRisk.CHANGE_NSEC3)
        risks.forEach { risk ->
            compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
            click("ds-risk-${risk.name}")
        }
        click("ds-confirm")
        compose.runOnIdle { assertEquals(risks.toSet(), confirms(h).single().acknowledgedRisks) }
    }

    @Test fun returnKeepsDraftEditingClearsRiskAndCancelDoesNotSubmit() {
        val h = install(); click("ds-status-disabled"); click("ds-preview"); click("ds-risk-DISABLE"); click("ds-back")
        compose.onNodeWithTag("ds-status-disabled").performScrollTo().assertIsSelected()
        click("ds-multi-true"); click("ds-preview")
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
        compose.onNodeWithTag("ds-risk-CHANGE_MULTI_SIGNER").performScrollTo().assertIsOff()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsNotEnabled()
        click("ds-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()); assertTrue(h.intents.any { it is DnssecPanelIntent.DraftInvalidated }) }
    }

    @Test fun authorityLossAndRestorationCannotReviveOldAcknowledgementOrPlan() {
        val h = install(); click("ds-status-disabled"); click("ds-preview"); click("ds-risk-DISABLE")
        val snap = h.state.value.snapshot!!
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = snap.copy(writeAllowed = null)) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = snap) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        click("ds-preview")
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun changingPlanIdRevisionOrRefInvalidatesOldRisk() {
        val h = install(); click("ds-status-disabled"); click("ds-preview"); click("ds-risk-DISABLE")
        compose.runOnIdle { h.mutable.value = h.state.value.copy(prepared = h.state.value.prepared!!.copy(planId = "new-plan")) }
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
        click("ds-risk-DISABLE")
        compose.runOnIdle { val p = h.state.value.prepared!!; h.mutable.value = h.state.value.copy(revision = p.revision + 1, prepared = p.copy(revision = p.revision + 1)) }
        compose.onNodeWithTag("ds-risk-DISABLE").performScrollTo().assertIsOff()
        compose.runOnIdle { h.mutable.value = h.state.value.copy(prepared = h.state.value.prepared!!.copy(resourceRef = ResourceRef(scope, "dnssec", "other-zone"))) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun losingNsec3QualificationInvalidatesEnablePreview() {
        val h = install(DnssecHarness(scope, nsec3 = true)); click("ds-nsec3-true"); click("ds-preview"); click("ds-risk-CHANGE_NSEC3")
        compose.runOnIdle { h.mutable.value = h.state.value.copy(snapshot = h.state.value.snapshot!!.copy(nsec3Available = null)) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun zskErrorDoesNotBlockDnssecButDetailsErrorBlocksOnlyThisPanel() {
        val h = DnssecHarness(scope)
        h.mutable.value = h.state.value.copy(errors = listOf(DnssecPanelError(scope, DnssecPanelSource.ZSK, "ZSK403"),
            DnssecPanelError(ZoneScope(scope.account, "other-zone"), DnssecPanelSource.DNSSEC, "OTHER_ZONE_ERROR")))
        install(h)
        compose.onNodeWithTag("ds-error-zsk").performScrollTo().assertTextContains("ZSK403")
        compose.onAllNodesWithText("OTHER_ZONE_ERROR", substring = true).assertCountEquals(0)
        click("ds-status-disabled"); click("ds-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size); assertTrue(confirms(h).isEmpty()) }
        click("ds-back")
        compose.runOnIdle { h.mutable.value = h.state.value.copy(errors = listOf(DnssecPanelError(scope, DnssecPanelSource.DNSSEC, "DETAILS403"))) }
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        click("ds-refresh")
        compose.runOnIdle { assertEquals(DnssecPanelIntent.Refresh(scope), h.intents.last()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun pendingAcceptedStaysWaitingWithoutAutomaticPollOrReplay() {
        val h = DnssecHarness(scope); h.replaceDetails(h.state.value.snapshot!!.details.copy(status = "pending")); h.addTask(TaskState.ACCEPTED)
        install(h)
        compose.onNodeWithTag("ds-task-operation").performScrollTo().assertTextContains("已受理", substring = true)
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(h.intents.isEmpty()) }
        click("ds-reconcile-operation")
        compose.runOnIdle { assertEquals(listOf(DnssecPanelIntent.Reconcile(scope, "operation")), h.intents); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun unknownFixedOperationsBlockEvenMissingOrWrongRefButOtherZoneDoesNot() {
        val h = DnssecHarness(scope); h.addTask(TaskState.UNKNOWN); install(h)
        val original = h.state.value.pendingTasks.single()
        listOf(DnssecMutation.UPDATE, DnssecMutation.DELETE).forEach { mutation ->
            listOf<ResourceRef?>(null, ResourceRef(scope, "unrecognized", "wrong"), ResourceRef(scope, "dnssec", "other-zone")).forEach { ref ->
                compose.runOnIdle { h.mutable.value = h.state.value.copy(pendingTasks = listOf(original.copy(actionKey = mutation.operationId, resourceRef = ref))) }
                compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
            }
        }
        compose.runOnIdle {
            val other = ZoneScope(scope.account, "other-zone")
            h.mutable.value = h.state.value.copy(pendingTasks = listOf(original.copy(scope = other, resourceRef = ResourceRef(other, "dnssec", other.zoneId))))
        }
        click("ds-status-disabled"); click("ds-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun lostDeleteCannotBeCalledDeletedFromDisabledOr404AndHasNoRetryDelete() {
        val h = DnssecHarness(scope); h.replaceDetails(h.state.value.snapshot!!.details.copy(status = "disabled")); h.addTask(TaskState.UNKNOWN, DnssecMutation.DELETE)
        install(h)
        compose.onNodeWithTag("ds-task-operation").performScrollTo().assertTextContains("404", substring = true).assertTextContains("不能证明删除", substring = true)
        click("ds-select-delete")
        compose.onNodeWithTag("ds-preview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ds-retry-delete").assertDoesNotExist()
        click("ds-reconcile-operation")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()); assertEquals(DnssecPanelIntent.Reconcile(scope, "operation"), h.intents.last()) }
    }

    @Test fun largeFontNarrowScreenCanReachEachRiskAndFinalActions() {
        val h = install(DnssecHarness(scope, nsec3 = true, type = "secondary"), font = 2f)
        click("ds-status-disabled"); click("ds-multi-true"); click("ds-presigned-true"); click("ds-nsec3-true"); click("ds-preview")
        click("ds-risk-DISABLE"); click("ds-risk-CHANGE_MULTI_SIGNER"); click("ds-risk-CHANGE_PRESIGNED"); click("ds-risk-CHANGE_NSEC3")
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled()
        screenshot("narrow-multi-risk")
        click("ds-back")
        compose.onNodeWithTag("ds-status-disabled").performScrollTo().assertIsSelected()
        click("ds-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "task8-panel-screenshots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}

private class DnssecHarness(val scope: ZoneScope, allowed: Boolean? = true, nsec3: Boolean? = null, type: String = "full") {
    val expectedScope = mutableStateOf(scope)
    val intents = mutableListOf<DnssecPanelIntent>()
    private var sequence = 0
    private val owner = ZoneDetails(scope, "example.com", "active", false, type, emptyList(), buildJsonObject {
        put("account", buildJsonObject { put("id", scope.account.accountId) }); put("id", scope.zoneId); put("name", "example.com"); put("type", type)
    })
    private val initial = DnssecDetails(scope, "active", null, null, null, null, false, false, false, JsonObject(emptyMap()))
    val mutable = MutableStateFlow(DnssecPanelState(scope = scope, snapshotMutation = DnssecMutation.UPDATE,
        snapshot = DnssecSnapshot(owner, initial, allowed, nsec3)))
    val state: StateFlow<DnssecPanelState> = mutable
    init { replaceDetails(initial) }
    fun replaceDetails(details: DnssecDetails) {
        val raw = buildJsonObject {
            details.status?.let { put("status", it) }; details.multiSigner?.let { put("dnssec_multi_signer", it) }
            details.presigned?.let { put("dnssec_presigned", it) }; details.useNsec3?.let { put("dnssec_use_nsec3", it) }
            details.ds?.let { put("ds", it) }; details.digest?.let { put("digest", it) }; details.algorithm?.let { put("algorithm", it) }; details.keyTag?.let { put("key_tag", it) }
        }
        mutable.value = state.value.copy(snapshot = state.value.snapshot!!.copy(details = details.copy(raw = raw)))
    }
    fun addTask(status: TaskState, mutation: DnssecMutation = DnssecMutation.UPDATE) {
        mutable.value = state.value.copy(pendingTasks = listOf(OperationTask("operation", scope.connectionId, scope, mutation.operationId,
            ResourceRef(scope, "dnssec", scope.zoneId), "a".repeat(64), "b".repeat(64), status, "测试请求等待核对", null, null, 1, 1)))
    }
    fun onIntent(intent: DnssecPanelIntent) {
        intents += intent
        val s = state.value
        when (intent) {
            is DnssecPanelIntent.SelectMutation -> mutable.value = s.copy(mutation = intent.mutation, snapshotMutation = intent.mutation, prepared = null, revision = s.revision + 1, editorRevision = s.editorRevision + 1)
            is DnssecPanelIntent.DraftInvalidated -> mutable.value = s.copy(prepared = null, revision = s.revision + 1)
            is DnssecPanelIntent.BackToDraft -> mutable.value = s.copy(prepared = null)
            is DnssecPanelIntent.Cancel -> mutable.value = s.copy(prepared = null, editorRevision = s.editorRevision + 1)
            is DnssecPanelIntent.Preview -> {
                val a = intent.action
                val before = s.snapshot!!.details
                // Display-only fixture; risk texts are explicit representative core projections, not controller verification.
                val risks = if (a is DnssecAction.Delete) setOf(DnssecRisk.DELETE) else {
                    a as DnssecAction.Update
                    setOfNotNull(if (a.input.status == "disabled") DnssecRisk.DISABLE else null,
                        if (a.input.multiSigner != null) DnssecRisk.CHANGE_MULTI_SIGNER else null,
                        if (a.input.presigned != null) DnssecRisk.CHANGE_PRESIGNED else null,
                        if (a.input.useNsec3 != null) DnssecRisk.CHANGE_NSEC3 else null)
                }
                val impact = when {
                    a is DnssecAction.Delete -> "独立 DELETE；停止签名后已有 DS 可能导致验证失败"
                    (a as DnssecAction.Update).input.presigned == true -> "所有记录 DNS-only；不支持 Secondary DNS Overrides/Load Balancing。primary 必须转 DNSKEY/RRSIG/NSEC(NSEC3)；DS 来自 primary signer。AXFR/签名转移健康/外部 DS 未探测"
                    a.input.presigned == false -> "关闭预签名不能保证代理自动恢复；AXFR/外部 DS 未探测"
                    else -> "配置变化只证明供应商当前状态，不证明全网验证成功"
                }
                val after = (a as? DnssecAction.Update)?.input?.let { before.copy(status = it.status ?: before.status,
                    multiSigner = it.multiSigner ?: before.multiSigner, presigned = it.presigned ?: before.presigned, useNsec3 = it.useNsec3 ?: before.useNsec3) }
                mutable.value = s.copy(prepared = DnssecPanelPreview("dnssec-ui-plan-${++sequence}", s.revision,
                    DnssecPreview(scope, owner.name, s.mutation, risks, "注册商/父区 DS 状态未知，未探测；已有 DS 时停止签名会使验证失败，需在注册商处理并等待父区 TTL。本应用不修改 DS。", impact,
                        "服务端无 CAS；预提交回读仍有竞争窗口"), a, ResourceRef(scope, "dnssec", scope.zoneId), before, after))
            }
            else -> Unit
        }
    }
}
