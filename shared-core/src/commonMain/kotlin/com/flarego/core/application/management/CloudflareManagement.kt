package com.flarego.core.application.management

import com.flarego.core.application.dns.DnsPagePort
import com.flarego.core.application.zones.ZonePagePort
import com.flarego.core.model.Connection
import com.flarego.core.model.*
import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.ChangePlan

/** Optional application UI entry. UI consumes state/intents, never credentials or confirmations. */
data class CloudflareManagement(val dns: DnsPagePort, val zones: ZonePagePort, val batch: DnsBatchPagePort? = null)

/** Snapshot of independent app selection and credentials, never derived from a preview. */
data class ManagementContext(val connection: Connection, val selectionEpoch: Long, val credentialRevision: Long)

/** Internal product-to-app bridge. Only the lifetime runtime issues confirmations. */
interface ManagementActions {
    suspend fun pendingManagement(scope: CloudScope): OperationTask?
    suspend fun prepareDns(action: DnsWriteAction, stillCurrent: () -> Boolean): PreparedDnsChange
    fun dnsPreview(id: String): DnsChangePreview
    suspend fun confirmDns(prepared: PreparedDnsChange): OperationTask
    suspend fun prepareZone(action: ZoneAction, stillCurrent: () -> Boolean): ChangePlan
    fun zonePreview(id: String): com.flarego.core.model.ZoneChangePreview
    suspend fun confirmZone(action: ZoneAction, plan: ChangePlan): OperationTask
    suspend fun reconcileManagement(scope: CloudScope, taskId: String, stillCurrent: () -> Boolean): OperationTask
}
