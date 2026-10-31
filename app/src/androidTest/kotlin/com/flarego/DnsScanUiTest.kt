package com.flarego

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.dns.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose + real published pure helpers. Synthetic state is not production authority/runtime evidence. */
class DnsScanUiTest {
    @get:Rule val compose=createComposeRule()
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun edit(tag:String,value:String)=compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    private fun shown(tag:String)=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun install(h:Task7Harness=Task7Harness(),settings:Boolean=false,font:Float=1f):Task7Harness {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    if(settings) { val state by h.settings.collectAsState(); DnsZoneSettingsPanel(h.outerScope.value,h.outerEpoch.longValue,state,h::settingsIntent) }
                    else { val state by h.scan.collectAsState(); DnsScanPanel(h.outerScope.value,h.outerEpoch.longValue,state,h::scanIntent) }
                } }
            }
        };return h
    }
    private fun noConfirm(h:Task7Harness)=compose.runOnIdle {
        assertTrue(h.scanIntents.none { it is DnsScanPanelIntent.Confirm });assertTrue(h.settingIntents.none { it is DnsZoneSettingsPanelIntent.Confirm })
    }
    private fun scanPreview() { click("ds-accept-候选-甲");click("ds-preview-apply") }
    private fun settingsPreview() { edit("dz-ns_ttl","601");click("dz-preview") }

    @Test fun triggerNeedsPreviewAndSeparateConfirmWithNoAutomaticWrites() {
        val h=install();click("ds-preview-trigger");shown("ds-preview")
        compose.onNodeWithTag("ds-preview").assertTextContains("example.com",substring=true);noConfirm(h)
        val plan=h.scan.value.prepared!!;click("ds-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsScanPanelIntent.Confirm(plan.planId,plan.revision)),h.scanIntents.filterIsInstance<DnsScanPanelIntent.Confirm>()) }
    }
    @Test fun acceptedTriggerIsNotCompletionAndDoesNotPermanentlyLockNewRequests() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(pendingTasks=listOf(h.task(DNS_SCAN_TRIGGER_OPERATION,TaskState.ACCEPTED)))
        install(h);compose.onNodeWithTag("ds-task-task").performScrollTo().assertTextContains("已受理",substring=true)
        compose.onNodeWithText("扫描完成").assertDoesNotExist();click("ds-preview-trigger");shown("ds-confirm");noConfirm(h)
    }
    @Test fun emptyReviewIsNotCompletionAndExplicitRefreshHasNoPaginationOrAutoPoll() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(snapshot=h.scan.value.snapshot!!.copy(candidates=emptyList()))
        install(h);compose.onNodeWithTag("ds-empty").performScrollTo().assertTextContains("不代表",substring=true)
        compose.runOnIdle { assertTrue(h.scanIntents.isEmpty()) };click("ds-refresh-review")
        compose.runOnIdle { assertEquals(listOf(DnsScanPanelIntent.RefreshReview(h.f.scope)),h.scanIntents) }
        compose.onNodeWithTag("ds-next-page").assertDoesNotExist()
    }
    @Test fun usageNullAndZeroQuotaAreDistinctAndIndependentOfWritePermission() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(snapshot=h.scan.value.snapshot!!.copy(writeAllowed=null))
        install(h);compose.onNodeWithTag("ds-usage").performScrollTo().assertTextContains("8",substring=true).assertTextContains("限额未提供",substring=true)
        compose.onNodeWithTag("ds-preview-trigger").performScrollTo().assertIsNotEnabled()
        click("ds-refresh-usage")
        compose.runOnIdle { h.scan.value=h.scan.value.copy(usage=DnsScanUsage(1,DnsUsage(h.f.scope,8,0,JsonObject(emptyMap())))) }
        compose.onNodeWithTag("ds-usage").performScrollTo().assertTextContains("限额：0",substring=true);noConfirm(h)
    }
    @Test fun reviewAndUsageErrorsAreIsolatedBySectionScopeAndEpoch() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(errors=listOf(h.error(DnsTask7Section.SCAN_REVIEW,"REVIEW_403"),
            h.error(DnsTask7Section.USAGE,"OLD_ERROR").copy(epoch=0)))
        install(h);shown("ds-error-SCAN_REVIEW");shown("ds-usage");compose.onNodeWithText("OLD_ERROR").assertDoesNotExist();click("ds-refresh-usage")
        compose.runOnIdle { h.scan.value=h.scan.value.copy(errors=listOf(h.error(DnsTask7Section.USAGE,"USAGE_403"))) }
        scanPreview();shown("ds-confirm");noConfirm(h)
    }
    @Test fun actualUnicodeIdsHashesAndMutuallyExclusiveSelectionAreUsed() {
        val h=install();click("ds-accept-候选-甲");click("ds-reject-候选-甲")
        compose.runOnIdle { assertEquals(emptyList<String>(),h.scan.value.selection.acceptIds);assertEquals(listOf("候选-甲"),h.scan.value.selection.rejectIds) }
        click("ds-accept-srv-candidate");click("ds-preview-apply")
        compose.runOnIdle {
            val input=(h.scanIntents.filterIsInstance<DnsScanPanelIntent.Preview>().single().action as DnsScanAction.Apply).input
            assertEquals(listOf("srv-candidate"),input.acceptIds);assertEquals(listOf("候选-甲"),input.rejectIds)
            assertEquals(setOf("srv-candidate","候选-甲"),input.expectedCandidateHashes.keys)
        };noConfirm(h)
    }
    @Test fun futureAndUnknownNestedCandidatesCannotBeAcceptedButCanBeRejected() {
        val h=install()
        listOf("future-candidate","unknown-nested").forEach {
            compose.onNodeWithTag("ds-accept-$it").performScrollTo().assertIsNotEnabled();shown("ds-reason-$it")
        }
        click("ds-reject-future-candidate");click("ds-preview-apply");shown("ds-confirm");noConfirm(h)
    }
    @Test fun srvAndTxtAreDisplayedThroughRealProjectionWithoutRawMetadata() {
        install();compose.onNodeWithTag("ds-row-srv-candidate").performScrollTo().assertTextContains("443",substring=true).assertTextContains("sip.example.com",substring=true)
        compose.onNodeWithTag("ds-row-候选-甲").performScrollTo().assertTextContains("second line",substring=true)
        compose.onNodeWithText("never-a-request-field",substring=true).assertDoesNotExist()
    }
    @Test fun duplicateIdAndMalformedHashCannotEnterReviewSelection() {
        val h=Task7Harness();val first=h.f.candidates.first()
        h.scan.value=h.scan.value.copy(snapshot=h.scan.value.snapshot!!.copy(candidates=listOf(first,first.copy(selectionHash="bad"))))
        install(h);shown("ds-candidate-error");compose.onNodeWithTag("ds-preview-apply").performScrollTo().assertIsNotEnabled();noConfirm(h)
    }
    @Test fun tamperedScanPreviewIdsHashesNameTypeDataAndDomainCannotConfirm() {
        val h=install();click("ds-accept-srv-candidate");click("ds-preview-apply");val p=h.scan.value.prepared!!
        val doc=p.preview.records.single()
        val tampered=listOf(p.preview.copy(candidateHashes=emptyMap()),p.preview.copy(acceptIds=listOf("候选-甲")),p.preview.copy(domain="other.example"),
            p.preview.copy(records=listOf(doc.copy(raw=JsonObject(doc.raw+ ("name" to JsonPrimitive("other.example.com")))))),
            p.preview.copy(records=listOf(doc.copy(raw=JsonObject(doc.raw+("type" to JsonPrimitive("TXT")))))),
            p.preview.copy(records=listOf(doc.copy(raw=JsonObject(doc.raw+("data" to Json.parseToJsonElement("""{"priority":10,"weight":0,"port":8443,"target":"sip.example.com"}""")))))))
        tampered.forEach { v -> compose.runOnIdle { h.scan.value=h.scan.value.copy(prepared=p.copy(preview=v)) };compose.onNodeWithTag("ds-confirm").assertDoesNotExist() }
        shown("ds-preview-invalid");noConfirm(h)
    }
    @Test fun freshHashAndActualSelectionChangesNeverReviveOldPlan() {
        val h=install();scanPreview();val p=h.scan.value.prepared!!;val before=h.scan.value.snapshot!!
        compose.runOnIdle { h.scan.value=h.scan.value.copy(snapshot=before.copy(candidates=before.candidates.map { if(it.id=="候选-甲") it.copy(selectionHash="e".repeat(64)) else it })) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.runOnIdle { h.scan.value=h.scan.value.copy(snapshot=before,prepared=p) }
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun scanBackRetainsSelectionAndCancelDoesNotConfirmOrDeleteTasks() {
        val h=install();scanPreview();click("ds-back");click("ds-preview-apply");click("ds-cancel")
        compose.runOnIdle { assertEquals(listOf("候选-甲"),h.scan.value.selection.acceptIds) };shown("ds-header");noConfirm(h)
    }
    @Test fun sameScopeOldEpochHidesAllScanValuesErrorsPlansAndPermissions() {
        val h=install();scanPreview();compose.runOnIdle { h.outerEpoch.longValue=2 }
        shown("ds-context-mismatch");listOf("ds-usage","ds-preview-trigger","ds-confirm","ds-row-候选-甲").forEach { compose.onNodeWithTag(it).assertDoesNotExist() };noConfirm(h)
    }
    @Test fun wrongScopeHidesWholeSettingsSnapshotAndRefreshUsesActualScope() {
        val h=install(settings=true);settingsPreview()
        compose.runOnIdle { h.outerScope.value=h.f.scope.copy(zoneId="other-zone") }
        shown("dz-context-mismatch");compose.onNodeWithTag("dz-key-ns_ttl").assertDoesNotExist();compose.onNodeWithTag("dz-confirm").assertDoesNotExist();click("dz-refresh")
        compose.runOnIdle { assertEquals(DnsZoneSettingsPanelIntent.Refresh(h.outerScope.value),h.settingIntents.last()) };noConfirm(h)
    }
    @Test fun wrongOwnerDoesNotExposeSettingsOrScanningControls() {
        val h=Task7Harness();h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(owner=h.f.owner.copy(ref=h.f.scope.copy(zoneId="other"))))
        install(h,settings=true);shown("dz-owner-mismatch");compose.onNodeWithTag("dz-preview").assertDoesNotExist();noConfirm(h)
    }
    @Test fun eightKeysAreNativeAndFoundationAndUnknownRemainReadonly() {
        install(settings=true);DnsZoneSettingKey.entries.forEach { shown("dz-key-${it.wire}") }
        shown("dz-foundation");compose.onNodeWithTag("dz-foundation-editor").assertDoesNotExist();compose.onNodeWithTag("dz-json-editor").assertDoesNotExist()
        compose.onNodeWithText("never-a-request-field",substring=true).assertDoesNotExist()
    }
    @Test fun explicitFalseIsOnlyDeltaAndUntouchedValuesAreOmitted() {
        val h=Task7Harness();h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(settings=DnsZoneSettings(h.f.scope,JsonObject(h.f.settings.raw+("flatten_all_cnames" to JsonPrimitive(true))))))
        install(h,settings=true);click("dz-bool-flatten_all_cnames-false");click("dz-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"flatten_all_cnames":false}"""),h.settingIntents.filterIsInstance<DnsZoneSettingsPanelIntent.Preview>().single().action.changed) };noConfirm(h)
    }
    @Test fun invalidNumericTextStaysVisibleAndInvalidatesWithoutZeroOrPreview() {
        val h=install(settings=true);edit("dz-ns_ttl","oops");compose.onNodeWithTag("dz-ns_ttl").assertTextContains("oops")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled();shown("dz-validation")
        edit("dz-ns_ttl","");compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(h.settingIntents.any { it is DnsZoneSettingsPanelIntent.DraftInvalidated });assertTrue(h.settingIntents.none { it is DnsZoneSettingsPanelIntent.Preview }) };noConfirm(h)
    }
    @Test fun decimalsUsePublishedLosslessDeltaAndNotRecordTtlDefaults() {
        val h=install(settings=true);edit("dz-ns_ttl","300.0");compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
        edit("dz-ns_ttl","30.5");click("dz-preview")
        compose.runOnIdle { assertEquals("30.5",h.settingIntents.filterIsInstance<DnsZoneSettingsPanelIntent.Preview>().single().action.changed["ns_ttl"]!!.jsonPrimitive.content) };noConfirm(h)
    }
    @Test fun nameserverThreeBranchesDoNotLeakOldFieldsOrDefaultOptionalOne() {
        val h=install(settings=true);click("dz-ns-custom.account")
        compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
        edit("dz-ns-set","2");click("dz-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"type":"custom.account","ns_set":2}"""),h.settings.value.prepared!!.action.changed["nameservers"]) }
        click("dz-back");click("dz-ns-custom");edit("dz-set-id","a".repeat(32));click("dz-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"type":"custom","nameserver_set_id":"${"a".repeat(32)}"}"""),h.settings.value.prepared!!.action.changed["nameservers"]) }
        click("dz-back");click("dz-ns-cloudflare.advanced");click("dz-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"type":"cloudflare.advanced"}"""),h.settings.value.prepared!!.action.changed["nameservers"]) };noConfirm(h)
    }
    @Test fun customSetAndExistingSetRequireInputSpecificVerifiedEvidence() {
        val h=Task7Harness();h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(eligibility=h.settings.value.snapshot!!.eligibility.copy(ownedCustomSetIds=emptySet(),existingNameserverSets=emptyMap())))
        install(h,settings=true);click("dz-ns-custom");edit("dz-set-id","a".repeat(32));compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
        click("dz-ns-custom.account");edit("dz-ns-set","2");compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled();shown("dz-validation");noConfirm(h)
    }
    @Test fun nestedUnknownSemanticsIsReadonlyButTypedSoaNullAndInternalAreShown() {
        val h=install(settings=true)
        compose.onNodeWithTag("dz-read-soa").performScrollTo().assertTextContains("供应商分配",substring=true).assertTextContains("hostmaster.example.com",substring=true)
        compose.onNodeWithTag("dz-read-internal_dns").performScrollTo().assertTextContains("owned-reference-zone",substring=true)
        compose.onNodeWithTag("dz-soa-ttl").performScrollTo().assertIsNotEnabled();shown("dz-reason-soa");noConfirm(h)
    }
    @Test fun writePermissionAndFieldEligibilityAreIndependentAndReadStillWorks() {
        val h=Task7Harness();h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(writeAllowed=null))
        install(h,settings=true);shown("dz-readonly");shown("dz-key-zone_mode");compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(writeAllowed=true,eligibility=DnsSettingsEligibility())) }
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().assertIsNotEnabled();noConfirm(h)
    }
    @Test fun settingPreviewRequiresFrozenBeforeAfterAndActualChangedBinding() {
        val h=install(settings=true);settingsPreview();val p=h.settings.value.prepared!!
        val after=p.preview.after!!
        listOf(p.preview.copy(before=null),p.preview.copy(after=null),p.preview.copy(domain="other.example"),
            p.preview.copy(after=after.copy(nsTtl=JsonPrimitive(999)))).forEach { preview ->
            compose.runOnIdle { h.settings.value=h.settings.value.copy(prepared=p.copy(preview=preview)) };compose.onNodeWithTag("dz-confirm").assertDoesNotExist()
        };shown("dz-preview-invalid");noConfirm(h)
    }
    @Test fun twoRisksRequireTwoIndependentUserAcknowledgementsAndIdRevisionOnly() {
        val h=install(settings=true);click("dz-ns-cloudflare.advanced");click("dz-mode-dns_only");click("dz-preview")
        compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsNotEnabled();click("dz-risk-CHANGE_NAMESERVERS")
        compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsNotEnabled();noConfirm(h);click("dz-risk-CHANGE_ZONE_MODE")
        val p=h.settings.value.prepared!!;click("dz-confirm")
        compose.runOnIdle { assertEquals(DnsZoneSettingsPanelIntent.Confirm(p.planId,p.revision,DnsSettingsRisk.entries.toSet()),h.settingIntents.filterIsInstance<DnsZoneSettingsPanelIntent.Confirm>().single()) }
    }
    @Test fun backAndDraftChangeClearRiskAndKeepActualTextWithoutPlanRefill() {
        val h=install(settings=true);click("dz-mode-dns_only");edit("dz-ns_ttl","601");click("dz-preview");click("dz-risk-CHANGE_ZONE_MODE");click("dz-back")
        compose.onNodeWithTag("dz-ns_ttl").performScrollTo().assertTextContains("601");edit("dz-ns_ttl","602");click("dz-preview")
        compose.onNodeWithTag("dz-risk-CHANGE_ZONE_MODE").performScrollTo().assertIsOff();compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsNotEnabled();click("dz-cancel");noConfirm(h)
    }
    @Test fun permissionRestorationAndActualDraftChangeNeverReviveOldConfirm() {
        val h=install(settings=true);settingsPreview();val p=h.settings.value.prepared!!
        compose.runOnIdle { h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(writeAllowed=false)) }
        compose.onNodeWithTag("dz-confirm").assertDoesNotExist()
        compose.runOnIdle { h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(writeAllowed=true),prepared=p) }
        compose.onNodeWithTag("dz-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun everyPublishedMutationFamilyBlocksApplyAndSettingsWhileReadsRemainAvailable() {
        val h=install(settings=true)
        DNS_MUTATION_KEYS.forEach { key -> compose.runOnIdle { h.settings.value=h.settings.value.copy(pendingTasks=listOf(h.task(key))) };shown("dz-task-task");compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled() }
        click("dz-reconcile-task");click("dz-cancel")
        compose.runOnIdle { assertEquals(1,h.settings.value.pendingTasks.size);assertTrue(h.settingIntents.any { it is DnsZoneSettingsPanelIntent.Reconcile }) };noConfirm(h)
    }
    @Test fun triggerUnknownDoesNotBlockReadonlyReviewOrApplyInAnotherMutationFamily() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(pendingTasks=listOf(h.task(DNS_SCAN_TRIGGER_OPERATION)))
        install(h);compose.onNodeWithTag("ds-preview-trigger").performScrollTo().assertIsNotEnabled();click("ds-refresh-review");scanPreview();shown("ds-confirm");noConfirm(h)
    }
    @Test fun unknownApplyStaysReadonlyEvenIfReviewNowEmptyAndCancelCannotClearTask() {
        val h=Task7Harness();h.scan.value=h.scan.value.copy(pendingTasks=listOf(h.task(DNS_SCAN_APPLY_OPERATION)),snapshot=h.scan.value.snapshot!!.copy(candidates=emptyList()),
            outcome=DnsScanPanelOutcome(h.f.scope,1,DnsScanApplyOutcome(ExecutionReceipt.Unknown())))
        install(h);shown("ds-outcome");compose.onNodeWithTag("ds-preview-apply").performScrollTo().assertIsNotEnabled();click("ds-reconcile-task");click("ds-cancel")
        compose.runOnIdle { assertEquals(1,h.scan.value.pendingTasks.size) };noConfirm(h)
    }
    @Test fun otherZoneTasksAndOldEpochErrorsDoNotBlockCurrentSettingsDraft() {
        val h=Task7Harness();h.settings.value=h.settings.value.copy(pendingTasks=listOf(h.task(DNS_SETTINGS_PATCH_KEY,otherScope=h.f.scope.copy(zoneId="other"))),errors=listOf(h.error(DnsTask7Section.SETTINGS,"OLD_ERROR").copy(epoch=0)))
        install(h,settings=true);settingsPreview();shown("dz-confirm");compose.onNodeWithText("OLD_ERROR").assertDoesNotExist();noConfirm(h)
    }
    @Test fun sameScopeOldEpochHidesAllSettingsAndDoesNotReviveOldRiskOnReturn() {
        val h=install(settings=true);click("dz-mode-dns_only");click("dz-preview");click("dz-risk-CHANGE_ZONE_MODE")
        compose.runOnIdle { h.outerEpoch.longValue=2 };shown("dz-context-mismatch");compose.onNodeWithTag("dz-confirm").assertDoesNotExist()
        compose.runOnIdle { h.outerEpoch.longValue=1 };compose.onNodeWithTag("dz-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun narrowFontTwoScanConfirmationIsActuallyScrollable() {
        val h=install(font=2f);scanPreview();shown("ds-confirm");screenshot("scan-confirm-font2");click("ds-back");click("ds-cancel");noConfirm(h)
    }
    @Test fun narrowFontTwoSettingsRisksAndConfirmationAreActuallyScrollable() {
        val h=install(settings=true,font=2f);click("dz-ns-cloudflare.advanced");click("dz-mode-dns_only");click("dz-preview")
        click("dz-risk-CHANGE_NAMESERVERS");click("dz-risk-CHANGE_ZONE_MODE");shown("dz-confirm");screenshot("settings-confirm-font2");noConfirm(h)
    }
@Test fun wrongSettingsLocatorCannotConfirmDespiteMatchingScopeKindPlanAndRevision() {
    val h=install(settings=true);settingsPreview();val p=h.settings.value.prepared!!
    compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsEnabled()
    compose.runOnIdle {
        h.settings.value=h.settings.value.copy(prepared=p.copy(ref=p.ref.copy(externalId="tampered-locator")))
    }
    compose.onNodeWithTag("dz-confirm").assertDoesNotExist();noConfirm(h)
    // Core fake-preview fixture must obtain the legitimate ref from the newly published
    // dnsZoneSettingsResourceRef(actualAction, dnsZoneSettingsView(actualSnapshot.settings)).
    // Do not guess/keep the old joinToString separator or manufacture a locator.
}

@Test fun existingNameserverBeforeAndAfterShowTheirExactTypedSetNumbers() {
    val h=Task7Harness()
    val before=DnsZoneSettings(h.f.scope,JsonObject(h.f.settings.raw+("nameservers" to Json.parseToJsonElement("""{"type":"custom.account","ns_set":2}"""))))
    h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(settings=before))
    assertEquals(DnsNameservers.Existing("custom.account",2),dnsZoneSettingsView(before).nameservers)
    install(h,settings=true);click("dz-ns-custom.account");edit("dz-ns-set","3")
    compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"nameservers":{"type":"custom.account","ns_set":3}}"""),h.settings.value.draft!!.changed) }
    compose.onNodeWithTag("dz-preview").performScrollTo().assertIsEnabled();click("dz-preview")
    compose.runOnIdle {
        val plan=h.settings.value.prepared!!
        assertEquals(plan.action,h.settings.value.draft)
        assertTrue("legitimate Existing frozen projection",dnsZoneSettingsPreviewMatches(plan.action,plan.preview,dnsZoneSettingsView(before),h.f.owner.name))
        assertEquals(dnsZoneSettingsResourceRef(plan.action,dnsZoneSettingsView(before)),plan.ref)
    }
    compose.onNodeWithText("变更前：",substring=true).performScrollTo().assertTextContains("custom.account",substring=true).assertTextContains("集合序号=2",substring=true)
    compose.onNodeWithText("变更后：",substring=true).performScrollTo().assertTextContains("custom.account",substring=true).assertTextContains("集合序号=3",substring=true)
    noConfirm(h)
}

@Test fun customNameserverBeforeAndAfterShowTheirExactTypedSetIds() {
    val h=Task7Harness();val a="a".repeat(32);val b="b".repeat(32)
    val before=DnsZoneSettings(h.f.scope,JsonObject(h.f.settings.raw+("nameservers" to Json.parseToJsonElement("""{"type":"custom","nameserver_set_id":"$a"}"""))))
    val snapshot=h.settings.value.snapshot!!
    h.settings.value=h.settings.value.copy(snapshot=snapshot.copy(settings=before,eligibility=snapshot.eligibility.copy(ownedCustomSetIds=setOf(a,b))))
    install(h,settings=true,font=2f);edit("dz-set-id",b);click("dz-preview")
    compose.onNodeWithText("变更前：",substring=true).performScrollTo().assertTextContains("集合ID=$a",substring=true)
    compose.onNodeWithText("变更后：",substring=true).performScrollTo().assertTextContains("集合ID=$b",substring=true)
    screenshot("settings-custom-identity-font2");noConfirm(h)
}

@Test fun existingNameserverNeedsExplicitVerifiedSetAndNeverInventsDefaultOne() {
    val h=install(settings=true);click("dz-ns-custom.account")
    compose.onNodeWithTag("dz-ns-set").performScrollTo()
    assertEquals("",compose.onNodeWithTag("dz-ns-set").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    compose.onNodeWithTag("dz-preview").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag("dz-confirm").assertDoesNotExist()
    compose.onNodeWithTag("dz-validation").performScrollTo().assertTextContains("明确",substring=true)
    compose.runOnIdle { assertTrue(1 in h.settings.value.snapshot!!.eligibility.existingNameserverSets.getValue("custom.account"));assertTrue(h.settingIntents.none { it is DnsZoneSettingsPanelIntent.Preview }) }
    edit("dz-ns-set","2");click("dz-preview")
    compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"nameservers":{"type":"custom.account","ns_set":2}}"""),h.settingIntents.filterIsInstance<DnsZoneSettingsPanelIntent.Preview>().single().action.changed) }
    noConfirm(h)
}

    @Test fun asyncScanApplyPreservesActualSelectionThroughReviewAndApplyBusyUntilExplicitConfirm() {
        val h=Task7Harness(deferPreviews=true);install(h)
        click("ds-accept-srv-candidate");click("ds-preview-apply");compose.waitForIdle()
        compose.onNodeWithTag("ds-preview-apply").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(h.scan.value.prepared);assertTrue(DnsTask7Section.SCAN_REVIEW in h.scan.value.loading) }
        noConfirm(h)
        compose.runOnIdle { h.finishScanPreview() };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertExists().assertIsNotEnabled()
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=setOf(DnsTask7Section.SCAN_APPLY)) };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertExists().assertIsNotEnabled();noConfirm(h)
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=emptySet()) };compose.waitForIdle()
        val snapshot=h.scan.value.snapshot!!
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=setOf(DnsTask7Section.SCAN_APPLY)) };compose.waitForIdle()
        compose.runOnIdle { h.scan.value=h.scan.value.copy(snapshot=snapshot.copy(candidates=snapshot.candidates.map { if(it.id=="srv-candidate")it.copy(selectionHash="e".repeat(64)) else it })) };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist()
        compose.runOnIdle { h.scan.value=h.scan.value.copy(snapshot=snapshot,loading=emptySet()) };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").assertDoesNotExist();noConfirm(h)
        click("ds-preview-apply")
        compose.runOnIdle { h.finishScanPreview();h.scan.value=h.scan.value.copy(loading=emptySet()) };compose.waitForIdle()
        val plan=h.scan.value.prepared!!
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled();noConfirm(h)
        click("ds-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsScanPanelIntent.Confirm(plan.planId,plan.revision)),h.scanIntents.filterIsInstance<DnsScanPanelIntent.Confirm>()) }
    }
    @Test fun asyncScanTriggerPreservesRequestThroughTriggerAndAdapterReviewBusyUntilExplicitConfirm() {
        val h=Task7Harness(deferPreviews=true);install(h);click("ds-preview-trigger");compose.waitForIdle()
        compose.runOnIdle { assertNull(h.scan.value.prepared);assertTrue(DnsTask7Section.SCAN_REVIEW in h.scan.value.loading) }
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=setOf(DnsTask7Section.SCAN_TRIGGER)) };compose.waitForIdle()
        compose.onNodeWithTag("ds-preview-trigger").performScrollTo().assertIsNotEnabled();noConfirm(h)
        compose.runOnIdle { h.finishScanPreview() };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertExists().assertIsNotEnabled()
        // Current app adapter marks Scan prepare as SCAN_REVIEW even for Trigger.
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=setOf(DnsTask7Section.SCAN_REVIEW)) };compose.waitForIdle()
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertExists().assertIsNotEnabled();noConfirm(h)
        compose.runOnIdle { h.scan.value=h.scan.value.copy(loading=emptySet()) };compose.waitForIdle()
        val plan=h.scan.value.prepared!!
        compose.onNodeWithTag("ds-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled();click("ds-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsScanPanelIntent.Confirm(plan.planId,plan.revision)),h.scanIntents.filterIsInstance<DnsScanPanelIntent.Confirm>()) }
    }
    @Test fun asyncDnsSettingsKeepsQualifiedActualDraftAndOnlyExplicitRisksAcrossBusyCompositions() {
        val h=Task7Harness(deferPreviews=true);install(h,settings=true)
        click("dz-ns-cloudflare.advanced");click("dz-mode-dns_only")
        val actual=h.settings.value.draft!!;val clickRevision=h.settings.value.revision
        click("dz-preview");compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(h.settings.value.loading);assertNull(h.settings.value.prepared)
            assertEquals(actual,h.settings.value.draft)
            assertTrue("Model the existing adapter's legal prepare revision change without a UI +1 rule",h.settings.value.revision>clickRevision)
        }
        noConfirm(h)
        compose.runOnIdle { h.finishSettingsPreview() };compose.waitForIdle()
        compose.onNodeWithTag("dz-confirm").performScrollTo().assertExists().assertIsNotEnabled()
        DnsSettingsRisk.entries.forEach { compose.onNodeWithTag("dz-risk-${it.name}").performScrollTo().assertIsOff().assertIsNotEnabled() }
        compose.runOnIdle { h.settings.value=h.settings.value.copy(loading=false) };compose.waitForIdle()
        click("dz-risk-CHANGE_NAMESERVERS");compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.settings.value=h.settings.value.copy(loading=true) };compose.waitForIdle()
        compose.onNodeWithTag("dz-risk-CHANGE_NAMESERVERS").performScrollTo().assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("dz-risk-CHANGE_ZONE_MODE").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("dz-confirm").performScrollTo().assertIsNotEnabled();noConfirm(h)
        compose.runOnIdle { h.settings.value=h.settings.value.copy(loading=false) };compose.waitForIdle()
        compose.onNodeWithTag("dz-risk-CHANGE_NAMESERVERS").performScrollTo().assertIsOn()
        compose.onNodeWithTag("dz-risk-CHANGE_ZONE_MODE").performScrollTo().assertIsOff()
        click("dz-risk-CHANGE_ZONE_MODE");noConfirm(h)
        val eligibility=h.settings.value.snapshot!!.eligibility
        compose.runOnIdle { h.settings.value=h.settings.value.copy(loading=true) };compose.waitForIdle()
        compose.runOnIdle { h.settings.value=h.settings.value.copy(snapshot=h.settings.value.snapshot!!.copy(eligibility=eligibility.copy(nameserverTypes=emptySet()))) };compose.waitForIdle()
        compose.onNodeWithTag("dz-confirm").assertDoesNotExist()
        compose.runOnIdle { h.settings.value=h.settings.value.copy(loading=false,snapshot=h.settings.value.snapshot!!.copy(eligibility=eligibility)) };compose.waitForIdle()
        compose.onNodeWithTag("dz-confirm").assertDoesNotExist();noConfirm(h)
        click("dz-preview")
        compose.runOnIdle { h.finishSettingsPreview();h.settings.value=h.settings.value.copy(loading=false) };compose.waitForIdle()
        DnsSettingsRisk.entries.forEach { compose.onNodeWithTag("dz-risk-${it.name}").performScrollTo().assertIsOff() }
        click("dz-risk-CHANGE_NAMESERVERS");click("dz-risk-CHANGE_ZONE_MODE");noConfirm(h)
        val plan=h.settings.value.prepared!!;click("dz-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsZoneSettingsPanelIntent.Confirm(plan.planId,plan.revision,DnsSettingsRisk.entries.toSet())),h.settingIntents.filterIsInstance<DnsZoneSettingsPanelIntent.Confirm>()) }
    }

    private fun screenshot(name:String) {
        compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"task7-ui-panel-screenshots").apply { mkdirs() }
        i.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
}

private class Task7Harness(private val deferPreviews:Boolean=false) {
    val f=Task7DomainFixtures();val outerScope=mutableStateOf(f.scope);val outerEpoch=mutableLongStateOf(1)
    val scan=MutableStateFlow(DnsScanPanelState(scope=f.scope,epoch=1,snapshot=DnsScanSnapshot(f.owner,f.candidates,true),reviewLoaded=true,usage=DnsScanUsage(1,DnsUsage(f.scope,8,null,JsonObject(emptyMap())))))
    val eligibility=DnsSettingsEligibility(fields=DnsZoneSettingKey.entries.toSet(),nameserverTypes=setOf("cloudflare.standard","cloudflare.advanced","custom.account","custom.tenant","custom.zone","custom"),
        ownedCustomSetIds=setOf("a".repeat(32)),existingNameserverSets=mapOf("custom.account" to setOf(1,2,3,4,5),"custom.tenant" to setOf(1,2,3,4,5),"custom.zone" to setOf(1,2,3,4,5)))
    val settings=MutableStateFlow(DnsZoneSettingsPanelState(scope=f.scope,epoch=1,snapshot=DnsZoneSettingsSnapshot(f.owner,f.settings,true,eligibility)))
    val scanIntents=mutableListOf<DnsScanPanelIntent>();val settingIntents=mutableListOf<DnsZoneSettingsPanelIntent>();private var sequence=0
    private var pendingScan:DnsScanAction?=null
    private var pendingSetting:DnsZoneSettingsAction?=null
    fun finishScanPreview() {
        val s=scan.value;val action=pendingScan!!
        scan.value=s.copy(prepared=DnsScanPanelPreview("scan-plan-${++sequence}",s.revision,s.epoch,action,f.scanPreview(action),ResourceRef(f.scope,if(action is DnsScanAction.Trigger) "dns_scan_trigger" else "dns_scan_review",f.scope.zoneId)))
    }
    fun finishSettingsPreview() {
        val s=settings.value;val action=pendingSetting!!;val before=dnsZoneSettingsView(s.snapshot!!.settings)
        settings.value=s.copy(prepared=DnsSettingsPanelPreview("settings-plan-${++sequence}",s.revision,s.epoch,action,f.settingsPreview(action,before),dnsZoneSettingsResourceRef(action,before)))
    }
    fun error(section:DnsTask7Section,text:String)=DnsTask7Error(f.scope,1,section,text)
    fun task(key:String,state:TaskState=TaskState.UNKNOWN,otherScope:ZoneScope=f.scope)=OperationTask("task",otherScope.connectionId,otherScope,key,null,"a".repeat(64),null,state,"结果尚未核对",null,null,1,1)
    fun scanIntent(intent:DnsScanPanelIntent) {
        scanIntents+=intent;val s=scan.value
        when(intent) {
            is DnsScanPanelIntent.DraftInvalidated -> scan.value=s.copy(prepared=null,revision=s.revision+1)
            is DnsScanPanelIntent.SelectionChanged -> scan.value=s.copy(selection=intent.input)
            is DnsScanPanelIntent.Preview -> {
                pendingScan=intent.action
                if(deferPreviews) scan.value=s.copy(prepared=null,revision=s.revision+1,loading=setOf(DnsTask7Section.SCAN_REVIEW))
                else finishScanPreview()
            }
            is DnsScanPanelIntent.BackToDraft,is DnsScanPanelIntent.Cancel -> scan.value=s.copy(prepared=null)
            else -> Unit
        }
    }
    fun settingsIntent(intent:DnsZoneSettingsPanelIntent) {
        settingIntents+=intent;val s=settings.value
        when(intent) {
            is DnsZoneSettingsPanelIntent.DraftInvalidated -> settings.value=s.copy(prepared=null,draft=null,revision=s.revision+1)
            is DnsZoneSettingsPanelIntent.DraftChanged -> settings.value=s.copy(draft=intent.action)
            is DnsZoneSettingsPanelIntent.Preview -> {
                pendingSetting=intent.action
                if(deferPreviews) settings.value=s.copy(prepared=null,draft=intent.action,revision=s.revision+1,loading=true)
                else finishSettingsPreview()
            }
            is DnsZoneSettingsPanelIntent.BackToDraft,is DnsZoneSettingsPanelIntent.Cancel -> settings.value=s.copy(prepared=null)
            else -> Unit
        }
    }
}

/** Synthetic public domain inputs; every projection and validation uses real published helpers. */
private class Task7DomainFixtures {
    val scope = ZoneScope(AccountScope("scan-ui-connection", "scan-ui-account"), "scan-ui-zone")
    val owner = ZoneDetails(scope, "example.com", "active", false, "full", emptyList(), obj("""
        {"id":"scan-ui-zone","name":"example.com","account":{"id":"scan-ui-account"},"type":"full","status":"active"}
    """))
    // Fixed synthetic snapshot hashes; never compute DNS normalization/authorization here.
    // The real backend validates actual review hashes. These fixtures drive pure UI display binding only.
    val candidates = listOf(
        candidate("候选-甲", "a", """{"id":"候选-甲","type":"TXT","name":"txt.example.com","content":"quoted\nsecond line","ttl":300,"proxied":false,"meta":{"readonly_marker":"never-a-request-field"}}"""),
        candidate("srv-candidate", "b", """{"id":"srv-candidate","type":"SRV","name":"_sip._tcp.example.com","data":{"priority":10,"weight":0,"port":443,"target":"sip.example.com"},"ttl":3600}"""),
        candidate("future-candidate", "c", """{"id":"future-candidate","type":"FUTURE","name":"future.example.com","future_value":"readonly"}"""),
        candidate("unknown-nested", "d", """{"id":"unknown-nested","type":"SRV","name":"_unsafe._tcp.example.com","data":{"priority":10,"weight":0,"port":443,"target":"sip.example.com","future_nested":"must-not-strip"},"ttl":3600}""")
    )
    val settings = DnsZoneSettings(scope, obj("""
        {"flatten_all_cnames":false,"multi_provider":false,"secondary_overrides":false,"ns_ttl":300.0,"zone_mode":"standard",
         "nameservers":{"type":"cloudflare.standard"},
         "soa":{"expire":86400,"min_ttl":60,"mname":null,"refresh":600,"retry":600,"rname":"hostmaster.example.com","ttl":300},
         "internal_dns":{"reference_zone_id":"owned-reference-zone"},"foundation_dns":false,"future_readonly":"never-a-request-field"}
    """))
    // Every display/validation value comes from the published real pure helpers, not a fake validator.
    fun candidateViews() = candidates.map { dnsScanCandidateView(it, owner.name) }
    fun readView() = dnsZoneSettingsView(settings)
    fun selection(accept: List<String>, reject: List<String>) = DnsScanReviewInput(accept, reject,
        (accept + reject).associateWith { id -> candidates.single { it.id == id }.selectionHash })
    fun scanPreview(action: DnsScanAction): DnsScanPreview = when (action) {
        is DnsScanAction.Trigger -> DnsScanPreview(scope, owner.name, emptyList(), emptyList(), emptyList(),
            listOf("请求仅启动扫描，不自动接受候选；受理不代表扫描完成"))
        is DnsScanAction.Apply -> DnsScanPreview(scope, owner.name, action.input.acceptIds, action.input.rejectIds,
            (action.input.acceptIds + action.input.rejectIds).map { id -> candidates.single { it.id == id }.record },
            listOf("审核可能永久接受或拒绝当前候选；无CAS，不保证全网传播完成"), action.input.expectedCandidateHashes)
    }
    fun settingsPreview(action: DnsZoneSettingsAction, before: DnsSettingsReadView = readView()): DnsZoneSettingsPreview {
        val projection = dnsZoneSettingsDelta(before, action.changed)
        val risks = buildSet {
            if ("nameservers" in projection.changed) add(DnsSettingsRisk.CHANGE_NAMESERVERS)
            if ("zone_mode" in projection.changed) add(DnsSettingsRisk.CHANGE_ZONE_MODE)
        }
        return DnsZoneSettingsPreview(scope, owner.name, projection, risks,
            listOf("无CAS；预提交回读不保证与外部并发互斥"), before, dnsZoneSettingsAfter(before, projection))
    }
    fun actualChanged(text: String): DnsZoneSettingsAction {
        // Tests provide explicit known domain edits, never a provider metadata object.
        val changed = obj(text)
        projectDnsZoneSettings(changed) // Actual published pure shape validation, not permission.
        return DnsZoneSettingsAction(scope, changed)
    }
    private fun candidate(id: String, hashChar: String, text: String) =
        DnsScanCandidate(id, DnsRecordDocument(id, scope, obj(text)), hashChar.repeat(64))
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
}
