package com.flarego.core.model

/** Null namespace is the account-level create intent only. */
data class KvWriteTarget(val account: AccountScope, val namespaceId: String?)
data class KvWriteObservation(
    val target: KvWriteTarget, val operationId: String, val decision: WriteAuthorityDecision,
    val reason: WriteAuthorityReason, val credentialRevision: Long, val observedAtMs: Long, val validUntilMs: Long? = null,
)
internal class KvWriteProof internal constructor() { override fun toString() = "KvWriteProof" }
