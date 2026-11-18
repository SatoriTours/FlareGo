package com.flarego

import android.content.Context
import com.flarego.platform.CredentialVault
import com.flarego.platform.TokenVault

/**
 * One app-wide credential store: replace/remove cannot resurrect a retired credential.
 *
 * The [TokenVault] is unchanged (Android Keystore AES/GCM); only the value semantics moved from
 * the retired OAuth helper into [CredentialVault], which also recognizes legacy OAuth records.
 *
 * The commit-gate resolver is bound separately (and idempotently) so the cached vault always
 * consults the *current* durable gate instead of whatever callback happened to create it first.
 */
internal object CredentialVaultOwner {
    private var vault: CredentialVault? = null
    private var gateResolver: suspend (String) -> Boolean = { false }

    /** Bound by the owner once the gate store is available; later calls replace the resolver. */
    @Synchronized
    fun bindGate(resolver: suspend (String) -> Boolean) {
        gateResolver = resolver
    }

    @Synchronized
    fun get(context: Context): CredentialVault = vault ?: run {
        val storage = TokenVault(context.applicationContext)
        CredentialVault(
            read = storage::get,
            write = storage::put,
            delete = storage::remove,
            blocked = { id -> gateResolver(id) },
        ).also { vault = it }
    }
}
