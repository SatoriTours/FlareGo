package com.flarego.core.application.management

import com.flarego.core.model.*
import kotlinx.serialization.json.JsonElement

/** Actual user domain input. No UI state, payload restoration or confirmation authority. */
sealed interface ExtendedManagementCommand {
    val scope: ZoneScope
    data class Import(override val scope: ZoneScope, val file: DnsFileRef, val proxied: Boolean) : ExtendedManagementCommand
    data class Scan(val action: DnsScanAction) : ExtendedManagementCommand { override val scope get() = action.scope }
    data class DnsSettings(val action: DnsZoneSettingsAction) : ExtendedManagementCommand { override val scope get() = action.scope }
    data class Dnssec(val action: DnssecAction) : ExtendedManagementCommand { override val scope get() = action.scope }
    data class ZoneSettings(val action: ZoneSettingsAction) : ExtendedManagementCommand { override val scope get() = action.scope }
}
sealed interface ExtendedChangeDetails {
    data class Import(val preview: DnsImportPreview) : ExtendedChangeDetails
    data class Scan(val preview: DnsScanPreview) : ExtendedChangeDetails
    data class DnsSettings(val preview: DnsZoneSettingsPreview) : ExtendedChangeDetails
    data class Dnssec(val preview: DnssecPreview, val before: DnssecSnapshot? = null, val after: DnssecDetails? = null) : ExtendedChangeDetails
    data class ZoneSettings(val preview: ZoneSettingsPreview, val before: ZoneSettingsSnapshot? = null, val after: JsonElement? = null) : ExtendedChangeDetails
}
data class ExtendedManagementPreview(val planId: String, val revision: Long, val scope: ZoneScope,
    val actionKey: String, val resourceRef: ResourceRef, val details: ExtendedChangeDetails)
data class ExtendedRiskChoices(val dnssec: Set<DnssecRisk> = emptySet(), val dnsSettings: Set<DnsSettingsRisk> = emptySet(),
    val zoneSettings: ZoneSettingsRisk? = null)
enum class ExtendedManagementFamily { FILES, SCAN, DNS_SETTINGS, DNSSEC, ZONE_SETTINGS }
sealed interface ExtendedManagementRead {
    data object Files : ExtendedManagementRead
    data object ScanReview : ExtendedManagementRead
    data object Usage : ExtendedManagementRead
    data object DnsSettings : ExtendedManagementRead
    data class Dnssec(val mutation: DnssecMutation = DnssecMutation.UPDATE) : ExtendedManagementRead
    data object DnssecKeys : ExtendedManagementRead
    data class ZoneSettings(val target: ZoneSettingsTarget) : ExtendedManagementRead
}
sealed interface ExtendedManagementReadResult {
    data class Files(val snapshot: DnsFileImportState, val readback: DnsImportReadback? = null) : ExtendedManagementReadResult
    data class ScanReview(val snapshot: DnsScanSnapshot) : ExtendedManagementReadResult
    data class Usage(val usage: DnsUsage) : ExtendedManagementReadResult
    data class DnsSettings(val snapshot: DnsZoneSettingsSnapshot) : ExtendedManagementReadResult
    data class Dnssec(val snapshot: DnssecSnapshot) : ExtendedManagementReadResult
    data class DnssecKeys(val keys: List<DnssecKey>) : ExtendedManagementReadResult
    data class ZoneSettings(val snapshot: ZoneSettingsSnapshot) : ExtendedManagementReadResult
}
interface ExtendedManagementActions {
    suspend fun prepareExtended(command: ExtendedManagementCommand, revision: Long, stillCurrent: () -> Boolean): ExtendedManagementPreview
    suspend fun confirmExtended(planId: String, revision: Long, risks: ExtendedRiskChoices = ExtendedRiskChoices()): OperationTask
    suspend fun importOutcome(scope: ZoneScope, taskId: String): DnsImportOutcome? = null
    fun invalidateExtended(scope: ZoneScope)
    suspend fun readExtended(scope: ZoneScope, request: ExtendedManagementRead, stillCurrent: () -> Boolean): ExtendedManagementReadResult
    suspend fun exportDns(scope: ZoneScope, target: DnsExportTarget, stillCurrent: () -> Boolean): DnsExportResult
    suspend fun pendingExtended(scope: ZoneScope, family: ExtendedManagementFamily, target: ZoneSettingsTarget? = null): List<OperationTask>
    suspend fun reconcileExtended(scope: ZoneScope, taskId: String, stillCurrent: () -> Boolean): OperationTask
}
internal fun extendedUnavailable() = CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "此管理端口尚未接入当前会话，当前不可执行")
