package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch C job allowlist: exactly the three pinned operations.
 *
 * The two reads keep the bounded read-only retry; `r2-create-bucket-job` is a write and therefore
 * never retries (each POST creates a different job, so an unknown outcome must not be re-sent).
 * The operation can delete data (a prefix delete or a whole-bucket clear), so its static risk is
 * DESTRUCTIVE even when the request is a migration.
 *
 * `cf-r2-data-catalog-check` is a controlled protection header: only this POST may carry it, only
 * with the fixed value `true`, and no caller can supply an arbitrary header map or a different
 * value. It is enforced by [CloudflareGateway] and by the platform transport.
 */
internal fun r2JobRegistrations(): Array<ActionRegistration> {
    val bucket = "/accounts/{account_id}/r2/buckets/{bucket_name}"
    fun read(id: String, path: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        path,
        ActionScope.ACCOUNT,
    )
    return arrayOf(
        read(R2_LIST_JOBS_KEY, "$bucket/jobs"),
        read(R2_GET_JOB_KEY, "$bucket/jobs/{job_id}"),
        ActionRegistration(
            ActionPolicy(R2_CREATE_JOB_KEY, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, RiskLevel.DESTRUCTIVE, ReadRetryPolicy()),
            "POST",
            "$bucket/jobs",
            ActionScope.ACCOUNT,
        ),
    )
}

internal const val R2_LIST_JOBS_KEY: String = "r2-list-bucket-jobs"
internal const val R2_GET_JOB_KEY: String = "r2-get-bucket-job"
internal const val R2_CREATE_JOB_KEY: String = "r2-create-bucket-job"

/** The one controlled protection header of the reviewed create-job request. */
internal const val R2_DATA_CATALOG_CHECK_HEADER: String = "cf-r2-data-catalog-check"

/** The only admitted value of [R2_DATA_CATALOG_CHECK_HEADER]. */
internal const val R2_DATA_CATALOG_CHECK_VALUE: String = "true"

/**
 * The exact request shape admitted to carry the protection header: a POST whose registered action
 * is [R2_CREATE_JOB_KEY]. A GET, another route or any other value is refused before the transport.
 */
internal fun isCatalogCheckHeaderAdmitted(actionKey: String, method: String, headers: Map<String, String>): Boolean {
    val entries = headers.entries.filter { it.key.equals(R2_DATA_CATALOG_CHECK_HEADER, ignoreCase = true) }
    if (entries.isEmpty()) return true
    if (actionKey != R2_CREATE_JOB_KEY || method != "POST") return false
    if (entries.size != 1) return false
    return entries.single().value == R2_DATA_CATALOG_CHECK_VALUE
}
