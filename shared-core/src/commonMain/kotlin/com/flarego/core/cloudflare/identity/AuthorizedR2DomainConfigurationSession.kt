package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2DomainConfigurationClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.model.ZoneScope
import com.flarego.core.model.freezeR2DomainWrite
import com.flarego.core.model.r2DomainWriteKey
import com.flarego.core.ports.R2DomainConfigurationSession
import com.flarego.core.ports.R2DomainZoneLookup
import com.flarego.core.ports.ZoneManagementPort

/**
 * The reviewed zone read bound to a domain write.
 *
 * It reuses `ZoneManagementPort.get(ZoneScope)` unchanged, re-checks that the returned zone is
 * exactly the requested account+zone, and returns the provider's zone name. It never creates,
 * edits or deletes a DNS record and never probes an arbitrary host.
 */
private class ZoneManagementDomainLookup(private val port: ZoneManagementPort) : R2DomainZoneLookup {
    override suspend fun zoneName(account: AccountScope, zoneId: String): String {
        val scope = ZoneScope(account, zoneId)
        val details = port.get(scope)
        if (details.ref != scope || details.name.isBlank()) {
            throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "无法证明该 Zone 属于当前账号；未发送自定义域写入")
        }
        return details.name
    }
}

/**
 * All production batch B2 domain writes pass here.
 *
 * Each write obtains a fresh account-level `Workers R2 Storage Write` observation for exactly this
 * account, bucket, jurisdiction and operation, mints an issuer-owned proof and installs the
 * proof-bound final guard in the wire client. S3 or bucket-item permissions are never accepted as
 * REST administration, and a credential/clock change during the read phase ends in zero sends.
 *
 * Reads are delegated unchanged: reading a domain grants no write permission.
 */
class AuthorizedR2DomainConfigurationSession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val issuer: R2TokenAuthorityIssuer,
    zonePort: ZoneManagementPort,
) : R2DomainConfigurationSession {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)
    private val zones = ZoneManagementDomainLookup(zonePort)

    private fun reader() = CloudflareR2DomainConfigurationClient(connection, gateway, zones)

    override suspend fun getManagedDomain(target: R2WriteTarget): R2DomainDocument = reader().getManagedDomain(target)

    override suspend fun listCustomDomains(target: R2WriteTarget): R2DomainDocument = reader().listCustomDomains(target)

    override suspend fun getCustomDomain(target: R2DomainTarget): R2DomainDocument = reader().getCustomDomain(target)

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
    ): ExecutionReceipt {
        val frozen = freezeR2DomainWrite(write)
        val target = frozen.target
        val operation = r2DomainWriteKey(frozen)
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            throw CloudFailure(
                CloudFailureCode.PERMISSION_REQUIRED,
                "无法证明当前 Token 拥有该账号级 R2 REST 写权限；未发送域名配置写入",
            )
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        val client =
            CloudflareR2DomainConfigurationClient(connection, gateway, zones) {
                issuer.requireCurrent(proof, target, operation)
            }
        return client.writeDomainConfiguration(frozen, beforeSend)
    }
}
