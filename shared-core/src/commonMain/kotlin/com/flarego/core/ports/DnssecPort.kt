package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*

interface DnssecPort {
    suspend fun get(scope: ZoneScope): DnssecDetails
    suspend fun update(scope: ZoneScope, input: DnssecUpdateInput): DnssecDetails
    suspend fun delete(scope: ZoneScope)
    suspend fun zsks(scope: ZoneScope): List<DnssecKey>
}
interface DnssecSession : DnssecPort {
    suspend fun observe(scope: ZoneScope, mutation: DnssecMutation): DnssecSnapshot
    /** Ownership, qualification and provider reads precede the final non-suspending send guard. */
    suspend fun write(action: DnssecAction, beforeSend: (DnssecSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt
}
