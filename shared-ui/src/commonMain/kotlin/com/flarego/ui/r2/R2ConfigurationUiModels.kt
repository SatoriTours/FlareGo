package com.flarego.ui.r2

import com.flarego.core.application.r2.R2ConfigurationEditing
import com.flarego.core.application.r2.R2ConfigurationProjection
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LifecycleStorageTransition
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JURISDICTIONS

/**
 * One editable configuration family.
 *
 * Batch B1 panels keep their [R2ConfigurationKind]; batch B2 domain panels carry a
 * [R2DomainKind] instead, so no `rules[]` normalization can be applied to a domain document.
 */
enum class R2ConfigurationPanel(val kind: R2ConfigurationKind?, val domainKind: R2DomainKind?) {
    CORS(R2ConfigurationKind.CORS, null),
    LIFECYCLE(R2ConfigurationKind.LIFECYCLE, null),
    LOCAL_UPLOADS(R2ConfigurationKind.LOCAL_UPLOADS, null),
    LOCK(R2ConfigurationKind.LOCK, null),

    /** The r2.dev public endpoint of the bucket. */
    MANAGED_DOMAIN(null, R2DomainKind.MANAGED_DOMAIN),

    /** The custom-domain collection plus one selected domain editor. */
    CUSTOM_DOMAINS(null, R2DomainKind.CUSTOM_DOMAINS),

    /**
     * Batch B3 Sippy on-demand migration.
     *
     * It deliberately has neither a B1 `R2ConfigurationKind` nor a domain kind: Sippy is its own
     * typed family, and no `rules[]` or domain normalization may be applied to it.
     */
    SIPPY(null, null),
}

/** Independent risk acknowledgements; the static registration risk is never lowered here. */
data class R2ConfigurationRisks(
    val fullReplacementAcknowledged: Boolean = false,
    val destructiveImpactAcknowledged: Boolean = false,
    /** Public/public-endpoint exposure is its own impact, separate from destructive deletes. */
    val publicExposureAcknowledged: Boolean = false,
    /** Lowering minimum TLS is a security downgrade and is acknowledged separately. */
    val tlsDowngradeAcknowledged: Boolean = false,
)

/**
 * One custom domain as shown in the editor.
 *
 * [ciphers] is display-only in this stage: the app has no reviewed evidence about writing a new
 * cipher allowlist (or clearing one), so the editor never sends a cipher value of its own and only
 * preserves the provider's current list.
 */
data class R2CustomDomainDraft(
    val domain: String,
    val enabled: Boolean,
    val minTls: String?,
    val ciphers: List<String>?,
    val zoneId: String?,
    val zoneName: String?,
    val ownership: String?,
    val ssl: String?,
)

/** Creation draft; [enabled] is always explicit and [minTls] defaults to the safe 1.2. */
data class R2CustomDomainCreateDraft(
    val domain: String = "",
    val zoneId: String = "",
    val enabled: Boolean = false,
    val minTls: String = "1.2",
)

/**
 * Edit draft with an explicit change set: null means "do not change this field".
 *
 * There is deliberately no cipher field: writing a cipher list (including an empty one) has no
 * reviewed evidence, so this stage does not expose it.
 */
data class R2CustomDomainEditDraft(
    val enabled: Boolean? = null,
    val minTls: String? = null,
)

/** The exact target a configuration write addresses; jurisdiction is never inferred from the object page. */
data class R2ConfigurationDraftTarget(val bucket: String = "", val jurisdiction: String = "default") {
    val jurisdictions: List<String> get() = R2_JURISDICTIONS.sorted()
}

enum class R2LockConditionType { AGE_SECONDS, DATE, INDEFINITE }

/** One CORS rule draft. Text fields are parsed by the browser, never by the screen. */
data class R2CorsDraft(
    val id: String = "",
    val methods: String = "GET",
    val origins: String = "",
    val allowedHeaders: String = "",
    val exposeHeaders: String = "",
    val maxAgeSeconds: String = "",
)

/** The two lifecycle condition shapes of the pinned schema; a condition is never guessed. */
sealed interface R2LifecycleConditionDraft {
    /** Whole seconds, still text so an invalid entry is reported instead of silently rewritten. */
    data class Age(val seconds: String) : R2LifecycleConditionDraft

    /**
     * The exact RFC3339 UTC text of the provider or the user, never trimmed or re-encoded.
     * A fractional second within this app's 1..9 digit subset is preserved verbatim.
     */
    data class Date(val utcDate: String) : R2LifecycleConditionDraft
}

enum class R2LifecycleDeleteMode { NONE, AGE, DATE }
enum class R2LifecycleConditionType { AGE, DATE }

/** One lifecycle rule draft. [deleteCondition] is null when the rule has no delete transition. */
data class R2LifecycleDraft(
    val id: String = "",
    val enabled: Boolean = true,
    val prefix: String = "",
    val deleteMode: R2LifecycleDeleteMode = R2LifecycleDeleteMode.NONE,
    val deleteAgeSeconds: String = "",
    val deleteDate: String = "",
    /** `abortMultipartUploadsTransition` is age-only in the pinned schema. */
    val abortAgeSeconds: String = "",
    val transitions: List<R2LifecycleTransitionDraft> = emptyList(),
)

data class R2LifecycleTransitionDraft(
    val conditionType: R2LifecycleConditionType = R2LifecycleConditionType.AGE,
    val ageSeconds: String = "",
    val utcDate: String = "",
    val storageClass: String = "InfrequentAccess",
)

/** One lock rule draft. The age field is `maxAgeSeconds`, never the lifecycle `maxAge`. */
data class R2LockDraft(
    val id: String = "",
    val enabled: Boolean = true,
    val prefix: String = "",
    val conditionType: R2LockConditionType = R2LockConditionType.AGE_SECONDS,
    val ageSeconds: String = "86400",
    val date: String = "",
)

/**
 * The safe public projection of one Sippy snapshot.
 *
 * Only allowlisted provider metadata appears here. A masked or truncated credential value is never
 * part of it, so nothing in this model can be re-sent as a credential or mistaken for the real one.
 */
data class R2SippySnapshotUi(
    val enabled: Boolean,
    val editable: Boolean,
    val readOnlyReason: String? = null,
    val provider: String? = null,
    val sourceBucket: String? = null,
    val sourceRegion: String? = null,
    val sourceContainer: String? = null,
    val sourceBucketUrl: String? = null,
    val destinationBucket: String? = null,
    val destinationAccount: String? = null,
    val detailsProvided: Boolean = false,
)

/**
 * The batch B3 Sippy surface.
 *
 * This stage is a reviewed read-only placeholder plus the complete non-visual credential owner and
 * the same root preview/confirm/settle path as B1/B2; the four-provider secret form itself is Task 6.
 * No credential value is representable here.
 */
data class R2SippySurfaceUi(
    val available: Boolean = false,
    /** The selected source provider (a public, non-secret choice preserved across re-entry). */
    val provider: com.flarego.core.model.R2SippyProvider = com.flarego.core.model.R2SippyProvider.AWS,
    /** True only while the real window protection is active for the current editing session. */
    val secureReady: Boolean = false,
    /**
     * Opaque, non-secret identity of the editing session the mounted secret surface captured.
     * Used only to report that surface's removal; it is no execution authority and no secret.
     */
    val secureEpoch: Long? = null,
    /** Non-secret credential revision; feeds display and the request fingerprint only. */
    val credentialRevision: Long = 0,
    val draftComplete: Boolean = false,
    val snapshot: R2SippySnapshotUi? = null,
    val snapshotError: String? = null,
    /** Safe, non-secret form gates of the Sippy surface. */
    val typedBucketName: String = "",
    /** The user confirmed that this submit sends the source/destination credentials to Cloudflare once. */
    val disclosureAcknowledged: Boolean = false,
    /** PUT-only: the user confirmed the destination is this bucket's existing R2 access-key pair. */
    val credentialsAcknowledged: Boolean = false,
    /** DELETE-only: the user confirmed this only stops new on-demand migration. */
    val disableAcknowledged: Boolean = false,
    val disableRequested: Boolean = false,
    val writing: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** Display-only preview; it never carries a plan id or an executable command. */
data class R2ConfigurationPreviewUi(
    val target: String,
    val impact: String,
    val concurrencyWarning: String,
    val requiresTypedTargetName: Boolean,
    val requiresTypedDomainName: Boolean = false,
)

/** Everything the settings screen may read. No raw client, no plan id, no command. */
data class R2ConfigurationUiState(
    val open: Boolean = false,
    val panel: R2ConfigurationPanel = R2ConfigurationPanel.CORS,
    val target: R2ConfigurationDraftTarget = R2ConfigurationDraftTarget(),
    val loading: Boolean = false,
    val error: String? = null,
    val validationMessage: String? = null,
    val notice: String? = null,
    val editable: Boolean = false,
    val readOnlyReason: String? = null,
    val rawDocumentText: String? = null,
    val cors: List<R2CorsDraft> = emptyList(),
    val lifecycle: List<R2LifecycleDraft> = emptyList(),
    val localUploadsEnabled: Boolean = false,
    val lock: List<R2LockDraft> = emptyList(),
    /** A whole-configuration DELETE is a separate explicit mode of CORS/LOCK only. */
    val deleteRequested: Boolean = false,
    val managedDomainHost: String? = null,
    val managedDomainEnabled: Boolean = false,
    val customDomains: List<R2CustomDomainDraft> = emptyList(),
    val selectedDomain: String? = null,
    val customDomainCreate: R2CustomDomainCreateDraft = R2CustomDomainCreateDraft(),
    val customDomainEdit: R2CustomDomainEditDraft = R2CustomDomainEditDraft(),
    val domainDeleteRequested: Boolean = false,
    val typedDomainName: String = "",
    val domainWritesAvailable: Boolean = false,
    val risks: R2ConfigurationRisks = R2ConfigurationRisks(),
    val typedTargetName: String = "",
    val preview: R2ConfigurationPreviewUi? = null,
    val canPreview: Boolean = false,
    val writing: Boolean = false,
    val writesAvailable: Boolean = false,
    /** Batch B3 Sippy surface state (safe projection only). */
    val sippy: R2SippySurfaceUi = R2SippySurfaceUi(),
)

/** Every settings intent; the screen sends these, never a URL, session or command. */
sealed interface R2ConfigurationIntent {
    data class Open(val bucket: String) : R2ConfigurationIntent
    object Close : R2ConfigurationIntent
    data class BucketChanged(val value: String) : R2ConfigurationIntent
    data class JurisdictionChanged(val value: String) : R2ConfigurationIntent
    data class PanelSelected(val panel: R2ConfigurationPanel) : R2ConfigurationIntent
    object Refresh : R2ConfigurationIntent
    object RestoreDocument : R2ConfigurationIntent
    data class RisksChanged(val risks: R2ConfigurationRisks) : R2ConfigurationIntent
    data class TypedTargetNameChanged(val value: String) : R2ConfigurationIntent

    object AddCorsRule : R2ConfigurationIntent
    data class RemoveCorsRule(val index: Int) : R2ConfigurationIntent
    data class CorsIdChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class CorsMethodsChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class CorsOriginsChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class CorsAllowedHeadersChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class CorsExposeHeadersChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class CorsMaxAgeChanged(val index: Int, val value: String) : R2ConfigurationIntent

    object AddLifecycleRule : R2ConfigurationIntent
    data class RemoveLifecycleRule(val index: Int) : R2ConfigurationIntent
    data class LifecycleIdChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LifecycleEnabledChanged(val index: Int, val value: Boolean) : R2ConfigurationIntent
    data class LifecyclePrefixChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LifecycleDeleteModeChanged(val index: Int, val value: R2LifecycleDeleteMode) : R2ConfigurationIntent
    data class LifecycleDeleteAgeChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LifecycleDeleteDateChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LifecycleAbortAgeChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class AddLifecycleTransition(val index: Int) : R2ConfigurationIntent
    data class RemoveLifecycleTransition(val index: Int, val transitionIndex: Int) : R2ConfigurationIntent
    data class LifecycleTransitionTypeChanged(
        val index: Int,
        val transitionIndex: Int,
        val value: R2LifecycleConditionType,
    ) : R2ConfigurationIntent
    data class LifecycleTransitionAgeChanged(
        val index: Int,
        val transitionIndex: Int,
        val value: String,
    ) : R2ConfigurationIntent
    data class LifecycleTransitionDateChanged(
        val index: Int,
        val transitionIndex: Int,
        val value: String,
    ) : R2ConfigurationIntent

    data class LocalUploadsEnabledChanged(val value: Boolean) : R2ConfigurationIntent

    object AddLockRule : R2ConfigurationIntent
    data class RemoveLockRule(val index: Int) : R2ConfigurationIntent
    data class LockIdChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LockEnabledChanged(val index: Int, val value: Boolean) : R2ConfigurationIntent
    data class LockPrefixChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LockConditionTypeChanged(val index: Int, val value: R2LockConditionType) : R2ConfigurationIntent
    data class LockAgeSecondsChanged(val index: Int, val value: String) : R2ConfigurationIntent
    data class LockDateChanged(val index: Int, val value: String) : R2ConfigurationIntent

    /** Explicit whole-configuration delete; only valid on the CORS and LOCK panels. */
    object RequestDeleteConfiguration : R2ConfigurationIntent
    object CancelDeleteConfiguration : R2ConfigurationIntent

    // ---------------------------------------------------------------- batch B2 domain intents
    data class ManagedDomainEnabledChanged(val value: Boolean) : R2ConfigurationIntent
    data class CustomDomainCreateDomainChanged(val value: String) : R2ConfigurationIntent
    data class CustomDomainCreateZoneIdChanged(val value: String) : R2ConfigurationIntent
    data class CustomDomainCreateEnabledChanged(val value: Boolean) : R2ConfigurationIntent
    data class CustomDomainCreateMinTlsChanged(val value: String) : R2ConfigurationIntent
    data class SelectCustomDomain(val domain: String?) : R2ConfigurationIntent
    data class CustomDomainEditEnabledChanged(val value: Boolean?) : R2ConfigurationIntent
    data class CustomDomainEditMinTlsChanged(val value: String?) : R2ConfigurationIntent
    data class TypedDomainNameChanged(val value: String) : R2ConfigurationIntent
    object RequestDeleteCustomDomain : R2ConfigurationIntent
    object CancelDeleteCustomDomain : R2ConfigurationIntent

    // ---------------------------------------------------------------- batch B3 Sippy intents
    /** Requests the real window protection before any secret input is rendered. */
    object BeginSippySecureEditing : R2ConfigurationIntent
    data class SippyProviderSelected(val provider: com.flarego.core.model.R2SippyProvider) : R2ConfigurationIntent
    /** A redacted private-value event: its toString never prints the value. */
    data class SippyFieldChanged(val field: R2SippySecretField, val input: R2SippySecretInput) : R2ConfigurationIntent
    data class SippyTypedBucketChanged(val value: String) : R2ConfigurationIntent
    data class SippyDisclosureAcknowledgedChanged(val acknowledged: Boolean) : R2ConfigurationIntent
    data class SippyCredentialsAcknowledgedChanged(val acknowledged: Boolean) : R2ConfigurationIntent
    data class SippyDisableAcknowledgedChanged(val acknowledged: Boolean) : R2ConfigurationIntent
    object EndSippySecureEditing : R2ConfigurationIntent
    object RequestDisableSippy : R2ConfigurationIntent
    object CancelDisableSippy : R2ConfigurationIntent

    object Preview : R2ConfigurationIntent
    object Confirm : R2ConfigurationIntent
    object CancelPreview : R2ConfigurationIntent
}
