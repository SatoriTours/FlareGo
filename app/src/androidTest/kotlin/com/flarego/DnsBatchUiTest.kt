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
import com.flarego.core.application.dns.*
import com.flarego.core.application.management.CloudflareManagement
import com.flarego.core.application.zones.*
import com.flarego.core.application.AppController
import com.flarego.core.demo.DemoSession
import com.flarego.core.demo.demoConnections
import com.flarego.core.ports.MemoryStore
import com.flarego.core.model.*
import com.flarego.core.ports.SessionFactory
import com.flarego.ui.*
import com.flarego.ui.dns.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Panel/native form; synthetic public ports are not backend/runtime/DB/cloud evidence. */
class DnsBatchUiTest {
    @get:Rule val compose = createComposeRule()
    private val scope = ZoneScope(AccountScope("batch-ui-connection", "batch-ui-account"), "batch-ui-zone")
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun edit(tag: String, text: String) = compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
    private fun previews(h: BatchHarness) = h.intents.filterIsInstance<DnsBatchIntent.Preview>()
    private fun confirms(h: BatchHarness) = h.intents.filterIsInstance<DnsBatchIntent.Confirm>()
    private fun parsed(h: BatchHarness) = h.state.value.draft!!
    private fun install(h: BatchHarness = BatchHarness(scope), font: Float = 1f): BatchHarness {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                FlareGoTheme { Surface(Modifier.fillMaxSize()) {
                    val state by h.state.collectAsState()
                    DnsBatchPanel(h.expectedScope.value, state, h::onIntent, recordsScope = h.recordsScope.value, records = h.records)
                } }
            }
        }
        return h
    }
    private fun createTxt(name: String = "new", value: String = "text") {
        click("db-add-POST"); click("dns-type-TXT"); edit("dns-name", name); edit("dns-content", value); click("dns-preview")
    }
    private fun selectRecord(kind: DnsBatchKind, id: String) { click("db-add-${kind.name}"); click("db-select-$id") }

    @Test fun nativePostUses21TypesAndSrvStructuredFieldsWithoutSubmitting() {
        val h = install(); click("db-add-POST")
        listOf("A","AAAA","CAA","CERT","CNAME","DNSKEY","DS","HTTPS","LOC","MX","NAPTR","NS","OPENPGPKEY","PTR","SMIMEA","SRV","SSHFP","SVCB","TLSA","TXT","URI").forEach {
            compose.onNodeWithTag("dns-type-$it").assertExists()
        }
        click("dns-type-SRV"); edit("dns-name", "_sip._tcp")
        edit("dns-data-priority", "10"); edit("dns-data-weight", "0"); edit("dns-data-port", "443"); edit("dns-data-target", "sip.example.com")
        compose.onNodeWithText("加入批量草稿").performScrollTo().assertIsDisplayed()
        click("dns-preview")
        compose.runOnIdle {
            val input = parsed(h).posts.single()
            assertEquals("SRV", input.type); assertEquals("_sip._tcp.example.com", input.name)
            assertEquals(Json.parseToJsonElement("""{"data":{"priority":10,"weight":0,"port":443,"target":"sip.example.com"}}"""), input.fields)
            assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty())
        }
    }

    @Test fun deletePatchPutPostCountsAndOrderFollowGroupsAndUserOrder() {
        val h = install(); createTxt("second"); createTxt("first")
        selectRecord(DnsBatchKind.PUT,"put"); edit("dns-content","replacement"); click("dns-preview")
        selectRecord(DnsBatchKind.PATCH,"patch"); edit("dns-comment","changed"); click("dns-preview")
        selectRecord(DnsBatchKind.DELETE,"delete"); click("db-delete-add")
        compose.onNodeWithTag("db-counts").performScrollTo().assertTextContains("DELETE 1",substring=true).assertTextContains("PATCH 1",substring=true)
            .assertTextContains("PUT 1",substring=true).assertTextContains("POST 2",substring=true)
        click("db-preview")
        compose.runOnIdle {
            val p = h.state.value.preparedChange!!
            assertEquals(listOf(DnsBatchKind.DELETE,DnsBatchKind.PATCH,DnsBatchKind.PUT,DnsBatchKind.POST,DnsBatchKind.POST),p.targets.map { it.kind })
            assertEquals(listOf("second.example.com","first.example.com"),previews(h).single().input.posts.map { it.name })
            assertTrue(confirms(h).isEmpty())
        }
        compose.onNodeWithTag("db-target-DELETE-0").performScrollTo().assertTextContains("delete.example.com",substring=true).assertTextContains("delete",substring=true)
    }

    @Test fun patchOnlySendsExplicitNestedDeltaNotProviderProjection() {
        val h = install(); selectRecord(DnsBatchKind.PATCH,"srv"); edit("dns-data-port","8443"); click("dns-preview")
        compose.runOnIdle {
            assertEquals(Json.parseToJsonElement("""{"data":{"port":8443}}"""),parsed(h).patches.single().fields)
            assertEquals("srv",parsed(h).patches.single().id); assertTrue(confirms(h).isEmpty())
        }
    }

    @Test fun patchRetainsExplicitFalseZeroEmptyAndOmitsUntouchedValues() {
        val h = install(); selectRecord(DnsBatchKind.PATCH,"srv")
        edit("dns-data-weight","0"); edit("dns-comment",""); click("dns-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"data":{"weight":0},"comment":""}"""),parsed(h).patches.single().fields) }
        selectRecord(DnsBatchKind.PATCH,"proxy"); click("dns-proxied"); click("dns-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"proxied":false}"""),parsed(h).patches.last().fields); assertTrue(previews(h).isEmpty()) }
    }

    @Test fun patchNameIsExplicitAndTypeChangeRequiresPut() {
        val h = install(); selectRecord(DnsBatchKind.PATCH,"patch"); edit("dns-name","renamed"); click("dns-preview")
        compose.runOnIdle { assertEquals(Json.parseToJsonElement("""{"name":"renamed.example.com"}"""),parsed(h).patches.single().fields) }
        click("db-remove-PATCH-0"); selectRecord(DnsBatchKind.PATCH,"patch"); click("dns-type-A"); edit("dns-content","198.51.100.1"); click("dns-preview")
        compose.onNodeWithTag("db-editor-error").performScrollTo().assertTextContains("PUT",substring=true)
        compose.runOnIdle { assertTrue(parsed(h).patches.isEmpty()); assertTrue(previews(h).isEmpty()) }
    }

    @Test fun putIsIndependentFullReplacementWithDefaultsWarningAndExplicitConfirm() {
        val h = install(); selectRecord(DnsBatchKind.PUT,"put"); edit("dns-content","replacement"); click("dns-preview"); click("db-preview")
        compose.onNodeWithTag("db-semantics").performScrollTo().assertTextContains("默认",substring=true).assertTextContains("PUT",substring=true)
        compose.onNodeWithTag("db-target-PUT-0").performScrollTo().assertTextContains("replacement",substring=true).assertTextContains("before",substring=true)
        compose.runOnIdle { assertEquals(3600,previews(h).single().input.puts.single().input.fields["ttl"]!!.jsonPrimitive.int); assertTrue(confirms(h).isEmpty()) }
        val p = h.state.value.preparedChange!!; click("db-confirm")
        compose.runOnIdle { assertEquals(DnsBatchIntent.Confirm(p.id,p.revision),confirms(h).single()) }
    }

    @Test fun editingAndRemovingDraftItemsInvalidateWithoutCloudPreviewOrConfirm() {
        val h = install(); createTxt("first","original"); click("db-edit-POST-0"); edit("dns-content","updated"); click("dns-preview")
        compose.runOnIdle { assertEquals("updated",parsed(h).posts.single().fields["content"]!!.jsonPrimitive.content); assertTrue(previews(h).isEmpty()) }
        click("db-remove-POST-0")
        compose.runOnIdle { assertTrue(parsed(h).posts.isEmpty()); assertTrue(h.intents.any { it==DnsBatchIntent.DraftInvalidated }); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun clearingEditedNumericPatchDoesNotResendItsEarlierDraftValue() {
        val h=install();selectRecord(DnsBatchKind.PATCH,"srv");edit("dns-data-port","8443");click("dns-preview")
        click("db-edit-PATCH-0");edit("dns-data-port","");click("dns-preview")
        compose.onNodeWithTag("db-editor-error").assertExists()
        compose.onNodeWithTag("dns-data-port").performScrollTo().assertTextContains("")
        compose.runOnIdle { assertEquals(1,h.intents.filterIsInstance<DnsBatchIntent.DraftChanged>().size);assertTrue(previews(h).isEmpty());assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun missingOutcomeItemsKeepTheirExactPendingTargetsReadonly() {
        val h=BatchHarness(scope)
        h.mutable.value=h.state.value.copy(outcome=DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,emptyList(),
            listOf(DnsBatchItemRef(DnsBatchKind.POST,2,createType="TXT",createName="missing.example.com"))))
        install(h)
        compose.onNodeWithTag("db-pending-POST-2").performScrollTo().assertTextContains("missing.example.com",substring=true).assertTextContains("UNKNOWN",substring=true)
        compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty());assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun readonlyErrorStillOffersExplicitReadOnlyBindWithoutClearingUnknown() {
        val h=BatchHarness(scope);h.addTask("dns-records-for-a-zone-import-dns-records")
        h.mutable.value=h.state.value.copy(error="当前读取失败，任务仍未知")
        install(h);click("db-refresh")
        compose.runOnIdle { assertEquals(listOf(DnsBatchIntent.Bind(scope)),h.intents);assertTrue(confirms(h).isEmpty());assertTrue(previews(h).isEmpty()) }
    }

    @Test fun duplicateIdAcrossGroupsAndSameGroupAreRejectedLocally() {
        val h = install(); selectRecord(DnsBatchKind.DELETE,"delete"); click("db-delete-add")
        selectRecord(DnsBatchKind.DELETE,"delete"); click("db-delete-add")
        compose.onNodeWithTag("db-editor-error").performScrollTo().assertTextContains("重复",substring=true)
        click("db-editor-cancel"); selectRecord(DnsBatchKind.PATCH,"delete"); edit("dns-comment","change"); click("dns-preview")
        compose.onNodeWithTag("db-editor-error").performScrollTo().assertTextContains("重复",substring=true)
        compose.runOnIdle { assertEquals(listOf("delete"),parsed(h).deletes); assertTrue(parsed(h).patches.isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun semanticDuplicatePostsAreRejectedIncludingIpv6AndDefaults() {
        val h = install(); click("db-add-POST"); click("dns-type-AAAA"); edit("dns-name","ipv6"); edit("dns-content","2001:db8::1"); click("dns-preview")
        click("db-add-POST"); click("dns-type-AAAA"); edit("dns-name","IPV6.EXAMPLE.COM."); edit("dns-content","2001:0db8:0:0:0:0:0:1"); edit("dns-ttl","1"); click("dns-preview")
        compose.onNodeWithTag("db-editor-error").performScrollTo().assertTextContains("重复",substring=true)
        compose.runOnIdle { assertEquals(1,parsed(h).posts.size); assertTrue(previews(h).isEmpty()) }
    }

    @Test fun invalidUnparsedTextInvalidatesButCannotEnterDraftOrDefaultToZero() {
        val h = install(); selectRecord(DnsBatchKind.PATCH,"srv"); edit("dns-data-port","oops")
        compose.runOnIdle { assertEquals(DnsBatchIntent.DraftInvalidated,h.intents.last()) }
        click("dns-preview")
        compose.onNodeWithTag("dns-data-port").performScrollTo().assertTextContains("oops")
        compose.onNodeWithTag("dns-form-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(h.intents.none { it is DnsBatchIntent.DraftChanged || it is DnsBatchIntent.Preview }); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun unknownNestedAndUnsafePutDoNotRelaxExistingSafety() {
        val h = install(); selectRecord(DnsBatchKind.PATCH,"unsafe"); edit("dns-data-port","8443"); click("dns-preview")
        compose.onNodeWithTag("db-editor-error").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(h.intents.none { it is DnsBatchIntent.DraftChanged }) }
        click("db-editor-cancel"); click("db-add-PUT")
        compose.onNodeWithTag("db-select-unsafe").performScrollTo().assertIsNotEnabled()
    }

    @Test fun futureTypesAreReadonlyAndMismatchedRecordScopeHasNoChoices() {
        val h = install(); click("db-add-PATCH")
        compose.onNodeWithTag("db-select-future").performScrollTo().assertIsNotEnabled()
        click("db-editor-cancel")
        compose.runOnIdle { h.recordsScope.value = ZoneScope(scope.account,"other-zone") }
        click("db-add-DELETE")
        compose.onNodeWithTag("db-select-delete").assertDoesNotExist()
        compose.onNodeWithTag("db-records-reason").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()) }
    }

    @Test fun providerZoneNameIsRequiredAndNeverGuessedFromRecordOwner() {
        val h = BatchHarness(scope); h.mutable.value = h.state.value.copy(zoneName=null); install(h)
        compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("db-zone-name-required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-name").assertDoesNotExist()
    }

    @Test fun trustedLimitRejects201And3501WithoutSilentChunking() {
        val h = BatchHarness(scope); h.mutable.value=h.state.value.copy(draft=posts(201)); install(h)
        compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("db-limit").performScrollTo().assertTextContains("200",substring=true)
        compose.runOnIdle { h.mutable.value=h.state.value.copy(maxOperations=3500) }
        click("db-preview")
        compose.runOnIdle { assertEquals(201,previews(h).single().input.posts.size); assertTrue(confirms(h).isEmpty()) }
        click("db-back")
        compose.runOnIdle { h.mutable.value=h.state.value.copy(draft=posts(3501),editorRevision=h.state.value.editorRevision+1,preparedChange=null) }
        compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1,previews(h).size); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun backRetainsListAndTextCancelNeverConfirmsAndEditInvalidatesPlan() {
        val h=install(); createTxt("long","  \"value\"\nline  "); click("db-preview"); click("db-back")
        click("db-edit-POST-0"); compose.onNodeWithTag("dns-content").performScrollTo().assertTextContains("  \"value\"\nline  ")
        edit("dns-content","changed"); click("dns-preview"); click("db-preview")
        compose.runOnIdle { assertEquals("changed",previews(h).last().input.posts.single().fields["content"]!!.jsonPrimitive.content); assertTrue(confirms(h).isEmpty()) }
        click("db-cancel"); compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun wrongScopeHidesOldOutcomeErrorPlanAndDraft() {
        val h=install(); createTxt(); click("db-preview")
        compose.runOnIdle { h.mutable.value=h.state.value.copy(error="OLD_ERROR",outcome=DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,emptyList(),emptyList())); h.expectedScope.value=ZoneScope(AccountScope("new-connection","new-account"),"new-zone") }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.onNodeWithTag("db-outcome").assertDoesNotExist()
        compose.onAllNodesWithText("OLD_ERROR",substring=true).assertCountEquals(0)
        compose.onNodeWithTag("db-item-POST-0").assertDoesNotExist()
    }

    @Test fun staleRevisionOrPlanScopeCannotConfirm() {
        val h=install(); createTxt(); click("db-preview")
        compose.runOnIdle { h.mutable.value=h.state.value.copy(revision=h.state.value.revision+1) }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    private fun tamperedPatch(change:(DnsBatchPreviewItem)->DnsBatchPreviewItem) {
        val h=install();selectRecord(DnsBatchKind.PATCH,"patch");edit("dns-comment","changed");click("dns-preview");click("db-preview")
        compose.runOnIdle {
            val p=h.state.value.preparedChange!!
            h.mutable.value=h.state.value.copy(preparedChange=p.copy(targets=p.targets.map(change)))
        }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun tamperedPatchAfterContentCannotConfirmSameIdRevisionAndCounts() = tamperedPatch { item ->
        val after=item.after!!
        item.copy(after=after.copy(fields=JsonObject(after.fields+("content" to JsonPrimitive("other-content")))))
    }

    @Test fun tamperedPatchAfterCommentCannotConfirmSameIdRevisionAndCounts() = tamperedPatch { item ->
        val after=item.after!!
        item.copy(after=after.copy(fields=JsonObject(after.fields+("comment" to JsonPrimitive("other-comment")))))
    }

    @Test fun tamperedPatchAfterNameCannotConfirmSameIdRevisionAndCounts() = tamperedPatch { item ->
        item.copy(after=item.after!!.copy(name="other.example.com"))
    }

    @Test fun tamperedPatchAfterTypeCannotConfirmSameIdRevisionAndCounts() = tamperedPatch { item ->
        val after=item.after!!
        item.copy(after=after.copy(type="A",fields=JsonObject(after.fields+("content" to JsonPrimitive("198.51.100.9")))))
    }

    @Test fun tamperedPatchTargetLabelCannotConfirmAnOtherwiseCorrectAfter() = tamperedPatch { item ->
        item.copy(name="other.example.com",type="A")
    }

    @Test fun completeLegitimateNestedPatchProjectionRetainsBeforeFieldsAndCanConfirm() {
        val h=BatchHarness(scope)
        h.nextPatchProjection=DnsRecordInput("SRV","_sip._tcp.example.com",Json.parseToJsonElement("""{"data":{"priority":10,"weight":5,"port":8443,"target":"sip.example.com"},"ttl":3600,"comment":"before"}""").jsonObject)
        install(h);selectRecord(DnsBatchKind.PATCH,"srv");edit("dns-data-port","8443");click("dns-preview");click("db-preview")
        compose.onNodeWithTag("db-confirm").performScrollTo().assertIsEnabled()
        val p=h.state.value.preparedChange!!;click("db-confirm")
        compose.runOnIdle {
            assertEquals(DnsBatchIntent.Confirm(p.id,p.revision),confirms(h).single())
            assertEquals(Json.parseToJsonElement("""{"data":{"port":8443}}"""),previews(h).single().input.patches.single().fields)
        }
    }

    @Test fun transactionCommitIsSeparateFromCurrentVerificationAndGlobalPropagation() {
        val h=BatchHarness(scope)
        val ref=DnsBatchItemRef(DnsBatchKind.POST,0,createType="TXT",createName="new.example.com")
        h.mutable.value=h.state.value.copy(outcome=DnsBatchOutcome(DnsBatchTransaction.CONFIRMED_COMMIT,listOf(DnsBatchItemResult(ref,"provider-created",DnsBatchVerification.UNKNOWN)),listOf(ref)))
        install(h)
        compose.onNodeWithTag("db-outcome").performScrollTo().assertTextContains("CONFIRMED_COMMIT",substring=true)
        compose.onNodeWithTag("db-result-POST-0").performScrollTo().assertTextContains("UNKNOWN",substring=true)
        compose.onNodeWithTag("db-transaction-warning").performScrollTo().assertTextContains("传播非原子",substring=true)
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun rejectedTransactionMeansWholeBatchNotAppliedRatherThanPartialSuccess() {
        val h=BatchHarness(scope); h.mutable.value=h.state.value.copy(outcome=DnsBatchOutcome(DnsBatchTransaction.CONFIRMED_REJECT,
            listOf(DnsBatchItemResult(DnsBatchItemRef(DnsBatchKind.DELETE,0,"delete"),"delete",DnsBatchVerification.NOT_APPLIED)),emptyList()))
        install(h)
        compose.onNodeWithTag("db-outcome").performScrollTo().assertTextContains("整批未应用",substring=true)
        compose.onNodeWithTag("db-result-DELETE-0").performScrollTo().assertTextContains("NOT_APPLIED",substring=true)
    }

    @Test fun unknownTransactionWithMatchedItemsNeverClaimsHistoricalCommitOrOffersReplay() {
        val h=BatchHarness(scope); val ref=DnsBatchItemRef(DnsBatchKind.POST,0,createType="TXT",createName="new.example.com")
        h.mutable.value=h.state.value.copy(outcome=DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,listOf(DnsBatchItemResult(ref,"created",DnsBatchVerification.MATCHED)),listOf(ref)))
        h.addTask("dns-records-for-a-zone-batch-dns-records")
        install(h)
        compose.onNodeWithTag("db-outcome").performScrollTo().assertTextContains("UNKNOWN",substring=true).assertTextContains("不证明历史提交",substring=true)
        compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("db-retry").assertDoesNotExist(); compose.onNodeWithTag("db-recreate").assertDoesNotExist()
        click("db-reconcile")
        compose.runOnIdle { assertEquals(DnsBatchIntent.Reconcile("batch-task"),h.intents.last()); assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun singleBatchAndImportPendingFamilyAllBlockWithoutActionFilter() {
        val h=BatchHarness(scope); h.addTask("dns-records-for-a-zone-patch-dns-record"); install(h)
        listOf("dns-records-for-a-zone-patch-dns-record","dns-records-for-a-zone-batch-dns-records","dns-records-for-a-zone-import-dns-records").forEach { action ->
            compose.runOnIdle { h.addTask(action) }
            compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue(h.intents.isEmpty()) }
        click("db-reconcile"); compose.runOnIdle { assertEquals(DnsBatchIntent.Reconcile("batch-task"),h.intents.single()) }
    }

    @Test fun demoLabelOnlyFollowsCoreStateAndNarrowFont2ActionsAreReachable() {
        val h=BatchHarness(scope);h.mutable.value=h.state.value.copy(demo=true);install(h,font=2f)
        compose.onNodeWithTag("db-demo").performScrollTo().assertIsDisplayed()
        createTxt("very-long-record-name","  \"long TXT\"\n".repeat(25));click("db-preview")
        compose.onNodeWithTag("db-confirm").performScrollTo().assertIsDisplayed()
        screenshot("narrow-batch-preview")
        click("db-back");click("db-cancel")
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun dnsParentOptionalPortBindsSameScopeAndClearsOnScopeChange() {
        val h=BatchHarness(scope)
        val current=mutableStateOf(scope)
        compose.setContent { FlareGoTheme { DnsPage(DnsPageState(scope=current.value,zoneName="example.com",rows=h.records),{},batch=h) } }
        click("dns-open-batch")
        compose.runOnIdle { assertTrue(h.intents.any { it==DnsBatchIntent.Bind(scope) }) }
        compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { current.value=ZoneScope(scope.account,"other-zone") }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(h.intents.count { it==DnsBatchIntent.Clear }>=2);assertTrue(h.intents.any { it==DnsBatchIntent.Bind(current.value) }) }
    }

    @Test fun appOptionalBatchPortReachesSamePanelViaPublicManagementPorts() {
        val connection=demoConnections.first(); val account=AccountScope(connection.id,connection.accountId)
        val zone=ZoneDetails(ZoneScope(account,"batch-app-zone"),"example.com","active",false,"full",emptyList(),JsonObject(emptyMap()))
        val h=BatchHarness(zone.ref)
        val dns=object:DnsPagePort {
            val mutable=MutableStateFlow(DnsPageState())
            override val state:StateFlow<DnsPageState> = mutable
            override fun onIntent(intent:DnsIntent) { when(intent) { is DnsIntent.Load -> mutable.value=DnsPageState(scope=intent.scope,zoneName="example.com",rows=h.records); DnsIntent.Clear -> mutable.value=DnsPageState();else->Unit } }
        }
        val zones=object:ZonePagePort {
            val mutable=MutableStateFlow(ZonePageState())
            override val state:StateFlow<ZonePageState> = mutable
            override fun onIntent(intent:ZoneIntent) { when(intent) { is ZoneIntent.Load->mutable.value=ZonePageState(account=intent.account,zones=listOf(zone));is ZoneIntent.Open->mutable.value=mutable.value.copy(selected=zone);ZoneIntent.Clear->mutable.value=ZonePageState();else->Unit } }
        }
        compose.setContent { FlareGoTheme { val coroutine=rememberCoroutineScope();val controller=remember { AppController(coroutine,SessionFactory { DemoSession(it) },MemoryStore(),demoConnections) }
            FlareGoApp(controller,{_,_,_->},{},{},management=CloudflareManagement(dns,zones,h)) } }
        compose.onNodeWithTag("nav-domains").performClick();click("zone-row-batch-app-zone");compose.onNodeWithTag("zone-tab-dns").performClick();click("dns-open-batch")
        compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(h.intents.any { it==DnsBatchIntent.Bind(zone.ref) });assertTrue(confirms(h).isEmpty()) }
    }

    // Pending contract checkpoint. Insert into DnsBatchUiTest only after core compiles.
    // Synthetic backend-owned observation; this flag is never a UI control or send authority.
    private fun verifiedRecoveryHarness(verified: Boolean): BatchHarness {
        val h = BatchHarness(scope)
        val ref = DnsBatchItemRef(DnsBatchKind.POST, 0, createType = "TXT", createName = "recovered.example.com")
        h.addTask("dns-records-for-a-zone-batch-dns-records")
        h.mutable.value = h.state.value.copy(
            pendingTask = h.state.value.pendingTask!!.copy(state = TaskState.SUCCEEDED),
            outcome = DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,
                listOf(DnsBatchItemResult(ref, "provider-existing", DnsBatchVerification.MATCHED)), emptyList(),
                currentStateVerified = verified),
        )
        return h
    }

    @Test fun verifiedCurrentStateKeepsUnknownHistoryAndAllowsOnlyANewExplicitRequest() {
        val h = install(verifiedRecoveryHarness(true))
        compose.onNodeWithTag("db-outcome").performScrollTo().assertTextContains("UNKNOWN", substring = true)
            .assertTextContains("当前状态已匹配", substring = true).assertTextContains("不归因", substring = true)
        compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertTrue(h.intents.isEmpty()); assertTrue(confirms(h).isEmpty()) }
        createTxt("fresh-request", "new-explicit-value")
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
        click("db-preview")
        compose.runOnIdle { assertEquals(1, previews(h).size); assertTrue(confirms(h).isEmpty()) }
        val p = h.state.value.preparedChange!!
        click("db-confirm")
        compose.runOnIdle { assertEquals(DnsBatchIntent.Confirm(p.id, p.revision), confirms(h).single()) }
    }

    @Test fun matchedItemsAndSucceededTaskWithoutBackendProofRemainReadonly() {
        val h = install(verifiedRecoveryHarness(false))
        compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun backendProofCannotOverrideMissingItemsPendingConflictOrUnsettledTask() {
        val h = verifiedRecoveryHarness(true)
        val base = h.state.value
        val outcome = base.outcome!!
        h.mutable.value = base.copy(outcome = outcome.copy(items = emptyList()))
        install(h)
        val invalidStates = listOf(
            base.copy(outcome = outcome.copy(items = emptyList())),
            base.copy(outcome = outcome.copy(pending = listOf(outcome.items.single().ref))),
            base.copy(outcome = outcome.copy(items = outcome.items.map { it.copy(verification = DnsBatchVerification.UNKNOWN) })),
            base.copy(outcome = outcome.copy(items = outcome.items.map { it.copy(verification = DnsBatchVerification.CONFLICT) })),
            base.copy(pendingTask = base.pendingTask!!.copy(state = TaskState.UNKNOWN)),
        )
        invalidStates.forEach { invalid ->
            compose.runOnIdle { h.mutable.value = invalid }
            compose.onNodeWithTag("db-add-POST").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("db-preview").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue(previews(h).isEmpty()); assertTrue(confirms(h).isEmpty()) }
    }

    @Test fun verifiedRecoveryFromOtherScopeCannotExposeOldResultOrEnableDraft() {
        val h = verifiedRecoveryHarness(true)
        h.expectedScope.value = ZoneScope(AccountScope("new-connection", "new-account"), "new-zone")
        install(h)
        compose.onNodeWithTag("db-outcome").assertDoesNotExist()
        compose.onNodeWithTag("db-add-POST").assertDoesNotExist()
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(h.intents.isEmpty()) }
    }

    @Test fun verifiedCurrentRecoveryNeverRevivesThePreUnknownConfirmation() {
        val h = install(); createTxt("old-draft"); click("db-preview")
        val previous = h.state.value.preparedChange!!
        compose.runOnIdle { h.addTask("dns-records-for-a-zone-batch-dns-records") }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle {
            val ref = DnsBatchItemRef(DnsBatchKind.POST, 0, createType = "TXT", createName = "old-draft.example.com")
            h.mutable.value = h.state.value.copy(preparedChange = previous,
                pendingTask = h.state.value.pendingTask!!.copy(state = TaskState.SUCCEEDED),
                outcome = DnsBatchOutcome(DnsBatchTransaction.UNKNOWN,
                    listOf(DnsBatchItemResult(ref, "existing", DnsBatchVerification.MATCHED)), emptyList(), currentStateVerified = true))
        }
        compose.onNodeWithTag("db-confirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(confirms(h).isEmpty()) }
        createTxt("fresh-after-recovery"); click("db-preview")
        val next = h.state.value.preparedChange!!
        compose.runOnIdle { assertNotEquals(previous.id, next.id); assertTrue(confirms(h).isEmpty()) }
        click("db-confirm")
        compose.runOnIdle { assertEquals(DnsBatchIntent.Confirm(next.id, next.revision), confirms(h).single()) }
    }

    private fun posts(n:Int)=DnsBatchInput(posts=(0 until n).map { DnsRecordInput("TXT","item-$it.example.com",buildJsonObject { put("content","item-$it") }) })
    private fun screenshot(name:String) {
        compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"task5-panel-screenshots").apply { mkdirs() }
        i.uiAutomation.takeScreenshot()?.let { b->File(dir,"$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
    }
}

private class BatchHarness(val scope:ZoneScope):DnsBatchPagePort {
    val expectedScope=mutableStateOf(scope);val recordsScope=mutableStateOf<ZoneScope?>(scope)
    val intents=mutableListOf<DnsBatchIntent>();private var sequence=0
    var nextPatchProjection:DnsRecordInput?=null
    private fun fields(text:String)=Json.parseToJsonElement(text).jsonObject
    val records=listOf(
        DnsRecordView("srv","SRV","_sip._tcp.example.com",null,3600,false,null,fields("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com"},"ttl":3600,"comment":"before"}""")),
        DnsRecordView("delete","TXT","delete.example.com","delete",3600,false,null,fields("""{"content":"delete","ttl":3600,"comment":"before"}""")),
        DnsRecordView("patch","TXT","patch.example.com","before",3600,false,null,fields("""{"content":"before","ttl":3600,"comment":"before"}""")),
        DnsRecordView("put","TXT","put.example.com","before",3600,false,null,fields("""{"content":"before","ttl":3600}""")),
        DnsRecordView("proxy","A","proxy.example.com","198.51.100.4",1,true,null,fields("""{"content":"198.51.100.4","ttl":1,"proxied":true}""")),
        DnsRecordView("unsafe","SRV","_unsafe._tcp.example.com",null,3600,false,null,fields("""{"data":{"priority":10,"weight":5,"port":443,"target":"sip.example.com"},"ttl":3600}"""),unknownFields=setOf("data.future")),
        DnsRecordView("future","FUTURE","future.example.com","opaque",null,null,null,JsonObject(emptyMap()),readOnlyReason="未来类型只读"),
    )
    val mutable=MutableStateFlow(DnsBatchPageState(scope=scope,zoneName="example.com"))
    override val state:StateFlow<DnsBatchPageState> = mutable
    fun addTask(action:String) { mutable.value=state.value.copy(pendingTask=OperationTask("batch-task",scope.connectionId,scope,action,
        ResourceRef(scope,"dns_batch","batch"),"a".repeat(64),"b".repeat(64),TaskState.UNKNOWN,"结果未知，仅只读核对",null,null,1,1)) }
    override fun onIntent(intent:DnsBatchIntent) {
        intents+=intent;val s=state.value
        when(intent) {
            DnsBatchIntent.DraftInvalidated->mutable.value=s.copy(preparedChange=null,revision=s.revision+1)
            is DnsBatchIntent.DraftChanged->mutable.value=s.copy(draft=intent.input)
            DnsBatchIntent.BackToDraft->mutable.value=s.copy(preparedChange=null)
            DnsBatchIntent.Cancel->mutable.value=s.copy(preparedChange=null,editorRevision=s.editorRevision+1,draft=null)
            DnsBatchIntent.Clear->mutable.value=DnsBatchPageState(revision=s.revision+1,editorRevision=s.editorRevision+1)
            is DnsBatchIntent.Bind->mutable.value=DnsBatchPageState(scope=intent.scope,zoneName="example.com",revision=s.revision+1,editorRevision=s.editorRevision+1)
            is DnsBatchIntent.Preview->{
                val input=intent.input
                fun row(id:String)=records.first { it.id==id }.let { DnsRecordInput(it.type,it.name,it.fields) }
                val targets=input.deletes.mapIndexed { i,id->val r=row(id);DnsBatchPreviewItem(DnsBatchKind.DELETE,i,id,r.name,r.type,r,null) }+
                    input.patches.mapIndexed { i,p->val r=row(p.id);val after=nextPatchProjection?:r.copy(name=p.fields["name"]?.jsonPrimitive?.content?:r.name,fields=JsonObject(r.fields+(p.fields-"name"-"type")));DnsBatchPreviewItem(DnsBatchKind.PATCH,i,p.id,after.name,r.type,r,after) }+
                    input.puts.mapIndexed { i,p->DnsBatchPreviewItem(DnsBatchKind.PUT,i,p.id,p.input.name,p.input.type,row(p.id),p.input) }+
                    input.posts.mapIndexed { i,p->DnsBatchPreviewItem(DnsBatchKind.POST,i,null,p.name,p.type,null,p) }
                val counts=mapOf(DnsBatchKind.DELETE to input.deletes.size,DnsBatchKind.PATCH to input.patches.size,DnsBatchKind.PUT to input.puts.size,DnsBatchKind.POST to input.posts.size)
                mutable.value=s.copy(draft=input,preparedChange=DnsBatchPreview("batch-ui-plan-${++sequence}",s.revision,s.scope!!,counts,targets,listOf("服务端未提供CAS；预提交回读仍有竞争窗口")))
            }
            else->Unit
        }
    }
}
