package com.flarego.core.application.inventory

import com.flarego.core.model.CredentialKind

/**
 * Read-only capability decisions for one connection.
 *
 * The build is Token-only: an API Token may attempt every registered read directory, while a
 * legacy OAuth record (or an absent credential) may request nothing until a Token is configured.
 * Keeping the name and local record of an old OAuth connection is allowed; sending its old
 * grant to the provider is not.
 */
object ProductAccess {
    /** Read directories a plain API Token may attempt. */
    val tokenReadable: Set<ProductKey> = ProductKey.entries.toSet()

    /** The product set a connection may attempt; anything but a Token requests nothing. */
    fun readable(kind: CredentialKind, demo: Boolean): Set<ProductKey> = when {
        demo -> ProductKey.entries.toSet()
        kind == CredentialKind.TOKEN -> tokenReadable
        else -> emptySet()
    }

    /**
     * Registrar domain discovery is a read-only endpoint a Token may call on demand. A legacy
     * OAuth connection cannot ask at all, because OAuth is no longer a usable credential here.
     */
    fun registrarReadable(kind: CredentialKind, demo: Boolean): Boolean =
        demo || kind == CredentialKind.TOKEN
}
