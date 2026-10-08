package com.flarego.core.model

/** Stable, app-owned classification of a Cloudflare failure. */
enum class CloudFailureCode {
    SCOPE_MISMATCH,
    PERMISSION_REQUIRED,
    NOT_FOUND,
    CREDENTIAL_EXPIRED,
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE,
    CONFLICT,
    UNKNOWN_RESULT,
    INVALID_INPUT,
    UNSUPPORTED_CAPABILITY,
}

/**
 * A typed Cloudflare failure whose [publicMessage] is safe application text.
 *
 * The message is written for the user: raw provider response bodies, causes and
 * credentials never enter this model. [uncertain] marks an operation whose
 * provider-side result is unknown, so the caller must verify instead of
 * replaying it.
 */
class CloudFailure(
    val code: CloudFailureCode,
    val publicMessage: String,
    val uncertain: Boolean = false,
    val requestId: String? = null,
) : Exception(publicMessage)


/** Minimal bridge for code that still speaks the legacy [CloudException]. */
fun CloudFailure.toCloudException(): CloudException = CloudException(publicMessage, uncertain)

/**
 * Fixed validation failure.
 *
 * The message is a constant: rejected input values are never echoed back, so an
 * untrusted identifier, token or path cannot leak through an error message.
 */
internal fun invalidInputFailure(): CloudFailure =
    CloudFailure(CloudFailureCode.INVALID_INPUT, "输入无效：标识不能为空，数值必须为正数")

/** Fixed ownership failure that never echoes the rejected identifiers. */
internal fun scopeMismatchFailure(): CloudFailure =
    CloudFailure(CloudFailureCode.SCOPE_MISMATCH, "该资源不属于当前连接或账号，请切换账号后重试")
