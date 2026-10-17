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
import com.flarego.core.ports.HttpResponseHead
import com.flarego.ui.FlareGoTheme
import com.flarego.ui.dns.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose component; synthetic state/callbacks do not exercise SAF, runtime or Cloudflare. */
class DnsFilesUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = ZoneScope(AccountScope("files-ui-connection", "files-ui-account"), "files-ui-zone")
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun shown(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun install(h: FilesHarness = FilesHarness(scope), font: Float = 1f): FilesHarness {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    val state by h.state.collectAsState()
                    DnsFilesPanel(h.expectedScope.value, h.actualEpoch.longValue, state, h::intent)
                } }
            }
        }
        return h
    }
    private fun preview() { click("df-proxy-false"); click("df-preview") }
    private fun noConfirm(h: FilesHarness) = compose.runOnIdle { assertTrue(h.intents.none { it is DnsFilesPanelIntent.Confirm }) }

    @Test fun selectedMetadataIsBoundedAndOpaqueIdsAndBindContentAreNeverRendered() {
        install()
        compose.onNodeWithTag("df-file").performScrollTo().assertTextContains("zone.bind", substring=true)
            .assertTextContains("512", substring=true).assertTextContains("aaaaaaaaaaaa", substring=true)
        compose.onNodeWithText("opaque-import-id", substring=true).assertDoesNotExist()
        compose.onNodeWithTag("df-bind-editor").assertDoesNotExist()
        shown("df-header")
    }
    @Test fun selectingImportRequestsPickerWithoutPreparingOrSubmitting() {
        val h=install(); click("df-select-file")
        shown("df-selection")
        compose.runOnIdle {
            assertTrue(h.intents.any { it == DnsFilesPanelIntent.SelectImportFile(scope) })
            assertTrue(h.intents.none { it is DnsFilesPanelIntent.Preview || it is DnsFilesPanelIntent.Confirm })
        }
    }
    @Test fun proxiedMustBeChosenAndFalseIsAnActualDomainValue() {
        val h=install()
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        preview()
        val p=h.state.value.prepared!!
        compose.runOnIdle {
            assertEquals(DnsFilesImportRequest(scope,h.state.value.file!!,false),h.intents.filterIsInstance<DnsFilesPanelIntent.Preview>().single().request)
        }
        noConfirm(h); click("df-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsFilesPanelIntent.Confirm(p.planId,p.revision)),h.intents.filterIsInstance<DnsFilesPanelIntent.Confirm>()) }
    }
    @Test fun trueOptionChangesOnlyActualOptionAndInvalidatesPreviousPlan() {
        val h=install(); preview(); click("df-back"); click("df-proxy-true")
        compose.onNodeWithTag("df-confirm").assertDoesNotExist(); click("df-preview")
        compose.runOnIdle {
            assertTrue(h.intents.filterIsInstance<DnsFilesPanelIntent.Preview>().last().request.proxied)
            assertTrue(h.intents.any { it is DnsFilesPanelIntent.DraftInvalidated })
        }
        noConfirm(h)
    }
    @Test fun fileLimitIsInclusiveAndInvalidMetadataNeverPrepares() {
        val h=install(); click("df-proxy-false")
        listOf(262145L,0L,-1L).forEach { n ->
            compose.runOnIdle { h.state.value=h.state.value.copy(file=h.file.copy(length=n)) }
            compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { h.state.value=h.state.value.copy(file=h.file.copy(sha256="invalid")) }
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.state.value=h.state.value.copy(file=h.file.copy(length=262144)) }
        click("df-preview")
        compose.runOnIdle { assertEquals(262144L,h.intents.filterIsInstance<DnsFilesPanelIntent.Preview>().single().request.file.length) }
        noConfirm(h)
    }
    @Test fun unknownAndDeniedWritePermissionAreReadonlyButVerifiedExportIsIndependent() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(snapshot=h.state.value.snapshot!!.copy(writeAllowed=null))
        install(h)
        compose.onNodeWithTag("df-readonly").performScrollTo().assertTextContains("未知", substring=true)
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        click("df-select-export")
        compose.runOnIdle { assertTrue(h.intents.any { it is DnsFilesPanelIntent.SelectExportTarget }); h.state.value=h.state.value.copy(snapshot=h.state.value.snapshot!!.copy(writeAllowed=false)) }
        compose.onNodeWithTag("df-readonly").performScrollTo().assertTextContains("权限", substring=true)
        noConfirm(h)
    }
    @Test fun wrongOuterScopeHidesWholeOldStateAndRefreshUsesActualScope() {
        val h=install();preview()
        compose.runOnIdle {
            h.state.value=h.state.value.copy(errors=listOf(DnsFilesPanelError(scope,1,DnsFilesSection.IMPORT,"OLD_ERROR")),outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.Unknown()))
            h.expectedScope.value=scope.copy(zoneId="new-zone")
        }
        shown("df-context-mismatch")
        listOf("df-file","df-confirm","df-outcome","df-select-export").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("OLD_ERROR").assertDoesNotExist();click("df-refresh")
        compose.runOnIdle { assertEquals(DnsFilesPanelIntent.Refresh(h.expectedScope.value),h.intents.last()) }
        noConfirm(h)
    }
    @Test fun sameScopeOldEpochHidesFileErrorsOutcomePlanAndPermissions() {
        val h=install(); preview()
        compose.runOnIdle {
            h.state.value=h.state.value.copy(errors=listOf(DnsFilesPanelError(scope,1,DnsFilesSection.IMPORT,"OLD_EPOCH_ERROR")),outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.Unknown()))
            h.actualEpoch.longValue=2 // independently sourced outer epoch; old state remains epoch=1
        }
        shown("df-context-mismatch")
        listOf("df-file","df-confirm","df-outcome","df-preview","df-select-file","df-select-export","df-readonly").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithText("OLD_EPOCH_ERROR").assertDoesNotExist();click("df-refresh");noConfirm(h)
    }
    @Test fun ownerRefAndRawAccountMustMatchBeforeDisplayingFilesOrPermissions() {
        val h=FilesHarness(scope)
        h.state.value=h.state.value.copy(snapshot=h.state.value.snapshot!!.copy(owner=h.owner.copy(raw=buildJsonObject { put("id",scope.zoneId);put("name","example.com");putJsonObject("account") { put("id","other-account") } })))
        install(h);shown("df-owner-mismatch")
        compose.onNodeWithTag("df-file").assertDoesNotExist();compose.onNodeWithTag("df-preview").assertDoesNotExist();noConfirm(h)
    }
    @Test fun tamperedPreviewFileOptionDomainRefAndEpochCannotConfirm() {
        val h=install();preview();val original=h.state.value.prepared!!
        val bad=listOf(original.copy(preview=original.preview.copy(file=h.file.copy(sha256="b".repeat(64)))),
            original.copy(preview=original.preview.copy(proxied=true)),original.copy(preview=original.preview.copy(domain="other.example")),
            original.copy(resourceRef=ResourceRef(scope,"dns_record","record")),original.copy(epoch=0),original.copy(revision=999))
        bad.forEach { p ->
            compose.runOnIdle { h.state.value=h.state.value.copy(prepared=p) }
            compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        }
        shown("df-preview-invalid");noConfirm(h)
    }
    @Test fun backPreservesFileAndOptionAndCancellationDoesNotConfirm() {
        val h=install();preview();click("df-back")
        compose.onNodeWithTag("df-file").performScrollTo().assertTextContains("zone.bind",substring=true)
        click("df-preview");click("df-cancel-import")
        compose.onNodeWithTag("df-selection").performScrollTo().assertTextContains("取消",substring=true)
        compose.runOnIdle { assertFalse(h.state.value.proxied ?: true) /* cancel retains option as non executable UI preference */ }
        noConfirm(h)
    }
    @Test fun fileChangeInvalidatesPlanAndRestoringOldFileDoesNotReviveIt() {
        val h=install();preview();val old=h.state.value.prepared
        compose.runOnIdle { h.state.value=h.state.value.copy(file=h.file.copy(id="different-file")) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        compose.runOnIdle { h.state.value=h.state.value.copy(file=h.file,prepared=old) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun permissionLossAndRestorationNeverReviveOldConfirmation() {
        val h=install();preview();val old=h.state.value.prepared
        compose.runOnIdle { h.state.value=h.state.value.copy(snapshot=h.state.value.snapshot!!.copy(writeAllowed=false)) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        compose.runOnIdle { h.state.value=h.state.value.copy(snapshot=h.state.value.snapshot!!.copy(writeAllowed=true),prepared=old) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun scopedErrorsStayIsolatedBySectionScopeAndEpoch() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(errors=listOf(
            DnsFilesPanelError(scope,1,DnsFilesSection.IMPORT,"IMPORT_403"),DnsFilesPanelError(scope,0,DnsFilesSection.EXPORT,"OLD_ERROR"),
            DnsFilesPanelError(scope.copy(zoneId="other"),1,DnsFilesSection.IMPORT,"OTHER_ERROR")))
        install(h);shown("df-error-IMPORT");compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("OLD_ERROR").assertDoesNotExist();compose.onNodeWithText("OTHER_ERROR").assertDoesNotExist();click("df-select-export")
        compose.runOnIdle { h.state.value=h.state.value.copy(errors=listOf(DnsFilesPanelError(scope,1,DnsFilesSection.EXPORT,"EXPORT_ERROR"))) }
        preview();noConfirm(h)
    }
    @Test fun allMutationFamiliesUnknownAndPartialBlockOnlySameZoneAndAllowReadonlyReconcile() {
        val h=install(); click("df-proxy-false")
        listOf(DNS_IMPORT_OPERATION,"dns-records-for-a-zone-batch-dns-records","dns-records-for-a-zone-update-dns-record").forEachIndexed { i, action ->
            compose.runOnIdle { h.state.value=h.state.value.copy(pendingTasks=listOf(h.task(action,if(i==1) TaskState.PARTIAL else TaskState.UNKNOWN))) }
            shown("df-pending-task");compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled();click("df-reconcile-task")
        }
        click("df-cancel-import")
        compose.runOnIdle { assertEquals(1,h.state.value.pendingTasks.size);assertEquals(3,h.intents.filterIsInstance<DnsFilesPanelIntent.Reconcile>().size) }
        click("df-select-export");noConfirm(h)
    }
    @Test fun otherZoneTaskDoesNotBlockCurrentImport() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(pendingTasks=listOf(h.task(DNS_IMPORT_OPERATION,otherScope=scope.copy(zoneId="other"))))
        install(h);preview();shown("df-preview-details");compose.onNodeWithTag("df-pending-task").assertDoesNotExist();noConfirm(h)
    }
    @Test fun partialAndUnknownRemainReadonlyWithoutRecoveringPayloadOrReimporting() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.Partial(1,2,null)))
        install(h);compose.onNodeWithTag("df-outcome").performScrollTo().assertTextContains("PARTIAL",substring=true)
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.state.value=h.state.value.copy(outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.Unknown())) }
        compose.onNodeWithTag("df-outcome").performScrollTo().assertTextContains("UNKNOWN",substring=true)
        compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled();noConfirm(h)
    }
    @Test fun reportedSuccessSeparatesReceiptCurrentPageAndGlobalPropagation() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.ReportedSuccess(2,2,DnsImportReadback(scope,20,30))))
        install(h);compose.onNodeWithTag("df-outcome").performScrollTo().assertTextContains("供应商回执",substring=true).assertTextContains("20",substring=true).assertTextContains("全网",substring=true)
        compose.runOnIdle { h.state.value=h.state.value.copy(outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.ReportedSuccess(2,2,DnsImportReadback(scope.copy(zoneId="other"),20,30)))) }
        compose.onNodeWithTag("df-outcome").assertDoesNotExist();noConfirm(h)
    }
    @Test fun previewExplainsUnknownCountDirectivesPartialNoCasAndRateBoundary() {
        val h=install();preview()
        compose.onNodeWithTag("df-preview-details").performScrollTo().assertTextContains("记录数未知",substring=true).assertTextContains("example.com",substring=true)
        val warnings=compose.onNodeWithTag("df-import-warnings").performScrollTo()
        listOf("$"+"INCLUDE","$"+"ORIGIN","$"+"TTL","$"+"GENERATE","cf-proxied","262144","CAS","60","3","部分").forEach { warnings.assertTextContains(it,substring=true) }
        noConfirm(h)
    }
    @Test fun rejectedIncludeCancelledPickerAndLostGrantCannotReuseOldReadyFile() {
        val h=install();click("df-proxy-false")
        listOf(DnsFilesSelection.REJECTED,DnsFilesSelection.CANCELLED,DnsFilesSelection.PERMISSION_LOST).forEach { v ->
            compose.runOnIdle { h.state.value=h.state.value.copy(selection=v,errors=if(v==DnsFilesSelection.REJECTED) listOf(DnsFilesPanelError(scope,1,DnsFilesSection.IMPORT,"不支持 $"+"INCLUDE")) else emptyList()) }
            shown("df-selection");compose.onNodeWithTag("df-file").assertDoesNotExist();compose.onNodeWithTag("df-preview").performScrollTo().assertIsNotEnabled()
        }
        noConfirm(h)
    }
    @Test fun exportPermissionIsIndependentAndPickerDoesNotAutomaticallyExport() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(exportAllowed=null)
        install(h);compose.onNodeWithTag("df-export-readonly").performScrollTo().assertTextContains("未知",substring=true)
        compose.onNodeWithTag("df-select-export").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { h.state.value=h.state.value.copy(exportAllowed=true) };click("df-select-export")
        compose.runOnIdle { assertTrue(h.intents.none { it is DnsFilesPanelIntent.Export });h.state.value=h.state.value.copy(exportStage=DnsFilesExportStage.READY,exportTarget=h.target) }
        click("df-export")
        compose.runOnIdle { assertEquals(DnsFilesPanelIntent.Export(scope,h.target),h.intents.filterIsInstance<DnsFilesPanelIntent.Export>().single());assertEquals(DnsFilesExportStage.TEMPORARY,h.state.value.exportStage) }
        noConfirm(h)
    }
    @Test fun exportTemporaryCommittingSuccessCancellationAndLostPermissionAreDistinct() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(exportTarget=h.target)
        install(h)
        listOf(DnsFilesExportStage.TEMPORARY,DnsFilesExportStage.COMMITTING,DnsFilesExportStage.CANCELLED,DnsFilesExportStage.PERMISSION_LOST,DnsFilesExportStage.FAILED).forEach { stage ->
            compose.runOnIdle { h.state.value=h.state.value.copy(exportStage=stage,exportResult=h.exportResult()) }
            shown("df-export-stage");compose.onNodeWithTag("df-export-success").assertDoesNotExist()
        }
        compose.runOnIdle { h.state.value=h.state.value.copy(exportStage=DnsFilesExportStage.SUCCEEDED,exportResult=h.exportResult()) }
        compose.onNodeWithTag("df-export-success").performScrollTo().assertTextContains("完成",substring=true)
        noConfirm(h)
    }
    @Test fun exportResultMustBindActualScopeEpochTargetAndSuccessfulHead() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(exportStage=DnsFilesExportStage.SUCCEEDED,exportTarget=h.target,exportResult=h.exportResult())
        install(h);shown("df-export-success")
        val result=h.exportResult()
        listOf(result.copy(epoch=0),result.copy(target=h.target.copy(id="other")),result.copy(result=result.result.copy(scope=scope.copy(zoneId="other"))),
            result.copy(result=result.result.copy(head=result.result.head.copy(status=500)))).forEach { bad ->
            compose.runOnIdle { h.state.value=h.state.value.copy(exportResult=bad) }
            compose.onNodeWithTag("df-export-success").assertDoesNotExist()
        }
        shown("df-export-stage");noConfirm(h)
    }
    @Test fun narrowLargeFontScrollReachesSeparateConfirmationAndExportCancel() {
        val h=install(font=2f);preview();shown("df-import-warnings");shown("df-confirm")
        screenshot("narrow-import-confirm");noConfirm(h);click("df-back");click("df-select-export")
        compose.runOnIdle { h.state.value=h.state.value.copy(exportStage=DnsFilesExportStage.READY,exportTarget=h.target) }
        click("df-export");shown("df-cancel-export");screenshot("narrow-export-temporary");click("df-cancel-export")
        compose.onNodeWithTag("df-export-stage").performScrollTo().assertTextContains("取消",substring=true);noConfirm(h)
    }
    @Test fun actualOptionStateChangeRevokesPlanEvenWhenOldPreferenceReturns() {
        val h=install();preview();val old=h.state.value.prepared
        compose.runOnIdle { h.state.value=h.state.value.copy(proxied=true) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        compose.runOnIdle { h.state.value=h.state.value.copy(proxied=false,prepared=old) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun missingOwnerSnapshotIsReadonlyEvenWithFixtureTruePermissions() {
        val h=FilesHarness(scope);h.state.value=h.state.value.copy(snapshot=null,exportAllowed=true)
        install(h);compose.onNodeWithTag("df-owner-mismatch").performScrollTo().assertTextContains("尚无",substring=true)
        listOf("df-file","df-preview","df-confirm","df-select-export").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        noConfirm(h)
    }
    @Test fun lostFileGrantRestorationAndEpochRoundtripNeverReviveOldPlan() {
        val h=install();preview();val old=h.state.value.prepared
        compose.runOnIdle { h.state.value=h.state.value.copy(selection=DnsFilesSelection.PERMISSION_LOST) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        compose.runOnIdle { h.state.value=h.state.value.copy(selection=DnsFilesSelection.READY,prepared=old) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        click("df-preview")
        compose.runOnIdle { h.actualEpoch.longValue=2 }
        shown("df-context-mismatch")
        compose.runOnIdle { h.actualEpoch.longValue=1 }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun revisionChangeCannotReviveOldConfirmationWhenRevisionIsRestored() {
        val h=install();preview();val revision=h.state.value.revision
        compose.runOnIdle { h.state.value=h.state.value.copy(revision=revision+1) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist()
        compose.runOnIdle { h.state.value=h.state.value.copy(revision=revision) }
        compose.onNodeWithTag("df-confirm").assertDoesNotExist();noConfirm(h)
    }
    @Test fun asynchronousPreviewBusyCompositionKeepsOriginalRequestUntilExplicitConfirm() {
        val h=FilesHarness(scope,deferPreview=true)
        install(h);preview()
        compose.waitForIdle()
        compose.onNodeWithTag("df-readonly").performScrollTo().assertTextContains("正在",substring=true)
        compose.runOnIdle { assertTrue(h.state.value.loading);assertNull(h.state.value.prepared) }
        noConfirm(h)
        // Provider prepare returns while the adapter is still busy; this is a separate real composition.
        compose.runOnIdle { h.finishPreview(loading=true) }
        compose.waitForIdle()
        compose.onNodeWithTag("df-confirm").performScrollTo().assertExists().assertIsNotEnabled()
        noConfirm(h)
        compose.runOnIdle { h.state.value=h.state.value.copy(loading=false) }
        compose.waitForIdle()
        compose.onNodeWithTag("df-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val plan=h.state.value.prepared!!
        noConfirm(h);click("df-confirm")
        compose.runOnIdle { assertEquals(listOf(DnsFilesPanelIntent.Confirm(plan.planId,plan.revision)),h.intents.filterIsInstance<DnsFilesPanelIntent.Confirm>()) }
    }
    @Test fun preparedPreviewBusyRoundTripDisablesConfirmWithoutRevokingActualRequest() {
        val h=install();preview();shown("df-confirm")
        val plan=h.state.value.prepared!!
        compose.runOnIdle { h.state.value=h.state.value.copy(loading=true) }
        compose.waitForIdle()
        compose.onNodeWithTag("df-confirm").performScrollTo().assertExists().assertIsNotEnabled()
        noConfirm(h)
        compose.runOnIdle { h.state.value=h.state.value.copy(loading=false) }
        compose.waitForIdle()
        compose.onNodeWithTag("df-confirm").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(plan,h.state.value.prepared) };noConfirm(h)
    }
    @Test fun hardLossWhilePreviewPendingCannotReviveAfterValuesAndRevisionReturn() {
        val h=FilesHarness(scope,deferPreview=true,busyOnPreview=false)
        install(h);click("df-proxy-false")
        val base=h.state.value
        // Explicitly exercise a not-yet-prepared click: revision changes must invalidate even with no plan.
        val losses:List<(DnsFilesPanelState)->DnsFilesPanelState> = listOf(
            { it.copy(revision=it.revision+1) },
            { it.copy(snapshot=it.snapshot!!.copy(writeAllowed=null)) },
            { it.copy(snapshot=it.snapshot!!.copy(writeAllowed=false)) },
            { it.copy(epoch=2) },
            { it.copy(scope=scope.copy(zoneId="other")) },
            { it.copy(file=h.file.copy(sha256="b".repeat(64))) },
            { it.copy(proxied=true) },
            { it.copy(selection=DnsFilesSelection.PERMISSION_LOST) },
            { it.copy(snapshot=null) },
            { it.copy(pendingTasks=listOf(h.task(DNS_IMPORT_OPERATION,TaskState.UNKNOWN))) },
            { it.copy(outcome=DnsFilesImportObservation(scope,1,DnsImportOutcome.Partial(1,2,null))) },
            { it.copy(errors=listOf(DnsFilesPanelError(scope,1,DnsFilesSection.IMPORT,"IMPORT_403"))) },
        )
        losses.forEachIndexed { index,loss ->
            compose.runOnIdle { h.state.value=base }
            click("df-preview")
            compose.runOnIdle { assertNull(h.state.value.prepared);h.state.value=loss(h.state.value) }
            compose.waitForIdle()
            compose.onNodeWithTag("df-confirm").assertDoesNotExist()
            compose.runOnIdle { h.state.value=base;h.finishPreview(loading=false) }
            compose.waitForIdle()
            compose.onNodeWithTag("df-confirm").assertDoesNotExist()
            compose.onNodeWithTag("df-preview-invalid").performScrollTo().assertIsDisplayed()
            noConfirm(h)
        }
    }
    private fun screenshot(name:String) {
        compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"task6-ui-panel-screenshots").apply { mkdirs() }
        i.uiAutomation.takeScreenshot()?.let { b -> File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
}

private class FilesHarness(val scope:ZoneScope,private val deferPreview:Boolean=false,private val busyOnPreview:Boolean=true) {
    val expectedScope=mutableStateOf(scope)
    val actualEpoch=mutableLongStateOf(1L)
    val file=DnsFileRef("opaque-import-id","zone.bind",512,"a".repeat(64))
    val target=DnsExportTarget("opaque-target-id","export.bind")
    val owner=ZoneDetails(scope,"example.com","active",false,"full",emptyList(),buildJsonObject {
        put("id",scope.zoneId);put("name","example.com");putJsonObject("account") { put("id",scope.account.accountId) }
    })
    val state=MutableStateFlow(DnsFilesPanelState(scope=scope,epoch=1,snapshot=DnsFileImportState(owner,true),exportAllowed=true,selection=DnsFilesSelection.READY,file=file))
    val intents=mutableListOf<DnsFilesPanelIntent>()
    private var sequence=0
    private var pendingPreview:DnsFilesImportRequest?=null
    fun finishPreview(loading:Boolean) {
        val request=pendingPreview!!;val s=state.value
        state.value=s.copy(loading=loading,prepared=DnsFilesPanelPreview("files-plan-${++sequence}",s.revision,s.epoch,
            DnsImportPreview(scope,owner.name,request.file,request.proxied,null,listOf("核心预览：官方可能部分导入，无CAS")),request,ResourceRef(scope,"dns_import",scope.zoneId)))
    }
    fun task(action:String,state:TaskState=TaskState.UNKNOWN,otherScope:ZoneScope=scope)=OperationTask("task",otherScope.connectionId,otherScope,action,null,"a".repeat(64),null,state,"结果未知，只读核对",null,null,1,1)
    fun exportResult()=DnsFilesExportObservation(1,target,DnsExportResult(scope,file.copy(displayName="export.bind"),HttpResponseHead(200,mapOf("Content-Type" to listOf("text/plain")),file.length)))
    fun intent(intent:DnsFilesPanelIntent) {
        intents+=intent;val s=state.value
        when(intent) {
            is DnsFilesPanelIntent.DraftInvalidated -> state.value=s.copy(revision=s.revision+1,prepared=null)
            is DnsFilesPanelIntent.OptionsChanged -> state.value=s.copy(proxied=intent.proxied)
            is DnsFilesPanelIntent.Preview -> {
                pendingPreview=intent.request
                if(deferPreview) state.value=s.copy(prepared=null,loading=busyOnPreview)
                else finishPreview(loading=false)
            }
            is DnsFilesPanelIntent.BackToDraft -> state.value=s.copy(prepared=null)
            is DnsFilesPanelIntent.CancelImport -> state.value=s.copy(prepared=null,file=null,selection=DnsFilesSelection.CANCELLED,editorRevision=s.editorRevision+1)
            is DnsFilesPanelIntent.SelectImportFile -> state.value=s.copy(selection=DnsFilesSelection.SELECTING,prepared=null)
            is DnsFilesPanelIntent.SelectExportTarget -> state.value=s.copy(exportStage=DnsFilesExportStage.SELECTING,exportTarget=null,exportResult=null)
            is DnsFilesPanelIntent.Export -> state.value=s.copy(exportStage=DnsFilesExportStage.TEMPORARY)
            is DnsFilesPanelIntent.CancelExport -> state.value=s.copy(exportStage=DnsFilesExportStage.CANCELLED)
            else -> Unit
        }
    }
}
