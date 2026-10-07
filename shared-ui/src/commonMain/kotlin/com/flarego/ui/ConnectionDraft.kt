package com.flarego.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In-memory connection input, owned by the Android ViewModel across Activity recreation.
 * Never put this object or its token in a Bundle, SavedStateHandle, or persistent store.
 */
class ConnectionDraft {
    var name by mutableStateOf("")
    var accountId by mutableStateOf("")
    var token by mutableStateOf("")
    internal var isOpen = false

    fun clear() {
        name = ""
        accountId = ""
        token = ""
        isOpen = false
    }
}
