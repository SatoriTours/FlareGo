package com.flarego.core.model

/**
 * Non-authoritative Registrar search suggestion. Search results are cached by the provider and
 * must never be presented as proof of availability or as a confirmed price.
 */
data class RegistrarCandidate(
    val name: String,
    val registrable: Boolean?,
    val tier: String? = null,
    val currency: String? = null,
    val registrationCost: String? = null,
    val renewalCost: String? = null,
    val reason: String? = null,
)

/** Read-only search state; no purchase is implied by a candidate row. */
data class RegistrarSearch(
    val scope: CloudScope? = null,
    val keyword: String = "",
    val searching: Boolean = false,
    val results: List<RegistrarCandidate> = emptyList(),
    val observedAtMs: Long? = null,
    val error: String? = null,
    val notice: String? = null,
    val unsupported: Boolean = false,
)

sealed interface RegistrarIntent {
    data class Query(val keyword: String) : RegistrarIntent
    data object Retry : RegistrarIntent
    data object Clear : RegistrarIntent
}
