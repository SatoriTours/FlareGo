package com.flarego.ui.r2

/**
 * The only purposes that may hold `FLAG_SECURE` in this application.
 *
 * They are separate on purpose: the pre-existing connection/token dialog and the batch B3 Sippy
 * credential editor must never clear each other's flag.
 */
enum class SecurePurpose {
    /** The pre-existing connect/token dialog purpose. */
    CONNECTION_TOKEN,

    /** Batch B3 Sippy credential editing. */
    SIPPY_CREDENTIALS,
}

/**
 * A synchronous, generation-bound protection handle for one purpose.
 *
 * [isProtected] reads the *current* real window state, so a caller that renders a secret only when
 * it returns true cannot open a first-frame window: the flag was already applied when [request]
 * returned. A handle from a previous window (an Activity/window recreation) reports false.
 */
interface SecureWindowSession {
    val purpose: SecurePurpose

    /** The window generation this handle was issued for. */
    val generation: Long

    /** True only while the real window currently carries the protection for this generation. */
    fun isProtected(): Boolean
}

/**
 * The one port through which a secure-window purpose is requested and released.
 *
 * The production implementation owns the real Android `Window` flags. A missing port (a platform
 * without a window, or a detached Activity) simply denies protection, and the caller must then keep
 * every secret input and every secret write disabled.
 */
interface SecureWindowPort {
    /** The generation of the window this port is bound to. */
    val generation: Long

    /** Requests [purpose]; null means "no protection available", never "pretend it is secure". */
    fun request(purpose: SecurePurpose): SecureWindowSession?

    /** Releases exactly one purpose handle; a foreign or stale handle is ignored. */
    fun release(session: SecureWindowSession)

    /**
     * Two-phase retirement, step 1: **arm** it. The protection is kept.
     *
     * Returns true when this window armed the retirement for exactly this handle. A window that cannot
     * observe a frame commit still keeps its protection (see [contentRemoved]); an unobservable window
     * never gets its flag dropped by this app — fail-closed means "keep protecting", not "release
     * immediately".
     */
    fun armRetirement(session: SecureWindowSession): Boolean = false

    /**
     * Two-phase retirement, step 2: the production composition no longer contains the sensitive
     * surface. From here the bound window must release the exact handle only after **a frame that no
     * longer contains that surface has been committed**; if no such commit can be observed the handle
     * stays protected.
     *
     * This call reports a composition fact, never a frame commit.
     */
    fun contentRemoved(session: SecureWindowSession) {}
}

/**
 * Pure `FLAG_SECURE` purpose ownership; the Android adapter applies the real window bit.
 *
 * Rules this type exists for:
 * - the flag is set when the *first* purpose is acquired, and cleared only when the *last* purpose
 *   is released and the flag was not already set before this owner first touched the window;
 * - releasing one purpose never clears another purpose's flag;
 * - [baselineSecure] records a flag that already existed, so leaving never clears a bit this owner
 *   does not own;
 * - no other window flag is ever read, added or cleared.
 *
 * [acquire] returns true when the caller must set the flag now, [release] returns true when the
 * caller must clear it now (and only then).
 */
class SecureWindowOwnership(val generation: Long, val baselineSecure: Boolean) {
    /**
     * One entry per purpose with the number of *live handles* for it. A purpose can be requested more
     * than once (a re-entered editor, the legacy boolean plus an explicit handle), and the flag must
     * stay until the last of those references is gone.
     */
    private val counts = LinkedHashMap<SecurePurpose, Int>()

    /** The purposes that currently have at least one live handle. */
    val purposes: Set<SecurePurpose> get() = counts.keys.toSet()

    val isHeld: Boolean get() = counts.isNotEmpty()

    fun liveHandles(purpose: SecurePurpose): Int = counts[purpose] ?: 0

    /** Registers one handle; true only when the caller must set the flag now (first live handle). */
    fun acquire(purpose: SecurePurpose): Boolean {
        val previous = counts[purpose] ?: 0
        val firstOverall = counts.isEmpty()
        counts[purpose] = previous + 1
        return firstOverall && !baselineSecure
    }

    /** Releases one handle; true only when the caller must clear the flag now (last live handle). */
    fun release(purpose: SecurePurpose): Boolean {
        val current = counts[purpose] ?: return false
        if (current <= 1) counts.remove(purpose) else counts[purpose] = current - 1
        return counts.isEmpty() && !baselineSecure
    }

    /** Releases every held handle (window teardown); returns whether the flag must be cleared. */
    fun close(): Boolean {
        if (counts.isEmpty()) return false
        counts.clear()
        return !baselineSecure
    }
}
