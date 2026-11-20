package com.flarego.core.application.connection

import com.flarego.core.application.inventory.ProductKey
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.ZoneSettingKey
import com.flarego.core.ports.TokenKind

/** Official dashboard short keys, limited to the read features FlareGo provides. */
enum class ReadFeature(val key: String, val label: String, val product: ProductKey? = null, val zonal: Boolean = false) {
    ACCOUNT("account_settings", "账号"),
    ZONES("zone", "域名", ProductKey.ZONES),
    DNS("dns", "DNS", zonal = true),
    ZONE_SETTINGS("zone_settings", "站点设置", zonal = true),
    SSL("ssl_and_certificates", "通用 SSL", zonal = true),
    WORKERS("workers_scripts", "Workers", ProductKey.WORKERS),
    WORKERS_LEGACY("workers_scripts", "Workers（旧目录）"),
    KV("workers_kv_storage", "KV", ProductKey.KV),
    R2("workers_r2", "R2", ProductKey.R2),
    D1("d1", "D1", ProductKey.D1),
    BILLING("billing", "账单历史", ProductKey.BILLING),
}

data class ReadTarget(
    val feature: ReadFeature,
    val zoneId: String? = null,
    val setting: ZoneSettingKey? = null,
) {
    init {
        require(feature.zonal == (zoneId != null))
        require(setting == null || feature == ReadFeature.ZONE_SETTINGS)
    }
}

class OldTokenIdentityUnavailable : Exception("无法核验旧 Token ID；已保留原凭据，请稍后重试。")

/** Only a permission refusal for a requested read is evidence for offering that grant. */
suspend fun readUpgradeEvidence(
    requested: Set<ReadTarget>,
    targets: Set<ReadTarget>,
    read: suspend (ReadTarget) -> Set<String>,
): Map<ReadTarget, Set<String>> {
    require(requested.isNotEmpty() && targets.containsAll(requested))
    var missing = false
    val evidence = mutableMapOf<ReadTarget, Set<String>>()
    for (target in targets) {
        try {
            evidence[target] = read(target)
        } catch (failure: CloudFailure) {
            if (failure.code != CloudFailureCode.PERMISSION_REQUIRED) throw failure
            if (target in requested) {
                evidence[target] = emptySet()
                missing = true
            }
        }
    }
    if (!missing) throw CloudFailure(CloudFailureCode.CONFLICT, "这些功能现在可以读取；请返回刷新，无需替换 Token。")
    return evidence
}

suspend fun confirmReadUpgrade(evidence: Map<ReadTarget, Set<String>>, read: suspend (ReadTarget) -> Set<String>) {
    for ((target, existing) in evidence) {
        if (!read(target).containsAll(existing))
            throw CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "新 Token 看不到旧 Token 可访问的部分资源；请核对资源范围。")
    }
}

data class TokenUpgradePlan(val accountId: String, val accountName: String, val features: Set<ReadFeature>, val zoneId: String?)

/** Provider-issued nonsecret ID, never the token or a suffix of the token value. */
data class RetiredToken(val id: String, val kind: TokenKind)

data class TokenUpgradeResult(val outcome: CredentialSaveOutcome, val retired: RetiredToken? = null)

/** Verification is cancellable; only the final store operation runs as CONNECTING. */
class TokenUpgradeAttempt(
    val plan: TokenUpgradePlan,
    val verify: suspend (String) -> (suspend () -> TokenUpgradeResult),
)

fun requireDifferentToken(current: String, candidate: String, previous: RetiredToken, candidateId: String?) {
    if (current == candidate || (candidateId != null && previous.id.equals(candidateId, ignoreCase = true)))
        throw CloudFailure(CloudFailureCode.CONFLICT, "这是当前正在使用的 Token；请创建不同的 Token。")
}
