package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.decodeR2CustomDomain
import com.flarego.core.application.r2.decodeR2ManagedDomain
import com.flarego.core.application.r2.r2DomainReadOnlyReason
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.freezeR2DomainWrite
import com.flarego.core.model.r2DomainKindOf
import com.flarego.core.ports.R2DomainConfigurationSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A domain session double with the real read-only analysis and the production decoder.
 *
 * Only the provider is simulated. Every counter observes real calls, and the same-lock read-back
 * gate can hold the app-owned job after its single send.
 */
internal class FakeDomainSession(private val account: AccountScope) : R2DomainConfigurationSession {
    var managed: JsonObject? =
        buildJsonObject {
            put("domain", "pub-abc.r2.dev")
            put("enabled", false)
            put("bucketId", "bucket-id")
        }
    val collection = mutableListOf<JsonObject>()

    var receipt: ExecutionReceipt = ExecutionReceipt.Completed()
    var failReads = false
    var refuseBeforeSend = false
    var refuseAfterGuard = false
    var skipGuard = false
    var onRead: ((R2WriteTarget, R2DomainKind) -> Unit)? = null
    var onApply: ((R2DomainConfigurationWrite) -> Unit)? = null
    var documentOverride: ((R2DomainTarget?, R2DomainKind) -> R2DomainDocument?)? = null
    var readBackGate: (suspend () -> Unit)? = null

    val writes = mutableListOf<R2DomainConfigurationWrite>()
    val sends = mutableListOf<R2DomainConfigurationWrite>()
    val reads = mutableListOf<Pair<R2WriteTarget, R2DomainKind>>()
    var sessionOpens = 0
    var readsAfterADispatch = 0

    fun open() {
        sessionOpens++
    }

    fun seedDomain(
        domain: String,
        enabled: Boolean,
        minTls: String = "1.2",
        ciphers: List<String> = listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
        zoneId: String = "0123456789abcdef0123456789abcdef",
        zoneName: String = "example.com",
        ownership: String = "pending",
        ssl: String = "initializing",
    ): JsonObject =
        buildJsonObject {
            put("domain", domain)
            put("enabled", enabled)
            put("minTLS", minTls)
            if (ciphers.isEmpty()) put("ciphers", JsonArray(emptyList()))
            else put("ciphers", JsonArray(ciphers.map(::JsonPrimitive)))
            put("zoneId", zoneId)
            put("zoneName", zoneName)
            put("status", buildJsonObject { put("ownership", ownership); put("ssl", ssl) })
        }.also { collection += it }

    fun seedDomainWithoutCiphers(domain: String, enabled: Boolean, minTls: String = "1.2"): JsonObject =
        buildJsonObject {
            put("domain", domain)
            put("enabled", enabled)
            put("minTLS", minTls)
            put("zoneId", "0123456789abcdef0123456789abcdef")
            put("zoneName", "example.com")
            put("status", buildJsonObject { put("ownership", "pending"); put("ssl", "initializing") })
        }.also { collection += it }

    private suspend fun document(target: R2WriteTarget, kind: R2DomainKind, domain: String?): R2DomainDocument {
        reads += target to kind
        onRead?.invoke(target, kind)
        documentOverride?.invoke(domain?.let { R2DomainTarget(target, it) }, kind)?.let {
            if (sends.isNotEmpty()) readsAfterADispatch++
            return it
        }
        if (sends.isNotEmpty()) {
            readsAfterADispatch++
            readBackGate?.invoke()
        }
        if (failReads) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "合成域名读取失败")
        val raw =
            when (kind) {
                R2DomainKind.MANAGED_DOMAIN ->
                    managed ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源，请刷新后重试")
                R2DomainKind.CUSTOM_DOMAINS -> buildJsonObject { put("domains", JsonArray(collection.toList())) }
                R2DomainKind.CUSTOM_DOMAIN ->
                    collection.firstOrNull { (it["domain"] as? JsonPrimitive)?.content == domain }
                        ?: throw CloudFailure(CloudFailureCode.NOT_FOUND, "未找到请求的资源，请刷新后重试")
            }
        val reason = r2DomainReadOnlyReason(kind, raw)
        val editable = reason == null
        if (kind == R2DomainKind.CUSTOM_DOMAIN && editable) {
            val echoed = (raw["domain"] as? JsonPrimitive)?.content
            if (echoed != domain) throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "域名当前状态与请求不一致")
        }
        return R2DomainDocument(
            target = target,
            kind = kind,
            raw = raw,
            editable = editable,
            readOnlyReason = reason,
            managed = if (editable && kind == R2DomainKind.MANAGED_DOMAIN) decodeR2ManagedDomain(raw) else null,
            customDomains =
                if (editable && kind == R2DomainKind.CUSTOM_DOMAINS) {
                    collection.map(::decodeR2CustomDomain)
                } else {
                    emptyList()
                },
            customDomain = if (editable && kind == R2DomainKind.CUSTOM_DOMAIN) decodeR2CustomDomain(raw) else null,
        )
    }

    private fun apply(write: R2DomainConfigurationWrite) {
        onApply?.let { it(write); return }
        when (write) {
            is R2DomainConfigurationWrite.PutManagedDomain ->
                managed = managed?.let { current ->
                    buildJsonObject {
                        current.forEach { (key, value) -> if (key != "enabled") put(key, value) }
                        put("enabled", write.enabled)
                    }
                }
            is R2DomainConfigurationWrite.AddCustomDomain ->
                seedDomain(
                    domain = write.input.domain,
                    enabled = write.input.enabled,
                    minTls = write.input.minTls,
                    ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
                    zoneName = "example.com",
                )
            is R2DomainConfigurationWrite.EditCustomDomain -> {
                val index = collection.indexOfFirst { (it["domain"] as? JsonPrimitive)?.content == write.domain }
                if (index < 0) throw CloudFailure(CloudFailureCode.NOT_FOUND, "合成编辑目标不存在")
                val current = collection[index]
                collection[index] =
                    buildJsonObject {
                        current.forEach { (key, value) ->
                            if (key != "enabled" && key != "minTLS") put(key, value)
                        }
                        put("enabled", write.edit.enabled ?: (current["enabled"] as JsonPrimitive).content.toBoolean())
                        (write.edit.minTls ?: (current["minTLS"] as? JsonPrimitive)?.content)?.let { put("minTLS", it) }
                    }
            }
            is R2DomainConfigurationWrite.DeleteCustomDomain ->
                collection.removeAll { (it["domain"] as? JsonPrimitive)?.content == write.domain }
        }
    }

    override suspend fun getManagedDomain(target: R2WriteTarget): R2DomainDocument =
        document(target, R2DomainKind.MANAGED_DOMAIN, null)

    override suspend fun listCustomDomains(target: R2WriteTarget): R2DomainDocument =
        document(target, R2DomainKind.CUSTOM_DOMAINS, null)

    override suspend fun getCustomDomain(target: R2DomainTarget): R2DomainDocument =
        document(target.target, R2DomainKind.CUSTOM_DOMAIN, target.domain)

    override suspend fun putManagedDomain(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.PutManagedDomain(target, enabled)) {}

    override suspend fun addCustomDomain(target: R2WriteTarget, input: R2CustomDomainCreateInput): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.AddCustomDomain(target, input)) {}

    override suspend fun editCustomDomain(
        target: R2WriteTarget,
        domain: String,
        edit: R2CustomDomainEditInput,
    ): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.EditCustomDomain(target, domain, edit)) {}

    override suspend fun deleteCustomDomain(target: R2WriteTarget, domain: String): ExecutionReceipt =
        writeDomainConfiguration(R2DomainConfigurationWrite.DeleteCustomDomain(target, domain)) {}

    override suspend fun writeDomainConfiguration(
        write: R2DomainConfigurationWrite,
        beforeSend: (R2DomainDocument?) -> Unit,
    ): ExecutionReceipt {
        val frozen = freezeR2DomainWrite(write)
        writes += frozen
        // The real client reads the complete collection before a create/delete, that one domain
        // before an edit and the managed document before a managed write.
        val beforeKind =
            when (frozen) {
                is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
                is R2DomainConfigurationWrite.AddCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain ->
                    R2DomainKind.CUSTOM_DOMAINS
                is R2DomainConfigurationWrite.EditCustomDomain -> R2DomainKind.CUSTOM_DOMAIN
            }
        val observed = document(frozen.target, beforeKind, frozen.domain)
        if (refuseBeforeSend) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "合成发送前拒绝")
        if (!skipGuard) beforeSend(observed)
        if (refuseAfterGuard) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "合成最终守卫拒绝")
        sends += frozen
        apply(frozen)
        return receipt
    }

    companion object {
        fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    }
}
