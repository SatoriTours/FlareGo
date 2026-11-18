package com.flarego.core.model

/**
 * How a stored credential authenticates.
 *
 * Capabilities are decided by this kind, never by an OAuth scope list: an API Token is not
 * granted or denied a product because some OAuth scope happens to be present. Writes are a
 * separate question and remain gated by the existing operation proof chain.
 */
enum class CredentialKind {
    /** No credential is stored for this connection id. */
    ABSENT,

    /** OAuth grant with the exact scopes the read profile requested. */
    OAUTH,

    /** Plain Cloudflare API Token (user-owned or account-owned). */
    TOKEN,
}
