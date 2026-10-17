package com.flarego.core.cloudflare.protocol

/** Four auxiliary READs; not part of the candidate 54 or Token management writes. */
internal fun tokenAuthorityRegistrations(): Array<ActionRegistration> {
    fun read(id: String, path: String, scope: ActionScope) = ActionRegistration(
        ActionPolicy(id, ActionSemantics.READ, ResponseKind.ENVELOPE_JSON, RiskLevel.READ_ONLY, ReadRetryPolicy(enabled = true)),
        "GET", path, scope)
    return arrayOf(
        read("user-api-tokens-token-details", "/user/tokens/{token_id}", ActionScope.USER),
        read("account-api-tokens-token-details", "/accounts/{account_id}/tokens/{token_id}", ActionScope.ACCOUNT),
        read("permission-groups-list-permission-groups", "/user/tokens/permission_groups", ActionScope.USER),
        read("account-api-tokens-list-permission-groups", "/accounts/{account_id}/tokens/permission_groups", ActionScope.ACCOUNT),
    )
}
