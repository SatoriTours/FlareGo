package com.flarego.core.model

/** Null database is the account-level create intent only; it cannot grant another operation. */
data class D1WriteTarget(val account: AccountScope, val databaseId: String?)
data class D1WriteObservation(
    val target: D1WriteTarget, val operationId: String, val decision: WriteAuthorityDecision,
    val reason: WriteAuthorityReason, val credentialRevision: Long, val observedAtMs: Long, val validUntilMs: Long? = null,
)
internal class D1WriteProof internal constructor() { override fun toString() = "D1WriteProof" }
