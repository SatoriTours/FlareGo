package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.ZoneSettingKey
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.*
import kotlin.test.*

/** Audit assertions only: this data never grants routing, permission or authenticated acceptance. */
class P2CoverageTest {
    private val commit="6b00f157496cd6a1792a613dc94654c51273aeb9"
    private val schemaSha="06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461"
    private val historicSha="3f20a3ea404a5692725c685eb664beab0a8e3ccb11ef1c4202e92fdeb1b534be"
    private val root:Path get()=generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("docs/cloudflare-audit/p2-candidate-endpoints.csv")) }
    private fun read(relative:String)=Files.readString(root.resolve(relative))
    private fun json(relative:String)=Json.parseToJsonElement(read(relative)).jsonObject
    private val mappingPath="docs/cloudflare-audit/p2-runtime-support-2026-10-17.json"
    private fun mapping():JsonObject {
        assertTrue(Files.isRegularFile(root.resolve(mappingPath)),"54-candidate typed handler/test evidence mapping must be delivered: $mappingPath")
        return json(mappingPath)
    }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun JsonObject.text(key:String)=getValue(key).jsonPrimitive.content
    private val auxiliary=mapOf(
        "user-api-tokens-token-details" to "/user/tokens/{token_id}",
        "account-api-tokens-token-details" to "/accounts/{account_id}/tokens/{token_id}",
        "permission-groups-list-permission-groups" to "/user/tokens/permission_groups",
        "account-api-tokens-list-permission-groups" to "/accounts/{account_id}/tokens/permission_groups")
    private val historicalColumns=listOf("operation_id","method","path","tags","proposed_task","baseline_status","review_status","schema_commit")

    // RFC4180 subset sufficient for this committed UTF-8 CSV, including BOM, CRLF and quoted commas.
    private fun csv(text:String):List<List<String>> {
        val result=mutableListOf<List<String>>();var row=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var i=0;val data=text.removePrefix("\uFEFF")
        while(i<data.length) {
            val c=data[i]
            if(c=='"') { if(quoted && i+1<data.length && data[i+1]=='"') { field.append('"');i++ } else quoted=!quoted }
            else if(!quoted && c==',') { row.add(field.toString());field.clear() }
            else if(!quoted && (c=='\n' || c=='\r')) {
                if(c=='\r' && i+1<data.length && data[i+1]=='\n')i++
                row.add(field.toString());field.clear();result.add(row);row=mutableListOf()
            } else field.append(c)
            i++
        }
        assertFalse(quoted,"Unclosed CSV quotation")
        if(row.isNotEmpty() || field.isNotEmpty()) { row.add(field.toString());result.add(row) }
        return result
    }
    private fun candidates():List<Map<String,String>> {
        val rows=csv(read("docs/cloudflare-audit/p2-candidate-endpoints.csv"));val columns=rows.first()
        assertEquals(historicalColumns,columns.take(8),"Historical columns cannot be removed/reordered")
        return rows.drop(1).map { values -> assertEquals(columns.size,values.size);columns.zip(values).toMap() }
    }
    private fun denominator(rows:List<Map<String,String>>) {
        assertEquals(54,rows.size,"All 54 candidates remain in the denominator")
        assertEquals(54,rows.map { it.getValue("operation_id") }.toSet().size)
        val original=(listOf(historicalColumns)+rows.map { row -> historicalColumns.map(row::getValue) })
            .joinToString("\u001e") { it.joinToString("\u001f") }
        assertEquals(historicSha,sha(original.encodeToByteArray()),"Original 54 rows/eight fields must remain byte-value equivalent")
    }
    private fun policyRow(r:ActionRegistration)=buildJsonObject {
        put("method",r.method);put("path",r.pathTemplate);put("scope",r.scope.name)
        put("semantics",r.policy.semantics.name);put("response_kind",r.policy.responseKind.name);put("risk",r.policy.risk.name)
        put("retry",buildJsonObject { put("enabled",r.policy.retry.enabled);put("max_retries",r.policy.retry.maxRetries);put("max_wait_ms",r.policy.retry.maxWaitMs) })
    }
    private fun actualRegistry()=JsonObject(ActionPolicies.registry.registrations.toSortedMap().mapValues { policyRow(it.value) })
    private fun expectedIds(rows:List<Map<String,String>>):Set<String> {
        val p1=json("docs/cloudflare-audit/p1-runtime-support-2026-10-16.json").getValue("operations").jsonObject.keys
        assertEquals(16,p1.size)
        val ids=rows.map { it.getValue("operation_id") }.toSet()
        assertEquals(5,(p1 intersect ids).size);assertEquals(65,(p1+ids).size)
        assertTrue((auxiliary.keys intersect (p1+ids)).isEmpty())
        return p1+ids+auxiliary.keys
    }
    /**
     * Reviewed keys from later batches. The gate keeps forbidding unreviewed actions:
     * every new batch must add its own handwritten registration function here, and
     * no assertion is relaxed to a subset or an "at least" count.
     */
    private fun reviewedLaterBatchIds():Set<String> = r2Registrations().map { it.policy.key }.toSet()

    private fun checkRegistry(actual:JsonObject,rows:List<Map<String,String>>,manifest:JsonObject) {
        denominator(rows)
        val reviewed=expectedIds(rows)+reviewedLaterBatchIds()
        assertEquals(reviewed,actual.keys,"Missing route or unreviewed extra action (including writes)")
        assertEquals(reviewed.size,actual.size)
        val p1=json("docs/cloudflare-audit/p1-runtime-support-2026-10-16.json").getValue("operations").jsonObject
        val candidateIds=rows.map { it.getValue("operation_id") }.toSet()
        for((id,value) in p1.filterKeys { it !in candidateIds }) {
            val previous=value.jsonObject
            assertEquals("GET",previous.text("method"),"The frozen non-overlapping P1 subset is READ only")
            val path=previous.text("path")
            val expectedP1=buildJsonObject {
                put("method","GET");put("path",path);put("scope",if(path.startsWith("/user/") || path=="/accounts")"USER" else "ACCOUNT")
                put("semantics","READ");put("response_kind","ENVELOPE_JSON");put("risk","READ_ONLY")
                put("retry",buildJsonObject { put("enabled",true);put("max_retries",2);put("max_wait_ms",60000) })
            }
            assertEquals(expectedP1,actual.getValue(id),"Historical P1 route/policy: $id")
        }
        val expected=manifest.getValue("operations").jsonObject+manifest.getValue("auxiliary_operations").jsonObject
        for((id,value) in expected) {
            val row=value.jsonObject;assertEquals(row.getValue("policy"),actual.getValue(id),"Exact registry policy: $id")
        }
        for((id,path) in auxiliary) {
            val r=actual.getValue(id).jsonObject
            assertEquals("GET",r.text("method"));assertEquals(path,r.text("path"));assertEquals("READ",r.text("semantics"))
        }
        for((id,r) in actual) if(r.jsonObject.text("semantics")=="WRITE")assertEquals(JsonPrimitive(false),r.jsonObject.getValue("retry").jsonObject["enabled"],id)
    }
    private fun checkEvidence(manifest:JsonObject,rows:List<Map<String,String>>) {
        assertEquals(commit,manifest.text("schema_commit"));assertEquals(schemaSha,manifest.text("schema_sha256"))
        for((path,digest) in manifest.getValue("source_sha256").jsonObject) {
            assertEquals(digest.jsonPrimitive.content,sha(Files.readAllBytes(root.resolve(path))),"Evidence source drift: $path")
        }
        assertEquals(JsonPrimitive(false),manifest["authenticated_cloud_accepted"])
        assertEquals(JsonPrimitive(false),manifest["paid_eligibility_verified"])
        assertEquals(ZoneSettingKey.entries.map { it.route },manifest.getValue("settings_parameterized_evidence").jsonObject.getValue("targets").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(rows.map { it.getValue("operation_id") }.toSet(),manifest.getValue("operations").jsonObject.keys)
        assertEquals(auxiliary.keys,manifest.getValue("auxiliary_operations").jsonObject.keys)
        for((id,value) in manifest.getValue("operations").jsonObject+manifest.getValue("auxiliary_operations").jsonObject) {
            val row=value.jsonObject
            assertEquals("TYPED_HANDLER",row.text("technical_support"),"Cannot relabel missing code as blocked: $id")
            assertEquals(JsonPrimitive(false),row["authenticated_cloud_accepted"],id)
            val handler=row.getValue("handler").jsonObject
            assertTrue(Files.isRegularFile(root.resolve(handler.text("source_file"))),"Missing handler file: $id")
            assertTrue(Class.forName(handler.text("class")).methods.any { it.name==handler.text("method") },"Missing compiled typed handler: $id")
            val tests=row.getValue("tests").jsonArray
            assertTrue(tests.isNotEmpty(),"Missing behavior evidence: $id")
            for(t in tests) {
                val ref=t.jsonObject;assertTrue(Files.isRegularFile(root.resolve(ref.text("source_file"))))
                assertNotNull(Class.forName(ref.text("class")).getDeclaredMethod(ref.text("method")),"Missing compiled test: $id")
            }
        }
    }

    @Test fun pinnedDenominatorAndSchemaKeepEveryOriginalCandidate() {
        val rows=candidates();denominator(rows)
        val configured=System.getenv("SCHEMA_FILE")
        val file=if(configured!=null)Path.of(configured) else root.resolve(".superpowers/cloudflare/openapi.json")
        assertTrue(Files.isRegularFile(file),"SCHEMA_FILE must name the pinned public schema (no skip/fallback for a bad override)")
        val bytes=Files.readAllBytes(file);assertEquals(schemaSha,sha(bytes))
        val paths=Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("paths").jsonObject
        for((id,value) in actualRegistry()) {
            val row=value.jsonObject
            assertEquals(id,paths.getValue(row.text("path")).jsonObject.getValue(row.text("method").lowercase()).jsonObject.text("operationId"))
        }
    }
    @Test fun actualRegistryHasExactly54CandidatesP1Union65AndFourSeparateAuxReads() {
        val actual=actualRegistry()
        System.getenv("P2_COVERAGE_EVIDENCE")?.let { output ->
            Files.createDirectories(Path.of(output));Files.writeString(Path.of(output).resolve("actual-registry.json"),actual.toString())
        }
        println("Actual ActionPolicies.registry.registrations: ${actual.size} entries")
        checkRegistry(actual,candidates(),mapping())
    }
    @Test fun all54HaveRealCompiledHandlersAndConcreteTestEvidence() {
        val m=mapping();val rows=candidates();checkEvidence(m,rows)
        val review=json("docs/cloudflare-audit/review.json").getValue("operations").jsonObject
        for(row in rows) {
            val id=row.getValue("operation_id")
            assertEquals("TYPED_HANDLER",row.getValue("p2_technical_support"))
            val entry=m.getValue("operations").jsonObject.getValue(id).jsonObject
            val handler=entry.getValue("handler").jsonObject
            assertEquals(handler.text("class")+"#"+handler.text("method"),row.getValue("p2_handler"))
            assertEquals(entry.getValue("tests").jsonArray.joinToString(";") { it.jsonObject.text("class")+"#"+it.jsonObject.text("method") },row.getValue("p2_test_keys"))
            assertEquals("UNVERIFIED",row.getValue("p2_cloud_acceptance"))
            assertEquals(mappingPath+"#operations/"+id,row.getValue("p2_runtime_evidence"))
            assertEquals(mappingPath+"#operations/"+id,review.getValue(id).jsonObject.getValue("evidence").jsonObject.getValue("p2_runtime_support_2026_10_17").jsonObject.text("reference"))
        }
    }
    @Test fun extraWritesMissingRegistrationsAndPolicyDriftCannotPassCoverage() {
        val m=mapping();val rows=candidates();val actual=actualRegistry();checkRegistry(actual,rows,m)
        assertFailsWith<AssertionError> { checkRegistry(JsonObject(actual-actual.keys.first()),rows,m) }
        val rogue=ActionRegistration(ActionPolicy("unreviewed-write",ActionSemantics.WRITE,ResponseKind.ENVELOPE_JSON,RiskLevel.CHANGE,ReadRetryPolicy()),"POST","/zones/{zone_id}/unreviewed",ActionScope.ZONE)
        assertFailsWith<AssertionError> { checkRegistry(JsonObject(actual+("unreviewed-write" to policyRow(rogue))),rows,m) }
        val id="dns-records-for-a-zone-create-dns-record"
        for((key,bad) in mapOf("scope" to JsonPrimitive("USER"),"response_kind" to JsonPrimitive("TEXT"),"retry" to buildJsonObject { put("enabled",true);put("max_retries",2);put("max_wait_ms",60000) })) {
            assertFailsWith<AssertionError>(key) { checkRegistry(JsonObject(actual+(id to JsonObject(actual.getValue(id).jsonObject+(key to bad)))),rows,m) }
        }
    }
    @Test fun missingDenominatorHandlerOrTestCannotBeHiddenAsBlocked() {
        val m=mapping();val rows=candidates();checkEvidence(m,rows)
        assertFailsWith<AssertionError> { denominator(rows.dropLast(1)) }
        val id=rows.first().getValue("operation_id");val ops=m.getValue("operations").jsonObject;val row=ops.getValue(id).jsonObject
        for(bad in listOf(JsonObject(row+("tests" to JsonArray(emptyList()))),JsonObject(row+("technical_support" to JsonPrimitive("BLOCKED"))),JsonObject(row+("handler" to buildJsonObject { put("source_file","missing.kt");put("class","missing");put("method","missing") })))) {
            assertFailsWith<AssertionError> { checkEvidence(JsonObject(m+("operations" to JsonObject(ops+(id to bad)))),rows) }
        }
    }
    @Test fun referencedTypedBehaviorEvidenceActuallyRunsIncludingAll29SettingsRoutes() {
        val m=mapping();checkEvidence(m,candidates())
        val refs=(m.getValue("operations").jsonObject.values+m.getValue("auxiliary_operations").jsonObject.values)
            .flatMap { it.jsonObject.getValue("tests").jsonArray }.map { it.jsonObject }
            .distinctBy { it.text("class")+"#"+it.text("method") }
        val executed=mutableListOf<String>()
        for(ref in refs) {
            val type=Class.forName(ref.text("class"));val method=type.getDeclaredMethod(ref.text("method"))
            try { method.invoke(type.getDeclaredConstructor().newInstance()) }
            catch(e:InvocationTargetException) { throw AssertionError("Real typed behavior failed: ${ref.text("class")}#${ref.text("method")}",e.cause) }
            executed+=ref.text("class")+"#"+ref.text("method")
        }
        val settings="com.flarego.core.ZoneSettingsTest#"
        assertTrue(settings+"everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget" in executed)
        assertTrue(settings+"universalSslUsesDedicatedEndpointAndVerifiesReadbackWithoutClaimingCertificateIssuance" in executed)
        assertTrue(settings+"controlledPurgeVariantsNeverFallBackToAllAndUrlsOnlyEnterPayload" in executed)
        System.getenv("P2_COVERAGE_EVIDENCE")?.let { Files.writeString(Path.of(it).resolve("executed-evidence-keys.json"),JsonArray(executed.map(::JsonPrimitive)).toString()) }
    }
}
