package com.flarego.core.model

/** A single REST target. Jurisdiction is explicit; non-default is not yet admitted. */
data class R2WriteTarget(val account: AccountScope, val bucket: String, val jurisdiction: String = "default")

/** Display only. Copies and forged granted values never mint a proof. */
data class R2WriteObservation(
    val target: R2WriteTarget,
    val operationId: String,
    val decision: WriteAuthorityDecision,
    val reason: WriteAuthorityReason,
    val credentialRevision: Long,
    val observedAtMs: Long,
    val validUntilMs: Long? = null,
)

/** Issuer-owned identity; never persisted or supplied by UI. */
internal class R2WriteProof internal constructor() {
    override fun toString() = "R2WriteProof"
}
