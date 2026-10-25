package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Current, evidence-only coverage gate for the R2 slices.
 *
 * Two different things are deliberately kept apart:
 *
 * 1. **Frozen history.** `docs/cloudflare-audit/r2-batch-a-runtime-support-2026-10-18.json` is a
 *    byte-frozen batch A exit document: its `10 typed / 40 not started / 1 deferred` split, its
 *    79-entry registry observation and its source hashes describe that historical snapshot. Its
 *    file SHA is pinned and its own numbers are asserted verbatim; they are never rewritten into
 *    current numbers and never reused as current source hashes.
 * 1b. **Frozen batch B1 history.** `docs/cloudflare-audit/r2-b1-runtime-support-2026-10-21.json`
 *    is likewise byte-frozen now: its 20 typed / 30 not started / 1 deferred split, its 89-entry
 *    registry observation (46 READ / 43 WRITE) and its 59 source hashes describe the batch B1 exit
 *    snapshot. Its file SHA is pinned; its hashes are history and never restored onto current code.
 * 1c. **Frozen batch B2 history.** `docs/cloudflare-audit/r2-b2-runtime-support-2026-10-21.json`
 *    is byte-frozen now: its 27 typed / 23 not started / 1 deferred split, its 96-entry registry
 *    observation (49 READ / 47 WRITE) and its 77 source hashes describe the batch B2 exit snapshot.
 *    Its file SHA is pinned; its hashes are history and never restored onto current code.
 * 1d. **Frozen B3 history.** Its 30/20/1, registry99 and 113 source hashes remain byte-frozen.
 * 2. **Frozen C exit.** `docs/cloudflare-audit/r2-c-runtime-support-2026-10-22.json` records
 *    51 literal candidates = 38 typed + 12 not started + 1 deferred; registry107 = 55 READ +
 *    52 WRITE, with 147 historical source hashes. KV overlays current hashes and registry119;
 *    frozen C bytes and local-only qualification remain unchanged.
 *
 * No document, nor `review.json`, is ever loaded by the runtime: the last test scans the
 * production sources for any reference to them, and no registration is created from data.
 */
class R2CoverageTest {
    private val schemaCommit = "6b00f157496cd6a1792a613dc94654c51273aeb9"
    private val schemaSha = "06dbba919204662d42e758e1f5b1dc4e262e99ec8540e7bf564de920707b9461"
    private val aMatrixPath = "docs/cloudflare-audit/r2-batch-a-runtime-support-2026-10-18.json"
    private val aMatrixSha = "89a5c381d2df9027159584e6ed11d0189496c227688c06f316152749ed5dc81f"
    private val b1MatrixPath = "docs/cloudflare-audit/r2-b1-runtime-support-2026-10-21.json"
    private val b1MatrixSha = "61299d96aadd9091420f4dc9a403a5daa99c455fcc5df05bb0fd6f2c6327707b"
    private val b2MatrixPath = "docs/cloudflare-audit/r2-b2-runtime-support-2026-10-21.json"
    private val b2MatrixSha = "445d113b3642a60784a704ffaa30f0e0f90e456b90bfb15d4ac7fa86d5086b78"
    private val b3MatrixPath = "docs/cloudflare-audit/r2-b3-runtime-support-2026-10-21.json"
    private val b3MatrixSha = "bda711be0ae140366cf2d1bf5ffaceae96d9e96816a63983c5819b04ea7d9f44"
    private val matrixPath = "docs/cloudflare-audit/r2-c-runtime-support-2026-10-22.json"
    private val cBaselinePath = "docs/cloudflare-audit/evidence/r2-c-2026-10-22/baselines/review-before-c.json.gz"
    private val cBaselineSha = "cf3f3edc00640670777c6c8f552def4484011ba184cd983e0a813447f794be2c"
    private val cEvidenceKey = "r2_c_runtime_support_2026_10_22"
    private val cSummaryKey = "2026-10-22-r2-c"
    private val batchC = setOf(
        "r2-list-bucket-jobs",
        "r2-get-bucket-job",
        "r2-create-bucket-job",
        "r2-get-account-level-metrics",
        "r2-get-event-notification-configs",
        "r2-get-event-notification-config",
        "r2-put-event-notification-config",
        "r2-event-notification-delete-config",
    )
    // Independent literal denominator: no current document may select its own candidates.
    private val allCandidates = setOf(
        "r2-add-custom-domain",
        "r2-create-bucket",
        "r2-create-bucket-by-name",
        "r2-create-bucket-job",
        "r2-create-temp-access-credentials",
        "r2-delete-bucket",
        "r2-delete-bucket-cors-policy",
        "r2-delete-bucket-lock-configuration",
        "r2-delete-bucket-sippy-config",
        "r2-delete-custom-domain",
        "r2-delete-object",
        "r2-delete-objects",
        "r2-edit-custom-domain-settings",
        "r2-event-notification-delete-config",
        "r2-get-account-level-metrics",
        "r2-get-bucket",
        "r2-get-bucket-cors-policy",
        "r2-get-bucket-job",
        "r2-get-bucket-lifecycle-configuration",
        "r2-get-bucket-local-uploads-configuration",
        "r2-get-bucket-lock-configuration",
        "r2-get-bucket-public-policy",
        "r2-get-bucket-sippy-config",
        "r2-get-custom-domain-settings",
        "r2-get-event-notification-config",
        "r2-get-event-notification-configs",
        "r2-get-object",
        "r2-list-bucket-jobs",
        "r2-list-custom-domains",
        "r2-list-objects",
        "r2-patch-bucket",
        "r2-put-bucket-cors-policy",
        "r2-put-bucket-lifecycle-configuration",
        "r2-put-bucket-local-uploads-configuration",
        "r2-put-bucket-lock-configuration",
        "r2-put-bucket-public-policy",
        "r2-put-bucket-sippy-config",
        "r2-put-event-notification-config",
        "r2-put-object",
        "slurper-abort-all-jobs",
        "slurper-abort-job",
        "slurper-check-source-connectivity",
        "slurper-check-target-connectivity",
        "slurper-create-job",
        "slurper-delete-job",
        "slurper-get-job",
        "slurper-get-job-logs",
        "slurper-get-job-progress",
        "slurper-list-jobs",
        "slurper-pause-job",
        "slurper-resume-job",
    )
    private val reviewPath = "docs/cloudflare-audit/review.json"
    private val b1BaselinePath =
        "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task3/coverage/baseline/review-round-start-2026-10-21.json.gz"
    private val b2BaselinePath =
        "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task4/coverage/baseline/review-round-start-2026-10-21-b2.json.gz"
    private val b3BaselinePath =
        "docs/cloudflare-audit/evidence/r2-b-2026-10-19/task6/coverage/baseline/review-round-start-2026-10-21-b3.json.gz"
    private val p1Path = "docs/cloudflare-audit/p1-runtime-support-2026-10-16.json"
    private val p2Path = "docs/cloudflare-audit/p2-runtime-support-2026-10-17.json"
    private val b1EvidenceKey = "r2_b1_runtime_support_2026_10_21"
    private val b1SummaryKey = "2026-10-21-r2-b1"
    private val b2EvidenceKey = "r2_b2_runtime_support_2026_10_21"
    private val b2SummaryKey = "2026-10-21-r2-b2"
    private val b3EvidenceKey = "r2_b3_runtime_support_2026_10_21"
    private val b3SummaryKey = "2026-10-21-r2-b3"
    private val aEvidenceKey = "r2_batch_a_runtime_support_2026_10_18"
    private val deferred = "r2-create-temp-access-credentials"
    private val batchB1 = setOf(
        "r2-get-bucket-cors-policy", "r2-put-bucket-cors-policy", "r2-delete-bucket-cors-policy",
        "r2-get-bucket-lifecycle-configuration", "r2-put-bucket-lifecycle-configuration",
        "r2-get-bucket-local-uploads-configuration", "r2-put-bucket-local-uploads-configuration",
        "r2-get-bucket-lock-configuration", "r2-put-bucket-lock-configuration",
        "r2-delete-bucket-lock-configuration",
    )
    private val batchB2 = setOf(
        "r2-get-bucket-public-policy", "r2-put-bucket-public-policy",
        "r2-list-custom-domains", "r2-add-custom-domain",
        "r2-get-custom-domain-settings", "r2-edit-custom-domain-settings",
        "r2-delete-custom-domain",
    )
    private val batchB3 = setOf(
        "r2-get-bucket-sippy-config",
        "r2-put-bucket-sippy-config",
        "r2-delete-bucket-sippy-config",
    )

    private val root: Path
        get() = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("docs/cloudflare-audit/review.json")) }

    private fun bytes(relative: String): ByteArray {
        assertTrue(Files.isRegularFile(root.resolve(relative)), "required audit input is missing: $relative")
        return Files.readAllBytes(root.resolve(relative))
    }

    private fun json(relative: String): JsonObject = Json.parseToJsonElement(bytes(relative).decodeToString()).jsonObject

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun matrix(): JsonObject {
        // C is a frozen exit document. D1 carries the current cross-product overlay;
        // historical C counters/anchors/review observations remain byte-for-byte unchanged.
        assertEquals("763e9042c6224ac7faed0e07bc8109a953018c10e982646a605d93b25750f382",sha(bytes(matrixPath)))
        val historic=json(matrixPath)
        val current=json("docs/cloudflare-audit/d1-runtime-support-2026-10-25.json")
        return JsonObject(historic + mapOf(
            "source_sha256" to current.getValue("r2_current_source_sha256"),
            "runtime_registry_observed" to current.getValue("runtime_registry_observed")))
    }

    private fun b2Matrix() = json(b2MatrixPath)

    private fun b1Matrix() = json(b1MatrixPath)

    private fun aMatrix() = json(aMatrixPath)

    private fun review() = json(reviewPath)

    /**
     * A round-start ledger, stored compressed so the delta of one batch is verifiable.
     * The batch B2 baseline is this round's dirty starting point, which already carries A/B1.
     */
    private fun reviewBaseline(path: String = b3BaselinePath): JsonObject =
        GZIPInputStream(bytes(path).inputStream()).use { stream ->
            Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        }

    private fun schema(): JsonObject {
        val file = System.getenv("SCHEMA_FILE")?.let { Path.of(it) } ?: root.resolve(".superpowers/cloudflare/openapi.json")
        assertTrue(Files.isRegularFile(file), "SCHEMA_FILE must name the pinned public schema (a bad override never skips)")
        val raw = Files.readAllBytes(file)
        assertEquals(schemaSha, sha(raw), "the pinned schema drifted; a coverage pass would be meaningless")
        return Json.parseToJsonElement(raw.decodeToString()).jsonObject
    }

    private fun policy(reg: ActionRegistration) = buildJsonObject {
        put("method", reg.method)
        put("path", reg.pathTemplate)
        put("scope", reg.scope.name)
        put("semantics", reg.policy.semantics.name)
        put("response_kind", reg.policy.responseKind.name)
        put("risk", reg.policy.risk.name)
        put("trailing_placeholder_spans_segments", reg.trailingPlaceholderSpansSegments)
        put(
            "retry",
            buildJsonObject {
                put("enabled", reg.policy.retry.enabled)
                put("max_retries", reg.policy.retry.maxRetries)
                put("max_wait_ms", reg.policy.retry.maxWaitMs)
            },
        )
    }

    /** The frozen P1 + P2 + auxiliary baseline, exactly as the P2 gate defines it. */
    private fun historicalBaseline(): Set<String> {
        val p1 = json(p1Path).getValue("operations").jsonObject.keys
        val p2 = json(p2Path)
        return p1 + p2.getValue("operations").jsonObject.keys + p2.getValue("auxiliary_operations").jsonObject.keys
    }

    // ---------------------------------------------------------------- denominators

    private fun candidates(matrix: JsonObject): Map<String, JsonObject> {
        assertEquals(51, matrix.getValue("operations").jsonObject.size, "all 51 R2 candidates stay in the denominator")
        return matrix.getValue("operations").jsonObject.mapValues { it.value.jsonObject }
    }

    private fun denominator(matrix: JsonObject) {
        assertEquals(schemaCommit, matrix.text("schema_commit"))
        assertEquals(schemaSha, matrix.text("schema_sha256"))
        assertEquals(JsonPrimitive(51), matrix["candidate_count"])
        assertEquals(JsonPrimitive(10), matrix["batch_a_count"])
        assertEquals(JsonPrimitive(10), matrix["batch_b1_count"])
        assertEquals(JsonPrimitive(7), matrix["batch_b2_count"])
        assertEquals(JsonPrimitive(3), matrix["batch_b3_count"])
        assertEquals(JsonPrimitive(20), matrix["batch_b_total"])
        assertEquals(JsonPrimitive(20), matrix["batch_b_completed"])
        assertEquals(JsonPrimitive(38), matrix["typed_handler_count"])
        assertEquals(JsonPrimitive(12), matrix["not_started_count"])
        assertEquals(JsonPrimitive(1), matrix["deferred_count"])
        for (flag in listOf("authenticated_cloud_accepted", "paid_eligibility_verified", "release_qualified")) {
            assertEquals(JsonPrimitive(false), matrix[flag], "$flag must stay false: this is local evidence only")
        }
        // Developer-verified and independent-pending; never an acceptance claim.
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", matrix.text("batch_b3_acceptance"))
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", matrix.text("batch_b2_acceptance"))
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", matrix.text("batch_b1_acceptance"))
        for (key in listOf("batch_b1_acceptance", "batch_b2_acceptance", "batch_b3_acceptance")) {
            assertFalse(matrix.text(key).contains("ACCEPTED_"), key)
        }
        // Batch B is the complete reviewed twenty; none of it may still be claimed unstarted.
        assertEquals(JsonPrimitive(20), matrix["batch_b_completed"])

        assertEquals(JsonPrimitive(8), matrix["batch_c_count"])
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", matrix.text("batch_c_acceptance"))
        val rows = candidates(matrix)
        assertEquals(batchB1, rows.filterValues { it.text("batch") == "R2-B1" }.keys, "batch B1 is exactly the ten reviewed configuration operations")
        assertEquals(batchB2, rows.filterValues { it.text("batch") == "R2-B2" }.keys, "batch B2 is exactly the seven reviewed domain/public-policy operations")
        assertEquals(batchB3, rows.filterValues { it.text("batch") == "R2-B3" }.keys, "batch B3 is exactly the three reviewed Sippy operations")
        val typed = rows.filterValues { it.text("technical_support") == "TYPED_HANDLER" }.keys
        assertEquals(38, typed.size)
        assertEquals(batchB1 + batchB2 + batchB3 + batchC + batchATyped(matrix), typed)
        assertEquals(setOf(deferred), rows.filterValues { it.text("technical_support") == "DEFERRED_PENDING_APPROVAL" }.keys)
        assertEquals(12, rows.count { it.value.text("technical_support") == "NOT_STARTED" })
        for ((id, row) in rows) {
            assertEquals("account", row.text("scope_kind"), id)
            assertEquals(JsonPrimitive(false), row["authenticated_cloud_accepted"], id)
            assertEquals(JsonPrimitive(false), row["paid_eligibility_verified"], id)
            if (id !in typed) {
                assertNull(row["handler"], "a non-started operation cannot carry a handler: $id")
                assertNull(row["policy"], "a non-started operation cannot carry a registration policy: $id")
                assertTrue(row["tests"]?.jsonArray.orEmpty().isEmpty(), "a non-started operation cannot carry behaviour evidence: $id")
            }
        }
    }

    private fun batchATyped(matrix: JsonObject): Set<String> =
        candidates(matrix).filterValues { it.text("batch") == "R2-A" && it.text("technical_support") == "TYPED_HANDLER" }.keys

    /**
     * The denominator is the frozen historical 51 operationIds (including all twelve Slurper operations),
     * compared by set equality against the byte-frozen batch A document: a matrix that keeps the count but
     * swaps one unstarted candidate for a legal, unregistered non-candidate API is refused.
     */
    private fun validateCandidateSet(matrix: JsonObject, aMatrix: JsonObject = aMatrix()) {
        val frozen = aMatrix.getValue("operations").jsonObject.keys
        assertEquals(51, frozen.size, "the frozen denominator stays 51")
        assertEquals(12, frozen.count { it.startsWith("slurper-") }, "the frozen denominator keeps the twelve Slurper operations")
        assertEquals(allCandidates, frozen, "the historical candidates must match the independent 51 literals")
        assertEquals(allCandidates, candidates(matrix).keys, "the 51 candidates must be exactly the frozen historical operationIds")
    }

    // ---------------------------------------------------------------- current registry

    private fun validateRegistry(
        matrix: JsonObject,
        registry: Map<String, ActionRegistration>,
        baseline: Set<String> = historicalBaseline(),
        aMatrix: JsonObject = aMatrix(),
    ) {
        val rows = candidates(matrix)
        val typed = rows.filterValues { it.text("technical_support") == "TYPED_HANDLER" }.keys
        // Exact union: the frozen P1/P2/auxiliary baseline plus the reviewed A/B1/B2/B3/C additions.
        val expected = baseline + typed + kvRegistrations().map { it.policy.key } + d1Registrations().map { it.policy.key }
        assertEquals(69, baseline.size, "the frozen P1/P2/auxiliary baseline stays 69")
        assertEquals(131, expected.size, "the current reviewed registry is exactly 131 operations")
        assertEquals(expected, registry.keys, "a missing route or an unreviewed extra action (including a read) is refused")
        assertEquals(expected.size, registry.size)
        assertEquals(62, registry.values.count { it.policy.semantics == ActionSemantics.READ }, "exact current READ count")
        assertEquals(69, registry.values.count { it.policy.semantics == ActionSemantics.WRITE }, "exact current WRITE count")
        assertEquals(JsonPrimitive(131), matrix.getValue("runtime_registry_observed").jsonObject["count"])
        assertEquals(JsonPrimitive(62), matrix.getValue("runtime_registry_observed").jsonObject["read"])
        assertEquals(JsonPrimitive(69), matrix.getValue("runtime_registry_observed").jsonObject["write"])
        assertEquals(JsonPrimitive(69), matrix.getValue("runtime_registry_observed").jsonObject["historical_p1_p2_auxiliary_count"])
        assertEquals(JsonPrimitive(10), matrix.getValue("runtime_registry_observed").jsonObject["reviewed_batch_a_addition"])
        assertEquals(JsonPrimitive(10), matrix.getValue("runtime_registry_observed").jsonObject["reviewed_batch_b1_addition"])
        assertEquals(JsonPrimitive(7), matrix.getValue("runtime_registry_observed").jsonObject["reviewed_batch_b2_addition"])
        assertEquals(JsonPrimitive(3), matrix.getValue("runtime_registry_observed").jsonObject["reviewed_batch_b3_addition"])

        assertEquals(JsonPrimitive(8), matrix.getValue("runtime_registry_observed").jsonObject["reviewed_batch_c_addition"])
        val cRegistrations = r2JobRegistrations().toList() + r2MetricsRegistrations() + r2NotificationRegistrations()
        assertEquals(batchC, cRegistrations.map { it.policy.key }.toSet())
        assertEquals(8, cRegistrations.size)
        assertEquals(5, cRegistrations.count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(3, cRegistrations.count { it.policy.semantics == ActionSemantics.WRITE })
        for ((id, row) in rows) {
            val registration = registry[id]
            if (id !in typed) {
                assertNull(registration, "NOT_STARTED/DEFERRED operations can never be registered: $id")
                continue
            }
            assertNotNull(registration, "typed operation without a handwritten registration: $id")
            assertEquals(row.getValue("policy"), policy(registration), "exact registry policy drift: $id")
            assertEquals(row.text("method"), registration.method, id)
            assertEquals(row.text("path"), registration.pathTemplate, id)
            assertEquals(ActionScope.ACCOUNT, registration.scope, id)
            // Slash-tail: only the three reviewed object-key routes may own a spanning tail.
            assertEquals(id in setOf("r2-get-object", "r2-put-object", "r2-delete-object"), registration.trailingPlaceholderSpansSegments, id)
            val semantics = if (row.text("method") == "GET") ActionSemantics.READ else ActionSemantics.WRITE
            assertEquals(semantics, registration.policy.semantics, id)
            assertEquals(if (semantics == ActionSemantics.READ) true else false, registration.policy.retry.enabled, id)
            if (semantics == ActionSemantics.READ) {
                assertEquals(2, registration.policy.retry.maxRetries, id)
                assertEquals(60_000L, registration.policy.retry.maxWaitMs, id)
            }
            assertEquals(
                if (id == "r2-get-object") ResponseKind.BINARY else ResponseKind.ENVELOPE_JSON,
                registration.policy.responseKind,
                id,
            )
        }
        // The curated per-operation handler/test binding: existence alone is not operation evidence.
        validateCuratedBindings(matrix, aMatrix)
        // The batch A handwritten function keeps exactly its own ten operations.
        assertEquals(batchATyped(matrix), r2Registrations().map { it.policy.key }.toSet())
        assertEquals(10, r2Registrations().size)
        // The batch B1 function keeps exactly its own ten configuration operations.
        assertEquals(batchB1, r2ConfigurationRegistrations().map { it.policy.key }.toSet())
        assertEquals(10, r2ConfigurationRegistrations().size)
        // The batch B2 function keeps exactly its own seven domain/public-policy operations.
        assertEquals(batchB2, r2DomainRegistrations().map { it.policy.key }.toSet())
        assertEquals(7, r2DomainRegistrations().size)
        assertEquals(3, r2DomainRegistrations().count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(4, r2DomainRegistrations().count { it.policy.semantics == ActionSemantics.WRITE })
        // The batch B3 function keeps exactly its own three Sippy operations.
        assertEquals(batchB3, r2SippyRegistrations().map { it.policy.key }.toSet())
        assertEquals(3, r2SippyRegistrations().size)
        assertEquals(1, r2SippyRegistrations().count { it.policy.semantics == ActionSemantics.READ })
        assertEquals(2, r2SippyRegistrations().count { it.policy.semantics == ActionSemantics.WRITE })
        // Nothing from the later batches is registered, not even as a read.
        for (id in rows.keys - typed) assertNull(ActionPolicies.policy(id), id)
    }

    // ---------------------------------------------------------------- curated operation bindings

    /** One reviewed operation evidence binding: the exact compiled handler and the exact behaviour anchors. */
    private data class CuratedBinding(
        val handlerClass: String,
        val handlerMethod: String,
        val handlerSource: String,
        val tests: List<Triple<String, String, String>>,
    )

    /**
     * Batch B1 bindings are fixed here as independent literals, not derived from the rows under test.
     *
     * Two writes deliberately share `everyReviewedConfigurationWriteIsAdmittedOnceForItsOwnJurisdiction`
     * (it is a table-driven test that admits and observes each write separately) and the two deletes
     * additionally share the controller delete test; the shares are reviewed, not accidental.
     */
    private fun curatedB1(): Map<String, CuratedBinding> {
        fun test(cls: String, method: String, source: String) = Triple(cls, method, source)
        val client = "com.flarego.core.cloudflare.r2.CloudflareR2ConfigurationClient"
        val clientSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2ConfigurationClient.kt"
        val wire = test("com.flarego.core.R2ConfigurationClientTest", "", "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationClientTest.kt")
        val authority = test("com.flarego.core.R2ConfigurationAuthorityTest", "everyReviewedConfigurationWriteIsAdmittedOnceForItsOwnJurisdiction", "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationAuthorityTest.kt")
        val deletes = test("com.flarego.core.R2ConfigurationControllerTest", "deletesReachTheControllerAsRealDeleteCommandsAndA404ReadBackStaysUnknown", "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationControllerTest.kt")
        fun binding(method: String, tests: List<Triple<String, String, String>>) = CuratedBinding(client, method, clientSource, tests)
        fun wireTest(name: String) = listOf(wire.copy(second = name))
        return mapOf(
            "r2-get-bucket-cors-policy" to binding("getCors", wireTest("readsKeepTheProviderEnvelopeVerbatimForTheEditableKinds")),
            "r2-put-bucket-cors-policy" to binding("putCors", listOf(authority, wire.copy(second = "corsPutReplacesExplicitRulesAndPreservesFalseAndArrayOrder"))),
            "r2-delete-bucket-cors-policy" to binding("deleteCors", listOf(authority, deletes)),
            "r2-get-bucket-lifecycle-configuration" to binding("getLifecycle", wireTest("unknownNestedRuleAndOversizeDocumentRemainReadOnly")),
            "r2-put-bucket-lifecycle-configuration" to binding("putLifecycle", listOf(authority, wire.copy(second = "lifecycleAndLockUseDifferentAgeFields"))),
            "r2-get-bucket-local-uploads-configuration" to binding("getLocalUploads", wireTest("nonDefaultLocalUploadsNeverCallsTransport")),
            "r2-put-bucket-local-uploads-configuration" to binding("putLocalUploads", listOf(authority, wire.copy(second = "nonDefaultLocalUploadsNeverCallsTransport"))),
            "r2-get-bucket-lock-configuration" to binding("getLock", wireTest("unknownNestedRuleAndOversizeDocumentRemainReadOnly")),
            "r2-put-bucket-lock-configuration" to binding("putLock", listOf(authority, wire.copy(second = "lifecycleAndLockUseDifferentAgeFields"))),
            "r2-delete-bucket-lock-configuration" to binding("deleteLock", listOf(authority, deletes)),
        )
    }

    /**
     * Batch B2 bindings are also independent literals: they are never derived from the rows under
     * test. The delete operation is anchored by the real cross-layer test that drives the actual
     * client through the authorized session and the runtime/controller, not by a stub.
     */
    private fun curatedB2(): Map<String, CuratedBinding> {
        fun test(cls: String, method: String, source: String) = Triple(cls, method, source)
        val client = "com.flarego.core.cloudflare.r2.CloudflareR2DomainConfigurationClient"
        val clientSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2DomainConfigurationClient.kt"
        val configuration = "com.flarego.core.R2DomainConfigurationTest"
        val configurationSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainConfigurationTest.kt"
        val controller = "com.flarego.core.R2DomainControllerTest"
        val controllerSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt"
        val crossLayer = "com.flarego.core.R2DomainDeleteCrossLayerTest"
        val crossLayerSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainDeleteCrossLayerTest.kt"
        fun binding(method: String, tests: List<Triple<String, String, String>>) =
            CuratedBinding(client, method, clientSource, tests)
        fun at(cls: String, source: String, method: String) = test(cls, method, source)
        return mapOf(
            "r2-get-bucket-public-policy" to binding("getManagedDomain", listOf(
                at(configuration, configurationSource, "theManagedDomainDocumentKeepsEnabledSeparateFromEverythingElse"),
            )),
            "r2-put-bucket-public-policy" to binding("putManagedDomain", listOf(
                at(configuration, configurationSource, "disablingTheManagedDomainSendsAnExplicitFalse"),
                at(controller, controllerSource, "managedDomainFalseIsSentExplicitlyAndTheReadBackComparesTheManagedDocument"),
            )),
            "r2-list-custom-domains" to binding("listCustomDomains", listOf(
                at(configuration, configurationSource, "theDomainListKeepsEveryRowAndRefusesAnUnknownMember"),
            )),
            "r2-add-custom-domain" to binding("addCustomDomain", listOf(
                at(configuration, configurationSource, "aNewCustomDomainIsExplicitlyDisabledWithTlsAtLeast12"),
                at(controller, controllerSource, "addingADomainRequiresAbsenceInTheCompleteCollectionAndVerifiesTheNewDomain"),
            )),
            "r2-get-custom-domain-settings" to binding("getCustomDomain", listOf(
                // The positive single-domain GET keeps its own field proof next to the mismatch refusal.
                at(configuration, configurationSource, "customDomainsExposeOwnershipAndSslAsSeparateProviderStates"),
                at(configuration, configurationSource, "aSingleDomainReadMustReturnTheRequestedDomain"),
            )),
            "r2-edit-custom-domain-settings" to binding("editCustomDomain", listOf(
                at(configuration, configurationSource, "anEditSendsEnabledExplicitlyAndOnlyAProvenCipherList"),
                at(controller, controllerSource, "editingKeepsEveryPreservedFieldAndComparesTheCompleteObservableTarget"),
            )),
            "r2-delete-custom-domain" to binding("deleteCustomDomain", listOf(
                at(crossLayer, crossLayerSource, "aDeleteSendsOneSingleDomainDeleteAfterTheCompleteCollectionWasObserved"),
                at(controller, controllerSource, "deletingRequiresTheCompleteCollectionWithoutTheDomainAndA404IsNotAbsence"),
            )),
        )
    }

    /**
     * Batch B3 bindings are independent literals too. Each Sippy operation is anchored by its real
     * typed client path (the wire plays through the gateway/transport fakes) and the authority test
     * that mints its own fresh account-level proof; the DELETE also keeps the snapshot pre-read.
     */
    /** Independently reviewed C8 bindings: the current matrix records, never chooses, these. */
    private fun curatedC(): Map<String, CuratedBinding> {
        fun test(cls: String, method: String) = Triple("com.flarego.core.$cls", method, "shared-core/src/commonTest/kotlin/com/flarego/core/$cls.kt")
        fun binding(client: String, method: String, vararg tests: Triple<String, String, String>) = CuratedBinding(
            "com.flarego.core.cloudflare.r2.CloudflareR2${client}Client", method,
            "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2${client}Client.kt", tests.toList(),
        )
        return mapOf(
            "r2-list-bucket-jobs" to binding("Jobs", "listJobs",
                test("R2JobsClientTest", "listSendsTheExactQueryAndBoundsMaxKeysToTheClientRange"),
                test("R2JobsClientTest", "listDecodesBothKindsAndKeepsUnknownEntriesReadOnly"),
            ),
            "r2-get-bucket-job" to binding("Jobs", "getJob",
                test("R2JobsClientTest", "getJobRequiresTheRequestedIdAndKeepsUnknownStatusReadOnly"),
            ),
            "r2-create-bucket-job" to binding("Jobs", "createJob",
                test("R2JobsClientTest", "createWithATrustworthySameKindIdReturnsAcceptedEvenWhenItReportsCompleted"),
                test("R2JobsClientTest", "emptyPrefixIsTheExplicitWholeBucketClear"),
                test("R2JobsClientTest", "createMigrationSendsBothClassesExplicitlyWithoutTheCatalogHeader"),
                test("R2CJobsRuntimeTest", "oneCreatePostsOnceAndVerifiesTheSameJobWithAnIndependentGet"),
            ),
            "r2-get-account-level-metrics" to binding("Metrics", "getMetrics",
                test("R2MetricsClientTest", "metricsRequestCarriesNoBucketOrJurisdictionHeader"),
                test("R2MetricsClientTest", "numbersBeyondDoublePrecisionAndTinyExponentsAreNotRounded"),
            ),
            "r2-get-event-notification-configs" to binding("Notification", "listConfigurations",
                test("R2NotificationClientTest", "listConfigurationsTreats404AsNotFoundAndKeepsForeignBucketEchoReadOnly"),
            ),
            "r2-get-event-notification-config" to binding("Notification", "getConfiguration",
                test("R2NotificationClientTest", "aSingleQueueReadRejectsAForeignQueueEcho"),
            ),
            "r2-put-event-notification-config" to binding("Notification", "appendRules",
                test("R2NotificationClientTest", "appendSendsOnlyNewRulesAndKeepsFiltersVerbatim"),
                test("R2CNotificationRuntimeTest", "anAppendSendsOnlyNewRulesAndProvesThemWithAnIndependentRead"),
            ),
            "r2-event-notification-delete-config" to binding("Notification", "deleteRules",
                test("R2NotificationClientTest", "deleteSelectedSendsNonEmptyUniqueIdsAndAllSendsNoBody"),
                test("R2CNotificationRuntimeTest", "aSelectedDeletionRemovesExactlyThoseIdsAndKeepsTheOthers"),
                test("R2CNotificationRuntimeTest", "theWholeQueueClearUsesTheBodylessPathAndIsProvenByTheConfigurationList"),
            ),
        )
    }

    private fun curatedB3(): Map<String, CuratedBinding> {
        fun test(cls: String, method: String, source: String) = Triple(cls, method, source)
        val client = "com.flarego.core.cloudflare.r2.CloudflareR2SippyClient"
        val clientSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2SippyClient.kt"
        val wire = "com.flarego.core.R2SippyTest"
        val wireSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyTest.kt"
        val authority = "com.flarego.core.R2SippyAuthorityTest"
        val authoritySource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyAuthorityTest.kt"
        fun binding(method: String, tests: List<Triple<String, String, String>>) =
            CuratedBinding(client, method, clientSource, tests)
        return mapOf(
            "r2-get-bucket-sippy-config" to binding("getSippy", listOf(
                test(wire, "safeSippySnapshotDropsUnexpectedEchoedSecretsAndUnknownProvidersStayReadOnly", wireSource),
            )),
            "r2-put-bucket-sippy-config" to binding("putSippy", listOf(
                test(wire, "fourProvidersSendExactlyThePinnedFieldsOnce", wireSource),
                test(authority, "bothSippyWritesMintTheirOwnFreshAccountLevelProof", authoritySource),
            )),
            "r2-delete-bucket-sippy-config" to binding("deleteSippy", listOf(
                test(wire, "deleteSendsNoSensitiveBodyAndRequiresAReadableSnapshot", wireSource),
                test(authority, "bothSippyWritesMintTheirOwnFreshAccountLevelProof", authoritySource),
            )),
        )
    }

    /**
     * Batch B2's cross-cutting anchors are literals here, never read from the document under test.
     * A matrix may only *record* this exact set: it can never choose what the gate executes.
     */
    private data class CrossCuttingAnchor(val cls: String, val method: String, val source: String)

    private fun b2CrossCutting(): List<CrossCuttingAnchor> = listOf(
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainRuntimeTest",
            "anUnsettledDomainWriteSharesTheBucketGateWithBatchAAndB1InBothDirections",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainRuntimeTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainRuntimeTest",
            "twoDomainLocatorsOfOneBucketBlockEachOtherAndReconcilingOneDoesNotSettleTheOther",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainRuntimeTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainRuntimeTest",
            "mixedConfigurationAndDomainPreviewsShareTheSixtyFourSlotWindow",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainRuntimeTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainRuntimeTest",
            "aDomainCommandNeedsTheLiveCommandAndAConfiguredSession",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainRuntimeTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainControllerTest",
            "anAcceptedReceiptIsNotUpgradedAndIsNeverReplayed",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainControllerTest",
            "aDriftedDocumentOrAFailedReadBackStaysUnsettledAndIsNeverReplayed",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt",
        ),
        CrossCuttingAnchor(
            "com.flarego.core.R2DomainControllerTest",
            "anUnsettledWriteOfTheSameBucketBlocksADomainWrite",
            "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt",
        ),
    )

    /**
     * Batch B3's cross-cutting anchors are literals too: claim/release/revoke ownership, one-use
     * secret cleanup, unknown-result and lock/jurisdiction refusal, and the borrowed-locator guard.
     */
    private fun b3CrossCutting(): List<CrossCuttingAnchor> {
        val runtime = "com.flarego.core.R2SippyRuntimeTest"
        val runtimeSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyRuntimeTest.kt"
        val ownership = "com.flarego.core.R2SippyCrossLayerOwnershipTest"
        val ownershipSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyCrossLayerOwnershipTest.kt"
        val locator = "com.flarego.core.R2SippyLocatorTest"
        val locatorSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyLocatorTest.kt"
        val authority = "com.flarego.core.R2SippyAuthorityTest"
        val authoritySource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyAuthorityTest.kt"
        val fix1 = "com.flarego.core.R2SippyFix1Test"
        val fix1Source = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyFix1Test.kt"
        fun anchor(cls: String, source: String, method: String) = CrossCuttingAnchor(cls, method, source)
        return listOf(
            anchor(ownership, ownershipSource, "theTaskClaimMovesTheMaterialAwayFromTheEditorDraft"),
            anchor(ownership, ownershipSource, "aClaimedTaskKeepsItsMaterialWhileTheDraftUiRetiresDuringTheConfigurationRead"),
            anchor(ownership, ownershipSource, "aGenuineRouteRevokeInThatSameWindowStillSendsNothing"),
            anchor(ownership, ownershipSource, "theClaimedMaterialIsReleasedWhenTheGuardRefusesBeforeTheSend"),
            anchor(runtime, runtimeSource, "unknownPutIsNeverProvenByIdenticalPublicMetadata"),
            anchor(runtime, runtimeSource, "aDeleteIsProvenOnlyByAnIndependentlyReadDisabledState"),
            anchor(runtime, runtimeSource, "beforeSendAndFinalGuardDriftBothProduceZeroWrites"),
            anchor(runtime, runtimeSource, "sippySharesOneLockAndUnsettledGateWithAAndB1AndB2InBothDirections"),
            anchor(locator, locatorSource, "aMalformedOrBorrowedSippyLocatorFailsClosed"),
            anchor(authority, authoritySource, "bucketScopedOrItemOnlyPermissionNeverAuthorizesASippyWrite"),
            anchor(authority, authoritySource, "aNonDefaultJurisdictionIsAdmittedOnlyForTheReviewedSippyActions"),
            anchor(fix1, fix1Source, "retiringOrConsumingALeaseDropsItsMaterialReferenceDeterministically"),
            anchor(fix1, fix1Source, "aConsumedOrDiscardedSensitiveBodyDropsItsTemporaryText"),
            anchor(fix1, fix1Source, "aRefusedWriteReleasesTheTemporaryBodyAndKeepsAnExplicitLeasePolicy"),
        )
    }

    /** C's 81 compiled wire/runtime anchors. Literal references, never selected by audit data. */
    private fun cCrossCutting(): List<CrossCuttingAnchor> {
        fun anchors(cls: String, vararg methods: String) = methods.map {
            CrossCuttingAnchor("com.flarego.core.$cls", it, "shared-core/src/commonTest/kotlin/com/flarego/core/$cls.kt")
        }
        return listOf(
            anchors("R2JobsClientTest",
                "theThreeReviewedJobRoutesAreRegisteredExactlyOnce",
                "listSendsTheExactQueryAndBoundsMaxKeysToTheClientRange",
                "listDecodesBothKindsAndKeepsUnknownEntriesReadOnly",
                "getJobRequiresTheRequestedIdAndKeepsUnknownStatusReadOnly",
                "createWithATrustworthySameKindIdReturnsAcceptedEvenWhenItReportsCompleted",
                "emptyPrefixIsTheExplicitWholeBucketClear",
                "createMigrationSendsBothClassesExplicitlyWithoutTheCatalogHeader",
                "anUntrustworthyOrUnaddressableAnswerIsUnknownAndNeverCompleted",
                "aSentCreateWithAnUnexplainedResultShapeStaysUncertain",
                "createJobIsSentOnceAndNeverRetriedOn429OrServerError",
                "optionalCountersBranchExclusionAndClearConsistencyAreStrict",
                "aMissingRequiredStatusIsNeverTrustedAndCanNeverMintALocator",
                "onlyAPathSafeOpaqueJobIdCanBecomeATrustedLocatorOrAReadTarget",
                "anUnsafeContinuationCursorKeepsThePageReadOnly",
                "crossAccountTargetAndUntrustedIdentifiersAreRefusedBeforeTheRequest",
            ),
            anchors("R2MetricsClientTest",
                "theAccountMetricsReadIsRegisteredExactlyOnce",
                "metricsRequestCarriesNoBucketOrJurisdictionHeader",
                "decimalsAndMissingFieldsKeepTheirExactLexicalValues",
                "numbersBeyondDoublePrecisionAndTinyExponentsAreNotRounded",
                "nonFiniteWrongTypedOrUnknownValuesKeepTheDocumentReadOnly",
                "aCrossAccountScopeIsRefusedBeforeTheRequest",
            ),
            anchors("R2NotificationClientTest",
                "theFourReviewedNotificationRoutesAreRegisteredExactlyOnce",
                "listConfigurationsTreats404AsNotFoundAndKeepsForeignBucketEchoReadOnly",
                "missingOrDuplicatedQueueAndRuleIdentityKeepsTheConfigurationReadOnly",
                "appendSendsOnlyNewRulesAndKeepsFiltersVerbatim",
                "mutableCallerCollectionsCannotEscapeTheValidatedShapeIntoSend",
                "theNotificationBudgetCoversBeforeDeltaAndExpectedResult",
                "appendRefusesAReadOnlyOrForeignQueueBeforeAnyWrite",
                "deleteSelectedSendsNonEmptyUniqueIdsAndAllSendsNoBody",
                "deleteSelectedRefusesEmptyDuplicateMissingOrForeignIdsLocally",
                "writeStatusSemanticsDistinguishCompletedAcceptedAndUndeclaredSuccess",
                "writesNeverRetryAndAnUnknownOutcomeStaysUncertain",
                "ruleSpecsAndQueueIdsAreValidatedBeforeAnyRequest",
                "aSingleQueueReadRejectsAForeignQueueEcho",
            ),
            anchors("R2CJobsRuntimeTest",
                "oneCreatePostsOnceAndVerifiesTheSameJobWithAnIndependentGet",
                "aMissingOrForeignJobIdStaysUnknownAndIsNeverGuessed",
                "aPreflightThatCannotProveIdleRefusesBeforeAnySend",
                "aChangedBucketStateBetweenPreviewAndSendRefusesTheSend",
                "aChangedOrStaleCommandNeverConfirmsThePreview",
                "aBucketScopedGrantNeverMintsTheRestWriteProof",
                "aTerminalJobIsSettledOnlyByItsOwnVerifiedObservation",
                "aStillRunningJobKeepsTheAcceptedTaskInsteadOfBeingDowngraded",
                "aTaskWithoutItsOwnJobIdIsNeverRecoveredByGuessing",
                "theJobTaskSharesTheOneBucketGateWithBatchAOnTheSameBucketOnly",
                "aCancelledWaiterNeverCancelsOrResendsTheAdmittedCreation",
                "theFinalPreflightHappensAfterTheFreshProofSoAPauseCannotHideAChange",
                "theBucketPreflightReadsTheExactTargetJurisdictionAndNeverFallsBackToDefault",
                "aBucketClearWithKnownNotificationRulesIsRefusedLocally",
                "aTrustedJobIdSurvivesAFailedAdmissionReadAndIsRecoveredAfterARestart",
                "aMigrationIsOnlyAFullSuccessWhenEveryObservedFailureCounterIsZero",
                "aTerminalRowIsNeverRewrittenByALaterObservation",
                "theJobPreviewCopyDescribesTheRealJobScope",
                "aRevokedRouteOrCredentialDuringTheFreshProofRefusesTheSend",
                "aRevocationDuringTheFinalPreflightRefusesTheSendAfterTheProof",
            ),
            anchors("R2CNotificationRuntimeTest",
                "anAppendSendsOnlyNewRulesAndProvesThemWithAnIndependentRead",
                "aRuleDescriptionIsNeverAnIdentity",
                "aSelectedDeletionRemovesExactlyThoseIdsAndKeepsTheOthers",
                "theWholeQueueClearUsesTheBodylessPathAndIsProvenByTheConfigurationList",
                "aSelectionThatIsNoLongerInTheFreshDocumentNeverSends",
                "anUninterpretableOrForeignQueueIsReadOnly",
                "anAppendOverTheClientBudgetIsRefusedBeforeAnySend",
                "theGuardComparesTheWireOwnFreshDocument",
                "aWithdrawnCredentialNeverConfirmsThePreview",
                "anAcceptedWriteIsNeverUpgradedAndTheClearCanBeRecoveredReadOnly",
                "aSelectedDeletionAcceptedByTheProviderIsNeverSettledByAGuess",
                "aWholeQueueClearIsProvenByTheConfigurationListEvenWhenTheSingleReadIs404",
                "aSelectedDeletionThatClearsEveryRuleIsProvenByTheConfigurationList",
                "aForeignOrMissingCollectionIdentityIsNeverABaseline",
                "aMutatedCommandNeverSendsMoreThanThePreviewedContent",
                "aRecoveryRecomputesTheObservedStateInsteadOfEchoingTheDesiredHash",
                "aRecoveryNeverAcceptsAForeignFamilyForItsAction",
                "aTerminalRowKeepsItsHistoryAndSelectedRecoveryNeedsNoStoredPayload",
                "aSelectedDeletionThatClearedEverythingRecoversFromALegallyAbsentQueue",
                "aSelectedDeletionThatShouldLeaveRulesNeverSucceedsOnAnAbsentQueue",
                "aRecoveryNeverTrustsAnAmbiguousOrForeignCollectionFromATypedSession",
                "theImmediateAfterAlsoRejectsAnAmbiguousCollectionFromATypedSession",
                "anOwnButUninterpretableQueueNeverProvesADeletionOnTheImmediatePath",
                "theSameEvidencePredicateGuardsEveryPath",
                "theDocumentShapeMatrixNeverSettlesFromInvalidDocuments",
                "aSelectedDeletionWithRemainingRulesUsesTheSameQueueIdentityPredicate",
                "aFullyInterpretableEmptyQueueStillSettlesBothDeletionIntents",
            ),
        ).flatten().also { assertEquals(81, it.size) }
    }

    private val batchCReviewedPaths = listOf(
        "app/src/androidTest/kotlin/com/flarego/R2CManagementUiTest.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2JobValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2JobsController.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2MetricsValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2NotificationController.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2NotificationValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2JobsSession.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2NotificationSession.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2JobPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2MetricsPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2NotificationPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2JobsClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2MetricsClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2NotificationClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2JobModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2MetricsModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2NotificationModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2JobsPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2MetricsPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2NotificationPort.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2JobsClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2MetricsClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2NotificationClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2CJobsRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2CNotificationRuntimeTest.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2CController.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2CDeletionConfirmation.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2CScreen.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2ChangePreviewCard.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2CBrowserRuntimeIntegrationTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2CControllerGuardTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2CDeletionConfirmationTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2CReadAndIntentTest.kt",
        "shared-core/build.gradle.kts",
        "docs/cloudflare-audit/r2-b3-runtime-support-2026-10-21.json",
    )

    private fun cSummary() = buildJsonObject {
        put("reference", matrixPath)
        put("candidate_count", 51)
        put("typed_handler_count", 38)
        put("not_started_count", 12)
        put("deferred_pending_approval_count", 1)
        put("batch_c_count", 8)
        put("batch_c_acceptance", "DEVELOPER_VERIFIED_INDEPENDENT_PENDING")
        put("authenticated_cloud_accepted", false)
        put("paid_eligibility_verified", false)
        put("release_qualified", false)
    }

    private fun validateCReview(matrix: JsonObject, review: JsonObject) {
        assertEquals(cBaselineSha, sha(bytes(cBaselinePath)), "the compressed dirty-start baseline is byte-frozen")
        val rows = review.getValue("operations").jsonObject
        assertEquals(allCandidates, rows.filterValues { cEvidenceKey in it.jsonObject.getValue("evidence").jsonObject }.keys,
            "exactly the 51 R2 candidates receive one C observation")
        for (id in allCandidates) {
            val observation = rows.getValue(id).jsonObject.getValue("evidence").jsonObject.getValue(cEvidenceKey)
            val expected = buildJsonObject {
                put("reference", matrixPath + "#operations/$id")
                put("technical_support", candidates(matrix).getValue(id).text("technical_support"))
                put("authenticated_cloud_accepted", false)
                put("paid_eligibility_verified", false)
            }
            assertEquals(expected, observation, "C evidence drift: $id")
        }
        assertEquals(cSummary(), review.getValue("runtime_support_updates").jsonObject.getValue(cSummaryKey), "C summary drift")
        assertTrue(stripEvidence(review, cEvidenceKey, cSummaryKey) == reviewBaseline(cBaselinePath),
            "removing only C's 51 observations and one summary must reproduce the dirty-start review")
    }

    private fun crossCuttingOf(document: JsonObject): Map<String, Set<String>> =
        document["cross_cutting_local_tests"]?.jsonArray.orEmpty().associate { row ->
            val entry = row.jsonObject
            entry.text("class") to entry.getValue("methods").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }

    /**
     * The matrix's cross-cutting block must be exactly batch A's frozen eleven methods plus batch
     * B2's seven and batch B3's fourteen literals. Executing this before any behaviour runs means an
     * unverifiable or substituted cross-cutting set can never reach the reflective execution loop.
     */
    private fun validateCrossCutting(matrix: JsonObject, aMatrix: JsonObject) {
        val aExpected = crossCuttingOf(aMatrix)
        assertEquals(2, aExpected.size, "the frozen batch A cross-cutting block keeps its two classes")
        assertEquals(11, aExpected.values.sumOf { it.size }, "the frozen batch A cross-cutting block keeps its eleven methods")
        val entries = matrix.getValue("cross_cutting_local_tests").jsonArray
        for (entry in entries) {
            val methods = entry.jsonObject.getValue("methods").jsonArray
            assertEquals(methods.size, methods.toSet().size, "duplicated cross-cutting method")
        }
        val b2 = b2CrossCutting()
        assertEquals(7, b2.size, "batch B2 keeps exactly seven cross-cutting methods")
        val b3 = b3CrossCutting()
        assertEquals(14, b3.size, "batch B3 keeps exactly fourteen cross-cutting methods")
        val literalExpected = (b2 + b3 + cCrossCutting()).groupBy { it.cls }.mapValues { (_, anchors) -> anchors.map { it.method }.toSet() }
        assertEquals(12, literalExpected.size, "the B2/B3/C literals keep twelve classes")
        assertEquals(aExpected + literalExpected, crossCuttingOf(matrix), "cross-cutting evidence drifted from the fixed A/B2/B3/C set")
        // Map equality alone would hide a duplicated class entry, so the entry count is pinned too.
        assertEquals(14, matrix.getValue("cross_cutting_local_tests").jsonArray.size, "the cross-cutting block keeps exactly fourteen class entries")
        for (anchor in b2 + b3 + cCrossCutting()) {
            assertTrue(anchor.source in reviewedSourcePaths(), "the reviewed cross-cutting source is not reviewed: ${anchor.source}")
            assertNotNull(
                Class.forName(anchor.cls).declaredMethods.firstOrNull { it.name == anchor.method && it.parameterCount == 0 },
                "missing compiled cross-cutting method: ${anchor.cls}#${anchor.method}",
            )
        }
    }

    /** Batch A's curated bindings come from its byte-frozen historical document, never from the current rows. */
    private fun curatedA(aMatrix: JsonObject): Map<String, CuratedBinding> =
        aMatrix.getValue("operations").jsonObject
            .filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }
            .mapValues { (_, value) ->
                val row = value.jsonObject
                val handler = row.getValue("handler").jsonObject
                CuratedBinding(
                    handlerClass = handler.text("class"),
                    handlerMethod = handler.text("method"),
                    handlerSource = handler.text("source_file"),
                    tests = row.getValue("tests").jsonArray.map {
                        val test = it.jsonObject
                        Triple(test.text("class"), test.text("method"), test.text("source_file"))
                    },
                )
            }

    private fun validateCuratedBindings(matrix: JsonObject, aMatrix: JsonObject) {
        val rows = candidates(matrix)
        val curated = curatedA(aMatrix) + curatedB1() + curatedB2() + curatedB3() + curatedC()
        val typed = rows.filterValues { it.text("technical_support") == "TYPED_HANDLER" }.keys
        assertEquals(38, curated.size, "the thirty-eight typed operations own exactly thirty-eight curated bindings")
        assertEquals(curated.keys, typed, "a typed operation without a curated binding, or a binding without a typed operation")
        for ((id, expected) in curated) {
            val expectedBatch = when (id) {
                in batchB1 -> "R2-B1"
                in batchB2 -> "R2-B2"
                in batchB3 -> "R2-B3"
                in batchC -> "R2-C"
                else -> "R2-A"
            }
            assertEquals(expectedBatch, rows.getValue(id).text("batch"), id)
            val handler = rows.getValue(id)["handler"]?.jsonObject ?: throw AssertionError("typed operation without handler evidence: $id")
            assertEquals(expected.handlerClass, handler.text("class"), "handler class drift: $id")
            assertEquals(expected.handlerMethod, handler.text("method"), "handler method drift: $id")
            assertEquals(expected.handlerSource, handler.text("source_file"), "handler source drift: $id")
            assertTrue(Files.isRegularFile(root.resolve(expected.handlerSource)), "missing handler source: $id")
            assertTrue(
                Class.forName(expected.handlerClass).methods.any { it.name == expected.handlerMethod },
                "the curated compiled handler does not exist: $id",
            )
            val anchors = rows.getValue(id)["tests"]?.jsonArray?.map { it.jsonObject }
                ?: throw AssertionError("typed operation without behaviour evidence: $id")
            val actual = anchors.map { Triple(it.text("class"), it.text("method"), it.text("source_file")) }.toSet()
            assertEquals(anchors.size, actual.size, "duplicated operation behaviour anchor: $id")
            assertEquals(expected.tests.toSet(), actual, "behaviour anchors drifted for $id")
            for ((cls, method, source) in expected.tests) {
                assertTrue(Files.isRegularFile(root.resolve(source)), "missing evidence source: $source")
                assertNotNull(
                    Class.forName(cls).declaredMethods.firstOrNull { it.name == method && it.parameterCount == 0 },
                    "missing compiled behaviour method: $cls#$method",
                )
            }
        }
    }

    // ---------------------------------------------------------------- history

    private fun validateHistory(
        matrix: JsonObject,
        aMatrix: JsonObject,
        b1Matrix: JsonObject,
        b2Matrix: JsonObject,
        review: JsonObject,
        b3Baseline: JsonObject,
        b2Baseline: JsonObject,
        b1Baseline: JsonObject,
        aBytes: ByteArray,
        b1Bytes: ByteArray,
        b2Bytes: ByteArray,
    ) {
        validateCReview(matrix, review)
        val historicalReview = stripEvidence(review, cEvidenceKey, cSummaryKey)
        val b3Matrix = json(b3MatrixPath)
        assertEquals(b3MatrixSha, sha(bytes(b3MatrixPath)), "B3 is byte-frozen history")
        assertEquals(JsonPrimitive(30), b3Matrix["typed_handler_count"])
        assertEquals(JsonPrimitive(20), b3Matrix["not_started_count"])
        assertEquals(JsonPrimitive(99), b3Matrix.getValue("runtime_registry_observed").jsonObject["count"])
        // Batch A: byte-frozen history, its own numbers asserted verbatim.
        assertEquals(aMatrixSha, sha(aBytes), "the batch A historical document is byte-frozen; never rewrite it into current numbers")
        assertEquals(JsonPrimitive(51), aMatrix["candidate_count"])
        assertEquals(JsonPrimitive(10), aMatrix["batch_a_count"])
        assertEquals(JsonPrimitive(40), aMatrix["not_started_count"])
        assertEquals(JsonPrimitive(1), aMatrix["deferred_count"])
        assertEquals(JsonPrimitive(79), aMatrix.getValue("runtime_registry_observed").jsonObject["count"])
        val aTyped = aMatrix.getValue("operations").jsonObject.filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }.keys
        assertEquals(10, aTyped.size)
        assertTrue(aTyped.none { it in batchB1 || it in batchB2 || it in batchB3 }, "the historical batch A document must not absorb later batches")

        // Batch B1: a frozen exit document now. Its 89/20/30/1 numbers and its 59 source hashes are
        // history: they are asserted as its own record and never restored onto current sources.
        assertEquals(b1MatrixSha, sha(b1Bytes), "the batch B1 exit document is byte-frozen; never rewrite it into current numbers")
        assertEquals(JsonPrimitive(51), b1Matrix["candidate_count"])
        assertEquals(JsonPrimitive(20), b1Matrix["typed_handler_count"])
        assertEquals(JsonPrimitive(30), b1Matrix["not_started_count"])
        assertEquals(JsonPrimitive(1), b1Matrix["deferred_count"])
        assertEquals(JsonPrimitive(10), b1Matrix["batch_b_completed"])
        assertEquals(JsonPrimitive(89), b1Matrix.getValue("runtime_registry_observed").jsonObject["count"])
        assertEquals(JsonPrimitive(46), b1Matrix.getValue("runtime_registry_observed").jsonObject["read"])
        assertEquals(JsonPrimitive(43), b1Matrix.getValue("runtime_registry_observed").jsonObject["write"])
        assertEquals(59, b1Matrix.getValue("source_sha256").jsonObject.size, "the historical batch B1 snapshot keeps its own 59 paths")
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", b1Matrix.text("batch_b1_acceptance"))
        val b1Rows = b1Matrix.getValue("operations").jsonObject
        assertEquals(batchB1, b1Rows.filterValues { it.jsonObject.text("batch") == "R2-B1" }.keys, "batch B1 history keeps exactly its ten operations")
        assertEquals(20, b1Rows.filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }.size)
        assertTrue(b1Rows.filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }.keys.none { it in batchB2 || it in batchB3 }, "the batch B1 history must not absorb later batches")

        // Batch B2: also a frozen exit document now. Its 96/27/23/1 numbers and its 77 source hashes
        // are history only; its acceptance authority stays in the plan/QA report and is not rewritten.
        assertEquals(b2MatrixSha, sha(b2Bytes), "the batch B2 exit document is byte-frozen; never rewrite it into current numbers")
        assertEquals(JsonPrimitive(51), b2Matrix["candidate_count"])
        assertEquals(JsonPrimitive(27), b2Matrix["typed_handler_count"])
        assertEquals(JsonPrimitive(23), b2Matrix["not_started_count"])
        assertEquals(JsonPrimitive(1), b2Matrix["deferred_count"])
        assertEquals(JsonPrimitive(17), b2Matrix["batch_b_completed"])
        assertEquals(JsonPrimitive(96), b2Matrix.getValue("runtime_registry_observed").jsonObject["count"])
        assertEquals(JsonPrimitive(49), b2Matrix.getValue("runtime_registry_observed").jsonObject["read"])
        assertEquals(JsonPrimitive(47), b2Matrix.getValue("runtime_registry_observed").jsonObject["write"])
        assertEquals(77, b2Matrix.getValue("source_sha256").jsonObject.size, "the historical batch B2 snapshot keeps its own 77 paths")
        assertEquals("DEVELOPER_VERIFIED_INDEPENDENT_PENDING", b2Matrix.text("batch_b2_acceptance"))
        val b2Rows = b2Matrix.getValue("operations").jsonObject
        assertEquals(batchB2, b2Rows.filterValues { it.jsonObject.text("batch") == "R2-B2" }.keys, "batch B2 history keeps exactly its seven operations")
        assertEquals(27, b2Rows.filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }.size)
        assertTrue(b2Rows.filterValues { it.jsonObject.text("technical_support") == "TYPED_HANDLER" }.keys.none { it in batchB3 }, "the batch B2 history must not absorb batch B3")

        // review.json: only this batch's additive observations may differ from the round-start ledger.
        val rows = historicalReview.getValue("operations").jsonObject
        val r2 = rows.filterValues {
            it.jsonObject.text("product_id") == "r2-objects-and-buckets" && it.jsonObject.text("phase") == "P3"
        }
        assertEquals(51, r2.size)
        for ((id, value) in r2) {
            val evidence = value.jsonObject.getValue("evidence").jsonObject
            assertTrue(aEvidenceKey in evidence, "the historical batch A observation disappeared for $id")
            assertEquals(
                aMatrix.getValue("operations").jsonObject.getValue(id).jsonObject.text("technical_support"),
                evidence.getValue(aEvidenceKey).jsonObject.text("technical_support"),
                "the batch A observation is history and must keep its own status: $id",
            )
            // Batch B1 stays exactly as its own frozen document recorded it.
            val b1Observation = evidence.getValue(b1EvidenceKey).jsonObject
            assertEquals(b1MatrixPath + "#operations/$id", b1Observation.text("reference"), id)
            assertEquals(JsonPrimitive(false), b1Observation["authenticated_cloud_accepted"], id)
            assertEquals(JsonPrimitive(false), b1Observation["paid_eligibility_verified"], id)
            assertEquals(
                b1Rows.getValue(id).jsonObject.text("technical_support"),
                b1Observation.text("technical_support"),
                "the batch B1 observation must keep its own frozen status: $id",
            )
            // Batch B2 stays exactly as its own frozen document recorded it.
            val b2Observation = evidence.getValue(b2EvidenceKey).jsonObject
            assertEquals(b2MatrixPath + "#operations/$id", b2Observation.text("reference"), id)
            assertEquals(JsonPrimitive(false), b2Observation["authenticated_cloud_accepted"], id)
            assertEquals(JsonPrimitive(false), b2Observation["paid_eligibility_verified"], id)
            assertEquals(
                b2Rows.getValue(id).jsonObject.text("technical_support"),
                b2Observation.text("technical_support"),
                "the batch B2 observation must keep its own frozen status: $id",
            )
            // Batch B3 adds one observation per candidate, pointing at the current document.
            val b3Observation = evidence.getValue(b3EvidenceKey).jsonObject
            assertEquals(b3MatrixPath + "#operations/$id", b3Observation.text("reference"), id)
            assertEquals(JsonPrimitive(false), b3Observation["authenticated_cloud_accepted"], id)
            assertEquals(JsonPrimitive(false), b3Observation["paid_eligibility_verified"], id)
            assertEquals(
                b3Matrix.getValue("operations").jsonObject.getValue(id).jsonObject.text("technical_support"),
                b3Observation.text("technical_support"),
                "the batch B3 observation must match the current local state: $id",
            )
        }
        val updates = historicalReview.getValue("runtime_support_updates").jsonObject
        assertEquals(
            b3MatrixPath,
            updates.getValue(b3SummaryKey).jsonObject.text("reference"),
            "the current batch needs exactly one summary pointer",
        )
        assertEquals(
            b2MatrixPath,
            updates.getValue(b2SummaryKey).jsonObject.text("reference"),
            "the batch B2 summary pointer stays on its own frozen document",
        )
        assertEquals(
            b1MatrixPath,
            updates.getValue(b1SummaryKey).jsonObject.text("reference"),
            "the batch B1 summary pointer stays on its own frozen document",
        )
        assertTrue("2026-10-18-r2-batch-a" in updates && "2026-10-17" in updates, "historical summary pointers stay present")

        // Removing only this batch reproduces the round-start ledger exactly; removing B3 then B2
        // reproduces the batch B2 ledger, and removing B3/B2/B1 reproduces the batch B1 ledger.
        // Structural deep equality, reported with a short message instead of dumping both ledgers.
        assertTrue(
            stripEvidence(historicalReview, b3EvidenceKey, b3SummaryKey) == b3Baseline,
            "review.json changed outside this batch's additive observations",
        )
        val withoutB3 = stripEvidence(historicalReview, b3EvidenceKey, b3SummaryKey)
        assertTrue(
            stripEvidence(withoutB3, b2EvidenceKey, b2SummaryKey) == b2Baseline,
            "the batch B2 observations were altered by this batch",
        )
        assertTrue(
            stripEvidence(stripEvidence(withoutB3, b2EvidenceKey, b2SummaryKey), b1EvidenceKey, b1SummaryKey) == b1Baseline,
            "the batch B1 observations were altered by this batch",
        )
    }

    private fun stripEvidence(document: JsonObject, evidenceKey: String, summaryKey: String): JsonObject {
        val operations = document.getValue("operations").jsonObject.mapValues { (_, value) ->
            val row = value.jsonObject
            val evidence = row["evidence"]?.jsonObject ?: return@mapValues row
            if (!evidence.containsKey(evidenceKey)) row else JsonObject(row + ("evidence" to JsonObject(evidence - evidenceKey)))
        }
        val updates = JsonObject(document.getValue("runtime_support_updates").jsonObject - summaryKey)
        return JsonObject(document + ("operations" to JsonObject(operations)) + ("runtime_support_updates" to updates))
    }

    // ---------------------------------------------------------------- current source drift

    /**
     * The reviewed snapshot is a fixed set of 147 paths, independent of the current matrix:
     * batch A's 25 paths come from its byte-frozen historical document, batch B1/gate contributes 34
     * literals below, batch B2 contributes 18 and batch B3 contributes 36 more. No set is inferred.
     */
    private fun reviewedSourcePaths(aMatrix: JsonObject = aMatrix()): List<String> {
        val historical = aMatrix.getValue("source_sha256").jsonObject.keys
        assertEquals(25, historical.size, "the historical batch A snapshot keeps exactly 25 paths")
        assertEquals(34, batchBReviewedPaths.size, "the reviewed batch B1/gate source literals stay exactly 34 paths")
        val b1 = (historical + batchBReviewedPaths).distinct().sorted()
        assertEquals(59, b1.size, "the reviewed batch B1 snapshot is exactly 59 paths")
        assertEquals(18, batchB2ReviewedPaths.size, "batch B2 adds exactly 18 reviewed source literal paths")
        val b2 = (b1 + batchB2ReviewedPaths).distinct().sorted()
        assertEquals(77, b2.size, "the reviewed batch B2 snapshot is exactly 77 paths")
        assertEquals(36, batchB3ReviewedPaths.size, "batch B3 adds exactly 36 reviewed source literal paths")
        val current = (b2 + batchB3ReviewedPaths).distinct().sorted()
        assertEquals(113, current.size, "the reviewed batch B3 snapshot is exactly 113 paths")
        assertEquals(35, batchCReviewedPaths.size, "C adds exactly 35 literal paths")
        val withC = (current + batchCReviewedPaths).distinct().sorted()
        assertEquals(147, withC.size, "the current snapshot has exactly 147 paths (core build already reviewed)")
        return withC
    }

    private fun validateSources(matrix: JsonObject, claimed: Map<String, String> = recordedClaims(matrix)) {
        val expected = reviewedSourcePaths()
        assertEquals(147, expected.size, "the reviewed snapshot is exactly 147 paths")
        // Set equality against the fixed whitelist: a missing path, a same-count substitution of an
        // unrelated (even real) file, and an extra unreviewed path are all refused.
        assertEquals(expected.toSet(), claimed.keys, "the snapshot must be exactly the reviewed 147 paths")
        for (path in expected) {
            assertTrue(Files.isRegularFile(root.resolve(path)), "reviewed source is missing: $path")
            assertEquals(sha(bytes(path)), claimed.getValue(path), "current source drift: $path")
        }
    }

    /** Only the claimed hashes; the reviewed path set always comes from [reviewedSourcePaths]. */
    private fun recordedClaims(matrix: JsonObject): Map<String, String> =
        matrix.getValue("source_sha256").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    private val batchBReviewedPaths = listOf(
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2ConfigurationModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2ConfigurationPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2ConfigurationValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2ConfigurationController.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2TaskTargets.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2ConfigurationEditing.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2WriteDispatch.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2ConfigurationClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2ConfigurationPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/ActionPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2ConfigurationSession.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationClientTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationValidationTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationPoliciesTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationControllerTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationAuthorityTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationTestFixtures.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2TaskTargetsTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2CoverageTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/P2CoverageTest.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/CloudflareClient.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/CloudflareClientTest.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2ConfigurationBrowser.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2ConfigurationUiModels.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2ConfigurationScreen.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2CorsConfigurationPanel.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2LifecycleConfigurationPanel.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2LocalUploadsConfigurationPanel.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2LockConfigurationPanel.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2ConfigurationBrowserTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2ConfigurationUiTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2ConfigurationManagementUiTest.kt",
        "docs/cloudflare-audit/review.json",
    )

    /** Batch B2's own reviewed paths; literals here, never read from the document under test. */
    private val batchB2ReviewedPaths = listOf(
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2DomainConfigurationModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2DomainConfigurationPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2DomainConfigurationValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2DomainConfigurationController.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2DomainConfigurationEditing.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2DomainConfigurationClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2DomainConfigurationPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2DomainConfigurationSession.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainConfigurationTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainDeleteCrossLayerTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainTestFixtures.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2ManagedDomainConfigurationPanel.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2CustomDomainConfigurationPanel.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2DomainConfigurationBrowserTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2DomainConfigurationUiTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2DomainConfigurationManagementUiTest.kt",
    )

    /** Batch B3's own reviewed paths; literals here, never read from the document under test. */
    private val batchB3ReviewedPaths = listOf(
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2SippyController.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2SippySecrets.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2SippyValidation.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/model/R2SippyModels.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/ports/R2SippyPort.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2SippyClient.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2SippyPolicies.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2SippySession.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/HttpTransport.kt",
        "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/CloudflareGateway.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyRuntimeTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyAuthorityTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyCrossLayerOwnershipTest.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyFix1Test.kt",
        "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyLocatorTest.kt",
        "android-platform/src/main/kotlin/com/flarego/platform/CloudflareTransport.kt",
        "android-platform/src/test/kotlin/com/flarego/platform/CloudflareSensitiveBodyTest.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2SippyConfigurationPanel.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/R2SippyCredentialOwner.kt",
        "shared-ui/src/commonMain/kotlin/com/flarego/ui/r2/SecureWindowPort.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2SippyBrowserTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2SippyCredentialOwnerTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/R2SippyFormFlowTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/SecureWindowOwnershipTest.kt",
        "shared-ui/src/commonTest/kotlin/com/flarego/ui/r2/FakeSecureWindowPort.kt",
        "app/src/main/kotlin/com/flarego/AndroidSecureWindow.kt",
        "app/src/main/kotlin/com/flarego/CleanFrameMatch.kt",
        "app/src/test/kotlin/com/flarego/CleanFrameMatchTest.kt",
        "app/src/main/kotlin/com/flarego/MainActivity.kt",
        "app/src/androidTest/kotlin/com/flarego/R2SippyFormUiTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2SippyFrameBarrierTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2SippyRecentsTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2SippyRecreateTest.kt",
        "app/src/androidTest/kotlin/com/flarego/R2SippySecureWindowTest.kt",
        "app/build.gradle.kts",
    )

    // ---------------------------------------------------------------- schema + behaviour

    private fun validateSchema(matrix: JsonObject, schema: JsonObject = schema()) {
        val paths = schema.getValue("paths").jsonObject
        for ((id, value) in candidates(matrix)) {
            val row = value
            val declared = paths.getValue(row.text("path")).jsonObject
                .getValue(row.text("method").lowercase()).jsonObject
            assertEquals(id, declared.text("operationId"), "$id must keep the pinned method and path")
        }
    }

    private fun validateBehaviour(matrix: JsonObject, aMatrix: JsonObject) {
        // The curated binding is checked first: the executed set is never steered by the rows under test.
        validateCuratedBindings(matrix, aMatrix)
        // Validate the fixed A/B2/B3/C cross-cutting set before any method runs.
        validateCrossCutting(matrix, aMatrix)
        val curated = (curatedA(aMatrix).values.toList() + curatedB1().values.toList() + curatedB2().values.toList() + curatedB3().values.toList() + curatedC().values.toList())
        // The executed set comes from frozen/literal sources only: the matrix cannot choose it.
        val aCrossCutting = aMatrix["cross_cutting_local_tests"]?.jsonArray.orEmpty().flatMap { row ->
            val entry = row.jsonObject
            entry.getValue("methods").jsonArray.map { method ->
                Triple(entry.text("class"), method.jsonPrimitive.content, aMatrixPath)
            }
        }
        val refs = (curated.flatMap { binding -> binding.tests.map { Triple(it.first, it.second, it.third) } } +
            // The historical batch A cross-cutting runtime/controller evidence stays executed too.
            aCrossCutting +
            // B2/B3/C cross-cutting anchors are literals, not the current matrix rows.
            (b2CrossCutting() + b3CrossCutting() + cCrossCutting()).map { Triple(it.cls, it.method, it.source) })
            .distinctBy { it.first + "#" + it.second }
        assertEquals(
            168,
            refs.size,
            "38 curated operations with fixed A/B/C cross-cutting methods must execute exactly 168 distinct compiled anchors",
        )
        val executed = mutableListOf<String>()
        for ((cls, methodName, _) in refs) {
            val type = Class.forName(cls)
            val method = type.getDeclaredMethod(methodName)
            try {
                method.invoke(type.getDeclaredConstructor().newInstance())
            } catch (error: InvocationTargetException) {
                throw AssertionError("Actual R2 behaviour failed: $cls#$methodName", error.cause)
            }
            executed += "$cls#$methodName"
        }
        assertEquals(executed.size, executed.toSet().size)
        println("R2 current gate: 168 distinct compiled anchors executed (81 C, 87 historical); not JUnit test count")
        // Every typed operation is exercised by a curated method that really ran.
        for ((id, binding) in curatedA(aMatrix) + curatedB1() + curatedB2() + curatedB3() + curatedC()) {
            assertTrue(
                binding.tests.any { (cls, methodName, _) -> executed.contains("$cls#$methodName") },
                "no curated behaviour method actually ran for $id",
            )
        }
        // The batch B1/B2 evidence is common (JVM-reflectable) tests, never native classes.
        for (batch in listOf("R2-B1", "R2-B2", "R2-C")) {
            for (test in candidates(matrix).filterValues { it.text("batch") == batch }
                .values.flatMap { it.getValue("tests").jsonArray }.map { it.jsonObject }) {
                assertTrue(test.text("class").startsWith("com.flarego.core."), "$batch evidence must be a compiled common test: ${test.text("class")}")
            }
        }
        // The real cross-layer delete anchor is a compiled common test that actually ran.
        assertTrue(
            executed.contains("com.flarego.core.R2DomainDeleteCrossLayerTest#aDeleteSendsOneSingleDomainDeleteAfterTheCompleteCollectionWasObserved"),
            "the real cross-layer delete anchor must run",
        )
        // The real Sippy authority anchor (fresh account-level proof per write) must run too.
        assertTrue(
            executed.contains("com.flarego.core.R2SippyAuthorityTest#bothSippyWritesMintTheirOwnFreshAccountLevelProof"),
            "the real Sippy authority anchor must run",
        )
    }

    /** Production sources never reference the audit inputs: no ledger can grant a request. */
    private fun validateRuntimeNeverLoadsTheLedgers() {
        val modules = listOf("shared-core/src/commonMain", "shared-ui/src/commonMain", "app/src/main", "android-platform/src/main")
        val forbidden = listOf("docs/cloudflare-audit", "review.json", "runtime-support")
        for (module in modules) {
            val dir = root.resolve(module)
            assertTrue(Files.isDirectory(dir), module)
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { file ->
                    val text = Files.readString(file)
                    for (needle in forbidden) {
                        assertFalse(
                            text.contains(needle),
                            "production source must never read the audit ledger ($needle): ${root.relativize(file)}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun c8RejectsExistingButWrongHandlerAndBehaviourReferences() {
        val m = matrix()
        val ops = m.getValue("operations").jsonObject
        val id = "r2-get-bucket-job"
        val row = ops.getValue(id).jsonObject
        val wrongHandler = buildJsonObject {
            put("class", "com.flarego.core.cloudflare.r2.CloudflareR2JobsClient")
            put("method", "listJobs")
            put("source_file", "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2JobsClient.kt")
        }
        assertFailsWith<AssertionError>("C8 must not accept another existing job handler") {
            validateCuratedBindings(JsonObject(m + ("operations" to JsonObject(ops + (id to JsonObject(row + ("handler" to wrongHandler)))))), aMatrix())
        }
        val wrongTest = buildJsonObject {
            put("class", "com.flarego.core.R2JobsClientTest")
            put("method", "emptyPrefixIsTheExplicitWholeBucketClear")
            put("source_file", "shared-core/src/commonTest/kotlin/com/flarego/core/R2JobsClientTest.kt")
        }
        assertFailsWith<AssertionError>("C8 must not accept another existing job behaviour") {
            validateCuratedBindings(JsonObject(m + ("operations" to JsonObject(ops + (id to JsonObject(row + ("tests" to JsonArray(listOf(wrongTest)))))))), aMatrix())
        }
    }

    @Test
    fun c8RejectsDuplicateCrossCuttingMethodsRatherThanCollapsingThemToASet() {
        val m = matrix()
        val block = m.getValue("cross_cutting_local_tests").jsonArray
        val row = block.first().jsonObject
        val methods = row.getValue("methods").jsonArray
        val duplicate = JsonObject(row + ("methods" to JsonArray(methods + methods.first())))
        assertFailsWith<AssertionError>("duplicate method evidence must not hide behind set equality") {
            validateCrossCutting(JsonObject(m + ("cross_cutting_local_tests" to JsonArray(listOf(duplicate) + block.drop(1)))), aMatrix())
        }
    }

    @Test
    fun c8RequiresTheExactEightOperationBindingsAndRefusesDuplicateAnchors() {
        val m = matrix()
        val ops = m.getValue("operations").jsonObject
        validateCuratedBindings(m, aMatrix()) // Positive control: refusals cannot hide a broken baseline.
        for (id in batchC) {
            val row = ops.getValue(id).jsonObject
            fun changed(value: JsonObject) = JsonObject(m + ("operations" to JsonObject(ops + (id to value))))
            val wrong = buildJsonObject {
                put("class", "com.flarego.core.cloudflare.r2.CloudflareR2Client")
                put("method", "listBuckets")
                put("source_file", "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2Client.kt")
            }
            assertFailsWith<AssertionError>("wrong existing handler: $id") {
                validateCuratedBindings(changed(JsonObject(row + ("handler" to wrong))), aMatrix())
            }
            assertFailsWith<AssertionError>("missing handler: $id") {
                validateCuratedBindings(changed(JsonObject(row - "handler")), aMatrix())
            }
            val wrongTest = buildJsonObject {
                put("class", "com.flarego.core.R2BucketManagementTest")
                put("method", "aForeignCursorIsAnErrorInsteadOfAnEndlessLoop")
                put("source_file", "shared-core/src/commonTest/kotlin/com/flarego/core/R2BucketManagementTest.kt")
            }
            assertFailsWith<AssertionError>("wrong existing behaviour: $id") {
                validateCuratedBindings(changed(JsonObject(row + ("tests" to JsonArray(listOf(wrongTest))))), aMatrix())
            }
            val tests = row.getValue("tests").jsonArray
            assertFailsWith<AssertionError>("duplicate behaviour: $id") {
                validateCuratedBindings(changed(JsonObject(row + ("tests" to JsonArray(tests + tests.first())))), aMatrix())
            }
        }
        assertFailsWith<AssertionError>("C acceptance cannot be inflated") {
            denominator(JsonObject(m + ("batch_c_acceptance" to JsonPrimitive("ACCEPTED"))))
        }
        assertFailsWith<AssertionError>("schema identity cannot drift") {
            denominator(JsonObject(m + ("schema_sha256" to JsonPrimitive("0".repeat(64)))))
        }
    }

    @Test
    fun c8CrossCuttingAndSourcesCannotBeSubstitutedForSameCountUnrelatedEvidence() {
        val m = matrix()
        val block = m.getValue("cross_cutting_local_tests").jsonArray
        validateCrossCutting(m, aMatrix())
        val cls = "com.flarego.core.R2CJobsRuntimeTest"
        val victim = block.first { it.jsonObject.text("class") == cls }.jsonObject
        val methods = victim.getValue("methods").jsonArray
        fun changed(entries: JsonArray) = JsonObject(m + ("cross_cutting_local_tests" to entries))
        for (replacement in listOf(
            JsonArray(methods.drop(1)),
            JsonArray(methods + JsonPrimitive("anExistingButUnreviewedMethod")),
            JsonArray(listOf(JsonPrimitive("toString")) + methods.drop(1)),
            JsonArray(methods + methods.first()),
        )) {
            val swapped = JsonArray(block.map { if (it.jsonObject.text("class") == cls) JsonObject(victim + ("methods" to replacement)) else it })
            assertFailsWith<AssertionError> { validateCrossCutting(changed(swapped), aMatrix()) }
        }
        assertFailsWith<AssertionError> {
            validateCrossCutting(changed(JsonArray(block.filter { it.jsonObject.text("class") != cls })), aMatrix())
        }
        val claims = recordedClaims(m)
        val source = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2JobsClient.kt"
        assertFailsWith<AssertionError> { validateSources(m, claims + (source to "0".repeat(64))) }
        assertFailsWith<AssertionError> {
            validateSources(m, claims - source + ("README.md" to sha(bytes("README.md"))))
        }
    }

    // ---------------------------------------------------------------- the gate

    private fun validate(
        matrix: JsonObject = matrix(),
        aMatrix: JsonObject = aMatrix(),
        b1Matrix: JsonObject = b1Matrix(),
        b2Matrix: JsonObject = b2Matrix(),
        review: JsonObject = review(),
        b3Baseline: JsonObject = reviewBaseline(),
        b2Baseline: JsonObject = reviewBaseline(b2BaselinePath),
        b1Baseline: JsonObject = reviewBaseline(b1BaselinePath),
        registry: Map<String, ActionRegistration> = ActionPolicies.registry.registrations,
        claimed: Map<String, String> = recordedClaims(matrix()),
    ) {
        denominator(matrix)
        validateCandidateSet(matrix, aMatrix)
        validateRegistry(matrix, registry, aMatrix = aMatrix)
        validateCrossCutting(matrix, aMatrix)
        validateSchema(matrix)
        validateHistory(
            matrix, aMatrix, b1Matrix, b2Matrix, review, b3Baseline, b2Baseline, b1Baseline,
            bytes(aMatrixPath), bytes(b1MatrixPath), bytes(b2MatrixPath),
        )
        validateSources(matrix, claimed)
    }

    @Test
    fun all51CandidatesKeepTheCurrentExact38Typed12NotStartedAndOneDeferredState() {
        validate()
    }

    @Test
    fun handwrittenRoutesKeepThePinnedSchemaAndTheExact131EntryRegistry() {
        validateSchema(matrix())
        validateRegistry(matrix(), ActionPolicies.registry.registrations)
    }

    @Test
    fun theFrozenBatchAAndB1AndB2DocumentsKeepTheirOwnNumbersAndReviewOnlyAddsThisBatchesObservations() {
        validateHistory(
            matrix(), aMatrix(), b1Matrix(), b2Matrix(), review(), reviewBaseline(), reviewBaseline(b2BaselinePath),
            reviewBaseline(b1BaselinePath), bytes(aMatrixPath), bytes(b1MatrixPath), bytes(b2MatrixPath),
        )
    }

    @Test
    fun currentSourceSnapshotAndReviewBaselineActuallyMatchTheirFiles() {
        validateSources(matrix())
        // The recorded values are real file hashes: a deliberately wrong expectation must fail.
        val recorded = matrix().getValue("source_sha256").jsonObject
        val tampered = recorded.mapValues { (path, digest) ->
            if (path.endsWith("CloudflareR2SippyClient.kt")) JsonPrimitive("0".repeat(64)) else digest
        }
        assertFailsWith<AssertionError> { validateSources(matrix(), tampered.mapValues { it.value.jsonPrimitive.content }) }
        // Removing this batch's additions must reproduce the round-start ledger, byte for byte at JSON level.
        assertTrue(
            stripEvidence(stripEvidence(review(), cEvidenceKey, cSummaryKey), b3EvidenceKey, b3SummaryKey) == reviewBaseline(),
            "removing the batch B3 additions must reproduce the batch B3 round-start ledger",
        )
        // Removing B3, then B2, then B1 must reproduce each earlier round-start ledger.
        val withoutB3 = stripEvidence(stripEvidence(review(), cEvidenceKey, cSummaryKey), b3EvidenceKey, b3SummaryKey)
        assertTrue(
            stripEvidence(withoutB3, b2EvidenceKey, b2SummaryKey) == reviewBaseline(b2BaselinePath),
            "removing the batch B2 additions must reproduce the batch B2 round-start ledger",
        )
        assertTrue(
            stripEvidence(stripEvidence(withoutB3, b2EvidenceKey, b2SummaryKey), b1EvidenceKey, b1SummaryKey) ==
                reviewBaseline(b1BaselinePath),
            "removing the batch B1 additions must reproduce the batch B1 round-start ledger",
        )
        val tamperedHistory = JsonObject(review() + ("format" to JsonPrimitive("tampered")))
        assertFailsWith<AssertionError> {
            validateHistory(
                matrix(), aMatrix(), b1Matrix(), b2Matrix(), tamperedHistory, reviewBaseline(), reviewBaseline(b2BaselinePath),
                reviewBaseline(b1BaselinePath), bytes(aMatrixPath), bytes(b1MatrixPath), bytes(b2MatrixPath),
            )
        }
    }

    @Test
    fun reviewedCandidatesAndTheirCompiledBehaviourActuallyRun() {
        validateBehaviour(matrix(), aMatrix())
    }

    @Test
    fun missingCandidatesHandlersTestsStatusInflationAndRegistryDriftAreRejected() {
        val m = matrix()
        val ops = m.getValue("operations").jsonObject
        fun row(id: String) = ops.getValue(id).jsonObject
        fun replaced(id: String, replacement: JsonObject) = JsonObject(m + ("operations" to JsonObject(ops + (id to replacement))))

        // A missing candidate (or a shrunk denominator) cannot pass.
        assertFailsWith<AssertionError> { denominator(JsonObject(m + ("operations" to JsonObject(ops - ops.keys.first())))) }
        assertFailsWith<AssertionError> { validate(JsonObject(m + ("operations" to JsonObject(ops - ops.keys.first())))) }

        // Status inflation without a handler, a test or a policy is refused, including the deferred credential op.
        val notStarted = ops.filterValues { it.jsonObject.text("technical_support") == "NOT_STARTED" }.keys.first()
        for (id in listOf(notStarted, deferred)) {
            val inflated = JsonObject(row(id) + ("technical_support" to JsonPrimitive("TYPED_HANDLER")))
            assertFailsWith<AssertionError>(id) { validate(replaced(id, inflated)) }
            assertFailsWith<AssertionError>(id) { denominator(replaced(id, inflated)) }
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("typed_handler_count" to JsonPrimitive(29))))
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("typed_handler_count" to JsonPrimitive(31))))
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("batch_b1_acceptance" to JsonPrimitive("ACCEPTED"))))
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("batch_b2_acceptance" to JsonPrimitive("ACCEPTED"))))
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("batch_b3_acceptance" to JsonPrimitive("ACCEPTED"))))
        }
        // Registry observation inflation is refused even when the live registry itself matches.
        assertFailsWith<AssertionError> {
            validateRegistry(
                JsonObject(m + ("runtime_registry_observed" to buildJsonObject {
                    put("count", 100); put("read", 50); put("write", 50)
                    put("historical_p1_p2_auxiliary_count", 69)
                    put("reviewed_batch_a_addition", 10)
                    put("reviewed_batch_b1_addition", 10)
                    put("reviewed_batch_b2_addition", 7)
                    put("reviewed_batch_b3_addition", 3)
                })),
                ActionPolicies.registry.registrations,
            )
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("authenticated_cloud_accepted" to JsonPrimitive(true))))
        }
        assertFailsWith<AssertionError> {
            denominator(JsonObject(m + ("batch_b_completed" to JsonPrimitive(19))))
        }

        // A typed row whose handler, test or policy is missing/drifting cannot pass.
        val typedId = "r2-put-bucket-cors-policy"
        val typed = row(typedId)
        assertFailsWith<AssertionError> {
            validate(replaced(typedId, JsonObject(typed - "handler")))
        }
        assertFailsWith<AssertionError> {
            validate(replaced(typedId, JsonObject(typed + ("tests" to JsonArray(emptyList())))))
        }
        assertFailsWith<AssertionError> {
            validate(replaced(typedId, JsonObject(typed + ("handler" to buildJsonObject {
                put("source_file", "missing.kt"); put("class", "missing.Class"); put("method", "missing")
            }))))
        }
        assertFailsWith<AssertionError> {
            validate(replaced(typedId, JsonObject(typed + ("tests" to JsonArray(listOf(buildJsonObject {
                put("source_file", "missing.kt"); put("class", "com.flarego.core.R2ConfigurationClientTest"); put("method", "missingMethod")
            }))))))
        }

        // Method / path / scope / semantics / response / risk / retry / slash-tail drift is refused field by field.
        val policy = typed.getValue("policy").jsonObject
        for ((field, bad) in mapOf(
            "method" to JsonPrimitive("POST"),
            "path" to JsonPrimitive("/accounts/{account_id}/r2/buckets/{bucket_name}/cors/extra"),
            "scope" to JsonPrimitive("ZONE"),
            "semantics" to JsonPrimitive("READ"),
            "response_kind" to JsonPrimitive("TEXT"),
            "risk" to JsonPrimitive("READ_ONLY"),
            "trailing_placeholder_spans_segments" to JsonPrimitive(true),
            "retry" to buildJsonObject { put("enabled", true); put("max_retries", 2); put("max_wait_ms", 60000) },
        )) {
            assertFailsWith<AssertionError>(field) {
                validate(replaced(typedId, JsonObject(typed + ("policy" to JsonObject(policy + (field to bad))))))
            }
        }

        // Registry drift: a missing reviewed route and a rogue extra read/write are both refused.
        val registry = ActionPolicies.registry.registrations
        assertFailsWith<AssertionError> { validateRegistry(m, registry - typedId) }
        val rogueRead = ActionRegistration(
            ActionPolicy("unreviewed-r2-read", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
            "GET", "/accounts/{account_id}/r2/unreviewed", ActionScope.ACCOUNT,
        )
        val rogueWrite = ActionRegistration(
            ActionPolicy("unreviewed-r2-write", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, RiskLevel.CHANGE, ReadRetryPolicy()),
            "POST", "/accounts/{account_id}/r2/unreviewed", ActionScope.ACCOUNT,
        )
        assertFailsWith<AssertionError> { validateRegistry(m, registry + (rogueRead.policy.key to rogueRead)) }
        assertFailsWith<AssertionError> { validateRegistry(m, registry + (rogueWrite.policy.key to rogueWrite)) }
        assertFailsWith<AssertionError> { validateRegistry(m, registry, historicalBaseline() - historicalBaseline().first()) }

        // Schema drift is refused, and a swapped method/path operationId never passes.
        val driftedSchema = Json.parseToJsonElement(schema().toString()).jsonObject
        val paths = driftedSchema.getValue("paths").jsonObject
        val bucketPath = paths.getValue("/accounts/{account_id}/r2/buckets/{bucket_name}").jsonObject
        val swapped = JsonObject(bucketPath + ("get" to buildJsonObject {
            put("operationId", "not-r2-get-bucket")
        }))
        assertFailsWith<AssertionError> {
            validateSchema(m, JsonObject(driftedSchema + ("paths" to JsonObject(paths + ("/accounts/{account_id}/r2/buckets/{bucket_name}" to swapped)))))
        }
    }

    @Test
    fun theSourceSnapshotCannotBeSwappedForSameCountUnrelatedFiles() {
        val m = matrix()
        val recorded = m.getValue("source_sha256").jsonObject
        // A reviewed path outside the previous eight anchors.
        val victim = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/identity/AuthorizedR2ConfigurationSession.kt"
        assertTrue(victim in recorded, "the current snapshot must record the B1 authorized session")
        // A real, legal, unrelated repository file that was never reviewed for this snapshot.
        val unrelated = "shared-core/src/commonMain/kotlin/com/flarego/core/application/r2/R2Validation.kt"
        assertTrue(unrelated !in recorded, "the swap target must be outside the reviewed set")
        val unrelatedHash = sha(bytes(unrelated))
        // Same count, one reviewed path removed and one unrelated real file added with its real hash.
        val swapped = JsonObject(recorded - victim + (unrelated to JsonPrimitive(unrelatedHash)))
        assertEquals(recorded.size, swapped.size, "the substitution keeps the recorded count")
        assertFailsWith<AssertionError>("a same-count substitution of a reviewed path must be refused") {
            validateSources(JsonObject(m + ("source_sha256" to swapped)))
        }
        // Dropping a reviewed path is refused.
        assertFailsWith<AssertionError>("a missing reviewed path must be refused") {
            validateSources(JsonObject(m + ("source_sha256" to JsonObject(recorded - victim))))
        }
        // An extra unreviewed path is refused even when its hash is real.
        assertFailsWith<AssertionError>("an extra unreviewed path must be refused") {
            validateSources(JsonObject(m + ("source_sha256" to JsonObject(recorded + (unrelated to JsonPrimitive(unrelatedHash))))))
        }
        // The same substitution must be refused for a batch B2-reviewed path, not just the B1 anchors.
        val b2Victim = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2DomainConfigurationPolicies.kt"
        assertTrue(b2Victim in recorded, "the current snapshot must record the batch B2 registration source")
        assertFailsWith<AssertionError>("a same-count substitution of a batch B2 path must be refused") {
            validateSources(JsonObject(m + ("source_sha256" to JsonObject(recorded - b2Victim + (unrelated to JsonPrimitive(unrelatedHash))))))
        }
        // And for a batch B3-reviewed Sippy path.
        val b3Victim = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/protocol/R2SippyPolicies.kt"
        assertTrue(b3Victim in recorded, "the current snapshot must record the batch B3 registration source")
        assertFailsWith<AssertionError>("a same-count substitution of a batch B3 path must be refused") {
            validateSources(JsonObject(m + ("source_sha256" to JsonObject(recorded - b3Victim + (unrelated to JsonPrimitive(unrelatedHash))))))
        }
    }

    @Test
    fun curatedHandlerAndBehaviourBindingsCannotBeReplacedByExistingButWrongMethods() {
        val m = matrix()
        val a = aMatrix()
        val ops = m.getValue("operations").jsonObject
        fun row(id: String) = ops.getValue(id).jsonObject
        fun replaced(id: String, replacement: JsonObject) = JsonObject(m + ("operations" to JsonObject(ops + (id to replacement))))
        fun withHandler(id: String, handlerClass: String, method: String, source: String) =
            replaced(id, JsonObject(row(id) + ("handler" to buildJsonObject {
                put("class", handlerClass); put("method", method); put("source_file", source)
            })))
        fun withTests(id: String, classAndMethod: String, source: String) =
            replaced(id, JsonObject(row(id) + ("tests" to JsonArray(listOf(buildJsonObject {
                put("class", classAndMethod.substringBefore('#'))
                put("method", classAndMethod.substringAfter('#'))
                put("source_file", source)
            })))))
        val aClient = "com.flarego.core.cloudflare.r2.CloudflareR2Client"
        val aClientSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2Client.kt"
        val aTestSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2BucketManagementTest.kt"
        val cfg = "com.flarego.core.cloudflare.r2.CloudflareR2ConfigurationClient"
        val cfgSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2ConfigurationClient.kt"
        val cfgTestSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2ConfigurationClientTest.kt"

        // Batch A: another existing method of the same reviewed class is not this operation's handler.
        assertFailsWith<AssertionError>("an existing but wrong batch A handler must be refused") {
            validateRegistry(withHandler("r2-get-bucket", aClient, "listBuckets", aClientSource), ActionPolicies.registry.registrations)
        }
        // Batch A: an existing but wrong behaviour method is not this operation's evidence.
        assertFailsWith<AssertionError>("an existing but wrong batch A behaviour anchor must be refused") {
            validateRegistry(withTests("r2-get-bucket", "com.flarego.core.R2BucketManagementTest#aForeignCursorIsAnErrorInsteadOfAnEndlessLoop", aTestSource), ActionPolicies.registry.registrations)
        }
        // Batch B1: the same weak spots must be closed.
        assertFailsWith<AssertionError>("an existing but wrong batch B1 handler must be refused") {
            validateRegistry(withHandler("r2-get-bucket-cors-policy", cfg, "getLifecycle", cfgSource), ActionPolicies.registry.registrations)
        }
        assertFailsWith<AssertionError>("an existing but wrong batch B1 behaviour anchor must be refused") {
            validateRegistry(withTests("r2-get-bucket-cors-policy", "com.flarego.core.R2ConfigurationClientTest#anAcceptedStatusIsAReceiptAndNeverADocumentRead", cfgTestSource), ActionPolicies.registry.registrations)
        }
        // A wrong-but-existing reference must never be executed as this operation's evidence.
        assertFailsWith<AssertionError>("a wrong-but-existing behaviour reference cannot be accepted by the execution gate") {
            validateBehaviour(
                withTests("r2-put-bucket-cors-policy", "com.flarego.core.R2ConfigurationClientTest#anUncertainOutcomeAfterTheSingleSendIsReportedAndNeverReplayed", cfgTestSource),
                a,
            )
        }
        // Batch B2: an existing but wrong method of the same reviewed class is not this operation's handler.
        val domain = "com.flarego.core.cloudflare.r2.CloudflareR2DomainConfigurationClient"
        val domainSource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2DomainConfigurationClient.kt"
        val domainTestSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainConfigurationTest.kt"
        val domainControllerSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2DomainControllerTest.kt"
        assertFailsWith<AssertionError>("an existing but wrong batch B2 handler must be refused") {
            validateRegistry(withHandler("r2-get-bucket-public-policy", domain, "listCustomDomains", domainSource), ActionPolicies.registry.registrations)
        }
        assertFailsWith<AssertionError>("an existing but wrong batch B2 behaviour anchor must be refused") {
            validateRegistry(
                withTests("r2-get-bucket-public-policy", "com.flarego.core.R2DomainConfigurationTest#aZoneFromAnotherAccountIsRefusedBeforeAnyWrite", domainTestSource),
                ActionPolicies.registry.registrations,
            )
        }
        // A wrong-but-existing B2 anchor cannot be executed as this operation's evidence either.
        assertFailsWith<AssertionError>("a wrong-but-existing batch B2 behaviour reference cannot be accepted by the execution gate") {
            validateBehaviour(
                withTests("r2-edit-custom-domain-settings", "com.flarego.core.R2DomainControllerTest#aDriftedDocumentOrAFailedReadBackStaysUnsettledAndIsNeverReplayed", domainControllerSource),
                a,
            )
        }
        // The list operation is a real list read: the existing single-domain GET anchor (same class,
        // correct-looking) must be refused, not silently accepted as the list evidence.
        assertFailsWith<AssertionError>("a list operation bound to an existing single-domain GET anchor must be refused") {
            validateRegistry(
                withTests("r2-list-custom-domains", "com.flarego.core.R2DomainConfigurationTest#customDomainsExposeOwnershipAndSslAsSeparateProviderStates", domainTestSource),
                ActionPolicies.registry.registrations,
            )
        }
        assertFailsWith<AssertionError>("the execution gate must also refuse the wrong list anchor") {
            validateBehaviour(
                withTests("r2-list-custom-domains", "com.flarego.core.R2DomainConfigurationTest#customDomainsExposeOwnershipAndSslAsSeparateProviderStates", domainTestSource),
                a,
            )
        }
        // Batch B3: an existing method of the same Sippy client is not this operation's handler, and
        // an existing Sippy test that belongs to another operation is not this operation's evidence.
        val sippy = "com.flarego.core.cloudflare.r2.CloudflareR2SippyClient"
        val sippySource = "shared-core/src/commonMain/kotlin/com/flarego/core/cloudflare/r2/CloudflareR2SippyClient.kt"
        val sippyTestSource = "shared-core/src/commonTest/kotlin/com/flarego/core/R2SippyTest.kt"
        assertFailsWith<AssertionError>("an existing but wrong batch B3 handler must be refused") {
            validateRegistry(withHandler("r2-get-bucket-sippy-config", sippy, "putSippy", sippySource), ActionPolicies.registry.registrations)
        }
        assertFailsWith<AssertionError>("an existing but wrong batch B3 behaviour anchor must be refused") {
            validateRegistry(
                withTests("r2-get-bucket-sippy-config", "com.flarego.core.R2SippyTest#deleteSendsNoSensitiveBodyAndRequiresAReadableSnapshot", sippyTestSource),
                ActionPolicies.registry.registrations,
            )
        }
        assertFailsWith<AssertionError>("a wrong-but-existing batch B3 anchor cannot be accepted by the execution gate") {
            validateBehaviour(
                withTests("r2-get-bucket-sippy-config", "com.flarego.core.R2SippyTest#fourProvidersSendExactlyThePinnedFieldsOnce", sippyTestSource),
                a,
            )
        }
    }

    @Test
    fun crossCuttingEvidenceCannotBeChosenByTheMatrixUnderTest() {
        val m = matrix()
        val a = aMatrix()
        fun entry(cls: String, methods: List<String>) = buildJsonObject {
            put("class", cls)
            put("methods", buildJsonArray { methods.forEach { add(it) } })
        }
        // The baseline block is built from frozen A and B2/B3/C literals, so this test
        // never depends on the document under test already being valid.
        val literalByClass = (b2CrossCutting() + b3CrossCutting() + cCrossCutting()).groupBy { it.cls }
        val block = JsonArray(
            a.getValue("cross_cutting_local_tests").jsonArray.toList() +
                literalByClass.map { (cls, anchors) -> entry(cls, anchors.map { it.method }) },
        )
        fun withBlock(entries: JsonArray) = JsonObject(m + ("cross_cutting_local_tests" to entries))
        val domainController = "com.flarego.core.R2DomainControllerTest"
        val domainRuntime = "com.flarego.core.R2DomainRuntimeTest"
        val sippyRuntime = "com.flarego.core.R2SippyRuntimeTest"
        val sippyOwnership = "com.flarego.core.R2SippyCrossLayerOwnershipTest"
        fun methodsOf(cls: String) = block.first { it.jsonObject.text("class") == cls }
            .jsonObject.getValue("methods").jsonArray.map { it.jsonPrimitive.content }

        // Deleting a B2 cross-cutting entry is refused.
        assertFailsWith<AssertionError>("deleting a B2 cross-cutting entry must be refused") {
            validateCrossCutting(withBlock(JsonArray(block.filter { it.jsonObject.text("class") != domainController })), a)
        }
        // The same entry count with one existing but unrelated method swapped in is refused.
        val swapped = JsonArray(block.map { row ->
            val e = row.jsonObject
            if (e.text("class") != domainController) row else entry(
                domainController,
                listOf("aDocumentOfAnotherTargetOrKindCanNeverPrepareOrConfirmADomainWrite") + methodsOf(domainController).drop(1),
            )
        })
        assertEquals(block.size, swapped.size, "the substitution keeps the class-entry count")
        assertFailsWith<AssertionError>("an existing but unrelated cross-cutting method must be refused") {
            validateCrossCutting(withBlock(swapped), a)
        }
        // An extra existing cross-cutting method (a real test that is not part of the reviewed set) is refused.
        val extra = JsonArray(block.map { row ->
            val e = row.jsonObject
            if (e.text("class") != domainRuntime) row else entry(
                domainRuntime,
                methodsOf(domainRuntime) + "domainTasksArePublishedThroughTheSharedRuntime",
            )
        })
        assertFailsWith<AssertionError>("an extra cross-cutting method must be refused") {
            validateCrossCutting(withBlock(extra), a)
        }
        // A duplicated class entry cannot hide behind map equality.
        assertFailsWith<AssertionError>("a duplicated class entry must be refused") {
            validateCrossCutting(withBlock(JsonArray(block + entry(domainController, methodsOf(domainController)))), a)
        }
        // Batch B3: deleting the ownership/claim cross-cutting entry is refused.
        assertFailsWith<AssertionError>("deleting a B3 cross-cutting entry must be refused") {
            validateCrossCutting(withBlock(JsonArray(block.filter { it.jsonObject.text("class") != sippyOwnership })), a)
        }
        // Batch B3: same entry count with one existing but unrelated Sippy method is refused.
        val sippySwapped = JsonArray(block.map { row ->
            val e = row.jsonObject
            if (e.text("class") != sippyOwnership) row else entry(
                sippyOwnership,
                listOf("theSafePreviewPayloadHoldsOnlyPublicMetadata") + methodsOf(sippyOwnership).drop(1),
            )
        })
        assertEquals(block.size, sippySwapped.size, "the substitution keeps the class-entry count")
        assertFailsWith<AssertionError>("an existing but unrelated B3 cross-cutting method must be refused") {
            validateCrossCutting(withBlock(sippySwapped), a)
        }
        // Batch B3: an extra existing Sippy test outside the reviewed cross-cutting set is refused.
        val sippyExtra = JsonArray(block.map { row ->
            val e = row.jsonObject
            if (e.text("class") != sippyRuntime) row else entry(
                sippyRuntime,
                methodsOf(sippyRuntime) + "secretsNeverReachPreviewPlanTaskOrFingerprint",
            )
        })
        assertFailsWith<AssertionError>("an extra B3 cross-cutting method must be refused") {
            validateCrossCutting(withBlock(sippyExtra), a)
        }
        // The real gate refuses the mutated document before executing anything.
        assertFailsWith<AssertionError>("the execution gate must validate cross-cutting evidence first") {
            validateBehaviour(withBlock(JsonArray(block.filter { it.jsonObject.text("class") != domainController })), a)
        }
    }

    @Test
    fun theCandidateDenominatorMustBeExactlyTheFrozenFiftyOneOperationIds() {
        val m = matrix()
        val a = aMatrix()
        val frozen = a.getValue("operations").jsonObject.keys
        val ops = m.getValue("operations").jsonObject
        assertEquals(frozen, ops.keys, "the current denominator must be the frozen 51, including Slurper")
        assertEquals(51, frozen.size)
        // A legal, real API that is neither an R2 candidate nor registered anywhere (only the D1 list
        // endpoint is registered in P1), so the registry check cannot be what catches this swap.
        val nonCandidate = "d1-create-database"
        assertTrue(nonCandidate !in frozen, "the swap target must not be an R2 candidate")
        // Same count, one unstarted candidate removed and one legal but non-candidate API added in its place.
        val victim = "slurper-get-job-logs"
        assertTrue(victim in frozen && victim in ops)
        val swapped = JsonObject(ops - victim + (nonCandidate to buildJsonObject {
            put("batch", "R2-B")
            put("method", "POST")
            put("path", "/accounts/{account_id}/d1/database")
            put("scope_kind", "account")
            put("technical_support", "NOT_STARTED")
            put("authenticated_cloud_accepted", false)
            put("paid_eligibility_verified", false)
        }))
        assertEquals(ops.size, swapped.size, "the substitution keeps the candidate count")
        assertFailsWith<AssertionError>("a same-count swap of a candidate for a non-candidate API must be refused") {
            validate(JsonObject(m + ("operations" to swapped)))
        }
        assertFailsWith<AssertionError>("a shrunk denominator must be refused") {
            validate(JsonObject(m + ("operations" to JsonObject(ops - victim))))
        }
    }

    /**
     * The gate reads inputs outside the compiled classpath (build files and four immutable review
     * baselines). They must be declared Gradle inputs, otherwise an ordinary build stays UP-TO-DATE
     * and a drifted snapshot is never refused. This asserts the declaration; the actual refusal is
     * proven by the normal Gradle drift rounds, not by this in-memory check.
     */
    @Test
    fun everyConsumedExternalInputIsDeclaredAsAGradleInput() {
        val script = Files.readString(root.resolve("shared-core/build.gradle.kts"))
        for (required in listOf(
            "cloudflareAuditEvidence", "auditSource-", "auditExternalFiles", "cloudflarePinnedSchema", "SCHEMA_FILE",
            "app/build.gradle.kts", b1BaselinePath, b2BaselinePath, b3BaselinePath, cBaselinePath,
        )) {
            assertTrue(script.contains(required), "shared-core/build.gradle.kts must declare the consumed input: $required")
        }
        // Every reviewed source outside a module src tree must be one of those declared files.
        val declaredOutsideSrc = setOf("app/build.gradle.kts", "shared-core/build.gradle.kts")
        for (path in reviewedSourcePaths()) {
            val isSrc = listOf("shared-core", "shared-ui", "app", "android-platform").any { path.startsWith("$it/src/") }
            val isTopLevelAuditJson = path.startsWith("docs/cloudflare-audit/") && path.endsWith(".json") &&
                !path.removePrefix("docs/cloudflare-audit/").contains('/')
            if (!isSrc && !isTopLevelAuditJson) {
                assertTrue(path in declaredOutsideSrc, "a reviewed source outside src/docs is not declared as an input: $path")
            }
        }
    }

    @Test
    fun productionSourcesNeverReadTheAuditLedgers() {
        validateRuntimeNeverLoadsTheLedgers()
    }
}
