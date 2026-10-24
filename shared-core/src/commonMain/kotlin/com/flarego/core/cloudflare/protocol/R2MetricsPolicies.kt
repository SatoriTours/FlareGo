package com.flarego.core.cloudflare.protocol

/**
 * Handwritten R2 batch C account-metrics allowlist: exactly the one pinned read.
 *
 * The endpoint is account-level and declares no jurisdiction or bucket parameter, so the request
 * never carries either. It keeps the bounded read-only retry.
 */
internal fun r2MetricsRegistrations(): Array<ActionRegistration> = arrayOf(
    ActionRegistration(
        ActionPolicy(R2_ACCOUNT_METRICS_KEY, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET",
        "/accounts/{account_id}/r2/metrics",
        ActionScope.ACCOUNT,
    ),
)

internal const val R2_ACCOUNT_METRICS_KEY: String = "r2-get-account-level-metrics"
