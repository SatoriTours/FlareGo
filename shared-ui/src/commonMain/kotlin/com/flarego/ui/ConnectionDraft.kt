package com.flarego.ui

/** Retains only dialog visibility across Activity recreation. OAuth state lives in its owner. */
class ConnectionDraft {
    internal var isOpen = false
    fun clear() { isOpen = false }
}
