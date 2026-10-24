package com.flarego.ui.r2

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The destructive C actions that require the three-stage confirmation of spec §10.6.
 *
 * Only these are staged three times; a migration or a notification append keeps the reviewed
 * risk/impact + preview confirmation of the other families.
 */
enum class R2CDeleteKind {
    /** A prefix-delete job with a non-empty prefix. */
    PREFIX_DELETE,

    /** A prefix-delete job whose explicit mode is the whole bucket (empty prefix). */
    BUCKET_CLEAR,

    /** Removing an explicit, non-empty selection of notification rules. */
    NOTIFICATION_SELECTED,

    /** Clearing every rule of one queue. */
    NOTIFICATION_ALL,
}

/** The three confirmation stages, plus "nothing is being confirmed". */
enum class R2CStage { NONE, RISK, TYPED_NAME, FINAL }

/** Safe, non-secret confirmation state for the surface (no command, plan or credential). */
data class R2CConfirmationState(
    val stage: R2CStage = R2CStage.NONE,
    val kind: R2CDeleteKind? = null,
    /** account / jurisdiction / bucket / exact prefix-or-whole-bucket / queue + rule IDs, display only. */
    val scopeText: String = "",
    val bucket: String = "",
    val typedBucketName: String = "",
    val typedMatches: Boolean = false,
    val finalOpen: Boolean = false,
    val error: String? = null,
    /** Opaque surface identity captured by each dialog callback, never dynamically re-selected. */
    val handle: Long = 0,
)

/**
 * The immutable, memory-only inputs one destructive confirmation belongs to.
 *
 * [fingerprint] is supplied by the caller from its live editor (the same value the runtime's request
 * fingerprint binds); the owner only compares it. It is never persisted and never restored.
 */
data class R2CConfirmationDraft(
    val kind: R2CDeleteKind,
    val bucket: String,
    val fingerprint: String,
    val scopeText: String,
)

/**
 * A single-use permission to submit one *specific* destructive confirmation.
 *
 * It is bound to three things at once:
 * - the confirmation **handle** whose final stage minted it (a later cancel/restart makes every older
 *   handle stale, so an old dialog callback can never authorise a newer confirmation — even one with
 *   the same payload fingerprint),
 * - the payload fingerprint of the exact draft it was opened for,
 * - the **live route/credential binding** captured when the final dialog was opened (this is *not* part
 *   of the payload fingerprint, so a route or credential change alone still kills the ticket).
 *
 * [consumeOnce] re-reads all three at call time through the owner, so a ticket that was retained by a
 * cancelled dialog, or minted under an older route/credential, is no longer consumable at all.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2CConfirmationTicket internal constructor(
    val kind: R2CDeleteKind,
    val bucket: String,
    val fingerprint: String,
    val handle: Long,
    internal val liveBinding: String,
    /** Re-reads the owner's current handle/stage/route-credential binding at consumption time. */
    private val stillValid: () -> Boolean,
    private val consumed: AtomicLong = AtomicLong(0),
) {
    /** True exactly once, and only while this ticket's handle/binding is still the current one. */
    fun consumeOnce(): Boolean {
        if (!stillValid()) return false
        return consumed.compareAndSet(0, 1)
    }
}

/**
 * The memory-only three-stage confirmation owner for destructive C writes (spec §10.6).
 *
 * Contract:
 * - stage 1 shows the exact scope and requires an explicit acknowledgement (no default selection);
 * - stage 2 requires the operator to type the exact bucket name — no trimming, no case folding, no
 *   autofill; a mismatch keeps the stage and cannot advance;
 * - stage 3 is a separate final dialog that mints one single-use ticket for exactly the draft and the
 *   live route/credential binding it was opened with;
 * - **any** change of the draft fingerprint (input/mode/selection/target/jurisdiction/route/
 *   credential), an explicit cancel, or leaving the surface revokes everything, and every older handle
 *   and ticket becomes unusable;
 * - the state is never restored from a saved state, a task, a locator or a plan.
 *
 * [liveBinding] is supplied by the caller from its live editor/credential state (for example
 * `routeEpoch/credentialRevision`). It is deliberately independent of the payload fingerprint: a route
 * or credential change alone must invalidate a minted final ticket.
 */
@OptIn(ExperimentalAtomicApi::class)
class R2CDeletionConfirmation(private val liveBinding: () -> String) {
    private var handleSeq = 0L
    private var handle = 0L
    private var draft: R2CConfirmationDraft? = null
    private var stage = R2CStage.NONE
    private var typed = ""
    private var ticketBinding: String? = null
    private var error: String? = null

    val state: R2CConfirmationState
        get() =
            R2CConfirmationState(
                stage = stage,
                kind = draft?.kind,
                scopeText = draft?.scopeText.orEmpty(),
                bucket = draft?.bucket.orEmpty(),
                typedBucketName = typed,
                typedMatches = draft != null && typed.isNotEmpty() && typed == draft?.bucket,
                finalOpen = stage == R2CStage.FINAL,
                error = error,
                handle = handle,
            )

    /** Stage 1: start a confirmation for exactly this draft; returns its handle. */
    fun begin(next: R2CConfirmationDraft): Long {
        handleSeq++
        handle = handleSeq
        draft = next
        typed = ""
        ticketBinding = null
        error = null
        stage = R2CStage.RISK
        return handle
    }

    /** Stage 1 → stage 2, only for the current handle and while the live draft is unchanged. */
    fun acknowledgeRisk(handle: Long, liveFingerprint: String?): Boolean {
        val bound = draft ?: return false
        if (handle != this.handle || stage != R2CStage.RISK) return false
        if (liveFingerprint != bound.fingerprint) {
            revoke(R2C_CONFIRMATION_DRAFT_CHANGED)
            return false
        }
        stage = R2CStage.TYPED_NAME
        error = null
        return true
    }

    /** Stage 2 input. Never trimmed, never case-folded, never autofilled. */
    fun typeBucketName(handle: Long, text: String) {
        if (handle != this.handle || stage != R2CStage.TYPED_NAME) return
        typed = text
        error = null
    }

    /**
     * Stage 2 → stage 3 for exactly this handle: the typed name must match exactly, the live payload
     * fingerprint must be unchanged, and the ticket is bound to the live route/credential binding now.
     */
    fun openFinal(handle: Long, liveFingerprint: String?, liveBinding: String): R2CConfirmationTicket? {
        val bound = draft ?: return null
        if (handle != this.handle || stage != R2CStage.TYPED_NAME) return null
        if (liveFingerprint != bound.fingerprint) {
            revoke(R2C_CONFIRMATION_DRAFT_CHANGED)
            return null
        }
        if (typed.isEmpty() || typed != bound.bucket) {
            error = R2C_CONFIRMATION_NAME_MISMATCH
            return null
        }
        stage = R2CStage.FINAL
        ticketBinding = liveBinding
        error = null
        val mintedHandle = this.handle
        val boundBinding = liveBinding
        val currentBinding = this.liveBinding
        return R2CConfirmationTicket(
            kind = bound.kind,
            bucket = bound.bucket,
            fingerprint = bound.fingerprint,
            handle = mintedHandle,
            liveBinding = liveBinding,
            stillValid = {
                // The owner re-reads its own current state and the caller's live binding here.
                this.handle == mintedHandle && this.stage == R2CStage.FINAL && draft?.fingerprint == bound.fingerprint &&
                    ticketBinding == boundBinding && currentBinding() == boundBinding
            },
        )
    }

    /** Explicit cancel, leaving the surface, or any editor/target/route/credential change. */
    fun revoke(reason: String? = null) {
        if (stage == R2CStage.NONE && draft == null) {
            error = reason
            return
        }
        handleSeq++
        handle = 0
        draft = null
        typed = ""
        ticketBinding = null
        stage = R2CStage.NONE
        error = reason
    }

    /** The current confirmation handle (0 when nothing is being confirmed). */
    val currentHandle: Long get() = handle

    /** True while a confirmation (any stage) is open for the given live fingerprint. */
    fun isOpenFor(liveFingerprint: String?): Boolean =
        stage != R2CStage.NONE && draft != null && draft?.fingerprint == liveFingerprint
}

/** Fixed message: the live draft changed, so the whole confirmation is withdrawn. */
const val R2C_CONFIRMATION_DRAFT_CHANGED: String =
    "输入、目标、地区、路由或凭据已变化，本次删除确认已撤销；请重新核对范围与输入桶名"

/** Fixed message: the typed bucket name is not exactly the target bucket. */
const val R2C_CONFIRMATION_NAME_MISMATCH: String =
    "输入的桶名与目标不完全一致（不做去空格/大小写容错），请精确输入完整桶名后再继续"
