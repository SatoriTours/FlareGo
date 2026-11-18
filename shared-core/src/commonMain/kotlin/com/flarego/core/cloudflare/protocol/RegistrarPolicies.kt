package com.flarego.core.cloudflare.protocol

/**
 * Handwritten Registrar registrations.
 *
 * Exactly one reviewed read: domain discovery search. It returns cached provider
 * suggestions and nothing more.
 *
 * The write side of the Registrar API stays unregistered on purpose —
 * `POST /accounts/{account_id}/registrar/registrations`, `domain-check` and the
 * transfer operations are all absent, so the gateway refuses them before the wire
 * and no code path in this build can create or pay for a domain.
 */
internal fun registrarRegistrations(): Array<ActionRegistration> = arrayOf(
    ActionRegistration(
        ActionPolicy(
            "registrar-domain-discovery-search",
            ActionSemantics.READ,
            ResponseKind.ENVELOPE_JSON,
            RiskLevel.READ_ONLY,
            ReadRetryPolicy(enabled = true),
        ),
        "GET",
        "/accounts/{account_id}/registrar/domain-search",
        ActionScope.ACCOUNT,
    ),
)
