package com.flarego.core.application.operations

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.model.*
import com.flarego.core.ports.CloudSession
import com.flarego.core.ports.FingerprintPort
import kotlinx.serialization.json.*

/**
 * A typed DNS command. The lifecycle accepts no URL, header map or arbitrary body:
 * everything it needs is the validated draft and an explicit zone/record identity.
 */
data class DnsWriteCommand(
    val actionKey: String,
    val scope: ZoneScope,
    val resourceRef: ResourceRef?,
    val recordId: String?,
    val draft: DnsDraft,
    val frozenBefore: DnsRecord?,
    val desiredHash: String?,
    val requestFingerprint: String,
    val credentialRevision: Long,
)

/** Receipt-aware typed DNS port. The real product implements it in P2. */
fun interface DnsReceiptSource {
    suspend fun write(command: DnsWriteCommand): ExecutionReceipt
}

/**
 * Legacy adapter: the old Unit API cannot express accepted or partial results, so
 * the receipt is [ExecutionReceipt.Unknown] — never completion.
 */
class LegacyDnsReceiptSource(private val session: suspend () -> CloudSession?) : DnsReceiptSource {
    override suspend fun write(command: DnsWriteCommand): ExecutionReceipt {
        val api = session() ?: return ExecutionReceipt.Unknown()
        when (command.actionKey) {
            "dns-records-for-a-zone-create-dns-record" -> api.save(command.scope.zoneId, null, command.draft)
            "dns-records-for-a-zone-patch-dns-record" -> api.save(command.scope.zoneId, command.recordId, command.draft)
            else -> api.delete(command.scope.zoneId, command.recordId.orEmpty())
        }
        // The old Unit API cannot express accepted or partial results: information is
        // lost, so this is Unknown and never completion.
        return ExecutionReceipt.Unknown()
    }
}

/** Trusted zone ownership. Unknown ownership must refuse, producing zero writes. */
fun interface ZoneOwnerCheck {
    fun owns(scope: ZoneScope): Boolean
}

/**
 * The real DNS consumer of the confirmed-write path.
 *
 * It derives every trusted value from [OperationContextResolver.current] and the
 * frozen input (never from a plan or from a requested scope), which is why the
 * current input fingerprint must agree with the command fingerprint before anything
 * is prepared.
 */
class DnsOperationLifecycle(
    private val resolver: OperationContextResolver,
    private val plans: ChangePlans,
    private val coordinator: OperationCoordinator,
    private val clock: OperationClock,
    private val fingerprint: FingerprintPort,
    private val owner: ZoneOwnerCheck,
    private val receipts: DnsReceiptSource,
) {
    suspend fun create(zone: ZoneScope, draft: DnsDraft, frozenBefore: DnsRecord? = null): OperationTask =
        run(ACTION_CREATE, zone, null, draft, frozenBefore)

    suspend fun update(zone: ZoneScope, recordId: String, draft: DnsDraft, frozenBefore: DnsRecord? = null): OperationTask =
        run(ACTION_PATCH, zone, recordId, draft, frozenBefore)

    suspend fun delete(zone: ZoneScope, recordId: String, frozenBefore: DnsRecord? = null): OperationTask =
        run(ACTION_DELETE, zone, recordId, DnsDraft("DELETE", "", ""), frozenBefore)

    private suspend fun run(
        actionKey: String,
        zone: ZoneScope,
        recordId: String?,
        draft: DnsDraft,
        frozenBefore: DnsRecord?,
    ): OperationTask {
        if (!owner.owns(zone)) throw invalidInputFailure()
        val live = resolver.current() ?: throw invalidInputFailure()
        if (live.connection.id != zone.connectionId) throw invalidInputFailure()
        val resourceRef =
            recordId?.let { ResourceRef(zone, "dns_record", it) }
        val command =
            DnsWriteCommand(
                actionKey = actionKey,
                scope = zone,
                resourceRef = resourceRef,
                recordId = recordId,
                draft = draft,
                frozenBefore = frozenBefore,
                desiredHash = fingerprint.sha256(CanonicalJson.encode(desired(recordId, draft, live))),
                requestFingerprint = fingerprint.sha256(CanonicalJson.encode(commandInput(actionKey, zone, recordId, draft, live))),
                credentialRevision = live.credentialRevision,
            )
        // The trusted current input must be the one this command was built from.
        if (live.requestFingerprint != command.requestFingerprint) throw invalidInputFailure()
        val plan =
            plans.prepare(
                scope = zone,
                actionKey = actionKey,
                resourceRef = resourceRef,
                beforeHash = frozenBefore?.let { fingerprint.sha256(CanonicalJson.encode(before(it))) },
                desiredHash = command.desiredHash,
                requestFingerprint = command.requestFingerprint,
                credentialRevision = live.credentialRevision,
                nowMs = clock.nowMs(),
            )
        val confirmed =
            plans.confirm(
                planId = plan.id,
                currentScope = zone,
                credentialRevision = live.credentialRevision,
                requestFingerprint = command.requestFingerprint,
                nowMs = clock.nowMs(),
            )
        return coordinator.execute(confirmed) { receipts.write(command) }
    }

    private fun commandInput(
        actionKey: String,
        zone: ZoneScope,
        recordId: String?,
        draft: DnsDraft,
        live: LiveOperationContext,
    ): JsonElement =
        buildJsonObject {
            put("action", actionKey)
            put("connection", live.connection.id)
            put("zone", zone.zoneId)
            put("account", zone.account.accountId)
            put("record", recordId ?: "")
            put("draft", draftOf(draft))
            put("fingerprint", live.requestFingerprint)
        }

    private fun desired(recordId: String?, draft: DnsDraft, live: LiveOperationContext): JsonElement =
        buildJsonObject {
            put("connection", live.connection.id)
            put("type", draft.type)
            put("name", draft.name)
            put("content", draft.content)
            put("ttl", draft.ttl)
            put("proxied", draft.proxied)
            draft.priority?.let { put("priority", it) }
            put("record", recordId ?: "")
        }

    private fun before(record: DnsRecord): JsonElement =
        buildJsonObject {
            put("id", record.id)
            put("type", record.type)
            put("name", record.name)
            put("content", record.content)
            put("ttl", record.ttl)
            put("proxied", record.proxied)
        }

    private fun draftOf(draft: DnsDraft): JsonElement =
        buildJsonObject {
            put("type", draft.type)
            put("name", draft.name)
            put("content", draft.content)
            put("ttl", draft.ttl)
            put("proxied", draft.proxied)
            draft.priority?.let { put("priority", it) }
        }

    companion object {
        const val ACTION_CREATE: String = "dns-records-for-a-zone-create-dns-record"
        const val ACTION_PATCH: String = "dns-records-for-a-zone-patch-dns-record"
        const val ACTION_DELETE: String = "dns-records-for-a-zone-delete-dns-record"
    }
}

/** A reviewed relative locator for a zone-scoped record, used by receipt-aware sources. */
internal fun dnsRecordLocator(zone: ZoneScope, recordId: String?): String =
    "/zones/${encodePath(zone.zoneId)}/dns_records" + (recordId?.let { "/${encodePath(it)}" } ?: "")
