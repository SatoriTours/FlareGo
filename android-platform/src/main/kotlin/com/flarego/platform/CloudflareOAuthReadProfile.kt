package com.flarego.platform

import com.flarego.core.application.inventory.ProductKey

/** Exact IDs confirmed in the FlareGo OAuth client console; no runtime scope discovery. */
object CloudflareOAuthReadProfile {
    val scopes: List<String> = listOf(
        "account-settings.read", "zone.read", "dns.read", "workers-scripts.metadata_read",
        "workers-kv-storage.read", "workers-r2.read", "offline_access",
    )
    // Product directory reads only; this is not proof of an individual token's permissions.
    val products: Set<ProductKey> = setOf(ProductKey.ZONES, ProductKey.WORKERS, ProductKey.KV, ProductKey.R2)
}
