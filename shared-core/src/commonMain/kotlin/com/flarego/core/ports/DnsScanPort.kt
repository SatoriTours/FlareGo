package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import kotlinx.serialization.json.JsonObject

interface DnsScanPort {
    suspend fun trigger(scope: ZoneScope): ExecutionReceipt
    suspend fun review(scope: ZoneScope): List<DnsScanCandidate>
    suspend fun apply(scope: ZoneScope, input: DnsScanReviewInput): DnsScanApplyOutcome
}
interface DnsScanSession : DnsScanPort {
    suspend fun observe(action: DnsScanAction): DnsScanSnapshot
    /** All owner/eligibility/review reads precede this final guard; no mutation if it throws. */
    suspend fun write(action: DnsScanAction, beforeSend: (DnsScanSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt
}
interface DnsZoneSettingsPort {
    suspend fun usage(scope: ZoneScope): DnsUsage
    suspend fun settings(scope: ZoneScope): DnsZoneSettings
    suspend fun updateSettings(scope: ZoneScope, changed: JsonObject): DnsZoneSettings
}
interface DnsZoneSettingsSession : DnsZoneSettingsPort {
    suspend fun observe(action: DnsZoneSettingsAction): DnsZoneSettingsSnapshot
    /** Complete all suspending preflight before callback, then send exactly once. */
    suspend fun write(action: DnsZoneSettingsAction, beforeSend: (DnsZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt
}
interface DnsScanAuthority {
    /** Verified current Cloudflare credential/scope write permission; never successful reads or local consent. */
    suspend fun mayWrite(scope: ZoneScope): Boolean? = null
}
interface DnsZoneSettingsAuthority {
    suspend fun mayWrite(scope: ZoneScope): Boolean? = null
    suspend fun eligibility(scope: ZoneScope): DnsSettingsEligibility = DnsSettingsEligibility()
}
