package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch B3 Sippy allowlist: exactly the three pinned operations.
 *
 * Nothing here is loaded from a schema, the ledger or the UI. `r2-put-bucket-sippy-config` enables
 * on-demand migration and carries the sensitive source/destination credentials; it is a CHANGE and
 * is never retried. `r2-delete-bucket-sippy-config` only disables the configuration (objects already
 * copied remain), so it is also a CHANGE; its separate confirmation belongs to the UI. The GET is a
 * bounded read with the default read-only retry.
 */
internal fun r2SippyRegistrations(): Array<ActionRegistration> {
    val path = "/accounts/{account_id}/r2/buckets/{bucket_name}/sippy"
    fun read(id: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        path,
        ActionScope.ACCOUNT,
    )
    fun write(id: String, method: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, RiskLevel.CHANGE, ReadRetryPolicy()),
        method,
        path,
        ActionScope.ACCOUNT,
    )
    return arrayOf(
        read("r2-get-bucket-sippy-config"),
        write("r2-put-bucket-sippy-config", "PUT"),
        write("r2-delete-bucket-sippy-config", "DELETE"),
    )
}
