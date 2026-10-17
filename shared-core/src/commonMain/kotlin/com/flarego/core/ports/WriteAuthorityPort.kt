package com.flarego.core.ports

import com.flarego.core.model.*

/** A short transport session backed by one stable credential/session authority issuer. */
interface WriteAuthorityPort {
    suspend fun observe(scope: ZoneScope, operationId: String, forceRefresh: Boolean = false): WriteAuthorityObservation
    /** Explicit session invalidation; ordinary session reconstruction must not call this. */
    fun invalidate(connectionId: String)
}
