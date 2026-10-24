package com.flarego.ui.r2

/**
 * A test double of the real secure-window port.
 *
 * It models exactly what the production owner models: a purpose is held for a generation and the
 * protection is real only while that purpose is held. It never pretends to be secure when [grant] is
 * false, so a missing bridge is exercised as a refusal, not as an optimistic boolean.
 */
internal class FakeSecureWindowPort(
    override val generation: Long = 1L,
    var grant: Boolean = true,
) : SecureWindowPort {
    val purposes = mutableSetOf<SecurePurpose>()
    var requests = 0
    var releases = 0
    /** True models a window with an observable clean frame; false an unobservable one. */
    var barrier = true
    /** Handles armed for retirement but not yet released. */
    val armed = mutableSetOf<SecureWindowSession>()
    var contentRemovals = 0

    override fun request(purpose: SecurePurpose): SecureWindowSession? {
        if (!grant) return null
        requests++
        purposes += purpose
        return Session(purpose)
    }

    override fun armRetirement(session: SecureWindowSession): Boolean {
        if (!barrier) return false
        if (session.generation != generation) return false
        armed += session
        return true
    }

    override fun contentRemoved(session: SecureWindowSession) {
        if (!armed.remove(session)) return
        contentRemovals++
        release(session)
    }

    override fun release(session: SecureWindowSession) {
        if (session.generation != generation) return
        if (purposes.remove(session.purpose)) releases++
    }

    val isProtected: Boolean get() = grant && purposes.isNotEmpty()

    inner class Session(override val purpose: SecurePurpose) : SecureWindowSession {
        override val generation: Long get() = this@FakeSecureWindowPort.generation

        override fun isProtected(): Boolean =
            this@FakeSecureWindowPort.generation == generation &&
                grant && purpose in purposes
    }
}
