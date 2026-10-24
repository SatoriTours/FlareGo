package com.flarego.core.application.r2

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2ManagedDomain
import com.flarego.core.model.r2DomainKindOf
import com.flarego.core.model.R2_MIN_TLS_VERSIONS

/** Read-only projection of one domain document for the settings surface. */
data class R2DomainProjection(
    val editable: Boolean,
    val readOnlyReason: String?,
    val rawText: String,
    val managed: R2ManagedDomain? = null,
    val customDomains: List<R2CustomDomain> = emptyList(),
    val customDomain: R2CustomDomain? = null,
)

/** Result of the one local domain validation facade the UI may call; it never sends anything. */
data class R2DomainDraftValidation(val valid: Boolean, val message: String? = null)

/**
 * The one public bridge between the reviewed B2 domain parsers/encoders and the UI.
 *
 * A screen may display a domain document and validate a draft with these functions, but it never
 * decodes provider JSON itself and never gets an executable plan or a raw session. Cipher values are
 * display-only: there is no reviewed evidence for writing a cipher list, so no facade function
 * produces one from UI input.
 */
object R2DomainConfigurationEditing {
    fun project(document: R2DomainDocument): R2DomainProjection =
        R2DomainProjection(
            editable = document.editable,
            readOnlyReason = document.readOnlyReason,
            rawText = document.raw.toString(),
            managed = document.managed,
            customDomains = document.customDomains,
            customDomain = document.customDomain,
        )

    /** Local validation only: target/kind, body shape, safe size bounds. */
    fun validate(write: R2DomainConfigurationWrite): R2DomainDraftValidation =
        try {
            validateR2DomainTarget(
                R2DomainTarget(write.target, if (write is R2DomainConfigurationWrite.DeleteCustomDomain ||
                    write is R2DomainConfigurationWrite.EditCustomDomain
                ) {
                    write.domain
                } else {
                    null
                }),
                r2DomainKindOf(write),
            )
            when (write) {
                is R2DomainConfigurationWrite.EditCustomDomain -> {
                    r2EditCustomDomainBody(write.edit)
                    requireR2DomainBodyWithinLimits(r2EditCustomDomainBody(write.edit))
                }
                else -> r2DomainWriteBody(write)?.let(::requireR2DomainBodyWithinLimits)
            }
            R2DomainDraftValidation(true)
        } catch (failure: CloudFailure) {
            R2DomainDraftValidation(false, failure.publicMessage)
        }

    fun isValidDomainName(value: String): Boolean =
        try {
            validateR2DomainName(value)
            true
        } catch (_: CloudFailure) {
            false
        }

    fun isValidZoneId(value: String): Boolean =
        try {
            validateR2ZoneId(value)
            true
        } catch (_: CloudFailure) {
            false
        }

    fun isValidMinTls(value: String): Boolean = value in R2_MIN_TLS_VERSIONS

    /** True when [to] is a lower minimum TLS version than [from]; used for its own risk confirmation. */
    fun isTlsDowngrade(from: String?, to: String): Boolean {
        if (from == null) return to in setOf("1.0", "1.1")
        val order = listOf("1.0", "1.1", "1.2", "1.3")
        val fromIndex = order.indexOf(from)
        val toIndex = order.indexOf(to)
        if (fromIndex < 0 || toIndex < 0) return false
        return toIndex < fromIndex
    }
}
