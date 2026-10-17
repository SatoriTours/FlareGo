package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch A allowlist: the reviewed bucket control plane and the object
 * REST loop (`docs/superpowers/specs/2026-10-17-p3-r2-management.md`).
 *
 * Listing buckets keeps its original P1 registration, so it is not repeated here.
 * Nothing in this file is loaded from a schema, the audit ledger or the UI.
 *
 * `r2-create-temp-access-credentials` (R2-E) is deliberately absent: it returns a live
 * `secretAccessKey`/`sessionToken` pair, so it needs its own approved decision.
 */
internal fun r2Registrations(): Array<ActionRegistration> {
    val buckets = "/accounts/{account_id}/r2/buckets"
    val bucket = "$buckets/{bucket_name}"
    val objects = "$bucket/objects"
    val objectPath = "$objects/{object_key}"

    fun read(id: String, path: String, response: ResponseKind, spans: Boolean = false) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, response, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET", path, ActionScope.ACCOUNT, trailingPlaceholderSpansSegments = spans,
    )
    fun write(id: String, method: String, path: String, risk: RiskLevel, spans: Boolean = false) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()),
        method, path, ActionScope.ACCOUNT, trailingPlaceholderSpansSegments = spans,
    )

    return arrayOf(
        write("r2-create-bucket", "POST", buckets, RiskLevel.CHANGE),
        write("r2-create-bucket-by-name", "PUT", bucket, RiskLevel.CHANGE),
        read("r2-get-bucket", bucket, ResponseKind.ENVELOPE_JSON),
        write("r2-patch-bucket", "PATCH", bucket, RiskLevel.CHANGE),
        write("r2-delete-bucket", "DELETE", bucket, RiskLevel.DESTRUCTIVE),
        read("r2-list-objects", objects, ResponseKind.ENVELOPE_JSON),
        // Object keys are sent as literal path text, so the three key-addressed actions
        // own a trailing placeholder that may span segments.
        read("r2-get-object", objectPath, ResponseKind.BINARY, spans = true),
        write("r2-put-object", "PUT", objectPath, RiskLevel.CHANGE, spans = true),
        write("r2-delete-object", "DELETE", objectPath, RiskLevel.DESTRUCTIVE, spans = true),
        write("r2-delete-objects", "DELETE", objects, RiskLevel.DESTRUCTIVE),
    )
}
