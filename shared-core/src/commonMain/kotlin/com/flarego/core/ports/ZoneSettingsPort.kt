package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.*
import kotlinx.serialization.json.JsonObject

interface ZoneSettingsPort {
    suspend fun get(scope: ZoneScope, setting: ZoneSettingKey): ZoneSettingValue
    suspend fun update(scope: ZoneScope, setting: ZoneSettingKey, input: JsonObject): ZoneSettingValue
    suspend fun universalSsl(scope: ZoneScope): UniversalSslSetting
    suspend fun updateUniversalSsl(scope: ZoneScope, enabled: Boolean): UniversalSslSetting
    suspend fun purge(scope: ZoneScope, input: CachePurgeInput): ExecutionReceipt
}

interface ZoneSettingsSession : ZoneSettingsPort {
    suspend fun observe(scope: ZoneScope, target: ZoneSettingsTarget): ZoneSettingsSnapshot
    /** Fresh ownership/eligibility reads precede beforeSend; no suspension after its return until send. */
    suspend fun write(action: ZoneSettingsAction, beforeSend: (ZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt
}
