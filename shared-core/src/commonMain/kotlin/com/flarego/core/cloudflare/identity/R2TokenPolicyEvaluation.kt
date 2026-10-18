package com.flarego.core.cloudflare.identity

import com.flarego.core.model.*
import kotlinx.serialization.json.*

/** REST admission only. S3 Bucket Item permissions are deliberately NOT REST admin permission. */
internal object R2TokenPolicyEvaluation {
    const val ACCOUNT = "com.cloudflare.api.account"
    private const val BUCKET = "com.cloudflare.edge.r2.bucket"
    const val WRITE = "Workers R2 Storage Write"
    val labels = setOf(WRITE, "Workers R2 Storage Read", "Workers R2 Storage Bucket Item Write", "Workers R2 Storage Bucket Item Read")
    val operations = setOf("r2-create-bucket", "r2-create-bucket-by-name", "r2-patch-bucket", "r2-delete-bucket",
        "r2-put-object", "r2-delete-object", "r2-delete-objects")
    private val accountId = Regex("^[a-f0-9]{32}$")
    private val bucketName = Regex("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
    private val jurisdictions = setOf("default", "eu", "us", "fedramp", "fedramp-high")
    private fun fail(): Nothing = throw AuthorityDecodeFailure(WriteAuthorityReason.UNKNOWN_POLICY)

    fun resources(raw: JsonObject): List<AuthorityResource> = raw.map { (key, value) ->
        when {
            key.startsWith("$ACCOUNT.") -> {
                val id = key.removePrefix("$ACCOUNT.")
                if (!accountId.matches(id)) fail()
                val allBuckets = buildJsonObject { put("$BUCKET.*", "*") }
                if (value != JsonPrimitive("*") && value != allBuckets) fail()
                AuthorityResource(account = id, r2 = true)
            }
            key.startsWith("$BUCKET.") && value == JsonPrimitive("*") -> {
                val parts = key.removePrefix("$BUCKET.").split('_')
                if (parts.size != 3 || !accountId.matches(parts[0]) || parts[1] !in jurisdictions || !bucketName.matches(parts[2])) fail()
                AuthorityResource(account = parts[0], r2 = true, bucket = parts[2], jurisdiction = parts[1])
            }
            else -> fail()
        }
    }
    fun evaluate(token: AuthorityToken, catalog: AuthorityCatalog, target: R2WriteTarget, operation: String, now: Long): AuthorityEvaluation {
        fun result(decision: WriteAuthorityDecision, reason: WriteAuthorityReason) = AuthorityEvaluation(decision, reason)
        if (operation !in operations) return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.UNSUPPORTED_OPERATION)
        if (token.identity.status != "active") return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TOKEN_NOT_ACTIVE)
        if (token.identity.notBefore?.let { now < it } == true || token.identity.expires?.let { now >= it } == true)
            return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TIME_NOT_VALID)
        val admin = catalog.groups[WRITE]?.takeIf { it.scopes == setOf(ACCOUNT) }
            ?: return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.CATALOG_AMBIGUOUS)
        val known = catalog.groups.values.map { it.id }.toSet()
        fun matches(r: AuthorityResource) = r.r2 && r.account == target.account.accountId &&
            (r.bucket == null || r.bucket == target.bucket && r.jurisdiction == target.jurisdiction)
        for (deny in token.policies.filter { it.deny && it.resources.any(::matches) }) {
            if (deny.groups.any { it !in known }) return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.UNKNOWN_POLICY)
            // Even an object-level deny must not be bypassed by a broad account allow.
            if (deny.groups.any { it in known }) return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.POLICY_DENIED)
        }
        val allowed = token.policies.any { !it.deny && admin.id in it.groups &&
            it.resources.any { resource -> matches(resource) && resource.bucket == null } }
        return if (allowed) result(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, WriteAuthorityReason.TOKEN_POLICY_GRANTED)
        else result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.NO_APPLICABLE_ALLOW)
    }
}
