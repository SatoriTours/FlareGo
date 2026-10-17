package com.flarego.core.cloudflare.zones

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.ZoneManagementSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Uses the P1 gateway/transport, including fixed host and bounded response handling. */
class CloudflareZoneClient(private val connection: Connection, private val gateway: CloudflareGateway) : ZoneManagementSession {
    override suspend fun list(account: AccountScope, page: PageRequest): CloudPage<ZoneDetails> {
        ScopeGuard.requireOwned(account, connection)
        if (page.limit !in 5..50 || page.token is PageToken.Cursor) throw invalidInputFailure()
        val paging = PaginationStrategy.Number((page.token as? PageToken.Number)?.value ?: 1, page.limit)
        // The provider otherwise omits internal Zones, which would make absence checks unsafe.
        val root = readEnvelope(gateway, "zones-get", "/zones?account.id=${encodePath(account.accountId)}" +
            "&type=full,partial,secondary,internal&${Pagination.query(paging)}")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { decode(it as? JsonObject ?: throw invalidResponse(false), account, null, false) }
        val metadata = Pagination.decode(root, paging)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun get(scope: ZoneScope): ZoneDetails {
        ScopeGuard.requireOwned(scope, connection)
        val root = readEnvelope(gateway, "zones-0-get", locator(scope))
        return decode(root["result"] as? JsonObject ?: throw invalidResponse(false), scope.account, scope.zoneId, false)
    }

    override suspend fun create(account: AccountScope, input: ZoneCreateInput): ZoneDetails =
        perform(ZoneAction.Create(account, input)) {}.details ?: throw unknownResult()

    override suspend fun edit(scope: ZoneScope, input: ZoneEditInput): ZoneDetails =
        perform(ZoneAction.Edit(scope, input)) {}.details ?: throw unknownResult()

    override suspend fun delete(scope: ZoneScope) {
        // Destructive product confirmation is enforced by ZoneController. This is a typed transport port.
        val result = performDelete(scope) {}
        if (result.receipt !is ExecutionReceipt.Completed) throw unknownResult()
    }

    override suspend fun activationCheck(scope: ZoneScope) {
        val result = perform(ZoneAction.ActivationCheck(scope)) {}
        if (result.receipt is ExecutionReceipt.Unknown) throw unknownResult()
    }

    override suspend fun write(action: ZoneAction, beforeSend: (ZoneDetails?) -> Unit): ExecutionReceipt =
        try {
            perform(freezeZoneAction(action), beforeSend).receipt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            // A failure after a possible send is never a reason to replay it.
            if (failure.uncertain) ExecutionReceipt.Unknown() else throw failure
        }

    private data class Result(val details: ZoneDetails?, val receipt: ExecutionReceipt)

    private suspend fun perform(action: ZoneAction, beforeSend: (ZoneDetails?) -> Unit): Result {
        ScopeGuard.requireOwned(action.scope, connection)
        val frozen = freezeZoneAction(action)
        if (frozen is ZoneAction.Delete) {
            if (frozen.name != frozen.typedName || frozen.name.isBlank()) throw invalidInputFailure()
            return performDelete(frozen.scope) { observed ->
                if (observed?.name != frozen.name) throw scopeMismatchFailure()
                beforeSend(observed)
            }
        }
        val observed = (frozen.scope as? ZoneScope)?.let { get(it) }
        if (frozen is ZoneAction.ActivationCheck && observed?.status != "pending") throw invalidInputFailure()
        val request = when (frozen) {
            is ZoneAction.Create -> HttpRequest("POST", "/zones", buildJsonObject {
                put("account", buildJsonObject { put("id", frozen.scope.accountId) })
                put("name", frozen.input.name)
                put("type", frozen.input.type)
            }.toString())
            is ZoneAction.Edit -> HttpRequest("PATCH", locator(frozen.scope), zoneEditFields(frozen.input).toString())
            is ZoneAction.ActivationCheck -> HttpRequest("PUT", "${locator(frozen.scope)}/activation_check")
            is ZoneAction.Delete -> throw invalidInputFailure()
        }
        // Everything that can suspend (session acquisition and ownership read) is before this guard.
        beforeSend(observed)
        val response = send(zoneActionKey(frozen), request)
        val root = envelope(response)
        if (response.status == 202) return Result(null, ExecutionReceipt.Accepted())
        return when (frozen) {
            is ZoneAction.Create -> {
                val details = decode(resultObject(root), frozen.scope, null, true)
                if (!details.name.equals(frozen.input.name, ignoreCase = true) || details.type != frozen.input.type) throw invalidResponse(true)
                Result(details, ExecutionReceipt.Completed())
            }
            is ZoneAction.Edit -> {
                val details = decode(resultObject(root), frozen.scope.account, frozen.scope.zoneId, true)
                if (details.name != observed?.name || !matchesEdit(details, frozen.input)) throw invalidResponse(true)
                Result(details, ExecutionReceipt.Completed())
            }
            is ZoneAction.ActivationCheck -> {
                requireResultId(root, frozen.scope)
                // This endpoint only triggers a check; it proves neither activation nor propagation.
                Result(null, ExecutionReceipt.Accepted())
            }
            is ZoneAction.Delete -> throw invalidInputFailure()
        }
    }

    private suspend fun performDelete(scope: ZoneScope, beforeSend: (ZoneDetails?) -> Unit): Result {
        ScopeGuard.requireOwned(scope, connection)
        val observed = get(scope) // Independent account/Zone ownership, even for direct port calls.
        val request = HttpRequest("DELETE", locator(scope))
        beforeSend(observed)
        val response = send("zones-0-delete", request)
        val root = envelope(response)
        if (response.status == 202) return Result(null, ExecutionReceipt.Accepted())
        requireResultId(root, scope)
        return Result(null, ExecutionReceipt.Completed())
    }

    private suspend fun send(key: String, request: HttpRequest): HttpResponse = try {
        gateway.send(key, request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CloudFailure) {
        throw failure
    } catch (_: Exception) {
        throw unknownResult()
    }

    private fun envelope(response: HttpResponse): JsonObject {
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        return root
    }

    private fun resultObject(root: JsonObject) = root["result"] as? JsonObject ?: throw invalidResponse(true)
    private fun requireResultId(root: JsonObject, scope: ZoneScope) {
        if (resultObject(root).text("id", true) != scope.zoneId) throw invalidResponse(true)
    }

    private fun decode(row: JsonObject, account: AccountScope, zoneId: String?, writing: Boolean): ZoneDetails {
        val owner = row["account"] as? JsonObject ?: throw invalidResponse(writing)
        if (owner.text("id", writing) != account.accountId) {
            if (writing) throw invalidResponse(true) else throw scopeMismatchFailure()
        }
        val id = row.text("id", writing)
        if (zoneId != null && id != zoneId) {
            if (writing) throw invalidResponse(true) else throw scopeMismatchFailure()
        }
        val paused = row["paused"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw invalidResponse(writing)
        } ?: false
        val servers = row["name_servers"]?.let { value ->
            (value as? JsonArray)?.map { it.stringValue(writing) } ?: throw invalidResponse(writing)
        } ?: emptyList()
        return ZoneDetails(ZoneScope(account, id), row.text("name", writing), row.optionalText("status", writing) ?: "unknown",
            paused, row.optionalText("type", writing) ?: "unknown", servers,
            Json.parseToJsonElement(row.toString()).jsonObject)
    }

    private fun matchesEdit(details: ZoneDetails, input: ZoneEditInput): Boolean = when {
        input.paused != null -> details.raw["paused"] == JsonPrimitive(input.paused)
        input.type != null -> details.type == input.type
        else -> details.raw["vanity_name_servers"] == JsonArray(input.vanityNameservers.orEmpty().map(::JsonPrimitive))
    }
}

private fun locator(scope: ZoneScope) = "/zones/${encodePath(scope.zoneId)}"
private fun JsonObject.text(key: String, writing: Boolean) = this[key]?.stringValue(writing) ?: throw invalidResponse(writing)
private fun JsonObject.optionalText(key: String, writing: Boolean): String? = this[key]?.stringValue(writing)
private fun JsonElement.stringValue(writing: Boolean) = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?.takeIf { it.isNotBlank() } ?: throw invalidResponse(writing)
private fun unknownResult() = CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "操作结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
