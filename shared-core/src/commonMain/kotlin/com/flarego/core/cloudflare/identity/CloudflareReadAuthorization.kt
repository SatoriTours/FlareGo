package com.flarego.core.cloudflare.identity

import com.flarego.core.application.connection.*
import com.flarego.core.application.inventory.kind
import com.flarego.core.cloudflare.billing.CloudflareBillingClient
import com.flarego.core.cloudflare.dns.CloudflareDnsClient
import com.flarego.core.cloudflare.inventory.CloudflareDirectoryClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.cloudflare.zones.CloudflareZoneSettings
import com.flarego.core.model.*
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.WorkersDirectoryMode
import com.flarego.core.ports.DnsFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Read-only proof for an exact connected account; never probes another account. */
class CloudflareReadAuthorization(private val connection: Connection, gateway: CloudflareGateway) {
    private val account = AccountScope(connection.id, connection.accountId)
    private val identity = CloudflareIdentityClient(gateway)
    private val directory = CloudflareDirectoryClient(connection, gateway)
    private val legacyWorkers = CloudflareDirectoryClient(connection, gateway, WorkersDirectoryMode.LEGACY)
    private val dns = CloudflareDnsClient(connection, gateway)
    private val settings = CloudflareZoneSettings(connection, gateway)
    private val billing = CloudflareBillingClient(connection, gateway)

    suspend fun tokenIdentity(kind: TokenKind): RetiredToken {
        val verified = identity.verifyToken(kind, account.accountId.takeIf { kind == TokenKind.ACCOUNT_OWNED })
        if (verified.active != true) throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "Token 已失效。")
        val id = verified.id?.takeIf(::isCloudflareId) ?: throw invalidResponse(false)
        return RetiredToken(id, kind)
    }

    suspend fun existingTokenIdentity(): RetiredToken {
        try {
            return try { tokenIdentity(TokenKind.USER_OWNED) }
            catch (failure: CloudFailure) {
                // A different token type can reject or lack the user endpoint. A malformed
                // success is not evidence that the credential is account-owned.
                if (failure.code !in setOf(CloudFailureCode.CREDENTIAL_EXPIRED, CloudFailureCode.PERMISSION_REQUIRED,
                        CloudFailureCode.NOT_FOUND)) throw failure
                tokenIdentity(TokenKind.ACCOUNT_OWNED)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw OldTokenIdentityUnavailable() }
    }

    suspend fun baseline(requested: Set<ReadTarget>): Map<ReadTarget, Set<String>> = withinBudget {
        val zones = ReadTarget(ReadFeature.ZONES)
        val zoneRead = runCatching { read(zones) }
        val failure = zoneRead.exceptionOrNull()
        if (failure != null && (failure as? CloudFailure)?.code != CloudFailureCode.PERMISSION_REQUIRED) throw failure
        val visible = zoneRead.getOrDefault(emptySet())
        val requiredZones = requested.mapNotNull { it.zoneId }.toSet()
        // A requested zone must belong to this account; scoped DNS/settings APIs alone don't prove ownership.
        if (!visible.containsAll(requiredZones)) throw scopeMismatchFailure()
        val zonal = visible.flatMap { zone -> ReadFeature.entries.filter { it.zonal }.map { ReadTarget(it, zone) } }
        val targets = ReadFeature.entries.filterNot { it.zonal }.map { ReadTarget(it) }.toSet() + zonal + requested
        readUpgradeEvidence(requested, targets) { if (it == zones) zoneRead.getOrThrow() else read(it) }
    }

    suspend fun confirm(evidence: Map<ReadTarget, Set<String>>) = withinBudget {
        // Even when the old credential could not read account metadata, the replacement must.
        if (ReadTarget(ReadFeature.ACCOUNT) !in evidence) read(ReadTarget(ReadFeature.ACCOUNT))
        confirmReadUpgrade(evidence, ::read)
    }

    private suspend fun read(target: ReadTarget): Set<String> = when (target.feature) {
        ReadFeature.ACCOUNT -> setOf(identity.verifyAccount(account.accountId).id)
        ReadFeature.ZONES -> ids({ directory.zones(account, it) }, Domain::id, requireZoneTotals = true).also {
            if (it.any { id -> !isCloudflareId(id) }) throw invalidResponse(false)
        }
        ReadFeature.DNS -> {
            val zone = ZoneScope(account, checkNotNull(target.zoneId))
            // DNS authorization is per zone, not per record. One validated page proves access.
            dns.list(zone, DnsFilter(), PageRequest(limit = 1))
            emptySet()
        }
        ReadFeature.ZONE_SETTINGS -> {
            settings.get(ZoneScope(account, checkNotNull(target.zoneId)), target.setting ?: ZoneSettingKey.ALWAYS_USE_HTTPS)
            emptySet()
        }
        ReadFeature.SSL -> {
            settings.universalSsl(ZoneScope(account, checkNotNull(target.zoneId)))
            emptySet()
        }
        ReadFeature.BILLING -> {
            billing.history(account, PageRequest(limit = 10))
            emptySet()
        }
        ReadFeature.WORKERS_LEGACY -> ids({ legacyWorkers.resources(account, ResourceKind.WORKER, it) }, CloudResource::id)
        else -> ids({ directory.resources(account, checkNotNull(target.feature.product).kind(), it) }, CloudResource::id)
    }

    private suspend fun <T> ids(load: suspend (PageRequest) -> CloudPage<T>, id: (T) -> String,
        requireZoneTotals: Boolean = false,
    ): Set<String> {
        val seen = mutableSetOf<PageToken>()
        val found = mutableSetOf<String>()
        var next: PageToken? = null
        var total: Long? = null
        repeat(50) {
            val page = try { load(PageRequest(limit = 50, token = next)) } catch (failure: CloudFailure) {
                if (requireZoneTotals && (failure.code == CloudFailureCode.INVALID_RESPONSE ||
                        (next != null && failure.code == CloudFailureCode.PERMISSION_REQUIRED))) throw incompleteZones()
                throw failure
            }
            for (item in page.items) {
                if (!found.add(id(item)) && requireZoneTotals) throw incompleteZones()
            }
            if (requireZoneTotals) {
                val count = page.total ?: throw incompleteZones()
                if (total != null && count != total) throw incompleteZones()
                total = count
                if (page.next == null && found.size.toLong() != count ||
                    page.next != null && (page.items.isEmpty() || found.size.toLong() >= count)) throw incompleteZones()
            }
            next = page.next ?: return found
            if (!seen.add(checkNotNull(next))) {
                if (requireZoneTotals) throw incompleteZones()
                throw invalidResponse(false)
            }
        }
        throw CloudFailure(CloudFailureCode.CONFLICT, if (requireZoneTotals)
            "域名分页超过 50 页，无法安全核对全部域名；已保留原 Token。"
        else "资源范围过大，无法安全核对；未替换 Token。")
    }

    private fun incompleteZones() = CloudFailure(
        CloudFailureCode.CONFLICT, "无法完整核对域名分页（总数缺失、不一致或后续页不可读）；已保留原 Token。",
    )

    private suspend fun <T : Any> withinBudget(read: suspend () -> T): T = withTimeoutOrNull(120_000) { read() }
        ?: throw CloudFailure(CloudFailureCode.CONFLICT, "权限和资源范围核对超时；已保留原 Token，请稍后重试。")
}

private fun isCloudflareId(id: String) = id.matches(Regex("[a-fA-F0-9]{32}"))
