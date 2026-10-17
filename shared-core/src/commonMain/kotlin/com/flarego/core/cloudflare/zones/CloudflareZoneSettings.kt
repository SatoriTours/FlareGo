package com.flarego.core.cloudflare.zones

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.ZoneSettingsSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** All traffic uses the P1 fixed-host bounded transport; asset URLs are body data only. */
class CloudflareZoneSettings(private val connection: Connection, private val gateway: CloudflareGateway,
    private val authority: ZoneSettingsAuthority = object : ZoneSettingsAuthority {}) : ZoneSettingsSession {
    private val zones = CloudflareZoneClient(connection, gateway)

    override suspend fun get(scope: ZoneScope, setting: ZoneSettingKey): ZoneSettingValue =
        observe(scope, ZoneSettingsTarget.Setting(setting)).setting ?: throw invalidResponse(false)
    override suspend fun universalSsl(scope: ZoneScope): UniversalSslSetting =
        observe(scope, ZoneSettingsTarget.UniversalSsl).universal ?: throw invalidResponse(false)
    override suspend fun update(scope: ZoneScope, setting: ZoneSettingKey, input: JsonObject): ZoneSettingValue =
        perform(ZoneSettingsAction.Update(scope, setting, input), {}, {}).snapshot?.setting ?: throw settingsUnknown()
    override suspend fun updateUniversalSsl(scope: ZoneScope, enabled: Boolean): UniversalSslSetting =
        perform(ZoneSettingsAction.UniversalSsl(scope, enabled), {}, {}).snapshot?.universal ?: throw settingsUnknown()
    override suspend fun purge(scope: ZoneScope, input: CachePurgeInput): ExecutionReceipt =
        perform(ZoneSettingsAction.Purge(scope, input), {}, {}).receipt

    override suspend fun observe(scope: ZoneScope, target: ZoneSettingsTarget): ZoneSettingsSnapshot {
        ScopeGuard.requireOwned(scope, connection)
        val owner = zones.get(scope) // Returned account/id proof, never just local ScopeGuard.
        val setting = (target as? ZoneSettingsTarget.Setting)?.let {
            val root = readEnvelope(gateway, it.key.readOperation, settingPath(scope, it.key))
            decodeSetting(root, scope, it.key, false)
        }
        val universal = if (target == ZoneSettingsTarget.UniversalSsl) {
            decodeUniversal(readEnvelope(gateway, UNIVERSAL_SSL_READ, universalPath(scope)), scope, false)
        } else null
        val operation = when (target) {
            is ZoneSettingsTarget.Setting -> target.key.writeOperation
            ZoneSettingsTarget.UniversalSsl -> UNIVERSAL_SSL_WRITE
            ZoneSettingsTarget.Cache -> "zone-purge"
        }
        val editable = setting?.editable ?: universal?.editable
        val entitlement = authority.eligible(scope, operation)
        val qualification = when {
            entitlement == false || editable == false -> false
            target == ZoneSettingsTarget.Cache -> true // All plans support these modes; unknown tier still uses 100.
            else -> editable ?: entitlement
        }
        val permission = authority.mayWrite(scope, operation)
        val allowed = when {
            editable == false || qualification == false || permission == false -> false
            qualification == true && permission == true -> true
            else -> null
        }
        return ZoneSettingsSnapshot(owner, setting, universal, allowed,
            if (target == ZoneSettingsTarget.Cache) authority.purgeTier(scope) else null)
    }

    override suspend fun write(action: ZoneSettingsAction, beforeSend: (ZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt =
        perform(action, beforeSend, afterRead).receipt

    private data class Result(val receipt: ExecutionReceipt, val snapshot: ZoneSettingsSnapshot? = null)
    private suspend fun perform(action: ZoneSettingsAction, beforeSend: (ZoneSettingsSnapshot) -> Unit, afterRead: () -> Unit): Result {
        val frozen = freezeSettingsAction(action)
        ScopeGuard.requireOwned(frozen.scope, connection)
        val target = settingsTarget(frozen)
        val observed = observe(frozen.scope, target)
        requireSettingsWritable(frozen, observed)
        val request = when (frozen) {
            is ZoneSettingsAction.Update -> HttpRequest("PATCH", settingPath(frozen.scope, frozen.key), frozen.input.toString())
            is ZoneSettingsAction.UniversalSsl -> HttpRequest("PATCH", universalPath(frozen.scope), settingsInput(frozen).toString())
            is ZoneSettingsAction.Purge -> HttpRequest("POST", "/zones/${encodePath(frozen.scope.zoneId)}/purge_cache", cachePurgeBody(frozen.input, observed.purgeTier).toString())
        }
        // Session acquisition, owner/setting reads, entitlement checks and body validation precede this guard.
        beforeSend(observed)
        return try {
            val response = gateway.send(settingsOperation(frozen), request)
            val root = ResponseDecoders.envelope(response, writing = true)
            requireCompleteEnvelope(root, true)
            if (frozen is ZoneSettingsAction.Purge) {
                val id = ((root["result"] as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (id.isNullOrBlank()) throw invalidResponse(true)
                // HTTP 200 only acknowledges receipt, never cache presence/eviction or global propagation.
                Result(ExecutionReceipt.Accepted())
            } else if (response.status == 202) {
                if (root["result"] != null && root["result"] != JsonNull) when (frozen) {
                    is ZoneSettingsAction.Update -> decodeSetting(root, frozen.scope, frozen.key, true)
                    is ZoneSettingsAction.UniversalSsl -> decodeUniversal(root, frozen.scope, true)
                }
                Result(ExecutionReceipt.Accepted())
            } else {
                when (frozen) {
                    is ZoneSettingsAction.Update -> {
                        val reply = decodeSetting(root, frozen.scope, frozen.key, true)
                        if (!settingProjectionMatches(reply.value, frozen.input.getValue("value"), settingPartialObjectPatch(frozen.key))) throw invalidResponse(true)
                    }
                    is ZoneSettingsAction.UniversalSsl -> if (decodeUniversal(root, frozen.scope, true).enabled != frozen.enabled) throw invalidResponse(true)
                }
                val readback = observe(frozen.scope, target)
                afterRead()
                val matches = when (frozen) {
                    is ZoneSettingsAction.Update -> readback.setting?.let {
                        it.scope == frozen.scope && it.key == frozen.key && settingProjectionMatches(it.value, frozen.input.getValue("value"), settingPartialObjectPatch(frozen.key))
                    } == true
                    is ZoneSettingsAction.UniversalSsl -> readback.universal?.let { it.scope == frozen.scope && it.enabled == frozen.enabled } == true
                }
                if (matches) Result(ExecutionReceipt.Completed(), readback) else Result(ExecutionReceipt.Unknown())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A possible send has occurred. Malformed/partial replies, permission errors,
            // readback failures and timeouts cannot prove execution or justify a retry.
            Result(ExecutionReceipt.Unknown())
        }
    }

    private fun decodeSetting(root: JsonObject, scope: ZoneScope, key: ZoneSettingKey, writing: Boolean): ZoneSettingValue {
        requireCompleteEnvelope(root, writing)
        val row = root["result"] as? JsonObject ?: throw invalidResponse(writing)
        if ((row["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content != key.responseId) throw invalidResponse(writing)
        val value = row["value"] ?: throw invalidResponse(writing)
        if (value == JsonNull) throw invalidResponse(writing)
        return ZoneSettingValue(scope, key, value, editable(row, writing), Json.parseToJsonElement(row.toString()).jsonObject)
    }
    private fun decodeUniversal(root: JsonObject, scope: ZoneScope, writing: Boolean): UniversalSslSetting {
        requireCompleteEnvelope(root, writing)
        val row = root["result"] as? JsonObject ?: throw invalidResponse(writing)
        val enabled = (row["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw invalidResponse(writing)
        return UniversalSslSetting(scope, enabled, editable(row, writing), Json.parseToJsonElement(row.toString()).jsonObject)
    }
    private fun editable(row: JsonObject, writing: Boolean): Boolean? = row["editable"]?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull ?: throw invalidResponse(writing)
    }
    private fun requireCompleteEnvelope(root: JsonObject, writing: Boolean) {
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true || (root["errors"] as? JsonArray)?.isEmpty() != true) throw invalidResponse(writing)
    }
}

private fun settingPath(scope: ZoneScope, key: ZoneSettingKey) = "/zones/${encodePath(scope.zoneId)}/settings/${key.route}"
private fun universalPath(scope: ZoneScope) = "/zones/${encodePath(scope.zoneId)}/ssl/universal/settings"
private fun settingsUnknown() = CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "配置结果尚未确认，请先核对当前设置，不要重发", uncertain = true)
