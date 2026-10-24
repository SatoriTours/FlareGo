package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_DOMAIN_READ_ONLY_WRITE
import com.flarego.core.application.r2.decodeR2CustomDomain
import com.flarego.core.application.r2.decodeR2ManagedDomain
import com.flarego.core.application.r2.r2DomainBelongsToZone
import com.flarego.core.application.r2.r2DomainReadOnlyReason
import com.flarego.core.application.r2.r2DomainWriteBody
import com.flarego.core.application.r2.r2EditCustomDomainBody
import com.flarego.core.application.r2.requireR2DomainBodyWithinLimits
import com.flarego.core.application.r2.rawR2DomainResult
import com.flarego.core.application.r2.validateR2DomainTarget
import com.flarego.core.application.r2.validateR2DomainName
import com.flarego.core.application.r2.validateR2ZoneId
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.freezeR2DomainWrite
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2DomainKindOf
import com.flarego.core.model.r2DomainReadKey
import com.flarego.core.model.r2DomainWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.R2DomainConfigurationSession
import com.flarego.core.ports.R2DomainZoneLookup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * R2 batch B2 domain plane over the P1 gateway and the handwritten policy registry.
 *
 * The client only builds reviewed requests:
 * - the bucket and jurisdiction are validated locally, and the controlled `cf-r2-jurisdiction`
 *   header is sent on every endpoint of this slice (all seven declare it);
 * - a custom-domain create first proves the zone belongs to this account and that the domain is
 *   inside the zone name on a DNS label boundary, before the bucket read and the single send;
 * - the before-observation follows the approved per-action contract: a create/delete proves the
 *   complete custom-domain collection (present/absent) and an edit proves that single domain, so a
 *   404 or an unreadable/incomplete list is never treated as "already absent";
 * - a `PUT`/`POST`/`DELETE` response is only a receipt; the same-lock read-back belongs to the
 *   controller that owns the bucket.
 */
class CloudflareR2DomainConfigurationClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val zones: R2DomainZoneLookup? = null,
    // Trusted integration seam: the authorized session installs its proof-bound final guard here.
    private val beforeMutation: () -> Unit = {},
) : R2DomainConfigurationSession {

    override suspend fun getManagedDomain(target: R2WriteTarget): R2DomainDocument =
        readDomain(target, R2DomainKind.MANAGED_DOMAIN, null)

    override suspend fun listCustomDomains(target: R2WriteTarget): R2DomainDocument =
        readDomain(target, R2DomainKind.CUSTOM_DOMAINS, null)

    override suspend fun getCustomDomain(target: R2DomainTarget): R2DomainDocument =
        readDomain(target.target, R2DomainKind.CUSTOM_DOMAIN, target.domain)

    override suspend fun putManagedDomain(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.PutManagedDomain(target, enabled)) {}

    override suspend fun addCustomDomain(target: R2WriteTarget, input: R2CustomDomainCreateInput): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.AddCustomDomain(target, input)) {}

    override suspend fun editCustomDomain(
        target: R2WriteTarget,
        domain: String,
        edit: R2CustomDomainEditInput,
    ): ExecutionReceipt = writeDomainConfiguration(R2DomainConfigurationWrite.EditCustomDomain(target, domain, edit)) {}

    override suspend fun deleteCustomDomain(target: R2WriteTarget, domain: String): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.DeleteCustomDomain(target, domain)) {}

    override suspend fun writeDomainConfiguration(
        write: R2DomainConfigurationWrite,
        beforeSend: (R2DomainDocument?) -> Unit,
    ): ExecutionReceipt =
        try {
            perform(write, beforeSend)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            // A failure after a possible send is never a reason to replay it.
            if (failure.uncertain) ExecutionReceipt.Unknown() else throw failure
        }

    private suspend fun perform(
        write: R2DomainConfigurationWrite,
        beforeSend: (R2DomainDocument?) -> Unit,
    ): ExecutionReceipt {
        val frozen = freezeR2DomainWrite(write)
        ScopeGuard.requireOwned(frozen.target.account, connection)
        val kind = r2DomainKindOf(frozen)
        // Only a single-domain action carries the domain in its identity; a create keeps it in the body.
        val identityDomain = if (kind == R2DomainKind.CUSTOM_DOMAIN) frozen.domain else null
        val target = validateR2DomainTarget(R2DomainTarget(frozen.target, identityDomain), kind).target
        val operation = r2DomainWriteKey(frozen)
        val registration = ActionPolicies.registry.registration(operation) ?: throw invalidInputFailure()
        val path = domainLocator(target, kind, identityDomain)
        if (!registration.matches(registration.method, path)) throw invalidInputFailure()
        // Validate every part of the body that does not depend on the provider's current document.
        when (frozen) {
            is R2DomainConfigurationWrite.EditCustomDomain -> r2EditCustomDomainBody(frozen.edit)
            else -> r2DomainWriteBody(frozen)?.let(::requireR2DomainBodyWithinLimits)
        }
        // A create proves the zone belongs to this account and owns the domain name first.
        if (frozen is R2DomainConfigurationWrite.AddCustomDomain) {
            proveZone(target, frozen.input.domain, frozen.input.zoneId)
        }
        // The before-observation contract is separate from the mutation path/resource family:
        // a create/delete proves the *complete collection* (a DELETE itself stays a single-domain
        // DELETE), an edit proves that one domain, and the managed endpoint proves its document.
        // Every read here finishes before the final guard; nothing suspends after it.
        val observationKind =
            when (frozen) {
                is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
                is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                    R2DomainKind.CUSTOM_DOMAINS
                is R2DomainConfigurationWrite.EditCustomDomain -> R2DomainKind.CUSTOM_DOMAIN
            }
        val observationDomain = if (observationKind == R2DomainKind.CUSTOM_DOMAIN) frozen.domain else null
        getBucket(target)
        val observed = readDomain(target, observationKind, observationDomain)
        if (!observed.editable) {
            throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, R2_DOMAIN_READ_ONLY_WRITE)
        }
        when (frozen) {
            is R2DomainConfigurationWrite.AddCustomDomain ->
                if (observed.customDomains.any { it.domain == frozen.input.domain }) {
                    throw CloudFailure(CloudFailureCode.CONFLICT, "该自定义域已存在，请刷新后再编辑或删除")
                }
            is R2DomainConfigurationWrite.DeleteCustomDomain ->
                if (observed.customDomains.none { it.domain == frozen.domain }) {
                    throw CloudFailure(CloudFailureCode.CONFLICT, "该自定义域不在当前完整列表中，请刷新后再确认")
                }
            else -> Unit
        }
        // The final body is built from the proven current document, then every check finishes before
        // the final guard: nothing suspends between the guard and the single send.
        val body =
            when (frozen) {
                is R2DomainConfigurationWrite.EditCustomDomain ->
                    r2EditCustomDomainBody(
                        frozen.edit,
                        observed.customDomain ?: throw CloudFailure(
                            CloudFailureCode.UNSUPPORTED_CAPABILITY,
                            R2_DOMAIN_READ_ONLY_WRITE,
                        ),
                    )
                else -> r2DomainWriteBody(frozen)
            }
        body?.let(::requireR2DomainBodyWithinLimits)
        val request =
            HttpRequest(
                method = registration.method,
                path = path,
                payload = body?.let { HttpBody.Text(it.toString(), JSON_CONTENT_TYPE) } ?: HttpBody.Empty,
                headers = jurisdictionHeader(target),
            )
        beforeSend(observed)
        beforeMutation()
        val response = send(operation, request)
        if (response.status == STATUS_ACCEPTED) return ExecutionReceipt.Accepted()
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        return ExecutionReceipt.Completed()
    }

    /**
     * Zone ownership proof. The lookup is the reviewed zone read bound to this account; it fails
     * closed, and this client additionally requires the domain to sit inside the zone name on a DNS
     * label boundary. No DNS write and no arbitrary host probe happens here.
     */
    private suspend fun proveZone(target: R2WriteTarget, domain: String, zoneId: String) {
        val lookup = zones ?: throw CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            "无法证明该 Zone 属于当前账号；未发送自定义域写入",
        )
        val validatedZone = validateR2ZoneId(zoneId)
        val zoneName =
            try {
                lookup.zoneName(target.account, validatedZone)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw failure
            } catch (_: Exception) {
                throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "无法证明该 Zone 属于当前账号；未发送自定义域写入")
            }
        if (!r2DomainBelongsToZone(domain, zoneName)) {
            throw CloudFailure(
                CloudFailureCode.SCOPE_MISMATCH,
                "该域名不在所声明 Zone 的归属范围内；未发送自定义域写入",
            )
        }
    }

    private suspend fun getBucket(target: R2WriteTarget) {
        val root = readEnvelope("r2-get-bucket", bucketLocator(target), jurisdictionHeader(target))
        val row = root["result"] as? JsonObject ?: throw invalidResponse(false)
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw invalidResponse(false)
        if (name != target.bucket) throw scopeMismatchFailure()
    }

    private suspend fun readDomain(target: R2WriteTarget, kind: R2DomainKind, domain: String?): R2DomainDocument {
        ScopeGuard.requireOwned(target.account, connection)
        val validated = validateR2DomainTarget(R2DomainTarget(target, domain), kind)
        val root = readEnvelope(r2DomainReadKey(kind), domainLocator(validated.target, kind, domain), jurisdictionHeader(validated.target))
        val raw = rawR2DomainResult(root, kind)
        val reason = r2DomainReadOnlyReason(kind, raw)
        val editable = reason == null
        // The provider must echo the exact domain that was asked for: a snapshot of another domain is
        // never evidence for this one, even when the row itself is fully understood.
        if (kind == R2DomainKind.CUSTOM_DOMAIN && !editable.not()) {
            val echoed = (raw["domain"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (echoed != domain) throw scopeMismatchFailure()
        }
        return R2DomainDocument(
            target = validated.target,
            kind = kind,
            raw = raw,
            editable = editable,
            readOnlyReason = reason,
            managed = if (editable && kind == R2DomainKind.MANAGED_DOMAIN) decodeR2ManagedDomain(raw) else null,
            customDomains =
                if (editable && kind == R2DomainKind.CUSTOM_DOMAINS) {
                    (raw["domains"] as? JsonArray).orEmpty().map { decodeR2CustomDomain(it as JsonObject) }
                } else {
                    emptyList()
                },
            customDomain = if (editable && kind == R2DomainKind.CUSTOM_DOMAIN) decodeR2CustomDomain(raw) else null,
        )
    }

    private suspend fun readEnvelope(key: String, path: String, headers: Map<String, String>): JsonObject {
        val response =
            try {
                gateway.send(key, HttpRequest("GET", path, headers = headers))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw failure
            } catch (_: Exception) {
                throw CloudFailure(CloudFailureCode.UNAVAILABLE, "暂时无法连接，请检查网络后重试")
            }
        val root = ResponseDecoders.envelope(response, writing = false)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(false)
        return root
    }

    private suspend fun send(key: String, request: HttpRequest): HttpResponse =
        try {
            gateway.send(key, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            throw failure
        } catch (_: Exception) {
            throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "配置写入结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
        }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val STATUS_ACCEPTED = 202
    }
}

private fun jurisdictionHeader(target: R2WriteTarget): Map<String, String> =
    mapOf("cf-r2-jurisdiction" to target.jurisdiction)

private fun bucketLocator(target: R2WriteTarget): String =
    "/accounts/${encodePath(target.account.accountId)}/r2/buckets/${encodePath(target.bucket)}"

private fun domainLocator(target: R2WriteTarget, kind: R2DomainKind, domain: String?): String {
    val base = bucketLocator(target)
    return when (kind) {
        R2DomainKind.MANAGED_DOMAIN -> "$base/domains/managed"
        R2DomainKind.CUSTOM_DOMAINS -> "$base/domains/custom"
        R2DomainKind.CUSTOM_DOMAIN -> "$base/domains/custom/${encodePath(validateR2DomainName(domain ?: throw invalidInputFailure()))}"
    }
}
