package com.flarego.core

import com.flarego.core.application.r2.requireR2ConfigurationJurisdictionSupported
import com.flarego.core.application.r2.r2ConfigurationReadOnlyReason
import com.flarego.core.application.r2.r2ConfigurationWriteBody
import com.flarego.core.application.r2.supportsR2ConfigurationJurisdiction
import com.flarego.core.application.r2.validateR2ConfigurationTarget
import com.flarego.core.application.r2.validateR2LifecycleRule
import com.flarego.core.application.r2.validateR2LockRule
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.freezeR2ConfigurationWrite
import com.flarego.core.model.r2ConfigurationReadKey
import com.flarego.core.model.r2ConfigurationWriteKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Local-only B1 rules: snapshot freezing, the exact pinned operationIds, the jurisdiction
 * enum, the two different age field names, the local editing limits and the read-only
 * analysis that keeps unknown provider shapes from being overwritten.
 */
class R2ConfigurationValidationTest {
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")

    @Test
    fun freezeCopiesEveryNestedListSoALaterDraftEditCannotChangeThePayload() {
        val methods = mutableListOf("PUT", "GET")
        val origins = mutableListOf("https://a.example")
        val headers = mutableListOf("x-a")
        val expose = mutableListOf("ETag")
        val rules = mutableListOf(R2CorsRule(id = "r", allowed = R2CorsAllowed(methods, origins, headers), exposeHeaders = expose))
        val frozen = freezeR2ConfigurationWrite(R2ConfigurationWrite.PutCors(target, rules)) as R2ConfigurationWrite.PutCors
        methods.clear()
        origins.clear()
        headers.clear()
        expose.clear()
        rules.clear()
        val rule = frozen.rules.single()
        assertEquals(listOf("PUT", "GET"), rule.allowed.methods)
        assertEquals(listOf("https://a.example"), rule.allowed.origins)
        assertEquals(listOf("x-a"), rule.allowed.headers)
        assertEquals(listOf("ETag"), rule.exposeHeaders)
        assertNotSame(target, frozen.target)

        val transitions = mutableListOf(R2LifecycleStorageTransition(R2LifecycleCondition.Age(5), "InfrequentAccess"))
        val lifecycleRules = mutableListOf(R2LifecycleRule("l", true, R2LifecycleConditions("p/"), storageClassTransitions = transitions))
        val frozenLifecycle = freezeR2ConfigurationWrite(R2ConfigurationWrite.PutLifecycle(target, lifecycleRules)) as R2ConfigurationWrite.PutLifecycle
        transitions.clear()
        lifecycleRules.clear()
        assertEquals(1, frozenLifecycle.rules.single().storageClassTransitions.size)

        val lockRules = mutableListOf(R2LockRule("k", true, "", R2LockCondition.Indefinite))
        val frozenLock = freezeR2ConfigurationWrite(R2ConfigurationWrite.PutLock(target, lockRules)) as R2ConfigurationWrite.PutLock
        lockRules.clear()
        assertEquals(1, frozenLock.rules.size)
    }

    @Test
    fun theWriteAndReadKeysAreTheExactPinnedOperationIds() {
        assertEquals("r2-put-bucket-cors-policy", r2ConfigurationWriteKey(R2ConfigurationWrite.PutCors(target, emptyList())))
        assertEquals("r2-delete-bucket-cors-policy", r2ConfigurationWriteKey(R2ConfigurationWrite.DeleteCors(target)))
        assertEquals("r2-put-bucket-lifecycle-configuration", r2ConfigurationWriteKey(R2ConfigurationWrite.PutLifecycle(target, emptyList())))
        assertEquals("r2-put-bucket-local-uploads-configuration", r2ConfigurationWriteKey(R2ConfigurationWrite.PutLocalUploads(target, false)))
        assertEquals("r2-put-bucket-lock-configuration", r2ConfigurationWriteKey(R2ConfigurationWrite.PutLock(target, emptyList())))
        assertEquals("r2-delete-bucket-lock-configuration", r2ConfigurationWriteKey(R2ConfigurationWrite.DeleteLock(target)))

        assertEquals("r2-get-bucket-cors-policy", r2ConfigurationReadKey(R2ConfigurationKind.CORS))
        assertEquals("r2-get-bucket-lifecycle-configuration", r2ConfigurationReadKey(R2ConfigurationKind.LIFECYCLE))
        assertEquals("r2-get-bucket-local-uploads-configuration", r2ConfigurationReadKey(R2ConfigurationKind.LOCAL_UPLOADS))
        assertEquals("r2-get-bucket-lock-configuration", r2ConfigurationReadKey(R2ConfigurationKind.LOCK))
    }

    @Test
    fun targetValidationRefusesUnknownJurisdictionsAndIllegalBucketNames() {
        assertEquals(R2WriteTarget(account, "my-bucket", "eu"), validateR2ConfigurationTarget(R2WriteTarget(account, "my-bucket", "eu")))
        for (jurisdiction in listOf("", "default ", "EU", "mars", "s3")) {
            assertFailsWith<CloudFailure> { validateR2ConfigurationTarget(R2WriteTarget(account, "my-bucket", jurisdiction)) }
        }
        assertFailsWith<CloudFailure> { validateR2ConfigurationTarget(R2WriteTarget(account, "Bad")) }
    }

    @Test
    fun jurisdictionHeaderSupportMatchesTheReviewedEndpoints() {
        assertTrue(supportsR2ConfigurationJurisdiction(R2ConfigurationKind.CORS))
        assertTrue(supportsR2ConfigurationJurisdiction(R2ConfigurationKind.LIFECYCLE))
        assertTrue(supportsR2ConfigurationJurisdiction(R2ConfigurationKind.LOCK))
        assertFalse(supportsR2ConfigurationJurisdiction(R2ConfigurationKind.LOCAL_UPLOADS))

        requireR2ConfigurationJurisdictionSupported(R2WriteTarget(account, "my-bucket", "eu"), R2ConfigurationKind.CORS)
        requireR2ConfigurationJurisdictionSupported(target, R2ConfigurationKind.LOCAL_UPLOADS)
        assertFailsWith<CloudFailure> {
            requireR2ConfigurationJurisdictionSupported(R2WriteTarget(account, "my-bucket", "eu"), R2ConfigurationKind.LOCAL_UPLOADS)
        }
        assertFailsWith<CloudFailure> { requireR2ConfigurationJurisdictionSupported(R2WriteTarget(account, "my-bucket", "mars"), R2ConfigurationKind.CORS) }
    }

    @Test
    fun theEncodedBodiesOnlyCarryModelledFieldsAndNeverInventDefaults() {
        val cors = r2ConfigurationWriteBody(R2ConfigurationWrite.PutCors(target, listOf(R2CorsRule(allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example"))))))
        assertNotNull(cors)
        assertEquals(setOf("allowed", "exposeHeaders"), cors.getValue("rules").jsonArray.single().jsonObject.keys)
        assertEquals(setOf("methods", "origins", "headers"), cors.getValue("rules").jsonArray.single().jsonObject.getValue("allowed").jsonObject.keys)

        val lifecycle =
            r2ConfigurationWriteBody(R2ConfigurationWrite.PutLifecycle(target, listOf(R2LifecycleRule("r", true, R2LifecycleConditions("")))))
        assertNotNull(lifecycle)
        assertEquals(
            setOf("id", "enabled", "conditions", "storageClassTransitions"),
            lifecycle.getValue("rules").jsonArray.single().jsonObject.keys,
        )
        assertTrue(lifecycle.getValue("rules").jsonArray.single().jsonObject.getValue("storageClassTransitions").jsonArray.isEmpty())

        val lock = r2ConfigurationWriteBody(R2ConfigurationWrite.PutLock(target, listOf(R2LockRule("k", true, "", R2LockCondition.Indefinite))))
        assertNotNull(lock)
        assertEquals(setOf("id", "enabled", "prefix", "condition"), lock.getValue("rules").jsonArray.single().jsonObject.keys)

        assertEquals(
            JsonObject(mapOf("enabled" to JsonPrimitive(false))),
            r2ConfigurationWriteBody(R2ConfigurationWrite.PutLocalUploads(target, false)),
        )
        assertNull(r2ConfigurationWriteBody(R2ConfigurationWrite.DeleteCors(target)))
        assertNull(r2ConfigurationWriteBody(R2ConfigurationWrite.DeleteLock(target)))
        assertTrue(r2ConfigurationWriteBody(R2ConfigurationWrite.PutCors(target, emptyList()))!!.getValue("rules").jsonArray.isEmpty())
    }

    @Test
    fun theReadOnlyAnalysisKeepsEveryUnknownShapeOut() {
        fun cors(json: String) = Json.parseToJsonElement(json).jsonObject
        assertNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[]}""")))
        assertNull(
            r2ConfigurationReadOnlyReason(
                R2ConfigurationKind.CORS,
                cors("""{"rules":[{"id":"a","allowed":{"methods":["GET"],"origins":["*"]},"exposeHeaders":[],"maxAgeSeconds":10}]}"""),
            ),
        )
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[],"extra":true}""")), "an unknown top-level member is read-only")
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[{"allowed":{"methods":["GET"],"origins":["*"],"nested":1}}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[{"allowed":{"methods":["TRACE"],"origins":["*"]}}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[{"allowed":{"methods":["GET"],"origins":["*"]},"maxAgeSeconds":86401}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.CORS, cors("""{"rules":[{"allowed":{"methods":["GET"]}}]}""")), "a missing required member is unreadable")

        assertNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCK, cors("""{"rules":[{"id":"k","enabled":true,"prefix":"","condition":{"type":"Indefinite"}}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCK, cors("""{"rules":[{"id":"k","enabled":true,"prefix":"","condition":{"type":"Age"}}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCK, cors("""{"rules":[{"id":"k","enabled":true,"prefix":"","condition":{"type":"Age","maxAgeSeconds":0}}]}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCK, cors("""{"rules":[{"id":"k","enabled":true,"prefix":"","condition":{"type":"Date","date":"2026-01-01T00:00:00+08:00"}}]}""")))

        assertNull(
            r2ConfigurationReadOnlyReason(
                R2ConfigurationKind.LIFECYCLE,
                cors("""{"rules":[{"id":"l","enabled":false,"conditions":{"prefix":""},"deleteObjectsTransition":{"condition":{"type":"Age","maxAge":100}}}]}"""),
            ),
        )
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LIFECYCLE, cors("""{"rules":[{"id":"l","enabled":false,"conditions":{"prefix":""},"deleteObjectsTransition":{"condition":{"type":"Date","date":"2026-01-01T00:00:00Z"}},"storageClassTransitions":[{"condition":{"type":"Age","maxAge":1},"storageClass":"Glacier"}]}]}""")))

        assertNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCAL_UPLOADS, cors("""{"enabled":true}""")))
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCAL_UPLOADS, cors("{}")), "a missing enabled flag is unknown, not false")
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCAL_UPLOADS, cors("""{"enabled":"true"}""")))
    }

    @Test
    fun dateValidationKeepsOnlyTheClientsUnambiguousUtcSubset() {
        fun lock(date: String) = R2LockRule("k", true, "", R2LockCondition.Date(date))

        fun deleteTransition(date: String) =
            R2LifecycleRule("l", true, R2LifecycleConditions(""), deleteObjectsTransition = R2LifecycleCondition.Date(date))

        fun storageTransition(date: String) =
            R2LifecycleRule(
                "l",
                true,
                R2LifecycleConditions(""),
                storageClassTransitions = listOf(R2LifecycleStorageTransition(R2LifecycleCondition.Date(date), "InfrequentAccess")),
            )

        // The supported editing subset: an explicit `Z` zone, real calendar components and whole seconds 00..59.
        for (accepted in listOf("1970-01-01T00:00:00Z", "2026-01-01T12:34:59Z", "2026-01-01T12:34:00.500Z", "9999-12-31T23:59:59Z")) {
            validateR2LockRule(lock(accepted))
            validateR2LifecycleRule(deleteTransition(accepted))
            validateR2LifecycleRule(storageTransition(accepted))
        }
        // RFC3339 allows a `:60` leap second at a real leap-second instant; this client does not
        // maintain a leap-second table, so such a value is outside its writing subset (and, more
        // simply, every one of these is not the supported shape).
        for (rejected in
            listOf(
                "2026-01-01T12:34:60Z",
                "2026-06-30T23:59:60Z",
                "2026-01-01T12:34:61Z",
                "2026-01-01T24:00:00Z",
                "2026-01-01T12:60:00Z",
                "2026-01-01T12:34:59",
                "2026-01-01T12:34:59+00:00",
                "2026-02-30T12:34:59Z",
                "1969-12-31T23:59:59Z",
                "10000-01-01T00:00:00Z",
            )
        ) {
            assertFailsWith<CloudFailure>(rejected) { validateR2LockRule(lock(rejected)) }
            assertFailsWith<CloudFailure>(rejected) { validateR2LifecycleRule(deleteTransition(rejected)) }
            assertFailsWith<CloudFailure>(rejected) { validateR2LifecycleRule(storageTransition(rejected)) }
        }

        // A document carrying such a value stays readable, keeps its exact text, and is never written back.
        val leapLock =
            Json.parseToJsonElement(
                """{"rules":[{"id":"k","enabled":true,"prefix":"","condition":{"type":"Date","date":"2026-06-30T23:59:60Z"}}]}""",
            ).jsonObject
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LOCK, leapLock))
        assertEquals(
            "2026-06-30T23:59:60Z",
            leapLock.getValue("rules").jsonArray.single().jsonObject.getValue("condition").jsonObject.getValue("date").jsonPrimitive.content,
        )
        val leapLifecycle =
            Json.parseToJsonElement(
                """{"rules":[{"id":"l","enabled":true,"conditions":{"prefix":""},"deleteObjectsTransition":{"condition":{"type":"Date","date":"2026-06-30T23:59:60Z"}}}]}""",
            ).jsonObject
        assertNotNull(r2ConfigurationReadOnlyReason(R2ConfigurationKind.LIFECYCLE, leapLifecycle))
    }
}
