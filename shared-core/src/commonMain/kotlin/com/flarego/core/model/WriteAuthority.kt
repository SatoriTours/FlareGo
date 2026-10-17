package com.flarego.core.model

/** Token policy admission only; never principal-wide access, eligibility or user confirmation. */
enum class WriteAuthorityDecision { TOKEN_POLICY_GRANTED, DENIED, UNKNOWN }
enum class WriteAuthorityReason {
    TOKEN_POLICY_GRANTED, POLICY_DENIED, NO_APPLICABLE_ALLOW, UNSUPPORTED_OPERATION,
    SELF_INSPECTION_UNAVAILABLE, INVALID_RESPONSE, TOKEN_NOT_ACTIVE, TIME_NOT_VALID,
    UNKNOWN_CONDITION, UNKNOWN_POLICY, CATALOG_AMBIGUOUS, CATALOG_CHANGED,
    TOKEN_ID_CHANGED, AMBIGUOUS_TOKEN_KIND, SCOPE_MISMATCH, CREDENTIAL_CHANGED, SESSION_INVALIDATED, CLOCK_ROLLBACK,
}

/** Display only. Copying/constructing a granted value never creates an issuer-owned proof. */
data class WriteAuthorityObservation(
    val decision: WriteAuthorityDecision,
    val reason: WriteAuthorityReason,
    val scope: ZoneScope,
    val operationId: String,
    val credentialRevision: Long,
    val observedAtMs: Long,
    val validUntilMs: Long? = null,
)

/** Opaque identity, never exposed by the public observation port or persisted. */
internal class WriteAuthorityProof internal constructor() {
    override fun toString() = "WriteAuthorityProof"
}
