package com.flarego.core.cloudflare.protocol

/** Handwritten Task 9 allowlist; generated data cannot grant routing or permission. */
internal fun zoneSettingsRegistrations(): Array<ActionRegistration> {
    fun read(id: String, path: String) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET", path, ActionScope.ZONE)
    fun write(id: String, method: String, path: String, risk: RiskLevel = RiskLevel.CHANGE) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.WRITE, ResponseKind.ENVELOPE_JSON, risk, ReadRetryPolicy()), method, path, ActionScope.ZONE)
    fun pair(get: String, patch: String, key: String) = arrayOf(read(get, "/zones/{zone_id}/settings/$key"), write(patch, "PATCH", "/zones/{zone_id}/settings/$key"))
    return arrayOf(
        *pair("zone-cache-settings-get-aegis-setting", "zone-cache-settings-change-aegis-setting", "aegis"),
        *pair("zone-settings-get-automatic-platform-optimization-setting", "zone-settings-change-automatic-platform-optimization-setting", "automatic_platform_optimization"),
        *pair("zone-settings-get-binary-ast-setting", "zone-settings-change-binary-ast-setting", "binary_ast"),
        *pair("zone-settings-get-fonts-setting", "zone-settings-change-fonts-setting", "fonts"),
        *pair("zone-settings-get-h2-prioritization-setting", "zone-settings-change-h2-prioritization-setting", "h2_prioritization"),
        *pair("zone-settings-get-image-resizing-setting", "zone-settings-change-image-resizing-setting", "image_resizing"),
        *pair("zone-cache-settings-get-origin-h2-max-streams-setting", "zone-cache-settings-change-origin-h2-max-streams-setting", "origin_h2_max_streams"),
        *pair("zone-cache-settings-get-origin-max-http-version-setting", "zone-cache-settings-change-origin-max-http-version-setting", "origin_max_http_version"),
        *pair("zone-settings-get-speed-brain-setting", "zone-settings-change-speed-brain-setting", "speed_brain"),
        *pair("zone-settings-get-transformations-allowed-origins-setting", "zone-settings-change-transformations-allowed-origins-setting", "transformations_allowed_origins"),
        *pair("zone-settings-get-transformations-c2pa-setting", "zone-settings-change-transformations-c2pa-setting", "transformations_c2pa"),
        *pair("zone-settings-get-transformations-config", "zone-settings-change-transformations-config", "transformations_config"),
        read("zone-settings-get-single-setting", "/zones/{zone_id}/settings/{setting_id}"),
        write("zone-settings-edit-single-setting", "PATCH", "/zones/{zone_id}/settings/{setting_id}", RiskLevel.DESTRUCTIVE),
        read("universal-ssl-settings-for-a-zone-universal-ssl-settings-details", "/zones/{zone_id}/ssl/universal/settings"),
        write("universal-ssl-settings-for-a-zone-edit-universal-ssl-settings", "PATCH", "/zones/{zone_id}/ssl/universal/settings", RiskLevel.DESTRUCTIVE),
        write("zone-purge", "POST", "/zones/{zone_id}/purge_cache", RiskLevel.DESTRUCTIVE),
    )
}
