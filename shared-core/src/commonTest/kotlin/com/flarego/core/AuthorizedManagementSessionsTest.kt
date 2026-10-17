package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.*

internal class ExtendedProvider {
    val connection=Connection("real","display","a".repeat(32))
    val zone=ZoneScope(AccountScope(connection.id,connection.accountId),"b".repeat(32))
    var revision=1L;var now=100L;var ownerAccount=connection.accountId
    var catalogSuffix="";var failDetails=false;var failRecords=false
    var before: suspend (HttpRequest)->Unit={}
    val requests=mutableListOf<HttpRequest>()
    val writes get()=requests.count { it.method!="GET" }
    val hash=FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    fun obj(s:String)=Json.parseToJsonElement(s).jsonObject
    var dnssec=obj("""{"status":"active","dnssec_multi_signer":false,"dnssec_presigned":false,"dnssec_use_nsec3":false}""")
    var dnsSettings=obj("""{"flatten_all_cnames":false,"multi_provider":false,"secondary_overrides":false,"ns_ttl":86400,"zone_mode":"standard","nameservers":{"type":"cloudflare.standard"}}""")
    var ssl="strict";var universal=true
    var transformations=obj("""{"allowed_origins":"example.com","c2pa":"off","transformations":"off","future":"keep"}""")
    val labels=listOf("DNS Write","Zone DNS Settings Write","Zone Settings Write","Zone Write","SSL and Certificates Write","Cache Purge")
    var candidates=JsonArray(emptyList())
    var records=JsonArray(emptyList())
    val issuer=TokenAuthorityIssuer(connection,1,{revision},OperationClock { now },TokenKind.USER_OWNED)
    fun envelope(result:JsonElement,info:JsonObject?=null)=HttpResponse(200,buildJsonObject {
        put("success",true);put("errors",JsonArray(emptyList()));put("messages",JsonArray(emptyList()));put("result",result)
        info?.let { put("result_info",it) }
    }.toString())
    val gateway=CloudflareGateway(HttpTransport { r ->
        requests+=r;before(r)
        when {
            r.path=="/user/tokens/verify" -> envelope(obj("""{"id":"self","status":"active"}"""))
            r.path=="/user/tokens/self" -> if(failDetails) HttpResponse(403,"unavailable") else envelope(buildJsonObject {
                put("id","self");put("status","active");put("policies",buildJsonArray { add(buildJsonObject {
                    put("id","policy");put("effect","allow");put("permission_groups",JsonArray(labels.indices.map { buildJsonObject { put("id","g$it$catalogSuffix") } }))
                    put("resources",buildJsonObject { put("com.cloudflare.api.account.zone.${zone.zoneId}","*") })
                }) })
            })
            r.path.endsWith("/permission_groups") -> envelope(JsonArray(labels.mapIndexed { i,label -> buildJsonObject {
                put("id","g$i$catalogSuffix");put("name",label);put("scopes",JsonArray(listOf(JsonPrimitive("com.cloudflare.api.account.zone"))));put("is_selectable",true)
            } }))
            r.path=="/zones/${zone.zoneId}" -> envelope(buildJsonObject {
                put("id",zone.zoneId);put("name","example.com");put("status","active");put("type","full");put("paused",false)
                put("account",buildJsonObject { put("id",ownerAccount) });put("name_servers",JsonArray(emptyList()))
            })
            r.path.endsWith("/dns_records/import") -> envelope(obj("""{"recs_added":1,"total_records_parsed":1}"""))
            r.path.endsWith("/dnssec/zsk") -> envelope(JsonArray(emptyList()))
            r.path.endsWith("/dnssec") -> { if(r.method=="PATCH") dnssec=JsonObject(dnssec+obj(r.body!!));envelope(dnssec) }
            r.path.endsWith("/dns_settings") -> { if(r.method=="PATCH") dnsSettings=JsonObject(dnsSettings+obj(r.body!!));envelope(dnsSettings) }
            r.path.endsWith("/dns_records/usage") -> envelope(obj("""{"record_usage":0,"record_quota":200}"""))
            r.path.endsWith("/dns_records/scan/trigger") -> envelope(JsonObject(emptyMap()))
            r.path.endsWith("/dns_records/scan/review") -> if(r.method=="GET") envelope(candidates) else {
                val payload=obj(r.body!!);val rejected=payload["rejects"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("id") }
                candidates=JsonArray(candidates.filterNot { it.jsonObject["id"] in rejected })
                envelope(buildJsonObject { put("accepts",JsonArray(emptyList()));put("rejects",JsonArray(rejected)) })
            }
            r.path.endsWith("/settings/transformations_config") -> envelope(buildJsonObject { put("id","transformations_config");put("editable",true);put("value",transformations) })
            r.path.endsWith("/settings/ssl") -> { if(r.method=="PATCH") ssl=obj(r.body!!).getValue("value").jsonPrimitive.content;envelope(buildJsonObject { put("id","ssl");put("editable",true);put("value",ssl) }) }
            r.path.endsWith("/ssl/universal/settings") -> { if(r.method=="PATCH") universal=obj(r.body!!).getValue("enabled").jsonPrimitive.boolean;envelope(buildJsonObject { put("enabled",universal);put("editable",true) }) }
            r.path.endsWith("/purge_cache") -> envelope(obj("""{"id":"receipt"}"""))
            r.path.substringBefore('?').endsWith("/dns_records") -> if(failRecords) HttpResponse(403,"records unavailable") else envelope(records,obj("""{"page":1,"per_page":20,"count":0,"total_count":0,"total_pages":1}"""))
            else -> error("unexpected synthetic route ${r.method} ${r.path}")
        }
    },sleeper={})
    fun services(eligibility:ManagementEligibilitySource=object:ManagementEligibilitySource {},files:AuthorizedDnsFilesDependencies?=null)=
        AuthorizedManagementSessions(connection,gateway,issuer,hash,files,eligibility).services()
    fun candidate() { candidates=JsonArray(listOf(obj("""{"id":"candidate","type":"A","name":"www.example.com","content":"192.0.2.1","ttl":60}"""))) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorizedManagementSessionsTest {
    @Test fun aggregateHasAllFiveTypedDomainsAndTwoArgumentServicesRemainCompatible()=runTest {
        val h=ExtendedProvider();val s=h.services()
        assertNotNull(s.scan);assertNotNull(s.dnsSettings);assertNotNull(s.dnssec);assertNotNull(s.zoneSettings)
        assertNull(s.files);assertNull(ManagementServices(s.dns,s.zones).dnssec)
    }
    @Test fun readOnlyDataSurvivesUnknownTokenPolicy()=runTest {
        val h=ExtendedProvider();h.failDetails=true;val s=h.services()
        assertEquals("active",s.dnssec!!.get(h.zone).status)
        assertEquals(0,s.dnsSettings!!.usage(h.zone).recordUsage)
        assertEquals("standard",s.dnsSettings!!.settings(h.zone).raw["zone_mode"]!!.jsonPrimitive.content)
        assertTrue(s.dnssec!!.zsks(h.zone).isEmpty());assertTrue(s.scan!!.review(h.zone).isEmpty())
        assertEquals("strict",s.zoneSettings!!.get(h.zone,ZoneSettingKey.SSL).value.jsonPrimitive.content)
        assertEquals(0,h.writes)
    }
    @Test fun realIssuerProofAllowsOneTypedWriteAfterForcedRefresh()=runTest {
        val h=ExtendedProvider();val s=h.services().dnssec!!
        val snapshot=s.observe(h.zone,DnssecMutation.UPDATE);assertEquals(true,snapshot.writeAllowed);assertEquals(0,h.writes)
        val before=h.requests.count { it.path.endsWith("/tokens/verify") }
        val receipt=s.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{}, {})
        assertIs<ExecutionReceipt.Completed>(receipt);assertEquals(1,h.writes)
        assertEquals(before+1,h.requests.count { it.path.endsWith("/tokens/verify") })
    }
    @Test fun failedStrongRefreshNeverReusesEarlierGrant()=runTest {
        val h=ExtendedProvider();val s=h.services().dnssec!!;assertEquals(true,s.observe(h.zone,DnssecMutation.UPDATE).writeAllowed)
        h.failDetails=true
        assertFailsWith<CloudFailure> { s.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{}, {}) }
        assertEquals(0,h.writes)
    }
    @Test fun catalogDriftTombstoneSurvivesShortSessionRecreation()=runTest {
        val h=ExtendedProvider();assertEquals(true,h.services().dnssec!!.observe(h.zone,DnssecMutation.UPDATE).writeAllowed)
        h.catalogSuffix="drift"
        assertFailsWith<CloudFailure> { h.services().dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{}, {}) }
        h.catalogSuffix=""
        assertFailsWith<CloudFailure> { h.services().dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{}, {}) }
        assertEquals(0,h.writes)
    }
    @Test fun invalidationAfterSuspendingQualificationPreventsSend()=runTest {
        val h=ExtendedProvider();val eligibility=object:ManagementEligibilitySource {
            override suspend fun nsec3Available(scope:ZoneScope):Boolean? { h.issuer.invalidate(h.connection.id);return true }
        }
        assertFailsWith<CloudFailure> { h.services(eligibility).dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(useNsec3=true)),{}, {}) }
        assertEquals(0,h.writes)
    }
    @Test fun finalGuardRechecksProofAfterTypedOwnerRead()=runTest {
        val h=ExtendedProvider();var owners=0
        h.before={ if(it.path=="/zones/${h.zone.zoneId}" && ++owners==2) h.issuer.invalidate(h.connection.id) }
        assertFailsWith<CloudFailure> { h.services().dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{}, {}) }
        assertEquals(0,h.writes)
    }
    @Test fun callbackInvalidationCannotSendWithExpiredProof()=runTest {
        val h=ExtendedProvider()
        assertFailsWith<CloudFailure> { h.services().dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(status="disabled")),{ h.now+=30001 },{}) }
        assertEquals(0,h.writes)
    }
    @Test fun tokenPermissionDoesNotInventPaidNsec3OrDnsSettingsEligibility()=runTest {
        val h=ExtendedProvider();val s=h.services()
        assertFailsWith<CloudFailure> { s.dnssec!!.write(DnssecAction.Update(h.zone,DnssecUpdateInput(useNsec3=true)),{}, {}) }
        assertFailsWith<CloudFailure> { s.dnsSettings!!.write(DnsZoneSettingsAction(h.zone,h.obj("""{"ns_ttl":60}""")),{}, {}) }
        assertEquals(0,h.writes)
    }
    @Test fun concreteOperationsAndProviderOwnershipAreNotInterchangeable()=runTest {
        val h=ExtendedProvider();val s=h.services()
        h.ownerAccount="foreign"
        assertFailsWith<CloudFailure> { s.scan!!.trigger(h.zone) };assertEquals(0,h.writes)
    }
    @Test fun directApplyRechecksProofAfterLastConstraintsCallback()=runTest {
        val h=ExtendedProvider();h.candidate()
        val row=t7Candidate(h.candidates.single().jsonObject,com.flarego.core.cloudflare.zones.CloudflareZoneClient(h.connection,h.gateway).get(h.zone),h.hash)
        val api=AuthorizedManagementSessions(h.connection,h.gateway,h.issuer,h.hash,constraints={ h.issuer.invalidate(h.connection.id);DnsConstraints() }).services().scan!!
        assertFailsWith<CloudFailure> { api.apply(h.zone,DnsScanReviewInput(emptyList(),listOf(row.id),mapOf(row.id to row.selectionHash))) }
        assertEquals(0,h.writes)
    }

}
