package com.flarego.core.model

/** How far this app has implemented a capability. Orthogonal to availability. */
enum class ImplementationLevel {
    NONE,
    REGISTERED,
    PROTOCOL,
    OPERABLE,
    DEEP;

    companion object {
        /**
         * Maps a provider or ledger name to a level. Unknown, missing or
         * differently-cased names fall back to the safe [NONE] level instead of
         * throwing, so a new provider value can never crash a read.
         */
        fun parse(raw: String?): ImplementationLevel {
            val name = raw?.trim()?.uppercase() ?: return NONE
            return entries.firstOrNull { it.name == name } ?: NONE
        }
    }
}

/** Whether the provider currently allows a capability for this account. */
enum class CapabilityAvailability {
    UNKNOWN,
    AVAILABLE,
    PERMISSION_REQUIRED,
    PLAN_REQUIRED,
    CREDENTIAL_REQUIRED,
    RESTRICTED,
    CONSOLE_ONLY,
    REMOVED,
    TEMPORARILY_UNAVAILABLE;

    companion object {
        /**
         * Maps a provider or ledger name to an availability. Unknown, missing or
         * differently-cased names fall back to the safe [UNKNOWN] state instead
         * of throwing; unknown never becomes available.
         */
        fun parse(raw: String?): CapabilityAvailability {
            val name = raw?.trim()?.uppercase() ?: return UNKNOWN
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

/**
 * Capability of one scoped identity.
 *
 * [implementation] describes this app; [availability] describes the provider
 * account. Implementation never implies availability. [reason] and
 * [observedAtMs] stay null while unknown: an absent observation is never
 * replaced by the current time or by a fabricated status.
 */
data class CapabilityStatus(
    val key: String,
    val scope: CloudScope,
    val implementation: ImplementationLevel = ImplementationLevel.NONE,
    val availability: CapabilityAvailability = CapabilityAvailability.UNKNOWN,
    val reason: String? = null,
    val observedAtMs: Long? = null,
)
