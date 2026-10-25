package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import kotlin.test.*

/** Current evidence overlay only. Never runtime routing, authority or cloud acceptance. */
class D1CoverageTest {
    private data class Row(val suffix:String,val handler:String,val test:String,val tag:String,val semantics:String)
    private val rows=listOf(
        Row("list-databases","listDatabases","directoryAndDetailUseNumericPagingAndOwnedUuid","d1-db-\$uuid","READ"),
        Row("create-database","createDatabase","createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards","d1-create","WRITE"),
        Row("get-database","getDatabase","directoryAndDetailUseNumericPagingAndOwnedUuid","d1-details","READ"),
        Row("update-database","updateDatabase","createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards","d1-open-PUT","WRITE"),
        Row("update-partial-database","patchDatabase","createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards","d1-open-PATCH","WRITE"),
        Row("delete-database","deleteDatabase","createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards","d1-open-DELETE","WRITE"),
        Row("undelete-database","undeleteDatabase","createPutPatchDeleteAndUndeleteSendExactTypedBodiesAndGuards","d1-undelete","WRITE"),
        Row("query-database","query","arbitrarySqlUsesOneShotStreamWithGuardWhileBrowseCannotAcceptSql","d1-open-SQL","WRITE"),
        Row("raw-database-query","raw","arbitrarySqlUsesOneShotStreamWithGuardWhileBrowseCannotAcceptSql","d1-open-RAW","WRITE"),
        Row("export-database","exportDatabase","importExportAndTimeTravelPreserveExactPhaseAndLocator","d1-open-EXPORT","WRITE"),
        Row("import-database","importDatabase","importExportAndTimeTravelPreserveExactPhaseAndLocator","d1-import-sql","WRITE"),
        Row("time-travel-get-bookmark","getBookmark","importExportAndTimeTravelPreserveExactPhaseAndLocator","d1-read-bookmark","READ"),
        Row("time-travel-restore","restore","importExportAndTimeTravelPreserveExactPhaseAndLocator","d1-open-RESTORE","WRITE"),
    )
    private val sourcePaths=setOf(
        "android-platform/src/main/kotlin/com/flarego/platform/D1DocumentFiles.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/D1EncryptedContent.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/D1SignedTransfers.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/D1DocumentFilesTest.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/D1EncryptedContentTest.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/D1SignedTransfersTest.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/D1WireTest.kt",
        "app/src/androidTest/kotlin/com/flarego/D1ManagementUiTest.kt",
        "app/src/main/kotlin/com/flarego/D1DocumentBridge.kt",
        "app/src/main/kotlin/com/flarego/FlareGoViewModel.kt",
        "app/src/main/kotlin/com/flarego/MainActivity.kt",
        "app/src/main/kotlin/com/flarego/ManagementPlatformOwner.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1BoundedSource.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1Content.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1Export.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1FilePipeline.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1JsonCursor.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1Mutation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1ResultJson.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1SqlJson.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1Validation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/d1/D1WriteRuntime.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/operations/OperationCoordinator.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/d1/CloudflareD1Client.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/d1/D1Wire.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedD1Session.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/CloudflareD1WriteAuthorityClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/D1TokenPolicyEvaluation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/D1Policies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/D1Models.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/D1WriteAuthority.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/D1ContentPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/D1Port.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/D1SignedTransferPort.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1ClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1ContentTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1CoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1FilePipelineTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1PolicyTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1ResultJsonTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1SqlJsonTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1ValidationTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1WriteAuthorityTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/D1WriteRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvCoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/P2CoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2CoverageTest.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/FlareGoApp.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/Screens.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/d1/D1Browser.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/d1/D1BrowserHost.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/d1/D1ContentOwner.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/SecureWindowPort.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/d1/D1BrowserTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/d1/D1ContentOwnerTest.kt",
    )
    private val matrixPath="docs/cloudflare-audit/d1-runtime-support-2026-10-25.json"
    private val root:Path get()=generateSequence(Path.of("").toAbsolutePath()){it.parent}.first{Files.isRegularFile(it.resolve("docs/cloudflare-audit/d1-current-schema-2026-10-25.json"))}
    private fun read(path:String)=Files.readString(root.resolve(path))
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun matrix()=Json.parseToJsonElement(read(matrixPath)).jsonObject
    private fun validateSources(claims:JsonObject){
        assertEquals(sourcePaths,claims.keys)
        for((path,digest)in claims)assertEquals(digest.jsonPrimitive.content,sha(Files.readAllBytes(root.resolve(path))),"Source drift: $path")
    }
    private fun validateOperations(operations:JsonObject){
        assertEquals(rows.map{"d1-"+it.suffix}.toSet(),operations.keys)
        for(row in rows){
            val item=operations.getValue("d1-"+row.suffix).jsonObject
            assertEquals(JsonPrimitive(row.handler),item["handler"]);assertEquals(JsonPrimitive(row.test),item["test"])
            assertEquals(JsonPrimitive(row.tag),item["native_tag"]);assertEquals(JsonPrimitive("TYPED_NATIVE"),item["technical_support"])
            assertEquals(JsonPrimitive(false),item["authenticated_cloud_accepted"])
            assertTrue(CloudflareD1Client::class.java.methods.any{it.name==row.handler})
            D1ClientTest::class.java.getDeclaredMethod(row.test)
        }
    }
    @Test fun thirteenLiteralOperationsMatchFrozenTwelveAndCurrentUndeleteSupplement(){
        val original=Files.readAllBytes(System.getenv("SCHEMA_FILE")?.let(Path::of) ?: root.resolve(".superpowers/cloudflare/openapi.json"))
        assertEquals("06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461",sha(original))
        val current=Files.readAllBytes(root.resolve("docs/cloudflare-audit/d1-current-schema-2026-10-25.json"))
        assertEquals("d207fe8955d0fd016652902b72cfee5f690e0a4da7ac59849f0e644198a54310",sha(current))
        fun routes(bytes:ByteArray):Map<String,Pair<String,String>> = buildMap {
            for((path,methods)in Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("paths").jsonObject)
                for((method,value)in methods.jsonObject){val op=value as? JsonObject?:continue;val id=op["operationId"]?.jsonPrimitive?.content?:continue
                    if(id.startsWith("d1-"))put(id,method.uppercase() to path)
                }
        }
        val old=routes(original);val now=routes(current)
        assertEquals(12,old.size);assertEquals(old.keys+"d1-undelete-database",now.keys)
        assertEquals(old,now.filterKeys{it in old});assertEquals(rows.map{"d1-"+it.suffix}.toSet(),now.keys)
        for(row in rows){val id="d1-"+row.suffix;val registration=assertNotNull(ActionPolicies.registry.registration(id))
            assertEquals(now.getValue(id),registration.method to registration.pathTemplate)
            assertEquals(row.semantics,registration.policy.semantics.name);assertEquals(ActionScope.ACCOUNT,registration.scope)
            if(row.semantics=="WRITE")assertFalse(registration.policy.retry.enabled)
        }
        assertEquals(131,ActionPolicies.registry.registrations.size)
        assertEquals(62,ActionPolicies.registry.registrations.values.count{it.policy.semantics==ActionSemantics.READ})
        assertEquals(69,ActionPolicies.registry.registrations.values.count{it.policy.semantics==ActionSemantics.WRITE})
        val m=matrix();assertEquals(JsonPrimitive(13),m["current_count"]);assertEquals(JsonPrimitive(12),m["new_count"])
        validateOperations(m.getValue("operations").jsonObject)
    }
    @Test fun currentSourcesAndActualMainNativeEntryStayPinned(){
        val m=matrix();validateSources(m.getValue("source_sha256").jsonObject)
        val native=read("app/src/androidTest/kotlin/com/flarego/D1ManagementUiTest.kt")
        for(row in rows)assertTrue(native.contains(row.tag),"Missing actual native action: ${row.tag}")
        assertTrue(read("app/src/main/kotlin/com/flarego/MainActivity.kt").contains("d1Browser = current.d1Browser"))
        assertTrue(read("app/src/main/kotlin/com/flarego/FlareGoViewModel.kt").contains("AuthorizedD1Session("))
        assertEquals("33b3b4419d60c4b16c95f92436c8f078b311dd04aabb2b7d43def7cdefb3a3ff",sha(Files.readAllBytes(root.resolve("docs/cloudflare-audit/kv-runtime-support-2026-10-25.json"))))
    }
    @Test fun missingExtraSwappedAndDriftedClaimsAreRejected(){
        val m=matrix();val claims=m.getValue("source_sha256").jsonObject;val victim=sourcePaths.first()
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims-victim))}
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims+(victim to JsonPrimitive("0".repeat(64)))))}
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims+("README.md" to JsonPrimitive("0".repeat(64)))))}
        val ops=m.getValue("operations").jsonObject;val id="d1-get-database"
        assertFailsWith<AssertionError>{validateOperations(JsonObject(ops-id))}
        assertFailsWith<AssertionError>{validateOperations(JsonObject(ops+("d1-unreviewed" to ops.getValue(id))))}
        assertFailsWith<AssertionError>{validateOperations(JsonObject(ops+(id to JsonObject(ops.getValue(id).jsonObject+("handler" to JsonPrimitive("listDatabases"))))))}
    }
    @Test fun compiledWireBehaviorAnchorsActuallyExecute(){
        for(test in rows.map{it.test}.distinct())D1ClientTest::class.java.getDeclaredMethod(test).invoke(D1ClientTest())
    }
}
