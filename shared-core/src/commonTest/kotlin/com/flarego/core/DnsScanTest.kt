package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import kotlin.test.*
import com.flarego.core.application.operations.*
import com.flarego.core.application.dns.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.dns.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

class DnsScanTest {
    @Test fun sixFixedPoliciesHaveExactRoutesScopesRisksAndNoWriteRetry() {
        val rows = dnsScanRegistrations().associateBy { it.policy.key }
        val expected = mapOf(
            DNS_SCAN_REVIEW_OPERATION to ("GET" to "/zones/{zone_id}/dns_records/scan/review"),
            DNS_SCAN_APPLY_OPERATION to ("POST" to "/zones/{zone_id}/dns_records/scan/review"),
            DNS_SCAN_TRIGGER_OPERATION to ("POST" to "/zones/{zone_id}/dns_records/scan/trigger"),
            DNS_USAGE_OPERATION to ("GET" to "/zones/{zone_id}/dns_records/usage"),
            DNS_SETTINGS_GET_OPERATION to ("GET" to "/zones/{zone_id}/dns_settings"),
            DNS_SETTINGS_PATCH_OPERATION to ("PATCH" to "/zones/{zone_id}/dns_settings"))
        assertEquals(expected.keys, rows.keys)
        for ((key, pair) in expected) {
            val row=rows.getValue(key)
            assertEquals(pair.first,row.method);assertEquals(pair.second,row.pathTemplate)
            assertEquals(ActionScope.ZONE,row.scope);assertEquals(ResponseKind.ENVELOPE_JSON,row.policy.responseKind)
            assertEquals(if(pair.first=="GET") ActionSemantics.READ else ActionSemantics.WRITE,row.policy.semantics)
            if(pair.first!="GET") assertFalse(row.policy.retry.enabled)
        }
        assertEquals(RiskLevel.DESTRUCTIVE,rows.getValue(DNS_SCAN_APPLY_OPERATION).policy.risk)
    }
    @Test fun sixRegistrationsAreConnectedToRealDefaultGatewayRegistry() {
        assertEquals(6,dnsScanRegistrations().size)
        for(row in dnsScanRegistrations()) assertEquals(row,ActionPolicies.registry.registration(row.policy.key))
    }
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val zone = ZoneScope(AccountScope("local", "account-one"), "zone-one")
    private val hash = FingerprintPort { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun ok(result: JsonElement? = null, success: Boolean = true, errors: JsonArray = JsonArray(emptyList())) = HttpResponse(200, buildJsonObject {
        put("success",success);put("errors",errors);put("messages",JsonArray(emptyList()));result?.let { put("result",it) }
    }.toString())
    private fun record(id: String = "candidate-é", type: String = "A", name: String = "www.example.com", content: String = "192.0.2.1") = buildJsonObject {
        put("id",id);put("type",type);put("name",name);put("content",content);put("ttl",300);put("proxied",false)
        put("settings",JsonObject(emptyMap()));put("tags",JsonArray(emptyList()));put("meta",obj("""{"future_readonly":"keep"}"""))
        put("created_on","2026-10-07T00:00:00Z")
    }
    private class Store : OperationStore {
        val rows=mutableMapOf<String,OperationTask>()
        override fun get(id:String)=rows[id]
        override fun list(connectionId:String)=rows.values.filter { it.connectionId==connectionId }
        override fun put(task:OperationTask) { rows[task.id]=task }
        override fun putWithSummary(task:OperationTask,summary:Operation) { put(task) }
        override fun removeConnection(connectionId:String) { rows.entries.removeAll { it.value.connectionId==connectionId } }
    }
    private class Resolver : OperationContextResolver {
        var live:LiveOperationContext?=null;var valid=true
        override fun current()=live
        override fun liveConnections()=listOfNotNull(live?.connection)
        override fun isLive(scope:CloudScope,credentialRevision:Long?)=valid && (credentialRevision==null || credentialRevision==live?.credentialRevision)
    }
    private inner class Harness(val test:TestScope) {
        var now=1000L;var sequence=0
        val clock=OperationClock { now };val dispatcher=StandardTestDispatcher(test.testScheduler)
        val resolver=Resolver();val store=Store();val plans=ChangePlans(resolver,clock,OperationIds { "t7-${++sequence}" })
        val coordinator=OperationCoordinator(plans,store,resolver,clock,test.backgroundScope,dispatcher)
        val requests=mutableListOf<HttpRequest>()
        var ownerAccount=zone.account.accountId;var ownerType="full";var ownerId=zone.zoneId
        var onOwner:(()->Unit)?=null;var onSession:(()->Unit)?=null;var afterWrite:(()->Unit)?=null
        var authorityAllowed:Boolean?=true;var authorityThrows=false;var authorityCalls=0
        var eligibility=DnsSettingsEligibility(DnsZoneSettingKey.entries.toSet(),emptySet(),setOf("cloudflare.standard","cloudflare.advanced","custom.account","custom.tenant","custom.zone","custom"),setOf("a".repeat(32)))
        var scanConstraints=DnsConstraints();val constraintsScopes=mutableListOf<ZoneScope>()
        var candidates=mutableListOf(record());val permanent=mutableMapOf<String,JsonObject>()
        var settingsRaw=obj("""{"flatten_all_cnames":false,"foundation_dns":false,"multi_provider":false,"secondary_overrides":false,"ns_ttl":86400,"zone_mode":"standard","nameservers":{"type":"cloudflare.standard"},"internal_dns":{"reference_zone_id":"reference"},"soa":{"expire":604800,"min_ttl":1800,"mname":null,"refresh":10000,"retry":2400,"rname":"admin.example.com","ttl":3600},"future_top":{"keep":true}}""")
        var usageResult:JsonElement=obj("""{"record_usage":7,"record_quota":null}""")
        var writeResponse:HttpResponse?=null;var throwWrite=false;var badRead=false;var readFailure=false
        val transport=HttpTransport { request ->
            requests+=request
            val path=request.path.substringBefore('?')
            if(request.method=="GET") {
                if(readFailure && path.endsWith("dns_settings")) HttpResponse(403,"private-read-marker")
                else when {
                    path.endsWith("/scan/review") -> if(badRead) ok(JsonArray(listOf(JsonPrimitive("bad")))) else ok(JsonArray(candidates))
                    path.endsWith("/dns_records/usage") -> ok(usageResult)
                    path.endsWith("/dns_settings") -> ok(settingsRaw)
                    "/dns_records/" in path -> {
                        val id=java.net.URLDecoder.decode(path.substringAfterLast('/'),"UTF-8")
                        permanent[id]?.let { ok(it) } ?: HttpResponse(404,"not found")
                    }
                    path.endsWith("/dns_records") -> ok(JsonArray(permanent.values.toList()))
                    else -> { onOwner?.invoke();ok(buildJsonObject { put("id",ownerId);put("name","example.com");put("type",ownerType);put("status","active");put("paused",false);put("account",buildJsonObject { put("id",ownerAccount) }) }) }
                }
            } else {
                if(throwWrite) throw IllegalStateException("private-timeout-marker")
                writeResponse ?: when {
                    path.endsWith("/scan/trigger") -> { afterWrite?.invoke();ok() }
                    path.endsWith("/scan/review") -> {
                        val input=Json.parseToJsonElement(request.body!!).jsonObject
                        val accepted=(input["accepts"] as? JsonArray).orEmpty().mapIndexed { index,value ->
                            val doc=value.jsonObject
                            candidates.removeAll { it["name"]==doc["name"] && it["type"]==doc["type"] }
                            JsonObject(doc+mapOf("id" to JsonPrimitive("永久-é-$index"))).also { permanent["永久-é-$index"]=it }
                        }
                        val rejected=(input["rejects"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("id") }
                        candidates.removeAll { it["id"] in rejected };afterWrite?.invoke()
                        ok(buildJsonObject { put("accepts",JsonArray(accepted));put("rejects",JsonArray(rejected)) })
                    }
                    path.endsWith("/dns_settings") -> {
                        val changed=Json.parseToJsonElement(request.body!!).jsonObject
                        settingsRaw=JsonObject(settingsRaw+changed.mapValues { (key,value) ->
                            if(key in setOf("soa","internal_dns") && value is JsonObject) JsonObject(settingsRaw.getValue(key).jsonObject+value) else value
                        });afterWrite?.invoke();ok(settingsRaw)
                    }
                    else -> error("Unexpected Task7 write")
                }
            }
        }
        val gateway=CloudflareGateway(transport,sleeper={})
        val scanApi=CloudflareDnsScan(connection,gateway,hash,constraints={ scope -> constraintsScopes+=scope;if(scope==zone) scanConstraints else DnsConstraints() },authority=object:DnsScanAuthority {
            override suspend fun mayWrite(scope:ZoneScope):Boolean? { authorityCalls++;if(authorityThrows) error("private-authority-marker");return authorityAllowed }
        })
        val settingsApi=CloudflareDnsZoneSettings(connection,gateway,hash,object:DnsZoneSettingsAuthority {
            override suspend fun mayWrite(scope:ZoneScope):Boolean? { authorityCalls++;if(authorityThrows) error("private-authority-marker");return authorityAllowed }
            override suspend fun eligibility(scope:ZoneScope)=eligibility
        })
        val scan=DnsScanController(resolver,plans,coordinator,clock,hash,store,dispatcher) { onSession?.invoke();scanApi }
        val settings=DnsZoneSettingsController(resolver,plans,coordinator,clock,hash,store,dispatcher) { onSession?.invoke();settingsApi }
        val writes get()=requests.filter { it.method!="GET" }
        suspend fun selection(accept:List<String> = candidates.take(1).map { it.getValue("id").jsonPrimitive.content },reject:List<String> = emptyList()):DnsScanReviewInput {
            val actual=scanApi.review(zone).associate { it.id to it.selectionHash }
            return DnsScanReviewInput(accept,reject,(accept+reject).associateWith { actual.getValue(it) })
        }
        fun select(action:DnsScanAction) { resolver.live=LiveOperationContext(connection,action.scope,1,scan.requestFingerprint(action)) }
        fun select(action:DnsZoneSettingsAction) { resolver.live=LiveOperationContext(connection,action.scope,1,settings.requestFingerprint(action)) }
        suspend fun prepare(action:DnsScanAction):ChangePlan { select(action);return scan.prepare(action) }
        suspend fun prepare(action:DnsZoneSettingsAction):ChangePlan { select(action);return settings.prepare(action) }
        fun confirm(plan:ChangePlan)=plans.confirm(plan.id,plan.scope,plan.credentialRevision,plan.requestFingerprint,now)
        suspend fun submit(action:DnsScanAction):OperationTask { val p=prepare(action);return scan.submit(action,confirm(p)) }
        suspend fun submit(action:DnsZoneSettingsAction):OperationTask {
            val p=prepare(action);val risks=settings.preview(p.id).risks.map { settings.acknowledgeRisk(p.id,it) }.toSet()
            return settings.submit(action,confirm(p),risks)
        }
        fun restoreScan():DnsScanController {
            val p=ChangePlans(resolver,clock,OperationIds { "restored-${++sequence}" })
            return DnsScanController(resolver,p,OperationCoordinator(p,store,resolver,clock,test.backgroundScope,dispatcher),clock,hash,store,dispatcher) { scanApi }
        }
        fun restoreSettings():DnsZoneSettingsController {
            val p=ChangePlans(resolver,clock,OperationIds { "restored-${++sequence}" })
            return DnsZoneSettingsController(resolver,p,OperationCoordinator(p,store,resolver,clock,test.backgroundScope,dispatcher),clock,hash,store,dispatcher) { settingsApi }
        }
        fun oldTask(key:String,reference:ResourceRef?=null,scope:ZoneScope=zone,state:TaskState=TaskState.UNKNOWN,id:String="old") =
            OperationTask(id,scope.connectionId,scope,key,reference,"a".repeat(64),"b".repeat(64),state,"未确认任务",null,null,now,now)
    }
    @Test fun reviewUsesOnlyRealScanRouteWithoutPagingAndPreservesFutureRaw()=runTest {
        val h=Harness(this);h.authorityThrows=true
        h.candidates+=record("future","FUTURE")
        val rows=h.scanApi.review(zone)
        assertEquals(listOf("candidate-é","future"),rows.map { it.id });assertEquals("FUTURE",rows.last().record.raw["type"]!!.jsonPrimitive.content)
        assertTrue(rows.last().record.raw.containsKey("meta"));assertEquals(0,h.authorityCalls)
        assertEquals(listOf("/zones/zone-one/dns_records/scan/review"),h.requests.filter { "dns_records" in it.path }.map { it.path })
    }
    @Test fun emptyReviewDoesNotCompleteScanAndRefreshCanDiscoverMore()=runTest {
        val h=Harness(this);h.candidates.clear();assertTrue(h.scanApi.review(zone).isEmpty())
        h.candidates+=record();assertEquals(1,h.scanApi.review(zone).size);assertEquals(0,h.writes.size)
    }
    @Test fun trigger200WithoutResultIsOnlyAcceptedAndDoesNotInventPollPath()=runTest {
        val h=Harness(this);val receipt=assertIs<ExecutionReceipt.Accepted>(h.scanApi.trigger(zone))
        assertNull(receipt.pollPath);assertEquals(1,h.writes.size);assertNull(h.writes.single().body)
        assertTrue(h.writes.single().path.endsWith("/scan/trigger"))
    }
    @Test fun acceptsAreRecordObjectsWithoutIdsAndRejectRequestObjectsDifferFromResponseStrings()=runTest {
        val h=Harness(this);val text="  \"quoted\"\n\nTXT stays exact  "
        h.candidates=mutableListOf(record("候选-é","TXT",content=text),record("reject-é",name="reject.example.com"))
        val result=h.scanApi.apply(zone,h.selection(listOf("候选-é"),listOf("reject-é")))
        assertIs<ExecutionReceipt.Completed>(result.receipt)
        assertEquals(listOf("reject-é"),result.rejected)
        val body=Json.parseToJsonElement(h.writes.single().body!!).jsonObject
        val accept=body.getValue("accepts").jsonArray.single().jsonObject
        assertEquals(text,accept["content"]!!.jsonPrimitive.content);assertEquals(JsonPrimitive(false),accept["proxied"])
        assertFalse(accept.keys.any { it in setOf("id","meta","candidate_id","created_on") })
        assertEquals(JsonPrimitive("reject-é"),body.getValue("rejects").jsonArray.single().jsonObject["id"])
        assertTrue(h.requests.any { "/dns_records/%" in it.path })
    }
    @Test fun acceptingStructuredSrvKeepsDataInsteadOfFormattedContent()=runTest {
        val h=Harness(this)
        h.candidates=mutableListOf(JsonObject(record(type="SRV",name="_sip._tcp.example.com",content="provider formatted")+mapOf("data" to obj("""{"port":443,"priority":10,"weight":20,"target":"target.example.com"}"""))))
        val result=h.scanApi.apply(zone,h.selection());assertIs<ExecutionReceipt.Completed>(result.receipt)
        val sent=Json.parseToJsonElement(h.writes.single().body!!).jsonObject["accepts"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(443),sent["data"]!!.jsonObject["port"]);assertFalse(sent.containsKey("content"))
    }
    @Test fun futureOrUnknownWritableAndNestedCandidatesRefuseAcceptButCanBeRejectedById()=runTest {
        for(mode in 0..2) {
            val h=Harness(this)
            h.candidates[0]=when(mode) { 0->record(type="FUTURE");1->JsonObject(record()+mapOf("future_write" to JsonPrimitive(true)));else->JsonObject(record()+mapOf("settings" to obj("""{"future":true}"""))) }
            val selected=h.selection();assertFailsWith<CloudFailure> { h.scanApi.apply(zone,selected) };assertEquals(0,h.writes.size)
            val rejected=h.scanApi.apply(zone,h.selection(emptyList(),listOf("candidate-é")))
            assertIs<ExecutionReceipt.Completed>(rejected.receipt);assertEquals(1,h.writes.size)
        }
    }
    @Test fun duplicateSemanticCandidatesCannotBePartiallyAcceptedWithInventedIdFields()=runTest {
        val h=Harness(this);h.candidates+=JsonObject(record()+mapOf("id" to JsonPrimitive("duplicate")))
        assertFailsWith<CloudFailure> { h.scanApi.apply(zone,h.selection()) };assertEquals(0,h.writes.size)
    }
    @Test fun selectionShapeAndChangedCandidateHashesFailBeforeWrite()=runTest {
        val h=Harness(this);val s=h.selection()
        val invalid=listOf(s.copy(acceptIds=listOf("candidate-é","candidate-é")),s.copy(rejectIds=listOf("candidate-é")),s.copy(expectedCandidateHashes=emptyMap()),s.copy(acceptIds=listOf("x".repeat(33))))
        for(input in invalid) assertFailsWith<CloudFailure> { h.scanApi.apply(zone,input) }
        h.candidates[0]=record(content="192.0.2.2")
        assertFailsWith<CloudFailure> { h.scanApi.apply(zone,s) };assertEquals(0,h.writes.size)
    }
    @Test fun ownerEvidenceIsIndependentAndWrongAccountOrZoneNeverWrites()=runTest {
        for(mode in 0..1) {
            val h=Harness(this);if(mode==0)h.ownerAccount="foreign" else h.ownerId="wrong-zone"
            assertFailsWith<CloudFailure> { h.scanApi.trigger(zone) };assertEquals(0,h.writes.size)
            assertFailsWith<CloudFailure> { h.settingsApi.settings(zone) }
        }
    }
    @Test fun defaultUnknownAuthoritiesAreReadOnlyAndReadsNeverConsultFailingWriteProbe()=runTest {
        val h=Harness(this);h.authorityThrows=true
        assertEquals(1,h.scanApi.review(zone).size);assertEquals(7L,h.settingsApi.usage(zone).recordUsage)
        assertTrue(h.settingsApi.settings(zone).raw.containsKey("foundation_dns"));assertEquals(0,h.authorityCalls)
        val scan=CloudflareDnsScan(connection,h.gateway,hash);val settings=CloudflareDnsZoneSettings(connection,h.gateway,hash)
        assertFailsWith<CloudFailure> { scan.trigger(zone) }
        assertFailsWith<CloudFailure> { settings.updateSettings(zone,obj("""{"multi_provider":true}""")) };assertEquals(0,h.writes.size)
    }
    @Test fun usageQuotaNullAndZeroRemainDifferentWithoutPlanInference()=runTest {
        val h=Harness(this);assertNull(h.settingsApi.usage(zone).recordQuota)
        h.usageResult=obj("""{"record_usage":0,"record_quota":0}""")
        assertEquals(0L,h.settingsApi.usage(zone).recordQuota);assertEquals(0L,h.settingsApi.usage(zone).recordUsage)
    }
    @Test fun invalidUsageMissingQuotaNegativeFractionStringOrOverflowIsRejected()=runTest {
        for(value in listOf("""{"record_usage":1}""","""{"record_usage":-1,"record_quota":2}""","""{"record_usage":1.5,"record_quota":2}""","""{"record_usage":"1","record_quota":2}""","""{"record_usage":9223372036854775808,"record_quota":null}""")) {
            val h=Harness(this);h.usageResult=obj(value);assertFailsWith<CloudFailure> { h.settingsApi.usage(zone) }
        }
    }
    @Test fun projectValidatesTypedSoaInternalAndNameserverBranchesWithoutGrantingWrite()=runTest {
        val h=Harness(this)
        val p=h.settings.project(obj("""{"multi_provider":false,"soa":{"ttl":300,"mname":null},"internal_dns":{"reference_zone_id":"参考-zone"},"nameservers":{"type":"custom","nameserver_set_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"""))
        assertEquals(JsonNull,p.soa!!.mname);assertEquals(JsonPrimitive(300),p.soa!!.ttl)
        assertEquals("参考-zone",p.internalDns!!.referenceZoneId);assertIs<DnsNameservers.CustomSet>(p.nameservers)
        assertEquals(JsonPrimitive(false),p.changed["multi_provider"]);assertEquals(0,h.writes.size)
    }
    @Test fun settingsProjectionRejectsDeprecatedUnknownNullAndBadNestedShape()=runTest {
        val h=Harness(this)
        for(value in listOf("""{"foundation_dns":true}""","""{"future":true}""","{}","""{"multi_provider":null}""","""{"soa":{"ttl":299}}""","""{"soa":{"retry":86401}}""","""{"soa":{"mname":7}}""","""{"soa":{"rname":null}}""","""{"internal_dns":{"reference_zone_id":null}}""","""{"soa":{"future":1}}""")) assertFailsWith<CloudFailure> { h.settings.project(obj(value)) }
    }
    @Test fun nameserversAdditionalPropertiesBranchEnumsSetRangesAndIdPatternAreEnforced()=runTest {
        val h=Harness(this)
        for(value in listOf("""{"type":"cloudflare.standard","ns_set":1}""","""{"type":"custom.account","ns_set":6}""","""{"type":"custom.account","ns_set":1.5}""","""{"type":"custom"}""","""{"type":"custom","nameserver_set_id":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}""","""{"type":"future"}""")) assertFailsWith<CloudFailure> { h.settings.project(obj("""{"nameservers":$value}""")) }
        assertIs<DnsNameservers.Cloudflare>(h.settings.project(obj("""{"nameservers":{"type":"cloudflare.advanced"}}""")).nameservers)
        assertIs<DnsNameservers.Existing>(h.settings.project(obj("""{"nameservers":{"type":"custom.account","ns_set":1}}""")).nameservers)
    }
    @Test fun scalarPatchSendsOnlyActualDeltaAndFalseIsNotOmitted()=runTest {
        val h=Harness(this);h.settingsRaw=JsonObject(h.settingsRaw+mapOf("multi_provider" to JsonPrimitive(true)))
        val task=h.submit(DnsZoneSettingsAction(zone,obj("""{"multi_provider":false,"ns_ttl":86400.0}""")))
        assertEquals(TaskState.SUCCEEDED,task.state)
        assertEquals(obj("""{"multi_provider":false}"""),Json.parseToJsonElement(h.writes.single().body!!))
        assertEquals(obj("""{"keep":true}"""),h.settingsRaw["future_top"])
    }
    @Test fun settingsNumericallyEquivalentProviderReadbackDoesNotBecomeUnknown()=runTest {
        val h=Harness(this);h.afterWrite={ h.settingsRaw=JsonObject(h.settingsRaw+mapOf("ns_ttl" to JsonPrimitive(3600.0))) }
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,obj("""{"ns_ttl":3600}"""))).state)
    }
    @Test fun noChangesOrUnverifiedNestedSemanticsNeverIssuesExecutablePlan()=runTest {
        val h=Harness(this)
        assertFailsWith<CloudFailure> { h.prepare(DnsZoneSettingsAction(zone,obj("""{"multi_provider":false}"""))) }
        for(value in listOf("""{"soa":{"ttl":400}}""","""{"internal_dns":{"reference_zone_id":"different"}}""")) {
            val failure=assertFailsWith<CloudFailure> { h.prepare(DnsZoneSettingsAction(zone,obj(value))) }
            assertTrue(failure.publicMessage.contains("needs_review"))
        }
        assertEquals(0,h.writes.size)
    }
    @Test fun verifiedNestedPatchPreservesSiblingsButUnknownSiblingsRefuseWrite()=runTest {
        val h=Harness(this);h.eligibility=h.eligibility.copy(nestedPatchSemanticsVerified=setOf(DnsZoneSettingKey.SOA,DnsZoneSettingKey.INTERNAL_DNS),verifiedReferenceZoneIds=setOf("参考-zone"))
        val a=DnsZoneSettingsAction(zone,obj("""{"soa":{"ttl":400},"internal_dns":{"reference_zone_id":"参考-zone"}}"""))
        assertEquals(TaskState.SUCCEEDED,h.submit(a).state);assertEquals(JsonPrimitive(2400),h.settingsRaw["soa"]!!.jsonObject["retry"])
        h.settingsRaw=JsonObject(h.settingsRaw+mapOf("soa" to JsonObject(h.settingsRaw["soa"]!!.jsonObject+mapOf("future" to JsonPrimitive(true)))))
        assertFailsWith<CloudFailure> { h.prepare(a.copy(changed=obj("""{"soa":{"ttl":500}}"""))) };assertEquals(1,h.writes.size)
    }
    @Test fun fieldQualificationAndActualSecondaryModeFailClosed()=runTest {
        for(mode in 0..3) {
            val h=Harness(this)
            val field=when(mode){0->"flatten_all_cnames";1->"multi_provider";2->"ns_ttl";else->"nameservers"}
            if(mode==0) h.eligibility=DnsSettingsEligibility() else if(mode==1 || mode==2) h.ownerType="secondary" else h.eligibility=h.eligibility.copy(ownedCustomSetIds=emptySet())
            val value=when(mode){2->JsonPrimitive(300);3->obj("""{"type":"custom","nameserver_set_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""");else->JsonPrimitive(true)}
            assertFailsWith<CloudFailure> { h.prepare(DnsZoneSettingsAction(zone,buildJsonObject { put(field,value) })) };assertEquals(0,h.writes.size)
        }
    }
    @Test fun independentRiskAcknowledgementIsBoundToPlanInputInstanceAndIssuer()=runTest {
        val a=DnsZoneSettingsAction(zone,obj("""{"nameservers":{"type":"cloudflare.advanced"},"zone_mode":"dns_only"}"""))
        val h=Harness(this);val p=h.prepare(a);val c=h.confirm(p)
        assertEquals(setOf(DnsSettingsRisk.CHANGE_NAMESERVERS,DnsSettingsRisk.CHANGE_ZONE_MODE),h.settings.preview(p.id).risks)
        assertFailsWith<CloudFailure> { h.settings.submit(a,c) }
        val forged=setOf(DnsSettingsRiskAcknowledgement(p.id,DnsSettingsRisk.CHANGE_NAMESERVERS),DnsSettingsRiskAcknowledgement(p.id,DnsSettingsRisk.CHANGE_ZONE_MODE))
        assertFailsWith<CloudFailure> { h.settings.submit(a,c,forged) };assertEquals(0,h.writes.size)
        val issued=h.settings.preview(p.id).risks.map { h.settings.acknowledgeRisk(p.id,it) }.toSet()
        val other=Harness(this);val otherPlan=other.prepare(a)
        assertFailsWith<CloudFailure> { other.settings.submit(a,other.confirm(otherPlan),issued) }
        assertEquals(TaskState.SUCCEEDED,h.settings.submit(a,c,issued).state)
    }
    @Test fun scanControllerCompletesOnlyConfirmedApplyAndFreshChangesAreNotApplied()=runTest {
        val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection());val p=h.prepare(a)
        assertEquals(0,h.writes.size);assertEquals(listOf("candidate-é"),h.scan.preview(p.id).acceptIds)
        h.candidates[0]=record(content="192.0.2.2")
        assertFailsWith<CloudFailure> { h.scan.submit(a,h.confirm(p)) }
        assertEquals(0,h.writes.size);assertEquals(TaskState.ACTION_REQUIRED,h.store.get(p.id)?.state)
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsScanAction.Apply(zone,h.selection())).state)
    }
    @Test fun triggerAcceptedAllowsFreshExplicitTriggerAndOtherDnsOperations()=runTest {
        val h=Harness(this);val a=DnsScanAction.Trigger(zone)
        assertEquals(TaskState.ACCEPTED,h.submit(a).state)
        assertEquals(TaskState.ACCEPTED,h.submit(a).state)
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,obj("""{"multi_provider":true}"""))).state)
        assertEquals(3,h.writes.size)
    }
    @Test fun unknownTriggerBlocksOnlyNewTriggerAndNeverReplaysOldConfirmation()=runTest {
        val h=Harness(this);val a=DnsScanAction.Trigger(zone);val p=h.prepare(a);val c=h.confirm(p);h.throwWrite=true
        val task=h.scan.submit(a,c);assertEquals(TaskState.UNKNOWN,task.state);h.throwWrite=false
        assertFailsWith<CloudFailure> { h.prepare(a) }
        assertEquals(TaskState.UNKNOWN,h.scan.submit(a,c).state);assertEquals(1,h.writes.size)
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,obj("""{"multi_provider":true}"""))).state)
    }
    @Test fun settingsStateAndPermissionPreflightConflictIsNotAppliedAndCanRepreview()=runTest {
        for(permissionChange in listOf(false,true)) {
            val h=Harness(this);val a=DnsZoneSettingsAction(zone,obj("""{"multi_provider":true}"""));val p=h.prepare(a)
            if(permissionChange) h.authorityAllowed=false else h.settingsRaw=JsonObject(h.settingsRaw+mapOf("multi_provider" to JsonPrimitive(true)))
            assertFailsWith<CloudFailure> { h.settings.submit(a,h.confirm(p)) }
            assertEquals(TaskState.ACTION_REQUIRED,h.store.get(p.id)?.state);assertEquals(0,h.writes.size)
            h.authorityAllowed=true;h.settingsRaw=JsonObject(h.settingsRaw+mapOf("multi_provider" to JsonPrimitive(false)));h.prepare(a)
        }
    }
    @Test fun changedRealInputContextCredentialOrExpiredPlanSendsNothing()=runTest {
        for(mode in 0..3) {
            val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection());val p=h.prepare(a);val c=h.confirm(p)
            when(mode){0->h.resolver.live=h.resolver.live!!.copy(requestFingerprint="0".repeat(64));1->h.resolver.live=h.resolver.live!!.copy(credentialRevision=2);2->h.resolver.valid=false;else->h.now+=600000}
            assertFailsWith<CloudFailure> { h.scan.submit(a,c) };assertEquals(0,h.writes.size)
        }
        val h=Harness(this);val a=DnsZoneSettingsAction(zone,obj("""{"ns_ttl":300}"""));val p=h.prepare(a)
        val changed=a.copy(changed=obj("""{"ns_ttl":301}"""));h.select(changed)
        assertFailsWith<CloudFailure> { h.settings.submit(changed,h.confirm(p)) };assertEquals(0,h.writes.size)
    }
    @Test fun suspendingSessionAcquisitionAndFinalOwnerConflictCannotBypassBoundGuard()=runTest {
        for(mode in 0..1) {
            val h=Harness(this);val a=DnsScanAction.Trigger(zone);val p=h.prepare(a)
            if(mode==0)h.onSession={h.resolver.live=h.resolver.live!!.copy(credentialRevision=2)} else h.onOwner={h.ownerAccount="foreign"}
            runCatching { h.scan.submit(a,h.confirm(p)) };assertEquals(0,h.writes.size)
        }
    }
    @Test fun mutationTimeoutPartialEnvelopeAndErrorsNeverRetryOrInventSuccess()=runTest {
        for(mode in 0..5) {
            val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection());val p=h.prepare(a)
            when(mode){0->h.throwWrite=true;1->h.writeResponse=ok();2->h.writeResponse=ok(success=false);3->h.writeResponse=HttpResponse(429,"private-ratelimit");4->h.writeResponse=HttpResponse(200,"{}");else->h.writeResponse=ok(obj("""{"accepts":[],"rejects":[]}"""))}
            val task=h.scan.submit(a,h.confirm(p));assertEquals(TaskState.UNKNOWN,task.state);assertEquals(1,h.writes.size)
            assertFalse(task.safeDetail.contains("private"))
        }
    }
    @Test fun settingsPostSendTimeoutWrongTargetOrReadFailureStaysUnknown()=runTest {
        for(mode in 0..3) {
            val h=Harness(this);val a=DnsZoneSettingsAction(zone,obj("""{"ns_ttl":300}"""));val p=h.prepare(a)
            when(mode){0->h.throwWrite=true;1->h.writeResponse=ok(JsonObject(h.settingsRaw+mapOf("zone_id" to JsonPrimitive("wrong"))));2->h.afterWrite={h.readFailure=true};else->h.afterWrite={h.resolver.live=h.resolver.live!!.copy(credentialRevision=2)}}
            val task=h.settings.submit(a,h.confirm(p));assertEquals(TaskState.UNKNOWN,task.state);assertEquals(1,h.writes.size)
        }
    }
    @Test fun persistedScanUnknownCanBeReadAfterRebuildButCannotRecoverPayloadOrClaimCompletion()=runTest {
        val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection());h.throwWrite=true
        val task=h.submit(a);h.throwWrite=false;h.candidates.clear();h.requests.clear()
        val result=h.restoreScan().reconcile(task.id)
        assertEquals(TaskState.UNKNOWN,result.state);assertEquals(0,h.writes.size)
        assertTrue(h.requests.any { it.path.endsWith("/scan/review") })
        assertFailsWith<CloudFailure> { h.prepare(DnsScanAction.Apply(zone,DnsScanReviewInput(listOf("candidate-é"),emptyList(),mapOf("candidate-é" to "a".repeat(64))))) }
    }
    @Test fun settingsRebuildUsesOwnedGetFiniteLocatorAndDesiredHashWithoutPayload()=runTest {
        val h=Harness(this);val a=DnsZoneSettingsAction(zone,obj("""{"ns_ttl":300,"multi_provider":true}"""));val p=h.prepare(a)
        h.afterWrite={h.readFailure=true};val task=h.settings.submit(a,h.confirm(p));assertEquals(TaskState.UNKNOWN,task.state)
        assertEquals(ResourceRef(zone,"dns_settings_v1","multi_provider,ns_ttl"),task.resourceRef)
        h.readFailure=false;h.requests.clear()
        assertEquals(TaskState.SUCCEEDED,h.restoreSettings().reconcile(task.id).state);assertEquals(0,h.writes.size)
    }
    @Test fun unknownResourceMetadataOrPermissionAndHashConflictCannotBeSettledFromPlan()=runTest {
        for(mode in 0..4) {
            val h=Harness(this);val a=DnsZoneSettingsAction(zone,obj("""{"ns_ttl":300}"""));val p=h.prepare(a);h.throwWrite=true
            var task=h.settings.submit(a,h.confirm(p));h.throwWrite=false
            task=when(mode){0->task.copy(resourceRef=null);1->task.copy(resourceRef=ResourceRef(zone,"future","unknown"));2->task.copy(desiredHash="0".repeat(64));else->task}
            h.store.put(task);if(mode==3)h.ownerAccount="foreign";if(mode==4)h.readFailure=true
            assertEquals(TaskState.UNKNOWN,h.restoreSettings().reconcile(task.id).state);assertEquals(1,h.writes.size)
        }
    }
    @Test fun sharedUnknownGateBlocksPrepareAndAlreadyPreparedSecondConfirmationForBothFamilies()=runTest {
        for(key in listOf("dns-records-for-a-zone-create-dns-record",DNS_BATCH_KEY,DNS_IMPORT_KEY,DNS_SCAN_APPLY_OPERATION,DNS_SETTINGS_PATCH_OPERATION)) for(family in 0..1) {
            val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection());val b=DnsZoneSettingsAction(zone,obj("""{"multi_provider":true}"""))
            val p=if(family==0)h.prepare(a) else h.prepare(b)
            h.store.put(h.oldTask(key))
            assertFailsWith<CloudFailure> { if(family==0)h.prepare(a) else h.prepare(b) }
            assertFailsWith<CloudFailure> { if(family==0)h.scan.submit(a,h.confirm(p)) else h.settings.submit(b,h.confirm(p)) };assertEquals(0,h.writes.size)
        }
    }
    @Test fun mutationGateOtherZoneTerminalAndReadonlyDoNotBlockTask7Writes()=runTest {
        val h=Harness(this);val a=DnsScanAction.Apply(zone,h.selection())
        h.store.put(h.oldTask(DNS_IMPORT_KEY,scope=zone.copy(zoneId="other"),id="other"))
        h.store.put(h.oldTask(DNS_SCAN_APPLY_OPERATION,state=TaskState.SUCCEEDED,id="terminal"))
        h.store.put(h.oldTask(DNS_USAGE_OPERATION,id="readonly"))
        assertEquals(TaskState.SUCCEEDED,h.submit(a).state)
    }
    @Test fun newFamiliesUnknownBlockExistingSingleBatchAndImportPublicGateInReverse()=runTest {
        for(key in listOf(DNS_SCAN_APPLY_OPERATION,DNS_SETTINGS_PATCH_OPERATION)) for(ref in listOf<ResourceRef?>(null,ResourceRef(zone,"future","unknown"))) {
            val h=Harness(this);h.store.put(h.oldTask(key,ref));val gate=DnsUncertaintyGate(h.store,h.dispatcher)
            for(incoming in listOf("dns-records-for-a-zone-patch-dns-record",DNS_BATCH_KEY,DNS_IMPORT_KEY)) assertFailsWith<CloudFailure> { gate.requireClear(zone,incoming,ResourceRef(zone,"dns_record","record")) }
        }
    }
    @Test fun readonlyReviewUsageSettingsStillWorkDuringMutationUnknownAndPermissionIsLocal()=runTest {
        val h=Harness(this);h.store.put(h.oldTask(DNS_SCAN_APPLY_OPERATION));h.authorityAllowed=null
        assertEquals(1,h.scanApi.review(zone).size);assertNull(h.settingsApi.usage(zone).recordQuota);assertEquals(zone,h.settingsApi.settings(zone).scope)
        h.readFailure=true;assertFailsWith<CloudFailure> { h.settingsApi.settings(zone) }
        assertEquals(1,h.scanApi.review(zone).size);assertEquals(0,h.writes.size)
    }
    @Test fun malformedOrDuplicateCandidateResponseCannotBecomeTrustedSelection()=runTest {
        val h=Harness(this);h.badRead=true
        assertFailsWith<CloudFailure> { h.scanApi.review(zone) }
        h.badRead=false;h.candidates+=record()
        assertFailsWith<CloudFailure> { h.scanApi.review(zone) };assertEquals(0,h.writes.size)
    }
    @Test fun exactDecimalSettingsAreNotRoundedThroughDoubleOrSilentlyTreatedAsNoop()=runTest {
        val h=Harness(this);h.settingsRaw=JsonObject(h.settingsRaw+mapOf("ns_ttl" to JsonPrimitive(300)))
        val input=obj("""{"ns_ttl":300.0000000000000000001}""")
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,input)).state)
        assertEquals("300.0000000000000000001",Json.parseToJsonElement(h.writes.single().body!!).jsonObject["ns_ttl"]!!.jsonPrimitive.content)
    }
    @Test fun pureCandidateProjectionUsesDomainViewAndPreservesTxtAndUnknownReadonlyReason() {
        val text="  \"quoted\"\nTXT  "
        val doc=DnsRecordDocument("候选-é",zone,record("候选-é","TXT",content=text))
        val c=DnsScanCandidate(doc.id,doc,"a".repeat(64))
        val view=dnsScanCandidateView(c,"example.com")
        assertEquals(doc.id,view.id);assertEquals(c.selectionHash,view.selectionHash)
        assertEquals(text,view.acceptInput!!.fields["content"]!!.jsonPrimitive.content)
        assertEquals(text,view.view.content)
        val future=dnsScanCandidateView(c.copy(record=doc.copy(raw=JsonObject(doc.raw+mapOf("future_write" to JsonPrimitive(true))))),"example.com")
        assertNull(future.acceptInput);assertNotNull(future.acceptUnavailableReason)
        assertTrue("future_write" in future.view.unknownFields)
    }
    @Test fun pureReadProjectionKeepsThreeBoolsNullMissingAndUnknownReasonsWithoutWriteProjection() {
        val read=dnsZoneSettingsView(DnsZoneSettings(zone,obj("""{"flatten_all_cnames":false,"multi_provider":true,"secondary_overrides":false,"ns_ttl":300.0,"zone_mode":"dns_only","nameservers":{"type":"custom.account","ns_set":2},"soa":{"ttl":300,"mname":null,"future":true},"internal_dns":{"reference_zone_id":"reference"},"foundation_dns":true,"future_top":7}""")))
        assertEquals(false,read.flattenAllCnames);assertEquals(true,read.multiProvider);assertEquals(false,read.secondaryOverrides)
        assertEquals(JsonNull,read.soa!!.mname);assertNull(read.soa!!.refresh)
        assertEquals(DnsNameservers.Existing("custom.account",2),read.nameservers)
        assertEquals(true,read.foundationDns);assertTrue(read.unknownReasons.keys.containsAll(setOf("foundation_dns","soa.future","future_top")))
        val missing=dnsZoneSettingsView(DnsZoneSettings(zone,obj("""{"soa":{"ttl":300}}""")))
        assertNull(missing.soa!!.mname);assertNull(missing.multiProvider);assertTrue("multi_provider" in missing.unknownReasons)
    }
    @Test fun pureSettingsValidationNeedsNoControllerAndProjectsAllTypedInputBranches() {
        val p=projectDnsZoneSettings(obj("""{"ns_ttl":3e2,"multi_provider":false,"soa":{"ttl":300,"mname":null},"internal_dns":{"reference_zone_id":"参考-zone"},"nameservers":{"type":"custom","nameserver_set_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"""))
        assertEquals(JsonNull,p.soa!!.mname);assertEquals("参考-zone",p.internalDns!!.referenceZoneId)
        assertIs<DnsNameservers.CustomSet>(p.nameservers);assertEquals(JsonPrimitive(false),p.changed["multi_provider"])
        assertFailsWith<CloudFailure> { projectDnsZoneSettings(obj("""{"foundation_dns":true}""")) }
    }
    @Test fun pureLosslessComparisonDistinguishesHighPrecisionButNormalizesEquivalentNumbers() {
        assertTrue(dnsZoneSettingsSemanticallyEqual(obj("""{"ns_ttl":300}"""),obj("""{"ns_ttl":3.000e2}""")))
        assertFalse(dnsZoneSettingsSemanticallyEqual(obj("""{"ns_ttl":300}"""),obj("""{"ns_ttl":300.0000000000000000001}""")))
        assertFalse(dnsZoneSettingsSemanticallyEqual(obj("{}"),obj("""{"soa":{"mname":null}}""")))
        assertFalse(dnsZoneSettingsSemanticallyEqual(obj("""{"ns_ttl":"300"}"""),obj("""{"ns_ttl":300}""")))
    }
    @Test fun pureSettingsBeforeAfterAndDeltaAreBoundToActualInputAndRisks() {
        val before=dnsZoneSettingsView(DnsZoneSettings(zone,obj("""{"multi_provider":true,"ns_ttl":300,"nameservers":{"type":"cloudflare.standard"},"zone_mode":"standard"}""")))
        val a=DnsZoneSettingsAction(zone,obj("""{"multi_provider":false,"ns_ttl":300.0,"nameservers":{"type":"cloudflare.advanced"}}"""))
        val delta=dnsZoneSettingsDelta(before,a.changed)
        assertEquals(setOf("multi_provider","nameservers"),delta.changed.keys)
        val after=dnsZoneSettingsAfter(before,delta)
        val p=DnsZoneSettingsPreview(zone,"example.com",delta,setOf(DnsSettingsRisk.CHANGE_NAMESERVERS),emptyList(),before,after)
        assertTrue(dnsZoneSettingsPreviewMatches(a,p,before,"example.com"))
        assertFalse(dnsZoneSettingsPreviewMatches(a,p.copy(after=after.copy(multiProvider=true)),before,"example.com"))
        assertFalse(dnsZoneSettingsPreviewMatches(a,p.copy(risks=emptySet()),before,"example.com"))
        assertFalse(dnsZoneSettingsPreviewMatches(a,p.copy(domain="foreign.com"),before,"example.com"))
    }
    @Test fun pureScanPreviewBindsRealIdsHashesAndSameSafeRecordProjection() {
        val doc=DnsRecordDocument("候选-é",zone,record("候选-é"))
        val c=dnsScanCandidateView(DnsScanCandidate(doc.id,doc,"a".repeat(64)),"example.com")
        val a=DnsScanAction.Apply(zone,DnsScanReviewInput(listOf(c.id),emptyList(),mapOf(c.id to c.selectionHash)))
        val p=DnsScanPreview(zone,"example.com",listOf(c.id),emptyList(),listOf(doc),emptyList(),mapOf(c.id to c.selectionHash))
        assertTrue(dnsScanPreviewMatches(a,p,listOf(c),"example.com"))
        assertFalse(dnsScanPreviewMatches(a,p.copy(candidateHashes=mapOf(c.id to "b".repeat(64))),listOf(c),"example.com"))
        val changed=doc.copy(raw=JsonObject(doc.raw+mapOf("content" to JsonPrimitive("192.0.2.9"))))
        assertFalse(dnsScanPreviewMatches(a,p.copy(records=listOf(changed)),listOf(c),"example.com"))
        assertFalse(dnsScanPreviewMatches(a,p,listOf(c.copy(view=c.view.copy(name="wrong.example.com"))),"example.com"))
    }
    @Test fun pureTypedReadUnknownNestedDoesNotEnableUnsafeDelta() {
        val before=dnsZoneSettingsView(DnsZoneSettings(zone,obj("""{"soa":{"ttl":300,"future":true},"nameservers":{"type":"future"},"zone_mode":"future"}""")))
        assertNull(before.nameservers);assertNull(before.zoneMode)
        assertFailsWith<CloudFailure> { dnsZoneSettingsDelta(before,obj("""{"soa":{"ttl":400}}""")) }
        assertTrue(before.unknownReasons.keys.containsAll(setOf("soa.future","nameservers","zone_mode")))
    }
    @Test fun trustedScopeConstraintsAllowTtl30DisplayPreviewAndConfirmedApply()=runTest {
        val h=Harness(this);h.scanConstraints=DnsConstraints(minTtl=30)
        h.candidates[0]=JsonObject(record()+mapOf("ttl" to JsonPrimitive(30)))
        val a=DnsScanAction.Apply(zone,h.selection());h.select(a)
        val fresh=h.scanApi.observe(a)
        assertEquals(30,fresh.constraints.minTtl);assertTrue(h.constraintsScopes.all { it==zone })
        val rows=fresh.candidates.map { dnsScanCandidateView(it,fresh.owner.name,fresh.constraints) }
        assertEquals(JsonPrimitive(30),rows.single().acceptInput?.fields?.get("ttl"))
        val p=h.scan.prepare(a)
        assertTrue(dnsScanPreviewMatches(a,h.scan.preview(p.id),rows,fresh.owner.name))
        assertEquals(TaskState.SUCCEEDED,h.scan.submit(a,h.confirm(p)).state)
        val wire=Json.parseToJsonElement(h.writes.single().body!!).jsonObject
        assertEquals(JsonPrimitive(30),wire["accepts"]!!.jsonArray.single().jsonObject["ttl"])
    }
    @Test fun defaultUnverifiedConstraintsRejectTtl30WithoutInferringPlanFromCandidate()=runTest {
        val h=Harness(this);h.candidates[0]=JsonObject(record()+mapOf("ttl" to JsonPrimitive(30)))
        val a=DnsScanAction.Apply(zone,h.selection());h.select(a)
        val fresh=h.scanApi.observe(a);assertEquals(60,fresh.constraints.minTtl)
        assertNull(dnsScanCandidateView(fresh.candidates.single(),fresh.owner.name,fresh.constraints).acceptInput)
        assertFailsWith<CloudFailure> { h.scan.prepare(a) }
        assertFailsWith<CloudFailure> { h.scanApi.apply(zone,a.input) }
        assertEquals(0,h.writes.size)
    }
    @Test fun finalFreshConstraintsLossRevokesTtl30ConfirmationAndLeavesZeroWriteMetadata()=runTest {
        val h=Harness(this);h.scanConstraints=DnsConstraints(minTtl=30)
        h.candidates[0]=JsonObject(record()+mapOf("ttl" to JsonPrimitive(30)))
        val a=DnsScanAction.Apply(zone,h.selection());val p=h.prepare(a)
        h.scanConstraints=DnsConstraints()
        val fresh=h.scanApi.observe(a)
        val rows=fresh.candidates.map { dnsScanCandidateView(it,fresh.owner.name,fresh.constraints) }
        assertNull(rows.single().acceptInput)
        assertFalse(dnsScanPreviewMatches(a,h.scan.preview(p.id),rows,fresh.owner.name))
        assertFailsWith<CloudFailure> { h.scan.submit(a,h.confirm(p)) }
        assertEquals(0,h.writes.size);assertEquals(TaskState.ACTION_REQUIRED,h.store.get(p.id)?.state)
        h.scanConstraints=DnsConstraints(minTtl=30);h.prepare(a)
    }

    @Test fun inputQualificationRequiresExactExistingNameserverSetAndDoesNotInventDefaultOne()=runTest {
        val h=Harness(this)
        val a=DnsZoneSettingsAction(zone,obj("""{"nameservers":{"type":"custom.account","ns_set":2}}"""))
        assertFailsWith<CloudFailure> { h.prepare(a) };assertEquals(0,h.writes.size)
        h.eligibility=h.eligibility.copy(existingNameserverSets=mapOf("custom.account" to setOf(1)))
        assertFailsWith<CloudFailure> { h.prepare(a) };assertEquals(0,h.writes.size)
        h.eligibility=h.eligibility.copy(existingNameserverSets=mapOf("custom.account" to setOf(2)))
        assertEquals(TaskState.SUCCEEDED,h.submit(a).state)
        assertEquals(obj("""{"nameservers":{"type":"custom.account","ns_set":2}}"""),Json.parseToJsonElement(h.writes.single().body!!))
        val withoutSet=DnsZoneSettingsAction(zone,obj("""{"nameservers":{"type":"custom.zone"}}"""))
        assertFailsWith<CloudFailure> { h.prepare(withoutSet) };assertEquals(1,h.writes.size)
    }
    @Test fun inputQualificationBindsInternalReferenceEvenWithVerifiedNestedPatchSemantics()=runTest {
        val h=Harness(this);h.eligibility=h.eligibility.copy(nestedPatchSemanticsVerified=setOf(DnsZoneSettingKey.INTERNAL_DNS),verifiedReferenceZoneIds=setOf("different"))
        val a=DnsZoneSettingsAction(zone,obj("""{"internal_dns":{"reference_zone_id":"参考-zone"}}"""))
        assertFailsWith<CloudFailure> { h.prepare(a) };assertEquals(0,h.writes.size)
        h.eligibility=h.eligibility.copy(verifiedReferenceZoneIds=setOf("参考-zone"))
        assertEquals(TaskState.SUCCEEDED,h.submit(a).state)
        assertEquals(obj("""{"internal_dns":{"reference_zone_id":"参考-zone"}}"""),Json.parseToJsonElement(h.writes.single().body!!))
    }
    @Test fun inputQualificationAllowsVerifiedSecondaryAndCustomSetWithActualWire()=runTest {
        val h=Harness(this);h.ownerType="secondary"
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,obj("""{"secondary_overrides":true}"""))).state)
        assertEquals(TaskState.SUCCEEDED,h.submit(DnsZoneSettingsAction(zone,obj("""{"secondary_overrides":false}"""))).state)
        assertEquals(obj("""{"secondary_overrides":false}"""),Json.parseToJsonElement(h.writes.last().body!!))
        h.ownerType="full"
        val a=DnsZoneSettingsAction(zone,obj("""{"flatten_all_cnames":true,"nameservers":{"type":"custom","nameserver_set_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}"""))
        assertEquals(TaskState.SUCCEEDED,h.submit(a).state)
        assertEquals(a.changed,Json.parseToJsonElement(h.writes.last().body!!))
    }

    @Test fun settingsResourceRefHelperMatchesControllerActualDeltaRatherThanAllDraftKeys()=runTest {
        val h=Harness(this);val before=dnsZoneSettingsView(h.settingsApi.settings(zone))
        val a=DnsZoneSettingsAction(zone,obj("""{"ns_ttl":86400.0,"multi_provider":true}"""))
        val expected=ResourceRef(zone,"dns_settings_v1","multi_provider")
        assertEquals(expected,dnsZoneSettingsResourceRef(a,before))
        val p=h.prepare(a);assertEquals(p.resourceRef,dnsZoneSettingsResourceRef(a,before))
        assertNotEquals(ResourceRef(zone,"dns_settings_v1","multi_provider,ns_ttl"),dnsZoneSettingsResourceRef(a,before))
        assertEquals(0,h.writes.size)
    }
    @Test fun settingsResourceRefHelperUsesFiniteCanonicalKeysAndRequiresActualScopeAndChange()=runTest {
        val h=Harness(this);val before=dnsZoneSettingsView(h.settingsApi.settings(zone))
        val a=DnsZoneSettingsAction(zone,obj("""{"zone_mode":"dns_only","ns_ttl":300.0,"flatten_all_cnames":true}"""))
        assertEquals(ResourceRef(zone,"dns_settings_v1","flatten_all_cnames,ns_ttl,zone_mode"),dnsZoneSettingsResourceRef(a,before))
        assertFailsWith<CloudFailure> { dnsZoneSettingsResourceRef(a.copy(scope=zone.copy(zoneId="different")),before) }
        for(value in listOf("{}","""{"ns_ttl":86400.0}""","""{"foundation_dns":true}""","""{"future":true}"""))
            assertFailsWith<CloudFailure> { dnsZoneSettingsResourceRef(a.copy(changed=obj(value)),before) }
        assertEquals(0,h.writes.size)
    }

}
