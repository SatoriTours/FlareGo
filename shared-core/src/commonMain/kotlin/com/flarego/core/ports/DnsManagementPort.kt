package com.flarego.core.ports

import com.flarego.core.application.dns.DnsWriteAction
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import kotlinx.serialization.json.JsonObject

data class DnsFilter(val name: String? = null, val type: String? = null, val content: String? = null, val comment: String? = null, val tags: List<String> = emptyList())

interface DnsManagementPort {
    suspend fun list(scope: ZoneScope, filter: DnsFilter, page: PageRequest): CloudPage<DnsRecordDocument>
    suspend fun get(scope: ZoneScope, recordId: String): DnsRecordDocument
    suspend fun create(scope: ZoneScope, input: DnsRecordInput): DnsRecordDocument
    suspend fun patch(scope: ZoneScope, recordId: String, fields: JsonObject): DnsRecordDocument
    suspend fun replace(scope: ZoneScope, recordId: String, input: DnsRecordInput): DnsRecordDocument
    suspend fun delete(scope: ZoneScope, recordId: String)
    /** Legacy implementations explicitly refuse; production/demo batch sessions must override. */
    suspend fun batch(scope: ZoneScope, input: DnsBatchInput): DnsBatchOutcome = throw com.flarego.core.model.batchUnavailable()
}

/** Trusted typed adapter: all suspending ownership/preflight reads precede the final guard. */
interface DnsManagementSession : DnsManagementPort {
    suspend fun zone(scope: ZoneScope): ZoneDetails
    suspend fun write(action: DnsWriteAction, beforeSend: (DnsRecordDocument?) -> Unit): ExecutionReceipt
}

/** A real batch adapter must implement every method; no default pretend handler. */
interface DnsBatchManagementSession : DnsManagementSession {
    /** Trusted adapter policy only. Defaults conservatively for legacy test sessions. */
    fun batchMaxOperations(scope:ZoneScope):Int = 200
    override suspend fun batch(scope: ZoneScope, input: DnsBatchInput): DnsBatchOutcome
    suspend fun observeBatch(scope: ZoneScope, input: DnsBatchInput): DnsBatchSnapshot
    suspend fun writeBatch(scope: ZoneScope, input: DnsBatchInput, beforeSend: (DnsBatchSnapshot) -> Unit): DnsBatchOutcome
}
