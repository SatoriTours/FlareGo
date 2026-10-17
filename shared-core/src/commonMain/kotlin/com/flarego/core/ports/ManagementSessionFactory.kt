package com.flarego.core.ports

import com.flarego.core.model.Connection

/** Credential acquisition belongs to the app; trailing nullable fields preserve two-argument callers. */
data class ManagementServices(val dns: DnsManagementSession, val zones: ZoneManagementSession,
    val files: DnsFilesSession? = null, val scan: DnsScanSession? = null, val dnsSettings: DnsZoneSettingsSession? = null,
    val dnssec: DnssecSession? = null, val zoneSettings: ZoneSettingsSession? = null)
fun interface ManagementSessionFactory { suspend fun create(connection: Connection): ManagementServices }
