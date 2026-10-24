package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.AccountScope
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget

/**
 * Read-only provider lookup of the zone a custom domain claims to live in.
 *
 * It exists so a domain write can prove the zone belongs to the current account and that the domain
 * is inside that zone's name before anything is sent. The implementation reuses the reviewed zone
 * read (`ZoneManagementPort.get(ZoneScope)`); it never creates, edits or deletes DNS records.
 */
interface R2DomainZoneLookup {
    /** The zone's own name for [zoneId] in [account]; failure means "not proven", never "ok". */
    suspend fun zoneName(account: AccountScope, zoneId: String): String
}

/**
 * Typed port for the reviewed batch B2 domain plane (managed r2.dev endpoint, custom domains).
 *
 * Every method takes an explicit [R2DomainTarget]: the reviewed [R2WriteTarget] plus the single
 * domain a CUSTOM_DOMAIN action addresses. Reads keep the provider's result object verbatim so an
 * unknown field or enum makes the document read-only instead of being overwritten by a guess.
 *
 * Domain writes return only an [ExecutionReceipt]: a `PUT`/`POST` response is not assumed to be the
 * resulting document, and the same-lock read-back belongs to the controller that owns the bucket.
 */
interface R2DomainConfigurationPort {
    suspend fun getManagedDomain(target: R2WriteTarget): R2DomainDocument

    suspend fun listCustomDomains(target: R2WriteTarget): R2DomainDocument

    suspend fun getCustomDomain(target: R2DomainTarget): R2DomainDocument

    suspend fun putManagedDomain(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt

    suspend fun addCustomDomain(target: R2WriteTarget, input: R2CustomDomainCreateInput): ExecutionReceipt

    suspend fun editCustomDomain(target: R2WriteTarget, domain: String, edit: R2CustomDomainEditInput): ExecutionReceipt

    suspend fun deleteCustomDomain(target: R2WriteTarget, domain: String): ExecutionReceipt
}

/**
 * Credential-bound typed adapter for domain writes.
 *
 * [beforeSend] is the last non-suspending check: validation, the request-shape match, the zone
 * ownership proof (create), the independent bucket read and the current document all precede it, and
 * the single send follows immediately.
 *
 * The document argument is null only for a *confirmed* absence. A 404 on a single-domain read is not
 * absence: deleting a domain whose current state cannot be proven is refused before the send.
 */
interface R2DomainConfigurationSession : R2DomainConfigurationPort {
    suspend fun writeDomainConfiguration(
        write: R2DomainConfigurationWrite,
        beforeSend: (R2DomainDocument?) -> Unit,
    ): ExecutionReceipt
}
