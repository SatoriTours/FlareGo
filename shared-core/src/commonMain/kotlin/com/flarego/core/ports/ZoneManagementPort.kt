package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*

interface ZoneManagementPort {
    suspend fun list(account: AccountScope, page: PageRequest): CloudPage<ZoneDetails>
    suspend fun get(scope: ZoneScope): ZoneDetails
    suspend fun create(account: AccountScope, input: ZoneCreateInput): ZoneDetails
    suspend fun edit(scope: ZoneScope, input: ZoneEditInput): ZoneDetails
    suspend fun delete(scope: ZoneScope)
    suspend fun activationCheck(scope: ZoneScope)
}

/**
 * Credential-bound typed adapter. Read ownership/state before invoking [beforeSend],
 * then call it immediately before the actual typed send. No suspension or session
 * acquisition may intervene after the guard. The observation must come from a fresh
 * provider read; create has no existing Zone and passes null.
 */
interface ZoneManagementSession : ZoneManagementPort {
    suspend fun write(action: ZoneAction, beforeSend: (ZoneDetails?) -> Unit): ExecutionReceipt
}
