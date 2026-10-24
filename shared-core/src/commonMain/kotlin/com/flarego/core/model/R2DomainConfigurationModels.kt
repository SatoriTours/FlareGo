package com.flarego.core.model

import kotlinx.serialization.json.JsonObject

/**
 * One batch B2 domain configuration family.
 *
 * This is a separate enum from [R2ConfigurationKind] on purpose: batch B1's document model is a
 * bucket-level `rules[]` set, while every B2 endpoint is a domain document with its own shape,
 * path segment and preservation semantics. Keeping them apart means no B1 `rules[]` normalisation
 * (or `when` branch) can be reused for a domain by accident.
 */
enum class R2DomainKind {
    /** `domains/managed`: the r2.dev public endpoint of the bucket. */
    MANAGED_DOMAIN,

    /** `domains/custom`: the custom-domain collection. */
    CUSTOM_DOMAINS,

    /** `domains/custom/{domain}`: one custom domain. */
    CUSTOM_DOMAIN,
}

/** The pinned schema's `minTLS` enum; declared values only. */
val R2_MIN_TLS_VERSIONS: Set<String> = setOf("1.0", "1.1", "1.2", "1.3")

/** The pinned schema's `ownership` enum for a custom domain. */
val R2_DOMAIN_OWNERSHIP_VALUES: Set<String> = setOf("pending", "active", "deactivated", "blocked", "error", "unknown")

/** The pinned schema's `ssl` enum for a custom domain. */
val R2_DOMAIN_SSL_VALUES: Set<String> = setOf("initializing", "pending", "active", "deactivated", "error", "unknown")

/**
 * One domain target: the reviewed bucket identity plus the single domain a CUSTOM_DOMAIN action
 * addresses.
 *
 * [target] stays the one [R2WriteTarget] (account, bucket, jurisdiction) so the B2 slice shares the
 * same account scope, jurisdiction enum and bucket ownership proof as batch A/B1. [domain] is fixed
 * for the single-domain operations and must be null for the collection/managed ones.
 */
data class R2DomainTarget(val target: R2WriteTarget, val domain: String? = null)

/** The r2.dev public endpoint document; `enabled` never implies anything about custom domains. */
data class R2ManagedDomain(val domain: String, val enabled: Boolean, val bucketId: String?)

/** Ownership and SSL are provider states of their own: `enabled` proves neither of them. */
data class R2CustomDomainStatus(val ownership: String, val ssl: String)

/** One custom domain as read from the provider; [raw] keeps unknown provider fields verbatim. */
data class R2CustomDomain(
    val domain: String,
    val enabled: Boolean,
    val minTls: String?,
    val ciphers: List<String>?,
    val zoneId: String?,
    val zoneName: String?,
    val status: R2CustomDomainStatus?,
    val raw: JsonObject,
)

/**
 * Creation input. [enabled] is always sent explicitly, so a new domain is never published by an
 * omitted field, and [minTls] defaults to 1.2 because the provider would otherwise default to 1.0.
 *
 * [ciphers] must be null in this stage: there is no reviewed evidence for writing a new cipher
 * list (or an empty one), so the wire validation refuses it instead of guessing a default.
 */
data class R2CustomDomainCreateInput(
    val domain: String,
    val zoneId: String,
    val enabled: Boolean = false,
    val minTls: String = "1.2",
    val ciphers: List<String>? = null,
)

/**
 * Edit input with an explicit change set.
 *
 * Only `minTLS` has a documented "unset defaults to previous value" omission. `enabled` and
 * `ciphers` have no such statement: a null [enabled] means "this UI did not change it" and is
 * resolved to the proven current value before sending, and [ciphers] must be null here because no
 * reviewed evidence supports writing a cipher list (the provider's current list is preserved
 * verbatim by the wire layer). At least one field must be set.
 */
data class R2CustomDomainEditInput(
    val enabled: Boolean? = null,
    val minTls: String? = null,
    val ciphers: List<String>? = null,
)

/**
 * A domain document as read from the provider.
 *
 * [editable] is false when the document cannot be round-tripped losslessly (unknown member or
 * unknown enum); then [readOnlyReason] explains it and no write may be built from it.
 */
data class R2DomainDocument(
    val target: R2WriteTarget,
    val kind: R2DomainKind,
    val raw: JsonObject,
    val editable: Boolean,
    val readOnlyReason: String? = null,
    val managed: R2ManagedDomain? = null,
    val customDomains: List<R2CustomDomain> = emptyList(),
    val customDomain: R2CustomDomain? = null,
)

/** One B2 domain write. */
sealed interface R2DomainConfigurationWrite {
    val target: R2WriteTarget

    val domain: String?

    /** `r2-put-bucket-public-policy`: [enabled] is always sent, so `false` is explicit. */
    data class PutManagedDomain(override val target: R2WriteTarget, val enabled: Boolean) : R2DomainConfigurationWrite {
        override val domain: String? get() = null
    }

    data class AddCustomDomain(override val target: R2WriteTarget, val input: R2CustomDomainCreateInput) : R2DomainConfigurationWrite {
        override val domain: String get() = input.domain
    }

    data class EditCustomDomain(
        override val target: R2WriteTarget,
        override val domain: String,
        val edit: R2CustomDomainEditInput,
    ) : R2DomainConfigurationWrite

    data class DeleteCustomDomain(override val target: R2WriteTarget, override val domain: String) : R2DomainConfigurationWrite
}

internal fun r2DomainKindOf(write: R2DomainConfigurationWrite): R2DomainKind =
    when (write) {
        is R2DomainConfigurationWrite.PutManagedDomain -> R2DomainKind.MANAGED_DOMAIN
        is R2DomainConfigurationWrite.AddCustomDomain -> R2DomainKind.CUSTOM_DOMAINS
        is R2DomainConfigurationWrite.EditCustomDomain, is R2DomainConfigurationWrite.DeleteCustomDomain -> R2DomainKind.CUSTOM_DOMAIN
    }

/** The pinned operationId of one domain read. */
internal fun r2DomainReadKey(kind: R2DomainKind): String =
    when (kind) {
        R2DomainKind.MANAGED_DOMAIN -> "r2-get-bucket-public-policy"
        R2DomainKind.CUSTOM_DOMAINS -> "r2-list-custom-domains"
        R2DomainKind.CUSTOM_DOMAIN -> "r2-get-custom-domain-settings"
    }

/** The pinned operationId of one domain write. */
internal fun r2DomainWriteKey(write: R2DomainConfigurationWrite): String =
    when (write) {
        is R2DomainConfigurationWrite.PutManagedDomain -> "r2-put-bucket-public-policy"
        is R2DomainConfigurationWrite.AddCustomDomain -> "r2-add-custom-domain"
        is R2DomainConfigurationWrite.EditCustomDomain -> "r2-edit-custom-domain-settings"
        is R2DomainConfigurationWrite.DeleteCustomDomain -> "r2-delete-custom-domain"
    }

/** Snapshot of one domain write; the nested lists and the target are copied. */
internal fun freezeR2DomainWrite(write: R2DomainConfigurationWrite): R2DomainConfigurationWrite =
    when (write) {
        is R2DomainConfigurationWrite.PutManagedDomain -> write.copy(target = write.target.copy())
        is R2DomainConfigurationWrite.AddCustomDomain ->
            write.copy(target = write.target.copy(), input = write.input.copy(ciphers = write.input.ciphers?.toList()))
        is R2DomainConfigurationWrite.EditCustomDomain ->
            write.copy(target = write.target.copy(), edit = write.edit.copy(ciphers = write.edit.ciphers?.toList()))
        is R2DomainConfigurationWrite.DeleteCustomDomain -> write.copy(target = write.target.copy())
    }
