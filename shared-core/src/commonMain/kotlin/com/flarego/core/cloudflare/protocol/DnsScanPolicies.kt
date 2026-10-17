package com.flarego.core.cloudflare.protocol

internal fun dnsScanRegistrations(): Array<ActionRegistration> = arrayOf(
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-review-dns-scan", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
        RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "GET", "/zones/{zone_id}/dns_records/scan/review", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-apply-dns-scan-results", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
        RiskLevel.DESTRUCTIVE, ReadRetryPolicy()), "POST", "/zones/{zone_id}/dns_records/scan/review", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-trigger-dns-scan", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
        RiskLevel.CHANGE, ReadRetryPolicy()), "POST", "/zones/{zone_id}/dns_records/scan/trigger", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-get-usage", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
        RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "GET", "/zones/{zone_id}/dns_records/usage", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-settings-for-a-zone-list-dns-settings", ActionSemantics.READ, ResponseKind.ENVELOPE_JSON,
        RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)), "GET", "/zones/{zone_id}/dns_settings", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-settings-for-a-zone-update-dns-settings", ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON,
        RiskLevel.CHANGE, ReadRetryPolicy()), "PATCH", "/zones/{zone_id}/dns_settings", ActionScope.ZONE),
)
