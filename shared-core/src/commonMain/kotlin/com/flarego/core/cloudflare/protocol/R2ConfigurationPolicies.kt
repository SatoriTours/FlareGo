package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch B1 configuration allowlist: CORS, lifecycle, local uploads and
 * bucket lock (`docs/superpowers/plans/2026-10-19-p3-r2-batch-b.md`).
 *
 * Nothing in this file is loaded from a schema, the audit ledger or the UI, and the batch A
 * function in `R2Policies.kt` keeps exactly its own ten operations: each batch owns its own
 * handwritten registration function.
 *
 * A `PUT` here replaces the complete rule set of its kind. The lifecycle `PUT` is registered
 * as DESTRUCTIVE because it can delete objects and abort multipart uploads, and both lock
 * mutations are DESTRUCTIVE because they can pin objects against deletion; those static risks
 * are never lowered by a UI flag.
 *
 * The controlled `cf-r2-jurisdiction` header is not part of a registration — the platform
 * transport owns the header allowlist, and local validation binds the header value to the
 * pinned five-value enum for every endpoint that declares it.
 */
internal fun r2ConfigurationRegistrations(): Array<ActionRegistration> {
    val bucket = "/accounts/{account_id}/r2/buckets/{bucket_name}"

    fun read(id: String, path: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        path,
        ActionScope.ACCOUNT,
    )

    fun write(id: String, method: String, path: String, risk: RiskLevel) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
        method,
        path,
        ActionScope.ACCOUNT,
    )

    return arrayOf(
        read("r2-get-bucket-cors-policy", "$bucket/cors"),
        write("r2-put-bucket-cors-policy", "PUT", "$bucket/cors", RiskLevel.CHANGE),
        write("r2-delete-bucket-cors-policy", "DELETE", "$bucket/cors", RiskLevel.DESTRUCTIVE),
        read("r2-get-bucket-lifecycle-configuration", "$bucket/lifecycle"),
        write("r2-put-bucket-lifecycle-configuration", "PUT", "$bucket/lifecycle", RiskLevel.DESTRUCTIVE),
        read("r2-get-bucket-local-uploads-configuration", "$bucket/local-uploads"),
        write("r2-put-bucket-local-uploads-configuration", "PUT", "$bucket/local-uploads", RiskLevel.CHANGE),
        read("r2-get-bucket-lock-configuration", "$bucket/lock"),
        write("r2-put-bucket-lock-configuration", "PUT", "$bucket/lock", RiskLevel.DESTRUCTIVE),
        write("r2-delete-bucket-lock-configuration", "DELETE", "$bucket/lock", RiskLevel.DESTRUCTIVE),
    )
}
