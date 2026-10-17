package com.flarego.core.cloudflare.identity

import com.flarego.core.model.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

internal class AuthorityDecodeFailure(val reason: WriteAuthorityReason) : Exception("Token strategy evidence is unavailable")
internal data class AuthorityIdentity(val id: String, val status: String, val notBefore: Long?, val expires: Long?)
internal data class AuthorityToken(val identity: AuthorityIdentity, val policies: List<AuthorityPolicy>)
internal data class AuthorityPolicy(val deny: Boolean, val groups: Set<String>, val resources: List<AuthorityResource>)
internal data class AuthorityResource(val zone: String? = null, val account: String? = null) {
    fun matches(scope: ZoneScope) = (zone == null || zone == scope.zoneId) && (account == null || account == scope.account.accountId)
}
internal data class AuthorityGroup(val id: String, val scopes: Set<String>)
internal data class AuthorityCatalog(val groups: Map<String, AuthorityGroup>, val unsupportedScopes: Boolean = false)
internal data class AuthorityEvaluation(val decision: WriteAuthorityDecision, val reason: WriteAuthorityReason)

/** Frozen reviewed semantics; never infer a permission from a suffix or internal x-cfPermissionsRequired. */
internal object TokenPolicyEvaluation {
    const val ZONE_SCOPE = "com.cloudflare.api.account.zone"
    val labels = setOf("DNS Write", "Zone DNS Settings Write", "Zone Settings Write", "Zone Write", "SSL and Certificates Write", "Cache Purge")
    val acceptedGroups: Map<String, Set<String>> = mapOf(
        "dns-records-for-a-zone-import-dns-records" to setOf("DNS Write"),
        "dns-records-for-a-zone-apply-dns-scan-results" to setOf("DNS Write"),
        "dns-records-for-a-zone-trigger-dns-scan" to setOf("DNS Write"),
        "dns-settings-for-a-zone-update-dns-settings" to setOf("Zone DNS Settings Write", "DNS Write"),
        "dnssec-delete-dnssec-records" to setOf("DNS Write"),
        "dnssec-edit-dnssec-status" to setOf("DNS Write"),
        "zone-purge" to setOf("Cache Purge"),
        "zone-cache-settings-change-aegis-setting" to setOf("Zone Settings Write", "Zone Write"),
        "zone-settings-change-automatic-platform-optimization-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-binary-ast-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-fonts-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-h2-prioritization-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-image-resizing-setting" to setOf("Zone Settings Write"),
        "zone-cache-settings-change-origin-h2-max-streams-setting" to setOf("Zone Settings Write", "Zone Write"),
        "zone-cache-settings-change-origin-max-http-version-setting" to setOf("Zone Settings Write", "Zone Write"),
        "zone-settings-change-speed-brain-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-transformations-allowed-origins-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-transformations-c2pa-setting" to setOf("Zone Settings Write"),
        "zone-settings-change-transformations-config" to setOf("Zone Settings Write"),
        "zone-settings-edit-single-setting" to setOf("Zone Settings Write"),
        "universal-ssl-settings-for-a-zone-edit-universal-ssl-settings" to setOf("SSL and Certificates Write"),
    )
    private fun fail(reason: WriteAuthorityReason = WriteAuthorityReason.INVALID_RESPONSE): Nothing = throw AuthorityDecodeFailure(reason)
    private fun JsonObject.text(key: String, max: Int = 256): String = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= max && it.none { c -> c.code < 32 } } ?: fail()
    private fun JsonObject.optionalTime(key: String, roundUp: Boolean): Long? {
        if (!containsKey(key)) return null
        val text = text(key, 80)
        return try {
            val instant = Instant.parse(text)
            val ms = instant.toEpochMilliseconds()
            if (roundUp && instant > Instant.fromEpochMilliseconds(ms)) {
                if (ms == Long.MAX_VALUE) fail(); ms + 1
            } else ms
        } catch (_: Exception) { fail() }
    }
    private fun identity(row: JsonObject) = AuthorityIdentity(row.text("id", 32), row.text("status").also {
        if (it !in setOf("active", "disabled", "expired")) fail()
    }, row.optionalTime("not_before", true), row.optionalTime("expires_on", false)).also {
        if (it.notBefore != null && it.expires != null && it.notBefore >= it.expires) fail()
    }
    fun verify(row: JsonObject): AuthorityIdentity {
        if (row.keys.any { it !in setOf("id", "status", "not_before", "expires_on") }) fail()
        return identity(row)
    }
    fun token(row: JsonObject, verified: AuthorityIdentity): AuthorityToken {
        val display = setOf("name", "creator_email_at_creation", "issued_on", "last_used_on", "modified_on", "provisioner_id", "provisioner_type")
        if (row.keys.any { it !in display + setOf("id", "status", "not_before", "expires_on", "condition", "policies") }) fail(WriteAuthorityReason.UNKNOWN_POLICY)
        for (name in display) if (row.containsKey(name)) {
            if (name == "provisioner_id" && row[name] == JsonNull) continue
            val value = row[name] as? JsonPrimitive ?: fail()
            if (!value.isString) fail()
        }
        val actual = identity(row)
        if (actual != verified) fail()
        row["condition"]?.let { value ->
            val condition = value as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_CONDITION)
            if (condition.keys.any { it != "request_ip" }) fail(WriteAuthorityReason.UNKNOWN_CONDITION)
            condition["request_ip"]?.let { ip ->
                val rules = ip as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_CONDITION)
                if (rules.keys.any { it !in setOf("in", "not_in") }) fail(WriteAuthorityReason.UNKNOWN_CONDITION)
                if (rules.values.any { it !is JsonArray || it.isNotEmpty() }) fail(WriteAuthorityReason.UNKNOWN_CONDITION)
            }
        }
        val policies = row["policies"] as? JsonArray ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
        if (policies.size > 512) fail(WriteAuthorityReason.UNKNOWN_POLICY)
        var groupCount = 0; var resourceCount = 0
        return AuthorityToken(actual, policies.map { value ->
            val policy = value as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
            if (policy.keys != setOf("id", "effect", "permission_groups", "resources")) fail(WriteAuthorityReason.UNKNOWN_POLICY)
            policy.text("id", 128)
            val effect = policy.text("effect")
            if (effect !in setOf("allow", "deny")) fail(WriteAuthorityReason.UNKNOWN_POLICY)
            val groups = policy["permission_groups"] as? JsonArray ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
            groupCount += groups.size
            if (groupCount > 4096) fail(WriteAuthorityReason.UNKNOWN_POLICY)
            val ids = groups.map { item ->
                val group = item as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
                if (group.keys.any { it !in setOf("id", "name", "meta") }) fail(WriteAuthorityReason.UNKNOWN_POLICY)
                group["name"]?.let { if (it !is JsonPrimitive || !it.isString) fail(WriteAuthorityReason.UNKNOWN_POLICY) }
                group["meta"]?.let { meta ->
                    val fields = meta as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
                    if (fields.keys.any { it !in setOf("category", "deprecated", "description", "editable", "eol_at", "label", "scopes", "visibility") } || fields.values.any { it !is JsonPrimitive || !it.isString }) fail(WriteAuthorityReason.UNKNOWN_POLICY)
                }
                group.text("id", 128)
            }
            if (ids.toSet().size != ids.size) fail(WriteAuthorityReason.UNKNOWN_POLICY)
            val rawResources = policy["resources"] as? JsonObject ?: fail(WriteAuthorityReason.UNKNOWN_POLICY)
            resourceCount += rawResources.size
            if (resourceCount > 4096) fail(WriteAuthorityReason.UNKNOWN_POLICY)
            val resources = rawResources.map { (key, entry) ->
                when {
                    key == "$ZONE_SCOPE.*" && entry == JsonPrimitive("*") -> AuthorityResource()
                    key.startsWith("$ZONE_SCOPE.") && entry == JsonPrimitive("*") -> {
                        val id = key.removePrefix("$ZONE_SCOPE.")
                        if (id.isBlank() || id == "*" || id.length > 128 || id.any { it == '.' || it == '*' || it.code < 32 }) fail(WriteAuthorityReason.UNKNOWN_POLICY)
                        AuthorityResource(zone = id)
                    }
                    key.startsWith("com.cloudflare.api.account.") && entry is JsonObject -> {
                        val id = key.removePrefix("com.cloudflare.api.account.")
                        if (id.isBlank() || id == "*" || id.length > 128 || id.any { it == '.' || it == '*' || it.code < 32 } || entry != buildJsonObject { put("$ZONE_SCOPE.*", "*") }) fail(WriteAuthorityReason.UNKNOWN_POLICY)
                        AuthorityResource(account = id)
                    }
                    else -> fail(WriteAuthorityReason.UNKNOWN_POLICY)
                }
            }
            AuthorityPolicy(effect == "deny", ids.toSet(), resources)
        })
    }
    fun catalog(root: JsonObject): AuthorityCatalog {
        val rows = root["result"] as? JsonArray ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
        if (rows.size > 4096) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
        root["result_info"]?.let { value ->
            val info = value as? JsonObject ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            if (info.keys.any { it !in setOf("count", "page", "per_page", "total_count") }) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            fun count(key: String): Int = (info[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            if (count("count") != rows.size || count("total_count") != rows.size || (info.containsKey("page") && count("page") != 1) || (info.containsKey("per_page") && count("per_page") < rows.size)) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
        }
        val ids = mutableSetOf<String>(); val names = mutableSetOf<String>(); val bound = mutableMapOf<String, AuthorityGroup>(); var unsupportedScopes = false
        for (value in rows) {
            val row = value as? JsonObject ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            if (row.keys.any { it !in setOf("id", "name", "scopes", "category", "is_selectable") }) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            val id = row.text("id", 128); val name = row.text("name")
            if (!ids.add(id) || !names.add(name)) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            row["is_selectable"]?.let { if (it !is JsonPrimitive || it.isString || it.booleanOrNull == null) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS) }
            row["category"]?.let { if (it !is JsonPrimitive || !it.isString) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS) }
            val scopes = row["scopes"] as? JsonArray ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            if (scopes.isEmpty() || scopes.size > 4) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            val scopeNames = scopes.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail(WriteAuthorityReason.CATALOG_AMBIGUOUS) }.toSet()
            if (scopeNames.size != scopes.size) fail(WriteAuthorityReason.CATALOG_AMBIGUOUS)
            if (scopeNames.any { it !in setOf("com.cloudflare.api.user", "com.cloudflare.api.account", ZONE_SCOPE, "com.cloudflare.edge.r2.bucket") }) unsupportedScopes = true
            if (name in labels) {
                bound[name] = AuthorityGroup(id, scopeNames)
            }
        }
        return AuthorityCatalog(bound.toMap(), unsupportedScopes)
    }
    fun evaluate(token: AuthorityToken, catalog: AuthorityCatalog, scope: ZoneScope, operation: String, now: Long): AuthorityEvaluation {
        if (token.identity.status != "active") return AuthorityEvaluation(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TOKEN_NOT_ACTIVE)
        if (token.identity.notBefore?.let { now < it } == true || token.identity.expires?.let { now >= it } == true) return AuthorityEvaluation(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TIME_NOT_VALID)
        val semantics = acceptedGroups[operation] ?: fail(WriteAuthorityReason.UNSUPPORTED_OPERATION)
        val accepted = semantics.mapNotNull { catalog.groups[it]?.id }.toSet()
        if (accepted.isEmpty()) return AuthorityEvaluation(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.CATALOG_AMBIGUOUS)
        val known = catalog.groups.values.map { it.id }.toSet()
        for (p in token.policies.filter { it.deny && it.resources.any { resource -> resource.matches(scope) } }) {
            if (p.groups.any { it !in known }) return AuthorityEvaluation(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.UNKNOWN_POLICY)
            if (p.groups.any { it in accepted }) return AuthorityEvaluation(WriteAuthorityDecision.DENIED, WriteAuthorityReason.POLICY_DENIED)
        }
        return if (token.policies.any { !it.deny && it.groups.any { id -> id in accepted } && it.resources.any { r -> r.matches(scope) } })
            AuthorityEvaluation(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, WriteAuthorityReason.TOKEN_POLICY_GRANTED)
        else AuthorityEvaluation(WriteAuthorityDecision.DENIED, WriteAuthorityReason.NO_APPLICABLE_ALLOW)
    }
    /** Reject duplicate escaped keys and excessive nesting before Json's last-key-wins tree parser. */
    fun envelope(body: String): JsonObject {
        if (body.length > 4 * 1024 * 1024 || body.encodeToByteArray().size > 4 * 1024 * 1024) fail()
        val stack = mutableListOf<MutableSet<String>?>(); var index = 0
        while (index < body.length) {
            when (body[index]) {
                '{', '[' -> { if (stack.size >= 32) fail(); stack.add(if (body[index] == '{') mutableSetOf() else null) }
                '}', ']' -> { if (stack.isEmpty()) fail(); stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = index++; var escaped = false
                    while (index < body.length) {
                        val c = body[index]
                        if (!escaped && c == '"') break
                        escaped = !escaped && c == '\\'; index++
                    }
                    if (index >= body.length) fail()
                    var next = index + 1
                    while (next < body.length && body[next].isWhitespace()) next++
                    if (next < body.length && body[next] == ':') {
                        val keys = stack.lastOrNull() ?: fail()
                        val key = try { Json.parseToJsonElement(body.substring(start, index + 1)).jsonPrimitive.content } catch (_: Exception) { fail() }
                        if (key.length > 512 || !keys.add(key)) fail()
                    }
                }
            }
            index++
        }
        if (stack.isNotEmpty()) fail()
        val root = try { Json.parseToJsonElement(body) as? JsonObject ?: fail() } catch (_: Exception) { fail() }
        if (root.keys.any { it !in setOf("success", "errors", "messages", "result", "result_info") } || root["success"] != JsonPrimitive(true) || (root["errors"] as? JsonArray)?.isEmpty() != true || root["messages"] !is JsonArray || !root.containsKey("result")) fail()
        return root
    }
}
