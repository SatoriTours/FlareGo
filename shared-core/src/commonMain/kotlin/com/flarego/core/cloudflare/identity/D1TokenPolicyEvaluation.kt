package com.flarego.core.cloudflare.identity

import com.flarego.core.model.*
import kotlinx.serialization.json.*

/** D1 account permissions only. No DNS/R2 permission or scope observation grants D1 access. */
internal object D1TokenPolicyEvaluation {
    const val ACCOUNT = "com.cloudflare.api.account"
    const val WRITE = "D1 Write"
    val labels = setOf(WRITE, "D1 Read")
    // Only mappings pinned in official schema are enabled in production.
    val operations = setOf(
        "d1-create-database", "d1-update-database", "d1-update-partial-database",
        "d1-delete-database", "d1-query-database", "d1-raw-database-query",
    )
    private val accountId = Regex("^[a-f0-9]{32}$")
    fun resources(raw: JsonObject): List<AuthorityResource> = raw.map { (key, value) ->
        if (!key.startsWith("$ACCOUNT.") || value != JsonPrimitive("*")) fail()
        val id = key.removePrefix("$ACCOUNT.")
        if (id != "*" && !accountId.matches(id)) fail()
        AuthorityResource(account = id)
    }
    fun evaluate(token: AuthorityToken, catalog: AuthorityCatalog, target: D1WriteTarget, operation: String, now: Long): AuthorityEvaluation {
        fun result(decision: WriteAuthorityDecision, reason: WriteAuthorityReason) = AuthorityEvaluation(decision, reason)
        if (operation !in operations) return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.UNSUPPORTED_OPERATION)
        if (token.identity.status != "active") return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TOKEN_NOT_ACTIVE)
        if (token.identity.notBefore?.let { now < it } == true || token.identity.expires?.let { now >= it } == true)
            return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.TIME_NOT_VALID)
        val writer = catalog.groups[WRITE]?.takeIf { it.scopes == setOf(ACCOUNT) }
            ?: return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.CATALOG_AMBIGUOUS)
        val known = catalog.groups.values.map { it.id }.toSet()
        fun matches(resource: AuthorityResource) = !resource.r2 && resource.zone == null && resource.bucket == null &&
            resource.account in setOf("*", target.account.accountId)
        for (deny in token.policies.filter { it.deny && it.resources.any(::matches) }) {
            if (deny.groups.any { it !in known }) return result(WriteAuthorityDecision.UNKNOWN, WriteAuthorityReason.UNKNOWN_POLICY)
            if (deny.groups.isNotEmpty()) return result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.POLICY_DENIED)
        }
        return if (token.policies.any { !it.deny && writer.id in it.groups && it.resources.any(::matches) })
            result(WriteAuthorityDecision.TOKEN_POLICY_GRANTED, WriteAuthorityReason.TOKEN_POLICY_GRANTED)
        else result(WriteAuthorityDecision.DENIED, WriteAuthorityReason.NO_APPLICABLE_ALLOW)
    }
    private fun fail(): Nothing = throw AuthorityDecodeFailure(WriteAuthorityReason.UNKNOWN_POLICY)
}
