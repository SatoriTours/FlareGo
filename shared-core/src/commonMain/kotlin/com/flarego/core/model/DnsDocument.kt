package com.flarego.core.model

import kotlinx.serialization.json.JsonObject

/** Provider document: keep all unknown fields and future types for display. */
data class DnsRecordDocument(val id: String, val scope: ZoneScope, val raw: JsonObject)

/** Name/type are separate from the writable, type-specific field map. */
data class DnsRecordInput(val type: String, val name: String, val fields: JsonObject)

/** A 30-second minimum must be supplied only after Enterprise entitlement is verified. */
data class DnsConstraints(
    val minTtl: Int = 60,
    val maxTtl: Int = 86400,
    val proxyTypes: Set<String> = setOf("A", "AAAA", "CNAME"),
) {
    init {
        require(minTtl in 30..86400 && maxTtl in minTtl..86400)
        require(proxyTypes.all { it in setOf("A", "AAAA", "CNAME") })
    }
}

/** HTTP write semantics; distinct from later confirmation/controller modes. */
enum class DnsWriteMethod { POST, PATCH, PUT }
