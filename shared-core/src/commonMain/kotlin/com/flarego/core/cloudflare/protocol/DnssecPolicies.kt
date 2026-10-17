package com.flarego.core.cloudflare.protocol

/** Handwritten fixed allowlist, independent of generated schemas. */
internal fun dnssecRegistrations(): Array<ActionRegistration> = arrayOf(
    ActionRegistration(ActionPolicy("dnssec-dnssec-details", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
        RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "GET", "/zones/{zone_id}/dnssec", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dnssec-list-dnssec-zsks", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
        RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "GET", "/zones/{zone_id}/dnssec/zsk", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dnssec-edit-dnssec-status", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
        RiskLevel.DESTRUCTIVE, ReadRetryPolicy()), "PATCH", "/zones/{zone_id}/dnssec", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dnssec-delete-dnssec-records", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
        RiskLevel.DESTRUCTIVE, ReadRetryPolicy()), "DELETE", "/zones/{zone_id}/dnssec", ActionScope.ZONE),
)
