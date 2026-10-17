package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.zones.CloudflareZoneSettings
import com.flarego.core.cloudflare.generated.ZoneSettingSchemas
import com.flarego.core.application.operations.*
import com.flarego.core.application.zones.ZoneSettingsController
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ZoneSettingsTest {
    private val dedicated = listOf(
        Triple("zone-cache-settings-get-aegis-setting", "zone-cache-settings-change-aegis-setting", "aegis"),
        Triple("zone-settings-get-automatic-platform-optimization-setting", "zone-settings-change-automatic-platform-optimization-setting", "automatic_platform_optimization"),
        Triple("zone-settings-get-binary-ast-setting", "zone-settings-change-binary-ast-setting", "binary_ast"),
        Triple("zone-settings-get-fonts-setting", "zone-settings-change-fonts-setting", "fonts"),
        Triple("zone-settings-get-h2-prioritization-setting", "zone-settings-change-h2-prioritization-setting", "h2_prioritization"),
        Triple("zone-settings-get-image-resizing-setting", "zone-settings-change-image-resizing-setting", "image_resizing"),
        Triple("zone-cache-settings-get-origin-h2-max-streams-setting", "zone-cache-settings-change-origin-h2-max-streams-setting", "origin_h2_max_streams"),
        Triple("zone-cache-settings-get-origin-max-http-version-setting", "zone-cache-settings-change-origin-max-http-version-setting", "origin_max_http_version"),
        Triple("zone-settings-get-speed-brain-setting", "zone-settings-change-speed-brain-setting", "speed_brain"),
        Triple("zone-settings-get-transformations-allowed-origins-setting", "zone-settings-change-transformations-allowed-origins-setting", "transformations_allowed_origins"),
        Triple("zone-settings-get-transformations-c2pa-setting", "zone-settings-change-transformations-c2pa-setting", "transformations_c2pa"),
        Triple("zone-settings-get-transformations-config", "zone-settings-change-transformations-config", "transformations_config"),
    )
    @Test fun all29FixedOperationsAreRegisteredWithExactRoutesAndNoWriteRetry() {
        val expected = dedicated.flatMap { (get, patch, route) -> listOf(Triple(get, "GET", "/zones/{zone_id}/settings/$route"), Triple(patch, "PATCH", "/zones/{zone_id}/settings/$route")) } + listOf(
            Triple("zone-settings-get-single-setting", "GET", "/zones/{zone_id}/settings/{setting_id}"),
            Triple("zone-settings-edit-single-setting", "PATCH", "/zones/{zone_id}/settings/{setting_id}"),
            Triple("universal-ssl-settings-for-a-zone-universal-ssl-settings-details", "GET", "/zones/{zone_id}/ssl/universal/settings"),
            Triple("universal-ssl-settings-for-a-zone-edit-universal-ssl-settings", "PATCH", "/zones/{zone_id}/ssl/universal/settings"),
            Triple("zone-purge", "POST", "/zones/{zone_id}/purge_cache"),
        )
        val registrations = zoneSettingsRegistrations().associateBy { it.policy.key }
        assertEquals(29, registrations.size)
        assertEquals(expected.map { it.first }.toSet(), registrations.keys)
        assertEquals(26, registrations.values.count { "/settings/" in it.pathTemplate && "/ssl/" !in it.pathTemplate })
        for ((id, method, template) in expected) {
            val r = registrations.getValue(id)
            assertEquals(method, r.method); assertEquals(template, r.pathTemplate); assertEquals(ActionScope.ZONE, r.scope)
            assertEquals(ResponseKind.ENVELOPE_JSON, r.policy.responseKind)
            assertEquals(if (method == "GET") ActionSemantics.READ else ActionSemantics.WRITE, r.policy.semantics)
            assertEquals(method == "GET", r.policy.retry.enabled)
        }
    }
    @Test fun helperMustBeConnectedToTheRealGatewayRegistryWithoutOldAllSettingsRoutes() {
        assertEquals(29, zoneSettingsRegistrations().size)
        for (r in zoneSettingsRegistrations()) assertEquals(r, ActionPolicies.registry.registration(r.policy.key))
        assertNull(ActionPolicies.registry.registration("zone-settings-get-all-zone-settings"))
        assertNull(ActionPolicies.registry.registration("zone-settings-edit-zone-settings-info"))
    }
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val zone = ZoneScope(AccountScope("local", "account-one"), "zone-one")
    private val hash = FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun input(value: JsonElement) = buildJsonObject { put("value", value) }
    private fun text(value: String) = JsonPrimitive(value)
    private fun before(key: ZoneSettingKey): JsonElement = when (key) {
        ZoneSettingKey.AEGIS -> json("""{"enabled":false,"pool_id":"pool-a"}""")
        ZoneSettingKey.ORIGIN_H2_MAX_STREAMS -> JsonPrimitive(1)
        ZoneSettingKey.ORIGIN_MAX_HTTP_VERSION -> text("1")
        ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS -> text("example.com")
        ZoneSettingKey.TRANSFORMATIONS_CONFIG -> json("""{"allowed_origins":"example.com","c2pa":"off","transformations":"off","future":"keep"}""")
        ZoneSettingKey.SSL -> text("full")
        ZoneSettingKey.MIN_TLS_VERSION -> text("1.0")
        ZoneSettingKey.BROWSER_CACHE_TTL -> JsonPrimitive(0)
        else -> text("off")
    }
    private fun after(key: ZoneSettingKey): JsonElement = when (key) {
        ZoneSettingKey.AEGIS -> json("""{"enabled":true,"pool_id":"pool-b"}""")
        ZoneSettingKey.AUTOMATIC_PLATFORM_OPTIMIZATION -> json("""{"cf":true,"enabled":true,"wordpress":true,"wp_plugin":true}""")
        ZoneSettingKey.BINARY_AST -> text("latest")
        ZoneSettingKey.H2_PRIORITIZATION -> text("custom")
        ZoneSettingKey.IMAGE_RESIZING -> text("open")
        ZoneSettingKey.ORIGIN_H2_MAX_STREAMS -> JsonPrimitive(50)
        ZoneSettingKey.ORIGIN_MAX_HTTP_VERSION -> text("2")
        ZoneSettingKey.TRANSFORMATIONS_ALLOWED_ORIGINS -> text("cdn.example.com")
        ZoneSettingKey.TRANSFORMATIONS_CONFIG -> json("""{"c2pa":"on"}""")
        ZoneSettingKey.SSL -> text("strict")
        ZoneSettingKey.MIN_TLS_VERSION -> text("1.2")
        ZoneSettingKey.BROWSER_CACHE_TTL -> JsonPrimitive(3600)
        else -> text("on")
    }
    private fun ok(result: JsonElement, success: Boolean = true) = HttpResponse(200, buildJsonObject {
        put("success", success); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())
    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
    }
    private class Resolver : OperationContextResolver {
        var live: LiveOperationContext? = null
        var valid = true
        override fun current() = live
        override fun liveConnections() = listOfNotNull(live?.connection)
        override fun isLive(scope: CloudScope, credentialRevision: Long?) = valid && (credentialRevision == null || live?.credentialRevision == credentialRevision)
    }
    private inner class Harness(val test: TestScope) {
        val resolver = Resolver(); val store = Store(); var now = 1_000L; var ids = 0
        val clock = OperationClock { now }; val dispatcher = StandardTestDispatcher(test.testScheduler)
        val plans = ChangePlans(resolver, clock, OperationIds { "setting-${++ids}" })
        val coordinator = OperationCoordinator(plans, store, resolver, clock, test.backgroundScope, dispatcher)
        val requests = mutableListOf<HttpRequest>()
        val values = mutableMapOf<Pair<String, ZoneSettingKey>, JsonElement>()
        val editable = mutableMapOf<ZoneSettingKey, Boolean?>()
        val deniedReads = mutableSetOf<String>()
        var permission: Boolean? = true; var entitlement: Boolean? = true; var tier: CachePurgeTier? = CachePurgeTier.FREE
        var ownerAccount = "account-one"; var ownerMissing = false; var ownerId: String? = null
        var onOwnerRead: (() -> Unit)? = null; var onSettingRead: (() -> Unit)? = null; var onSession: (() -> Unit)? = null
        var overrideWrite: HttpResponse? = null; var throwWrite = false; var applyWrite = true; var wrongResponseId = false
        var enabled = true
        val authority = object : ZoneSettingsAuthority {
            override fun mayWrite(scope: ZoneScope, operationId: String) = permission
            override fun eligible(scope: ZoneScope, operationId: String) = entitlement
            override fun purgeTier(scope: ZoneScope) = tier
        }
        fun settingRow(key: ZoneSettingKey, id: String = "zone-one", wrong: Boolean = false) = buildJsonObject {
            put("id", if (wrong) "wrong-setting" else key.responseId)
            put("value", values[id to key] ?: before(key))
            (if (editable.containsKey(key)) editable[key] else true)?.let { put("editable", it) }
            put("future", buildJsonObject { put("preserve", true) })
        }
        val transport = HttpTransport { r ->
            requests += r
            val parts = r.path.split('/'); val id = parts.getOrElse(2) { "" }; val route = parts.last()
            if (r.method == "GET") {
                if (route in deniedReads) HttpResponse(403, "private-provider-marker")
                else if (parts.size == 3) {
                    onOwnerRead?.invoke()
                    ok(buildJsonObject {
                        put("id", ownerId ?: id); put("name", "example.com"); put("type", "full"); put("status", "active"); put("paused", false)
                        put("name_servers", JsonArray(emptyList()))
                        if (!ownerMissing) put("account", buildJsonObject { put("id", ownerAccount) })
                    })
                } else if ("/ssl/universal/" in r.path) { onSettingRead?.invoke(); ok(buildJsonObject { put("enabled", enabled) }) }
                else {
                    onSettingRead?.invoke()
                    val key = ZoneSettingKey.fromId(route)
                    ok(settingRow(key, id))
                }
            } else {
                val body = Json.parseToJsonElement(r.body!!).jsonObject
                if (applyWrite) {
                    if ("/ssl/universal/" in r.path) enabled = body["enabled"]!!.jsonPrimitive.boolean
                    else if (route != "purge_cache") {
                        val key = ZoneSettingKey.fromId(route); val value = body.getValue("value")
                        values[id to key] = if (key == ZoneSettingKey.TRANSFORMATIONS_CONFIG && value is JsonObject)
                            JsonObject((values[id to key] ?: before(key)).jsonObject + value) else value
                    }
                }
                if (throwWrite) throw IllegalStateException("private-transport-marker")
                overrideWrite ?: if (route == "purge_cache") ok(buildJsonObject { put("id", "purge-receipt") })
                else if ("/ssl/universal/" in r.path) ok(buildJsonObject { put("enabled", enabled) })
                else ok(settingRow(ZoneSettingKey.fromId(route), id, wrongResponseId))
            }
        }
        val api = CloudflareZoneSettings(connection, CloudflareGateway(transport), authority)
        val controller = ZoneSettingsController(resolver, plans, coordinator, clock, hash, store, dispatcher) { onSession?.invoke(); api }
        fun select(action: ZoneSettingsAction) { resolver.live = LiveOperationContext(connection, action.scope, 1, controller.requestFingerprint(action)) }
        suspend fun prepare(action: ZoneSettingsAction): ChangePlan { select(action); return controller.prepare(action) }
        fun confirm(p: ChangePlan) = plans.confirm(p.id, p.scope, p.credentialRevision, p.requestFingerprint, now)
        fun restored(): ZoneSettingsController {
            val p = ChangePlans(resolver, clock, OperationIds { "new-${++ids}" })
            val c = OperationCoordinator(p, store, resolver, clock, test.backgroundScope, dispatcher)
            return ZoneSettingsController(resolver, p, c, clock, hash, store, dispatcher) { onSession?.invoke(); api }
        }
        val writes get() = requests.filter { it.method != "GET" }
    }

    @Test fun everyDedicatedAndNativeHandlerGetsUpdatesAndReadsBackItsExactTarget() = runTest {
        for (key in ZoneSettingKey.entries) {
            val h = Harness(this)
            val current = h.api.get(zone, key)
            assertEquals(key, current.key); assertEquals(zone, current.scope); assertEquals(before(key), current.value)
            val result = h.api.update(zone, key, input(after(key)))
            val expected = if (key == ZoneSettingKey.TRANSFORMATIONS_CONFIG) JsonObject(before(key).jsonObject + after(key).jsonObject) else after(key)
            assertEquals(expected, result.value)
            assertTrue(result.raw.containsKey("future"))
            assertEquals("/zones/zone-one/settings/${key.route}", h.writes.single().path)
            assertEquals(input(after(key)), Json.parseToJsonElement(h.writes.single().body!!))
            assertEquals("GET", h.requests.last().method)
            assertEquals(h.writes.single().path, h.requests.last().path)
        }
    }

    @Test fun dedicatedHandlersCannotAccidentallyUseGenericRegistrationEvenForSameUrl() = runTest {
        for (key in ZoneSettingKey.entries) {
            val h = Harness(this)
            val allowed = arrayOf(ActionPolicies.registry.registration("zones-0-get")!!,
                ActionPolicies.registry.registration(key.readOperation)!!, ActionPolicies.registry.registration(key.writeOperation)!!)
            val api = CloudflareZoneSettings(connection, CloudflareGateway(h.transport, ActionRegistry.of(*allowed)), h.authority)
            assertEquals(key, api.get(zone, key).key)
            api.update(zone, key, input(after(key)))
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun genericUnknownDeprecatedAndWrongTypedValuesAreRejectedBeforeIo() = runTest {
        for (id in listOf("future", "mirage", "cname_flattening", "response_buffering", "../ssl")) assertFailsWith<CloudFailure> { ZoneSettingKey.fromId(id) }
        for ((key, bad) in listOf(ZoneSettingKey.SSL to text("origin_pull"), ZoneSettingKey.SSL to text("on"),
            ZoneSettingKey.MIN_TLS_VERSION to text("zrt"), ZoneSettingKey.TLS_1_3 to text("1.2"),
            ZoneSettingKey.BROWSER_CACHE_TTL to JsonPrimitive(-1), ZoneSettingKey.BROWSER_CACHE_TTL to JsonPrimitive(1.5),
            ZoneSettingKey.BROWSER_CACHE_TTL to JsonPrimitive(31536001), ZoneSettingKey.HTTP2 to JsonPrimitive(true))) {
            val h = Harness(this)
            assertFailsWith<CloudFailure> { h.api.update(zone, key, input(bad)) }
            assertEquals(0, h.requests.size)
        }
        val h = Harness(this)
        for (bad in listOf(json("""{"id":"ssl","value":"off"}""").jsonObject, json("""{"value":"off","editable":true}""").jsonObject, buildJsonObject {})) {
            assertFailsWith<CloudFailure> { h.api.update(zone, ZoneSettingKey.SSL, bad) }
        }
        assertEquals(0, h.requests.size)
        assertEquals("needs_review", ZoneSettingSchemas.genericAudit["transformations"]!!.jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test fun readonlyUnknownQualificationAndUnknownOrDeniedTokenWritePermissionSendNothing() = runTest {
        for (mode in 0..3) {
            val h = Harness(this)
            when (mode) {
                0 -> h.editable[ZoneSettingKey.HTTP2] = false
                1 -> { h.editable[ZoneSettingKey.HTTP2] = null; h.entitlement = null }
                2 -> h.permission = null
                3 -> h.permission = false
            }
            assertFailsWith<CloudFailure> { h.api.update(zone, ZoneSettingKey.HTTP2, input(text("on"))) }
            assertEquals(0, h.writes.size)
        }
        val h = Harness(this)
        val unknown = CloudflareZoneSettings(connection, CloudflareGateway(h.transport))
        assertFailsWith<CloudFailure> { unknown.update(zone, ZoneSettingKey.HTTP2, input(text("on"))) }
        assertFailsWith<CloudFailure> { unknown.updateUniversalSsl(zone, false) }
        assertFailsWith<CloudFailure> { unknown.purge(zone, CachePurgeInput.All) }
        assertEquals(0, h.writes.size)
    }

    @Test fun oneSetting403DoesNotBlockOtherSettingsZonesOrDnsPolicy() = runTest {
        val h = Harness(this); h.deniedReads += "ssl"
        val failure = assertFailsWith<CloudFailure> { h.api.get(zone, ZoneSettingKey.SSL) }
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED, failure.code); assertFalse(failure.toString().contains("private-provider-marker"))
        assertEquals(ZoneSettingKey.HTTP2, h.api.get(zone, ZoneSettingKey.HTTP2).key)
        val other = zone.copy(zoneId = "zone-two")
        assertEquals(other, h.api.update(other, ZoneSettingKey.HTTP3, input(text("on"))).scope)
        assertNotNull(ActionPolicies.policy("dns-records-for-a-zone-list-dns-records"))
    }

    @Test fun localScopeAndIndependentProviderOwnershipBothRequired() = runTest {
        val h = Harness(this)
        assertFailsWith<CloudFailure> { h.api.get(ZoneScope(AccountScope("other", "account-one"), "zone-one"), ZoneSettingKey.HTTP2) }
        assertEquals(0, h.requests.size)
        for (mode in 0..2) {
            val bad = Harness(this)
            when (mode) { 0 -> bad.ownerAccount = "foreign"; 1 -> bad.ownerMissing = true; 2 -> bad.ownerId = "foreign-zone" }
            assertFailsWith<CloudFailure> { bad.api.update(zone, ZoneSettingKey.HTTP2, input(text("on"))) }
            assertEquals(0, bad.writes.size)
        }
    }

    @Test fun unknownNestedFieldsCannotBeLostWithoutProvenPartialObjectPatchContract() = runTest {
        val h = Harness(this); h.values["zone-one" to ZoneSettingKey.AEGIS] = json("""{"enabled":false,"pool_id":"p","future":"keep"}""")
        assertFailsWith<CloudFailure> { h.api.update(zone, ZoneSettingKey.AEGIS, input(after(ZoneSettingKey.AEGIS))) }
        assertEquals(0, h.writes.size)
        val partial = Harness(this)
        assertFailsWith<CloudFailure> { partial.api.update(zone, ZoneSettingKey.AEGIS, input(json("""{"enabled":true}"""))) }
        val result = partial.api.update(zone, ZoneSettingKey.TRANSFORMATIONS_CONFIG, input(json("""{"c2pa":"on"}""")))
        assertEquals(text("keep"), result.value.jsonObject["future"])
        assertEquals(setOf("c2pa"), Json.parseToJsonElement(partial.writes.single().body!!).jsonObject["value"]!!.jsonObject.keys)
    }

    @Test fun universalSslUsesDedicatedEndpointAndVerifiesReadbackWithoutClaimingCertificateIssuance() = runTest {
        val h = Harness(this)
        assertTrue(h.api.universalSsl(zone).enabled)
        assertFalse(h.api.updateUniversalSsl(zone, false).enabled)
        assertEquals("/zones/zone-one/ssl/universal/settings", h.writes.single().path)
        assertEquals(json("""{"enabled":false}"""), Json.parseToJsonElement(h.writes.single().body!!))
        assertEquals("GET", h.requests.last().method)
    }

    @Test fun controlledPurgeVariantsNeverFallBackToAllAndUrlsOnlyEnterPayload() = runTest {
        val cases = listOf(
            CachePurgeInput.Files(listOf(CachePurgeFile("https://www.example.com/a?q=1", mapOf("CF-IPCountry" to "US")))) to "files",
            CachePurgeInput.Hosts(listOf("www.example.com")) to "hosts",
            CachePurgeInput.Tags(listOf("asset-v1")) to "tags",
            CachePurgeInput.Prefixes(listOf("www.example.com/assets/")) to "prefixes",
        )
        for ((purge, field) in cases) {
            val h = Harness(this)
            assertIs<ExecutionReceipt.Accepted>(h.api.purge(zone, purge))
            val request = h.writes.single()
            assertEquals("/zones/zone-one/purge_cache", request.path)
            assertEquals(setOf(field), Json.parseToJsonElement(request.body!!).jsonObject.keys)
            assertTrue(request.headers.isEmpty()); assertTrue(h.requests.all { it.path.startsWith("/zones/") })
        }
    }

    @Test fun purgeLimitsUseVerifiedTierWithUnknownConservativeAndEmptyOrMalformedNeverAll() {
        val files = (1..101).map { CachePurgeFile("https://www.example.com/$it") }
        for (tier in listOf(null, CachePurgeTier.FREE, CachePurgeTier.PRO, CachePurgeTier.BUSINESS)) {
            assertFailsWith<CloudFailure> { cachePurgeBody(CachePurgeInput.Files(files), tier) }
            assertEquals(100, cachePurgeBody(CachePurgeInput.Files(files.take(100)), tier)["files"]!!.jsonArray.size)
        }
        assertEquals(500, cachePurgeBody(CachePurgeInput.Files((1..500).map { CachePurgeFile("https://www.example.com/$it") }), CachePurgeTier.ENTERPRISE)["files"]!!.jsonArray.size)
        val bad = listOf<CachePurgeInput>(CachePurgeInput.Files(emptyList()), CachePurgeInput.Tags(emptyList()), CachePurgeInput.Hosts(emptyList()), CachePurgeInput.Prefixes(emptyList()),
            CachePurgeInput.Files(listOf(CachePurgeFile("javascript:alert(1)"))), CachePurgeInput.Files(listOf(CachePurgeFile("https://user:password@example.com/a"))),
            CachePurgeInput.Files(listOf(CachePurgeFile("https://example.com/*"))), CachePurgeInput.Files(listOf(CachePurgeFile("https://example.com/a", mapOf("Authorization" to "private")))),
            CachePurgeInput.Hosts(listOf("https://example.com")), CachePurgeInput.Prefixes(listOf("https://example.com/a")),
            CachePurgeInput.Prefixes(listOf("example.com/a?q=1")), CachePurgeInput.Prefixes(listOf("example.com/a#f")),
            CachePurgeInput.Prefixes(listOf("example.com/" + "a/".repeat(31))), CachePurgeInput.Tags(List(101) { "tag-$it" }))
        for (item in bad) assertFailsWith<CloudFailure> { cachePurgeBody(item, CachePurgeTier.ENTERPRISE) }
    }

    @Test fun purgeHostAndFileMustBelongToTheVerifiedZone() = runTest {
        for (purge in listOf(CachePurgeInput.Files(listOf(CachePurgeFile("https://attacker.com/a"))), CachePurgeInput.Hosts(listOf("attacker.com")), CachePurgeInput.Prefixes(listOf("attacker.com/a")))) {
            val h = Harness(this)
            assertFailsWith<CloudFailure> { h.api.purge(zone, purge) }
            assertEquals(0, h.writes.size)
        }
    }

    @Test fun sslDowngradeUniversalDisableAndPurgeAllNeedIndependentExactRiskAcknowledgement() = runTest {
        val cases = listOf(
            ZoneSettingsAction.Update(zone, ZoneSettingKey.SSL, input(text("off"))) to ZoneSettingsRisk.SSL_DOWNGRADE,
            ZoneSettingsAction.UniversalSsl(zone, false) to ZoneSettingsRisk.DISABLE_UNIVERSAL_SSL,
            ZoneSettingsAction.Purge(zone, CachePurgeInput.All) to ZoneSettingsRisk.PURGE_ALL,
        )
        for ((action, expectedRisk) in cases) {
            val h = Harness(this); val plan = h.prepare(action); val confirmed = h.confirm(plan)
            assertEquals(expectedRisk, h.controller.preview(plan.id).risk)
            assertEquals("example.com", h.controller.preview(plan.id).domain)
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed) }
            assertEquals(0, h.writes.size)
            assertFailsWith<CloudFailure> { h.controller.acknowledgeRisk(plan.id, ZoneSettingsRisk.entries.first { it != expectedRisk }) }
            assertFailsWith<CloudFailure> { h.controller.submit(action, confirmed, ZoneSettingsRiskAcknowledgement(plan.id, expectedRisk)) }
            val ack = h.controller.acknowledgeRisk(plan.id, expectedRisk)
            val result = h.controller.submit(action, confirmed, ack)
            assertEquals(if (action is ZoneSettingsAction.Purge) TaskState.ACCEPTED else TaskState.SUCCEEDED, result.state)
            assertEquals(result, h.controller.submit(action, confirmed, ack)); assertEquals(1, h.writes.size)
        }
    }

    @Test fun riskTokenCannotCrossPlanInputOrIssuer() = runTest {
        val action = ZoneSettingsAction.Purge(zone, CachePurgeInput.All)
        val h = Harness(this); val first = h.prepare(action); val ack = h.controller.acknowledgeRisk(first.id, ZoneSettingsRisk.PURGE_ALL)
        val second = h.prepare(action)
        assertFailsWith<CloudFailure> { h.controller.submit(action, h.confirm(second), ack) }
        val foreign = Harness(this); val fp = foreign.prepare(action); val fa = foreign.controller.acknowledgeRisk(fp.id, ZoneSettingsRisk.PURGE_ALL)
        assertFailsWith<CloudFailure> { h.controller.submit(action, h.confirm(first), fa) }
        val changed = ZoneSettingsAction.Purge(zone, CachePurgeInput.Tags(listOf("different"))); h.select(changed)
        assertFailsWith<CloudFailure> { h.controller.submit(changed, h.confirm(first), ack) }
        assertEquals(0, h.writes.size)
    }

    @Test fun independentFingerprintAndFinalContextCredentialPlanChecksRefuseStaleSend() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
            val p = h.prepare(action); val c = h.confirm(p)
            when (mode) {
                0 -> h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "a".repeat(64))
                1 -> h.onSession = { h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
                2 -> h.onSettingRead = { h.resolver.live = h.resolver.live!!.copy(scope = zone.copy(zoneId = "zone-two")) }
                3 -> h.now = p.expiresAtMs
                4 -> h.values["zone-one" to ZoneSettingKey.HTTP2] = text("on")
            }
            runCatching { h.controller.submit(action, c) }
            assertEquals(0, h.writes.size)
        }
        val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
        h.select(action); h.resolver.live = h.resolver.live!!.copy(requestFingerprint = "b".repeat(64))
        assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        assertEquals(0, h.writes.size)
    }

    @Test fun successWithoutMatchingReadbackWrongResultPartialOrTimeoutNeverCompletesAndNeverRetries() = runTest {
        for (mode in 0..5) {
            val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
            val p = h.prepare(action); val c = h.confirm(p)
            when (mode) {
                0 -> h.applyWrite = false
                1 -> h.wrongResponseId = true
                2 -> h.overrideWrite = HttpResponse(200, """{"success":false,"result":null}""")
                3 -> h.overrideWrite = HttpResponse(429, "private")
                4 -> h.throwWrite = true
                5 -> h.overrideWrite = HttpResponse(200, "not-json")
            }
            assertEquals(TaskState.UNKNOWN, h.controller.submit(action, c).state)
            assertEquals(1, h.writes.size)
            assertFailsWith<CloudFailure> { h.controller.prepare(action) }
        }
    }

    @Test fun acceptedPurgeAllowsNewExplicitConfirmedRequestButUnknownTimeoutCannotReplay() = runTest {
        val h = Harness(this); val action = ZoneSettingsAction.Purge(zone, CachePurgeInput.All)
        for (round in 1..2) {
            val p = h.prepare(action); val c = h.confirm(p); val ack = h.controller.acknowledgeRisk(p.id, ZoneSettingsRisk.PURGE_ALL)
            val accepted = h.controller.submit(action, c, ack)
            assertEquals(TaskState.ACCEPTED, accepted.state)
            assertEquals(TaskState.ACCEPTED, h.restored().reconcile(accepted.id).state)
            assertEquals(round, h.writes.size)
        }
        h.throwWrite = true
        val p = h.prepare(action); val c = h.confirm(p); val ack = h.controller.acknowledgeRisk(p.id, ZoneSettingsRisk.PURGE_ALL)
        val unknown = h.controller.submit(action, c, ack)
        assertEquals(TaskState.UNKNOWN, unknown.state)
        assertFailsWith<CloudFailure> { h.restored().prepare(action) }
        assertEquals(TaskState.UNKNOWN, h.restored().reconcile(unknown.id).state)
        assertEquals(3, h.writes.size)
    }

    @Test fun restoredControllerReconcilesGenericDedicatedPartialObjectAndUniversalOnlyFromReadEvidence() = runTest {
        val cases = listOf<ZoneSettingsAction>(
            ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on"))),
            ZoneSettingsAction.Update(zone, ZoneSettingKey.AEGIS, input(after(ZoneSettingKey.AEGIS))),
            ZoneSettingsAction.Update(zone, ZoneSettingKey.TRANSFORMATIONS_CONFIG, input(after(ZoneSettingKey.TRANSFORMATIONS_CONFIG))),
            ZoneSettingsAction.UniversalSsl(zone, false),
        )
        for (action in cases) {
            val h = Harness(this); val p = h.prepare(action); val c = h.confirm(p)
            val ack = h.controller.preview(p.id).risk?.let { h.controller.acknowledgeRisk(p.id, it) }
            h.throwWrite = true
            val task = h.controller.submit(action, c, ack)
            assertEquals(TaskState.UNKNOWN, task.state)
            h.resolver.live = LiveOperationContext(connection, zone, 2, "c".repeat(64))
            val restored = h.restored()
            assertEquals(TaskState.SUCCEEDED, restored.reconcile(task.id).state)
            assertFailsWith<CloudFailure> { restored.submit(action, c, ack) }
            assertEquals(1, h.writes.size)
            assertFalse(task.toString().contains("private-transport-marker"))
        }
    }

    @Test fun restoredMismatchesWrongOwnerAndUnknownReferenceStayUnknownWithoutRestoringWrites() = runTest {
        for (mode in 0..4) {
            val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
            val p = h.prepare(action); h.throwWrite = true
            val task = h.controller.submit(action, h.confirm(p))
            when (mode) {
                0 -> h.values["zone-one" to ZoneSettingKey.HTTP2] = text("off")
                1 -> h.ownerAccount = "foreign"
                2 -> h.deniedReads += "http2"
                3 -> h.store.put(task.copy(resourceRef = ResourceRef(zone, "zone_setting", "FUTURE")))
                4 -> h.store.put(task.copy(desiredHash = "d".repeat(64)))
            }
            assertEquals(TaskState.UNKNOWN, h.restored().reconcile(task.id).state)
            assertEquals(1, h.writes.size)
        }
    }

    @Test fun deniedEntitlementOverridesEditableAndPermissionLossBeforeSendRefusesWrite() = runTest {
        val denied = Harness(this); denied.entitlement = false
        assertFailsWith<CloudFailure> { denied.api.update(zone, ZoneSettingKey.HTTP2, input(text("on"))) }
        assertEquals(0, denied.writes.size)
        val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
        val p = h.prepare(action); val c = h.confirm(p); h.permission = null
        runCatching { h.controller.submit(action, c) }
        assertEquals(0, h.writes.size)
    }

    @Test fun acceptedWrongSettingTargetAndContextChangeDuringReadbackNeverComplete() = runTest {
        val async = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
        val p = async.prepare(action)
        async.overrideWrite = HttpResponse(202, ok(buildJsonObject { put("id", "wrong"); put("value", "on") }).body)
        assertEquals(TaskState.UNKNOWN, async.controller.submit(action, async.confirm(p)).state)
        assertEquals(1, async.writes.size)
        val h = Harness(this); val plan = h.prepare(action); var reads = 0
        h.onSettingRead = { if (++reads == 2) h.resolver.live = h.resolver.live!!.copy(credentialRevision = 2) }
        assertEquals(TaskState.UNKNOWN, h.controller.submit(action, h.confirm(plan)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun mixedFileHeaderFormsUseOneSchemaBranchAndInvalidPrefixControlsAreRejected() {
        val body = cachePurgeBody(CachePurgeInput.Files(listOf(CachePurgeFile("https://example.com/a"),
            CachePurgeFile("https://example.com/b", mapOf("CF-IPCountry" to "US")))), null)
        assertTrue(body["files"]!!.jsonArray.all { it is JsonObject })
        assertFailsWith<CloudFailure> { cachePurgeBody(CachePurgeInput.Prefixes(listOf("example.com/a\u007f")), null) }
    }

    @Test fun purgeTagLengthUsesOfficial1024CharacterApiLimit() {
        assertEquals(1, cachePurgeBody(CachePurgeInput.Tags(listOf("a".repeat(1024))), null)["tags"]!!.jsonArray.size)
        assertFailsWith<CloudFailure> { cachePurgeBody(CachePurgeInput.Tags(listOf("a".repeat(1025))), null) }
    }

    @Test fun successTrueWithProviderErrorsCannotCompleteSettingOrAcknowledgePurge() = runTest {
        val errors = JsonArray(listOf(buildJsonObject { put("code", 1000); put("message", "private-partial-marker") }))
        fun partial(result: JsonObject) = HttpResponse(200, buildJsonObject {
            put("success", true); put("errors", errors); put("messages", JsonArray(emptyList())); put("result", result)
        }.toString())
        val h = Harness(this); val action = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
        val p = h.prepare(action)
        h.overrideWrite = partial(buildJsonObject { put("id", "http2"); put("value", "on"); put("editable", true) })
        val task = h.controller.submit(action, h.confirm(p))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertFalse(task.toString().contains("private-partial-marker"))
        val purge = Harness(this)
        purge.overrideWrite = partial(buildJsonObject { put("id", "purge-receipt") })
        assertIs<ExecutionReceipt.Unknown>(purge.api.purge(zone, CachePurgeInput.All))
        assertEquals(1, h.writes.size); assertEquals(1, purge.writes.size)
    }

    private fun missingOrUnknownReference(task: OperationTask, mode: Int) = task.copy(resourceRef = when (mode) {
        0 -> null
        1 -> ResourceRef(task.scope, "zone_setting", "FUTURE")
        else -> ResourceRef(task.scope, "legacy_target", "opaque")
    })

    @Test fun missingOrUnknownRefForGenericUnknownBlocksNewPlanAndWrite() = runTest {
        val counts = mutableListOf<Int>(); val failures = mutableListOf<Boolean>()
        for (mode in 0..2) {
            val h = Harness(this)
            val first = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
            val p = h.prepare(first); h.throwWrite = true
            val unknown = h.controller.submit(first, h.confirm(p))
            h.store.put(missingOrUnknownReference(unknown, mode))
            h.throwWrite = false; h.requests.clear()
            val next = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP3, input(text("on")))
            h.select(next)
            val result = runCatching {
                val prepared = h.controller.prepare(next)
                h.controller.submit(next, h.confirm(prepared))
            }
            counts += h.writes.size; failures += result.isFailure
            assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
        }
        assertEquals(listOf(0, 0, 0), counts, "missing, unknown key and unknown kind UNKNOWN must not allow new writes")
        assertTrue(failures.all { it })
    }

    @Test fun missingOrUnknownRefForGenericUnknownBlocksPrepreparedSecondConfirmation() = runTest {
        val counts = mutableListOf<Int>(); val refused = mutableListOf<Boolean>()
        for (mode in 0..2) {
            val h = Harness(this)
            val first = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP2, input(text("on")))
            val second = ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP3, input(text("on")))
            val p1 = h.prepare(first); val c1 = h.confirm(p1)
            val p2 = h.prepare(second); val c2 = h.confirm(p2)
            h.select(first); h.throwWrite = true
            val unknown = h.controller.submit(first, c1)
            h.store.put(missingOrUnknownReference(unknown, mode))
            h.throwWrite = false; h.requests.clear(); h.select(second)
            val result = runCatching { h.controller.submit(second, c2) }
            counts += h.writes.size; refused += result.isFailure || result.getOrNull()?.state != TaskState.SUCCEEDED
            assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
        }
        assertEquals(listOf(0, 0, 0), counts, "second confirmations must not bypass missing, unknown key or unknown kind UNKNOWN")
        assertTrue(refused.all { it })
    }

    @Test fun unresolvedUnknownDoesNotBlockProvablyIndependentFixedTargetsOrOtherZones() = runTest {
        for (mode in 0..3) {
            val h = Harness(this)
            val key = if (mode == 1) ZoneSettingKey.FONTS else ZoneSettingKey.HTTP2
            val first = ZoneSettingsAction.Update(zone, key, input(text("on")))
            val p = h.prepare(first); h.throwWrite = true
            val unknown = h.controller.submit(first, h.confirm(p))
            if (mode != 0) h.store.put(unknown.copy(resourceRef = null))
            h.throwWrite = false; h.requests.clear()
            val next: ZoneSettingsAction = when (mode) {
                2 -> ZoneSettingsAction.Purge(zone, CachePurgeInput.Tags(listOf("asset")))
                3 -> ZoneSettingsAction.Update(zone.copy(zoneId = "zone-two"), ZoneSettingKey.HTTP3, input(text("on")))
                else -> ZoneSettingsAction.Update(zone, ZoneSettingKey.HTTP3, input(text("on")))
            }
            val plan = h.prepare(next)
            assertEquals(if (next is ZoneSettingsAction.Purge) TaskState.ACCEPTED else TaskState.SUCCEEDED,
                h.controller.submit(next, h.confirm(plan)).state)
            assertEquals(1, h.writes.size)
            assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
        }
    }

    @Test fun acceptedPurgeWithMissingRefKeepsKnownAcknowledgementAndAllowsNewExplicitPurge() = runTest {
        val h = Harness(this)
        val action = ZoneSettingsAction.Purge(zone, CachePurgeInput.Tags(listOf("asset")))
        val p = h.prepare(action)
        val accepted = h.controller.submit(action, h.confirm(p))
        h.store.put(accepted.copy(resourceRef = null)); h.requests.clear()
        assertEquals(TaskState.ACCEPTED, h.restored().reconcile(accepted.id).state)
        val next = h.prepare(action)
        assertEquals(TaskState.ACCEPTED, h.controller.submit(action, h.confirm(next)).state)
        assertEquals(1, h.writes.size)
    }

    @Test fun unknownCombinedTransformationsWithMissingRefCannotBypassChildSettingBlock() = runTest {
        val h = Harness(this)
        val first = ZoneSettingsAction.Update(zone, ZoneSettingKey.TRANSFORMATIONS_CONFIG, input(json("""{"c2pa":"on"}""")))
        val p = h.prepare(first); h.throwWrite = true
        val unknown = h.controller.submit(first, h.confirm(p))
        h.store.put(unknown.copy(resourceRef = null))
        h.values["zone-one" to ZoneSettingKey.TRANSFORMATIONS_C2PA] = text("on")
        h.throwWrite = false; h.requests.clear()
        val next = ZoneSettingsAction.Update(zone, ZoneSettingKey.TRANSFORMATIONS_C2PA, input(text("off")))
        val result = runCatching { val plan = h.prepare(next); h.controller.submit(next, h.confirm(plan)) }
        assertEquals(0, h.writes.size)
        assertTrue(result.isFailure)
        assertEquals(TaskState.UNKNOWN, h.store.get(unknown.id)!!.state)
    }
}
