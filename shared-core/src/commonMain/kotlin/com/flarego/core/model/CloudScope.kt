package com.flarego.core.model

/**
 * Identity of a Cloudflare scope, bound to a local connection.
 *
 * Scopes carry identifiers verbatim: no trimming, case folding or "32 hex"
 * shape guessing. Display names are never identity. Equality is full
 * structural equality, so the same external resource id under a different
 * connection, account, zone, user or organization is a different resource.
 */
sealed interface CloudScope

/** An account owned by a local connection. */
data class AccountScope(val connectionId: String, val accountId: String) : CloudScope {
    init {
        if (connectionId.isBlank() || accountId.isBlank()) throw invalidInputFailure()
    }
}

/**
 * A zone inside an account. The connection is derived from [account], never
 * stored as an independent, forgeable field.
 */
data class ZoneScope(val account: AccountScope, val zoneId: String) : CloudScope {
    val connectionId: String
        get() = account.connectionId

    init {
        if (zoneId.isBlank()) throw invalidInputFailure()
    }
}

/** A user identity owned by a local connection; never inferred from an account id. */
data class UserScope(val connectionId: String, val userId: String) : CloudScope {
    init {
        if (connectionId.isBlank() || userId.isBlank()) throw invalidInputFailure()
    }
}

/** An organization identity owned by a local connection; never inferred from an account id. */
data class OrganizationScope(val connectionId: String, val organizationId: String) : CloudScope {
    init {
        if (connectionId.isBlank() || organizationId.isBlank()) throw invalidInputFailure()
    }
}

/** A provider resource identified by its scope, kind and verbatim external id. */
data class ResourceRef(val scope: CloudScope, val kind: String, val externalId: String) {
    init {
        if (kind.isBlank() || externalId.isBlank()) throw invalidInputFailure()
    }
}

/**
 * Local ownership check for a scope.
 *
 * A passing check only proves the scope belongs to the given local connection;
 * it never proves Cloudflare authorization or entitlement. Real permission and
 * owner proof belong to later capability and identity policy.
 */
object ScopeGuard {
    fun requireOwned(scope: CloudScope, connection: Connection) {
        val owned =
            when (scope) {
                is AccountScope ->
                    scope.connectionId == connection.id && scope.accountId == connection.accountId
                is ZoneScope ->
                    scope.connectionId == connection.id &&
                        scope.account.accountId == connection.accountId
                // A connection has no user or organization owner id, so only the
                // connection binding is checkable here. The account id is never
                // used to guess user or organization ownership.
                is UserScope -> scope.connectionId == connection.id
                is OrganizationScope -> scope.connectionId == connection.id
            }
        if (!owned) throw scopeMismatchFailure()
    }
}
