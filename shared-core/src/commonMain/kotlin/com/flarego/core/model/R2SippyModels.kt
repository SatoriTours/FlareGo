package com.flarego.core.model

/**
 * The four reviewed Sippy source providers of batch B3.
 *
 * The destination is always the current R2 bucket (`provider: "r2"`); this enum only names the
 * source shape the pinned `r2_put-bucket-sippy-config` request supports.
 */
enum class R2SippyProvider(val wireValue: String) {
    AWS("aws"),
    GCS("gcs"),
    S3("s3"),
    AZURE("azure"),
}

/** The public destination metadata of a configured Sippy rule (never a secret). */
data class R2SippyDestinationRef(
    val account: String,
    val bucket: String,
    val accessKeyId: String,
)

/**
 * The public source metadata of a configured Sippy rule.
 *
 * `bucketUrl` is provider-echoed text that has already passed the client's safe-URL validation
 * (no userinfo, no query/fragment); it is display-only and is never used to contact a host.
 */
data class R2SippySourceRef(
    val provider: R2SippyProvider,
    val bucket: String? = null,
    val region: String? = null,
    val container: String? = null,
    val bucketUrl: String? = null,
)

/**
 * A safe allowlist projection of `r2-get-bucket-sippy-config`.
 *
 * The provider's raw result is never retained: only allowlisted fields become typed values, and an
 * unknown/echoed member (including a nested one) makes the snapshot read-only instead of keeping
 * the raw JSON. A read-only snapshot is display-only and can never be used to build a write.
 */
data class R2SippySnapshot(
    val enabled: Boolean,
    val destination: R2SippyDestinationRef?,
    val source: R2SippySourceRef?,
    val editable: Boolean,
    val readOnlyReason: String? = null,
    /**
     * True when the provider included the full public destination/source details.
     *
     * The pinned `r2_sippy` object has no required member, so a 200 with `enabled=false` and no
     * details is a legitimate "disabled, public details not provided" shape — never a 404 or an
     * absence claim. [editable] then allows an explicit full new configuration; with `enabled=true`
     * and no details the snapshot stays read-only because the current configuration is not visible.
     */
    val detailsProvided: Boolean = destination != null && source != null,
)

/** The two reviewed Sippy writes: enable migration with new credentials, or disable it. */
enum class R2SippyAction { ENABLE, DISABLE }

/**
 * The complete safe payload of one Sippy preview.
 *
 * It exists so the later UI slice can render a reviewed preview without ever holding, echoing or
 * re-deriving a credential: only the source provider and the public metadata the provider itself
 * returns from a GET appear here. The destination access-key pair is represented by presence only.
 */
data class R2SippyWritePreview(
    val action: R2SippyAction,
    val target: R2WriteTarget,
    val provider: R2SippyProvider? = null,
    val source: R2SippySourceRef? = null,
    /** True when this draft supplies the existing R2 access-key pair; the values never appear. */
    val destinationAccessKeyConfigured: Boolean = false,
)

/** Client-side safety bounds; these are not provider limits. */
const val R2_SIPPY_MAX_SECRET_BYTES: Int = 8 * 1024

/** The complete sensitive request body stays within the same safe editing bound as B1/B2. */
const val R2_SIPPY_MAX_BODY_BYTES: Int = 256 * 1024
