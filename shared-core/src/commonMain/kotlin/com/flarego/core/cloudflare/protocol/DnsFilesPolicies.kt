package com.flarego.core.cloudflare.protocol

internal fun dnsFilesRegistrations(): Array<ActionRegistration> = arrayOf(
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-export-dns-records", ActionSemantics.READ,
        ResponseKind.TEXT, RiskLevel.READ_ONLY, ReadRetryPolicy()), "GET", "/zones/{zone_id}/dns_records/export", ActionScope.ZONE),
    ActionRegistration(ActionPolicy("dns-records-for-a-zone-import-dns-records", ActionSemantics.WRITE,
        ResponseKind.ENVELOPE_JSON, RiskLevel.CHANGE, ReadRetryPolicy()), "POST", "/zones/{zone_id}/dns_records/import", ActionScope.ZONE),
)
