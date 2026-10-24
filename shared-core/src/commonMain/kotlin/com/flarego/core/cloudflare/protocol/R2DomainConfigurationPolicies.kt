package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch B2 domain allowlist: the managed r2.dev endpoint and the custom domains.
 *
 * Nothing here is loaded from a schema, the audit ledger or the UI. Each batch keeps its own
 * function, so the batch A and batch B1 functions stay exactly as reviewed.
 *
 * Risk is the static registration risk, never lowered by a UI flag: deleting a custom domain and
 * changing the public endpoint are `CHANGE`/`DESTRUCTIVE` as reviewed, and a public-access toggle is
 * still an impact the product confirms separately.
 */
internal fun r2DomainRegistrations(): Array<ActionRegistration> {
    val bucket = "/accounts/{account_id}/r2/buckets/{bucket_name}"
    val managed = "$bucket/domains/managed"
    val custom = "$bucket/domains/custom"
    fun read(id: String, path: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET", path, ActionScope.ACCOUNT,
    )
    fun write(id: String, method: String, path: String, risk: RiskLevel) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
        method, path, ActionScope.ACCOUNT,
    )
    return arrayOf(
        read("r2-get-bucket-public-policy", managed),
        write("r2-put-bucket-public-policy", "PUT", managed, RiskLevel.CHANGE),
        read("r2-list-custom-domains", custom),
        write("r2-add-custom-domain", "POST", custom, RiskLevel.CHANGE),
        read("r2-get-custom-domain-settings", "$custom/{domain}"),
        write("r2-edit-custom-domain-settings", "PUT", "$custom/{domain}", RiskLevel.CHANGE),
        write("r2-delete-custom-domain", "DELETE", "$custom/{domain}", RiskLevel.DESTRUCTIVE),
    )
}
