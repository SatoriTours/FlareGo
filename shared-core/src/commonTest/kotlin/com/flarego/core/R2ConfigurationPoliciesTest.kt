package com.flarego.core

import com.flarego.core.application.r2.supportsR2ConfigurationJurisdiction
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.ActionScope
import com.flarego.core.cloudflare.protocol.ActionSemantics
import com.flarego.core.cloudflare.protocol.ResponseKind
import com.flarego.core.cloudflare.protocol.RiskLevel
import com.flarego.core.cloudflare.protocol.r2ConfigurationRegistrations
import com.flarego.core.cloudflare.protocol.r2Registrations
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2_JURISDICTIONS
import com.flarego.core.model.r2ConfigurationReadKey
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batch B1 allowlist is handwritten and reviewed. These assertions pin the exact ten
 * operations, their scope, risk and response handling, prove that batch A keeps its own
 * separate function, and check the pinned schema for the method, path and the controlled
 * `cf-r2-jurisdiction` parameter.
 */
class R2ConfigurationPoliciesTest {
    private val bucket = "/accounts/{account_id}/r2/buckets/{bucket_name}"

    private data class Expected(val method: String, val path: String, val semantics: ActionSemantics, val risk: RiskLevel)

    private val expected =
        linkedMapOf(
            "r2-get-bucket-cors-policy" to Expected("GET", "$bucket/cors", ActionSemantics.READ, RiskLevel.READ_ONLY),
            "r2-put-bucket-cors-policy" to Expected("PUT", "$bucket/cors", ActionSemantics.WRITE, RiskLevel.CHANGE),
            "r2-delete-bucket-cors-policy" to Expected("DELETE", "$bucket/cors", ActionSemantics.WRITE, RiskLevel.DESTRUCTIVE),
            "r2-get-bucket-lifecycle-configuration" to Expected("GET", "$bucket/lifecycle", ActionSemantics.READ, RiskLevel.READ_ONLY),
            "r2-put-bucket-lifecycle-configuration" to Expected("PUT", "$bucket/lifecycle", ActionSemantics.WRITE, RiskLevel.DESTRUCTIVE),
            "r2-get-bucket-local-uploads-configuration" to Expected("GET", "$bucket/local-uploads", ActionSemantics.READ, RiskLevel.READ_ONLY),
            "r2-put-bucket-local-uploads-configuration" to Expected("PUT", "$bucket/local-uploads", ActionSemantics.WRITE, RiskLevel.CHANGE),
            "r2-get-bucket-lock-configuration" to Expected("GET", "$bucket/lock", ActionSemantics.READ, RiskLevel.READ_ONLY),
            "r2-put-bucket-lock-configuration" to Expected("PUT", "$bucket/lock", ActionSemantics.WRITE, RiskLevel.DESTRUCTIVE),
            "r2-delete-bucket-lock-configuration" to Expected("DELETE", "$bucket/lock", ActionSemantics.WRITE, RiskLevel.DESTRUCTIVE),
        )

    private val kindOf =
        mapOf(
            "r2-get-bucket-cors-policy" to R2ConfigurationKind.CORS,
            "r2-put-bucket-cors-policy" to R2ConfigurationKind.CORS,
            "r2-delete-bucket-cors-policy" to R2ConfigurationKind.CORS,
            "r2-get-bucket-lifecycle-configuration" to R2ConfigurationKind.LIFECYCLE,
            "r2-put-bucket-lifecycle-configuration" to R2ConfigurationKind.LIFECYCLE,
            "r2-get-bucket-local-uploads-configuration" to R2ConfigurationKind.LOCAL_UPLOADS,
            "r2-put-bucket-local-uploads-configuration" to R2ConfigurationKind.LOCAL_UPLOADS,
            "r2-get-bucket-lock-configuration" to R2ConfigurationKind.LOCK,
            "r2-put-bucket-lock-configuration" to R2ConfigurationKind.LOCK,
            "r2-delete-bucket-lock-configuration" to R2ConfigurationKind.LOCK,
        )

    private fun schema(): JsonObject {
        val root =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("docs/cloudflare-audit/review.json")) }
        val file = System.getenv("SCHEMA_FILE")?.let { Path.of(it) } ?: root.resolve(".superpowers/cloudflare/openapi.json")
        assertTrue(Files.isRegularFile(file), "the pinned schema is required for the B1 route check")
        return Json.parseToJsonElement(Files.readString(file)).jsonObject
    }

    @Test
    fun registersExactlyTheTenReviewedB1Operations() {
        val registrations = r2ConfigurationRegistrations()
        assertEquals(expected.keys, registrations.map { it.policy.key }.toSet())
        assertEquals(10, registrations.size)
        assertEquals(4, registrations.count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(6, registrations.count { it.policy.semantics == ActionSemantics.WRITE })
        for (registration in registrations) {
            val route = expected.getValue(registration.policy.key)
            assertEquals(route.method, registration.method, registration.policy.key)
            assertEquals(route.path, registration.pathTemplate, registration.policy.key)
            assertEquals(ActionScope.ACCOUNT, registration.scope, registration.policy.key)
            assertEquals(route.semantics, registration.policy.semantics, registration.policy.key)
            assertEquals(route.risk, registration.policy.risk, registration.policy.key)
            assertEquals(ResponseKind.ENVELOPE_JSON, registration.policy.responseKind, registration.policy.key)
            assertFalse(registration.trailingPlaceholderSpansSegments, registration.policy.key)
            assertTrue(registration.pathTemplate.startsWith("/accounts/{account_id}/r2/buckets/"), registration.policy.key)
        }
    }

    @Test
    fun writesNeverRetryAndReadsKeepTheBoundedReadOnlyRetry() {
        for (registration in r2ConfigurationRegistrations()) {
            if (registration.policy.semantics == ActionSemantics.READ) {
                assertTrue(registration.policy.retry.enabled, registration.policy.key)
                assertEquals(2, registration.policy.retry.maxRetries)
                assertEquals(60_000L, registration.policy.retry.maxWaitMs)
            } else {
                assertFalse(registration.policy.retry.enabled, registration.policy.key)
            }
        }
    }

    @Test
    fun lifecycleAndLockChangesStayStaticallyHighRiskAndBatchAKeepsItsOwnFunction() {
        val risk = r2ConfigurationRegistrations().associate { it.policy.key to it.policy.risk }
        assertEquals(RiskLevel.DESTRUCTIVE, risk.getValue("r2-put-bucket-lifecycle-configuration"))
        assertEquals(RiskLevel.DESTRUCTIVE, risk.getValue("r2-put-bucket-lock-configuration"))
        assertEquals(RiskLevel.DESTRUCTIVE, risk.getValue("r2-delete-bucket-lock-configuration"))
        // Batch A owns exactly its own ten reviewed operations, in its own function.
        assertEquals(10, r2Registrations().size)
        assertTrue(r2Registrations().none { it.policy.key in expected })
    }

    @Test
    fun theRegistryExposesTheReviewedOperationsAndNoUnapprovedOnes() {
        for (id in expected.keys) assertNotNull(ActionPolicies.policy(id), id)
        // Batch B3 Sippy is registered by `r2SippyRegistrations()` under the approved B3 plan, so it
        // is no longer in this "must stay unregistered" list. Only the separately approved
        // temporary-credential operation (R2-E) still must stay unregistered.
        assertNull(ActionPolicies.policy("r2-create-temp-access-credentials"))
    }

    @Test
    fun theTenRoutesAndTheirJurisdictionParametersMatchThePinnedSchema() {
        val paths = schema().getValue("paths").jsonObject
        val found = mutableMapOf<String, Pair<String, Boolean>>()
        for ((path, operations) in paths) {
            val rows = operations as? JsonObject ?: continue
            for ((method, value) in rows) {
                val operation = value as? JsonObject ?: continue
                val id = (operation["operationId"] as? JsonPrimitive)?.content ?: continue
                if (id !in expected) continue
                val declaresJurisdiction =
                    operation.getValue("parameters").jsonArray.any { parameter ->
                        val entry = parameter as? JsonObject ?: return@any false
                        (entry["name"] as? JsonPrimitive)?.content == "cf-r2-jurisdiction" ||
                            (entry["\$ref"] as? JsonPrimitive)?.content == "#/components/parameters/r2_cf-r2-jurisdiction"
                    }
                assertEquals(null, found[id], id)
                found[id] = method.uppercase() to declaresJurisdiction
            }
        }
        assertEquals(expected.keys, found.keys, "every B1 operationId must exist in the pinned schema")

        for ((id, route) in expected) {
            val (method, declaresJurisdiction) = found.getValue(id)
            assertEquals(route.method, method, id)
            assertEquals(
                id,
                paths.getValue(route.path).jsonObject.getValue(route.method.lowercase()).jsonObject.getValue("operationId").jsonPrimitive.content,
            )
            assertEquals(
                supportsR2ConfigurationJurisdiction(kindOf.getValue(id)),
                declaresJurisdiction,
                "$id must declare the controlled jurisdiction header exactly when the endpoint supports it",
            )
        }
        // The four reads are exactly the read keys the client uses.
        for ((id, kind) in kindOf) if (id.startsWith("r2-get-")) assertEquals(id, r2ConfigurationReadKey(kind))

        // The independent bucket proof uses the same pinned endpoint and header.
        val bucketGet = paths.getValue(bucket).jsonObject.getValue("get").jsonObject
        assertEquals("r2-get-bucket", bucketGet.getValue("operationId").jsonPrimitive.content)
        assertTrue(
            bucketGet.getValue("parameters").jsonArray.any { parameter ->
                val entry = parameter as? JsonObject ?: return@any false
                (entry["name"] as? JsonPrimitive)?.content == "cf-r2-jurisdiction" ||
                    (entry["\$ref"] as? JsonPrimitive)?.content == "#/components/parameters/r2_cf-r2-jurisdiction"
            },
        )

        val declared =
            schema().getValue("components").jsonObject.getValue("parameters").jsonObject
                .getValue("r2_cf-r2-jurisdiction").jsonObject.getValue("schema").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(R2_JURISDICTIONS, declared)
    }
}
