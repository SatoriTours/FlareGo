package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import kotlin.test.*

/** Evidence-only gate: no runtime registration, permission, input or cloud acceptance comes from JSON. */
class KvCoverageTest {
    private data class Row(val suffix:String,val handler:String,val test:String,val tag:String,val semantics:String)
    private val rows=listOf(
        Row("list-namespaces","listNamespaces","namespacesUseExactNumberPagingAndRequireCreationIdentity","kv-refresh-namespaces","READ"),
        Row("create-a-namespace","createNamespace","namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard","kv-create","WRITE"),
        Row("get-a-namespace","getNamespace","namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard","kv-get-namespace","READ"),
        Row("rename-a-namespace","renameNamespace","namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard","kv-rename","WRITE"),
        Row("remove-a-namespace","deleteNamespace","namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard","kv-delete-namespace","WRITE"),
        Row("list-a-namespace'-s-keys","listKeys","keysUseLimitNotPerPageAndKeepEmptyPageWithOpaqueCursor","kv-refresh-keys","READ"),
        Row("read-the-metadata-for-a-key","readMetadata","listingNeverPublishesMetadataAndSingleMetadataIsSensitive","kv-read-metadata","READ"),
        Row("read-key-value-pair","readValue","exactBinaryReadUsesStreamingAndRefusesPartialOrNoContentStatuses","kv-read-value","READ"),
        Row("write-key-value-pair-with-metadata","putValue","valueOverwriteStreamsExactBytesAndMetadataWithOneExplicitExpiryStrategy","kv-prepare","WRITE"),
        Row("delete-key-value-pair","deleteValue","namespaceGetCreateRenameDeleteUseOwnedRoutesAndSevenWriteGuard","kv-delete-key","WRITE"),
        Row("get-multiple-key-value-pairs","bulkRead","bulkReadIsExplicitPostWithoutWholeResponseBuffering","kv-bulk-read","READ"),
        Row("write-multiple-key-value-pairs","bulkWrite","bulkWritesAndCurrentPostDeletesValidateCompletePartialResult","kv-bulk-write","WRITE"),
        Row("delete-multiple-key-value-pairs","bulkDelete","bulkWritesAndCurrentPostDeletesValidateCompletePartialResult","kv-bulk-delete","WRITE"),
    )
    private val sourcePaths=setOf(
        "android-platform/src/main/kotlin/com/flarego/platform/CloudflareClient.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/KvDocumentFiles.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/KvEncryptedContent.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/KvDocumentFilesTest.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/KvEncryptedContentTest.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/KvWireTest.kt",
        "app/src/androidTest/kotlin/com/flarego/KvManagementUiTest.kt",
        "app/src/main/kotlin/com/flarego/FlareGoViewModel.kt",
        "app/src/main/kotlin/com/flarego/KvDocumentBridge.kt",
        "app/src/main/kotlin/com/flarego/MainActivity.kt",
        "app/src/main/kotlin/com/flarego/ManagementPlatformOwner.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/kv/KvBulkJson.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/kv/KvExport.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/kv/KvMutation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/kv/KvValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/kv/KvWriteRuntime.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/operations/OperationCoordinator.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/HttpTransport.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedKvSession.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/CloudflareKvWriteAuthorityClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/KvTokenPolicyEvaluation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/kv/CloudflareKvClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/kv/KvEncodedKeyPath.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/KvPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/KvModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/KvWriteAuthority.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/KvContentPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/KvPort.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvBulkJsonTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvCoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvExportTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvPolicyTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvValidationTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvWriteAuthorityTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/KvWriteRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/P2CoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2CoverageTest.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/FlareGoApp.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/Screens.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/kv/KvBrowser.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/kv/KvBrowserHost.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/kv/KvContentOwner.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/SecureWindowPort.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/kv/KvBrowserTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/kv/KvContentOwnerTest.kt",
    )
    private val prefix="workers-kv-namespace-"
    private val deprecated=prefix+"delete-multiple-key-value-pairs-deprecated"
    private val root:Path get()=generateSequence(Path.of("").toAbsolutePath()){it.parent}.first{Files.isRegularFile(it.resolve("docs/cloudflare-audit/kv-runtime-support-2026-10-25.json"))}
    private fun read(path:String)=Files.readString(root.resolve(path))
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun matrix():JsonObject {
        val historical=read("docs/cloudflare-audit/kv-runtime-support-2026-10-25.json")
        assertEquals("33b3b4419d60c4b16c95f92436c8f078b311dd04aabb2b7d43def7cdefb3a3ff",sha(historical.encodeToByteArray()))
        val current=Json.parseToJsonElement(read("docs/cloudflare-audit/d1-runtime-support-2026-10-25.json")).jsonObject
        return JsonObject(Json.parseToJsonElement(historical).jsonObject+("source_sha256" to current.getValue("kv_current_source_sha256")))
    }
    private fun validateSources(claims:JsonObject){
        assertEquals(sourcePaths,claims.keys)
        for((path,digest)in claims)assertEquals(digest.jsonPrimitive.content,sha(Files.readAllBytes(root.resolve(path))),"Source drift: $path")
    }
    private fun validateOperations(operations:JsonObject){
        assertEquals(rows.map{prefix+it.suffix}.toSet(),operations.keys)
        for(row in rows){
            val item=operations.getValue(prefix+row.suffix).jsonObject
            assertEquals(JsonPrimitive(row.handler),item["handler"])
            assertEquals(JsonPrimitive(row.test),item["test"])
            assertEquals(JsonPrimitive(row.tag),item["native_tag"])
            assertEquals(JsonPrimitive("TYPED_NATIVE"),item["technical_support"])
            assertEquals(JsonPrimitive(false),item["authenticated_cloud_accepted"])
            assertTrue(com.flarego.core.cloudflare.kv.CloudflareKvClient::class.java.methods.any{it.name==row.handler})
            KvClientTest::class.java.getDeclaredMethod(row.test)
        }
    }
    @Test fun thirteenLiteralHandlersKeepThePinnedFourteenCandidateDenominatorAndMigration(){
        val schemaPath=System.getenv("SCHEMA_FILE")?:root.resolve(".superpowers/cloudflare/openapi.json").toString()
        val bytes=Files.readAllBytes(Path.of(schemaPath))
        assertEquals("06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461",sha(bytes))
        val paths=Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("paths").jsonObject
        val found=mutableSetOf<String>()
        for((path,methods)in paths)for((method,operation)in methods.jsonObject){
            val op=operation as? JsonObject?:continue
            val id=op["operationId"]?.jsonPrimitive?.content?:continue
            if(!id.startsWith(prefix))continue
            found+=id
            if(id==deprecated){assertEquals(JsonPrimitive(true),op["deprecated"]);continue}
            val row=rows.single{prefix+it.suffix==id}
            val registration=assertNotNull(ActionPolicies.registry.registration(id))
            assertEquals(method.uppercase(),registration.method);assertEquals(path,registration.pathTemplate)
            assertEquals(row.semantics,registration.policy.semantics.name)
            assertEquals(ActionScope.ACCOUNT,registration.scope)
            if(row.semantics=="WRITE")assertFalse(registration.policy.retry.enabled)
        }
        assertEquals(rows.map{prefix+it.suffix}.toSet()+deprecated,found)
        val m=matrix();validateOperations(m.getValue("operations").jsonObject)
        assertEquals(JsonPrimitive(14),m["candidate_count"]);assertEquals(JsonPrimitive(13),m["current_count"])
        assertEquals(JsonPrimitive(prefix+"delete-multiple-key-value-pairs"),m.getValue("deprecated_migration").jsonObject[deprecated])
        assertNull(ActionPolicies.policy(deprecated));assertEquals(131,ActionPolicies.registry.registrations.size)
        assertEquals(62,ActionPolicies.registry.registrations.values.count{it.policy.semantics==ActionSemantics.READ})
        assertEquals(69,ActionPolicies.registry.registrations.values.count{it.policy.semantics==ActionSemantics.WRITE})
    }
    @Test fun currentSourcesAndActualNativeEntryMatchAndFrozenHistoryIsUnchanged(){
        val m=matrix();validateSources(m.getValue("source_sha256").jsonObject)
        val host=read("shared-ui/src/commonMain/kotlin/com/flarego/ui/kv/KvBrowserHost.kt")
        for(row in rows)assertTrue(host.contains("\"${row.tag}\""),"Missing native action: ${row.tag}")
        assertTrue(read("app/src/main/kotlin/com/flarego/MainActivity.kt").contains("kvBrowser = current.kvBrowser"))
        assertTrue(read("app/src/main/kotlin/com/flarego/FlareGoViewModel.kt").contains("AuthorizedKvSession("))
        assertEquals("763e9042c6224ac7faed0e07bc8109a953018c10e982646a605d93b25750f382",sha(Files.readAllBytes(root.resolve("docs/cloudflare-audit/r2-c-runtime-support-2026-10-22.json"))))
        assertEquals("d98a266622f608d1527658b12b6d823b47d5bb997acc798c2955f724de9df2c1",sha(Files.readAllBytes(root.resolve("docs/cloudflare-audit/p2-runtime-support-2026-10-17.json"))))
    }
    @Test fun missingExtraDriftedSourcesOrSwappedHandlerEvidenceAreRejected(){
        val m=matrix();val claims=m.getValue("source_sha256").jsonObject;val victim=sourcePaths.first()
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims-victim))}
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims+(victim to JsonPrimitive("0".repeat(64)))))}
        assertFailsWith<AssertionError>{validateSources(JsonObject(claims+("README.md" to JsonPrimitive("0".repeat(64)))))}
        val operations=m.getValue("operations").jsonObject;val id=prefix+"get-a-namespace"
        assertFailsWith<AssertionError>{validateOperations(JsonObject(operations-id))}
        assertFailsWith<AssertionError>{validateOperations(JsonObject(operations+(deprecated to operations.getValue(id))))}
        assertFailsWith<AssertionError>{validateOperations(JsonObject(operations+(id to JsonObject(operations.getValue(id).jsonObject+("handler" to JsonPrimitive("listNamespaces"))))))}
    }
    @Test fun compiledWireBehaviorAnchorsActuallyExecute(){
        for(test in rows.map{it.test}.distinct())KvClientTest::class.java.getDeclaredMethod(test).invoke(KvClientTest())
    }
}
