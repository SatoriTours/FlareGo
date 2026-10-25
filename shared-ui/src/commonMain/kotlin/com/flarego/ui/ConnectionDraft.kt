package com.flarego.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flarego.core.model.PageToken
import com.flarego.core.ports.AccountSummary
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.TokenVerification

/** In-memory only: survives Activity recreation, never saved to a Bundle or disk. */
class ConnectionDraft {
    var name by mutableStateOf("")
    var manualToken by mutableStateOf(false)
    private var accountValue by mutableStateOf("")
    private var tokenValue by mutableStateOf("")
    private var kindValue by mutableStateOf(TokenKind.UNKNOWN)
    var revision by mutableStateOf(0L)
        private set
    var accountId: String
        get() = accountValue
        set(value) {
            if (accountValue != value) { accountValue = value; revision++; tokenVerification = null }
        }
    var token: String
        get() = tokenValue
        set(value) {
            if (tokenValue != value) {
                tokenValue = value
                revision++
                tokenVerification = null
                discoveredAccounts = emptyList()
                nextAccountsPage = null
            }
        }
    var tokenKind: TokenKind
        get() = kindValue
        set(value) {
            if (kindValue != value) { kindValue = value; revision++; tokenVerification = null }
        }
    var discoveredAccounts by mutableStateOf<List<AccountSummary>>(emptyList())
        internal set
    var nextAccountsPage by mutableStateOf<PageToken?>(null)
        internal set
    var tokenVerification by mutableStateOf<TokenVerification?>(null)
        internal set
    internal var isOpen = false

    fun clear() {
        name = ""
        manualToken = false
        accountId = ""
        token = ""
        tokenKind = TokenKind.UNKNOWN
        discoveredAccounts = emptyList()
        nextAccountsPage = null
        tokenVerification = null
        revision++
        isOpen = false
    }
}
