package com.flarego.core.cloudflare.dns

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.cloudflare.zones.CloudflareZoneClient
import com.flarego.core.model.*
import com.flarego.core.ports.DnssecSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Only fixed-host bounded P1 transport. No writes are retried; no registrar traffic is made. */
class CloudflareDnssecClient(private val connection: Connection, private val gateway: CloudflareGateway,
    private val authority: DnssecAuthority = object : DnssecAuthority {}) : DnssecSession {
    private val zones = CloudflareZoneClient(connection, gateway)
    override suspend fun get(scope: ZoneScope): DnssecDetails {
        owner(scope)
        return readDetails(scope)
    }
    override suspend fun zsks(scope: ZoneScope): List<DnssecKey> {
        owner(scope)
        val root = envelope(gateway.send("dnssec-list-dnssec-zsks", HttpRequest("GET", "${path(scope)}/zsk")), false)
        return (root["result"] as? JsonArray ?: throw dnssecInvalid(false)).map { decodeDnssecKey(scope, it as? JsonObject ?: throw dnssecInvalid(false)) }
    }
    override suspend fun observe(scope: ZoneScope, mutation: DnssecMutation): DnssecSnapshot {
        val actualOwner = owner(scope)
        val details = readDetails(scope)
        return DnssecSnapshot(actualOwner, details, authority.mayWrite(scope, mutation), authority.nsec3Available(scope))
    }
    override suspend fun update(scope: ZoneScope, input: DnssecUpdateInput): DnssecDetails =
        perform(DnssecAction.Update(scope, input), {}, {}).details ?: throw dnssecInvalid(true)
    override suspend fun delete(scope: ZoneScope) {
        if (perform(DnssecAction.Delete(scope), {}, {}).receipt !is ExecutionReceipt.Completed) throw dnssecInvalid(true)
    }
    override suspend fun write(action: DnssecAction, beforeSend: (DnssecSnapshot) -> Unit, afterRead: () -> Unit): ExecutionReceipt =
        perform(action, beforeSend, afterRead).receipt

    private data class Result(val receipt: ExecutionReceipt, val details: DnssecDetails? = null)
    private suspend fun perform(action: DnssecAction, beforeSend: (DnssecSnapshot) -> Unit, afterRead: () -> Unit): Result {
        val fields = dnssecFields(action) // Validate the actual typed input before any I/O.
        ScopeGuard.requireOwned(action.scope, connection)
        val observed = observe(action.scope, dnssecMutation(action))
        requireDnssecWritable(action, observed)
        val request = when (action) {
            is DnssecAction.Update -> HttpRequest("PATCH", path(action.scope), fields.toString())
            is DnssecAction.Delete -> HttpRequest("DELETE", path(action.scope))
        }
        // All suspending session/owner/DNSSEC reads and body checks are before this final guard.
        beforeSend(observed)
        return try {
            val response = gateway.send(dnssecMutation(action).operationId, request)
            val root = envelope(response, true)
            val reply = when (action) {
                is DnssecAction.Update -> decodeDnssecDetails(action.scope, root["result"] as? JsonObject ?: throw dnssecInvalid(true), true)
                is DnssecAction.Delete -> {
                    if ((root["result"] as? JsonPrimitive)?.isString != true) throw dnssecInvalid(true)
                    null // Official delete result is a string, not an id or a details object.
                }
            }
            if (reply != null && !matchesOrPending(action as DnssecAction.Update, reply)) throw dnssecInvalid(true)
            val readback = observe(action.scope, dnssecMutation(action)).details
            afterRead()
            val pending = reply?.status in dnssecPendingStatuses || readback.status in dnssecPendingStatuses
            val matches = when (action) {
                is DnssecAction.Update -> matchesOrPending(action, readback)
                is DnssecAction.Delete -> readback.status in setOf("disabled", "pending-disabled")
            }
            when {
                !matches -> Result(ExecutionReceipt.Unknown())
                pending || response.status == 202 -> Result(ExecutionReceipt.Accepted(), readback)
                else -> Result(ExecutionReceipt.Completed(), readback)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result(ExecutionReceipt.Unknown()) }
    }
    private fun matchesOrPending(action: DnssecAction.Update, actual: DnssecDetails): Boolean {
        if (actual.status !in setOf("active", "disabled", "pending", "pending-disabled")) return false
        val desired = action.input.fields()
        return desired.all { (name, value) ->
            if (name != "status") actual.raw[name] == value
            else actual.raw[name] == value || value == JsonPrimitive("active") && actual.status == "pending" ||
                value == JsonPrimitive("disabled") && actual.status == "pending-disabled"
        }
    }
    private suspend fun owner(scope: ZoneScope): ZoneDetails {
        requireDnssecScope(scope)
        ScopeGuard.requireOwned(scope, connection)
        return zones.get(scope) // Independent provider account/id evidence, not just local binding.
    }
    private suspend fun readDetails(scope: ZoneScope): DnssecDetails {
        val root = envelope(gateway.send("dnssec-dnssec-details", HttpRequest("GET", path(scope))), false)
        return decodeDnssecDetails(scope, root["result"] as? JsonObject ?: throw dnssecInvalid(false), false)
    }
    private fun envelope(response: HttpResponse, writing: Boolean): JsonObject {
        if (response.status in 200..299 && response.status != 200 && !(writing && response.status == 202)) throw dnssecInvalid(writing)
        val root = ResponseDecoders.envelope(response, writing)
        if ((root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull != true ||
            (root["errors"] as? JsonArray)?.isEmpty() != true || root["messages"] !is JsonArray) throw dnssecInvalid(writing)
        return root
    }
}
private fun path(scope: ZoneScope) = "/zones/${encodePath(scope.zoneId)}/dnssec"
