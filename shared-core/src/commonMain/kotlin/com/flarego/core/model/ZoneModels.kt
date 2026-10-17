package com.flarego.core.model

import kotlinx.serialization.json.*

/** Cloudflare Zone state; status describes the Zone, not domain registration. */
data class ZoneDetails(
    val ref: ZoneScope,
    val name: String,
    val status: String,
    val paused: Boolean,
    val type: String,
    val nameservers: List<String>,
    /** Read-only provider metadata. Never send this object as an edit body. */
    val raw: JsonObject,
)

data class ZoneCreateInput(val name: String, val type: String = "full") {
    init {
        requireZoneName(name)
        requireZoneType(type)
    }
}

/** The fixed API permits exactly one property per PATCH. Null means omitted. */
data class ZoneEditInput(val paused: Boolean? = null, val type: String? = null, val vanityNameservers: List<String>? = null) {
    init {
        if (listOfNotNull(paused, type, vanityNameservers).size != 1) throw invalidInputFailure()
        type?.let(::requireZoneType)
        vanityNameservers?.forEach(::requireZoneHostname)
    }
}

sealed interface ZoneAction {
    val scope: CloudScope
    data class Create(override val scope: AccountScope, val input: ZoneCreateInput) : ZoneAction
    data class Edit(override val scope: ZoneScope, val input: ZoneEditInput) : ZoneAction
    /** Both values are fingerprinted; name must also match fresh provider evidence. */
    data class Delete(override val scope: ZoneScope, val name: String, val typedName: String) : ZoneAction
    data class ActivationCheck(override val scope: ZoneScope) : ZoneAction
}

/** Display only: never a source of executable payload or ownership evidence. */
data class ZoneChangePreview(val scope: CloudScope, val domain: String, val impact: String, val concurrencyWarning: String)

internal fun freezeZoneAction(action: ZoneAction): ZoneAction = when (action) {
    // zones_name has already restricted this to ASCII. DNS names are case-insensitive;
    // both the independent input fingerprint and the wire request use this snapshot.
    is ZoneAction.Create -> action.copy(input = action.input.copy(name = action.input.name.lowercase()))
    is ZoneAction.Edit -> action.copy(input = action.input.copy(vanityNameservers = action.input.vanityNameservers?.toList()))
    is ZoneAction.Delete -> action.copy()
    is ZoneAction.ActivationCheck -> action.copy()
}

internal fun zoneActionKey(action: ZoneAction): String = when (action) {
    is ZoneAction.Create -> "zones-post"
    is ZoneAction.Edit -> "zones-0-patch"
    is ZoneAction.Delete -> "zones-0-delete"
    is ZoneAction.ActivationCheck -> "put-zones-zone_id-activation_check"
}

internal fun zoneEditFields(input: ZoneEditInput): JsonObject = buildJsonObject {
    input.paused?.let { put("paused", it) }
    input.type?.let { put("type", it) }
    input.vanityNameservers?.let { put("vanity_name_servers", JsonArray(it.map(::JsonPrimitive))) }
}

private fun requireZoneType(type: String) {
    if (type !in setOf("full", "partial", "secondary", "internal")) throw invalidInputFailure()
}

private fun requireZoneName(name: String) {
    requireZoneHostname(name)
    // Fixed zones_name pattern, plus RFC label-length/hostname checks.
    if (!Regex("^([a-zA-Z0-9][\\-a-zA-Z0-9]*\\.)+[\\-a-zA-Z0-9]{2,20}$").matches(name)) throw invalidInputFailure()
}

private fun requireZoneHostname(name: String) {
    if (name.isEmpty() || name.length > 253) throw invalidInputFailure()
    if (name.split('.').any { label ->
        label.isEmpty() || label.length > 63 || label.first() == '-' || label.last() == '-' ||
            label.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' }
    }) throw invalidInputFailure()
}
