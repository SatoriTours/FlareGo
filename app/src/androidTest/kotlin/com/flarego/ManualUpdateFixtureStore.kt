package com.flarego

import com.flarego.core.updates.UpdatePreferences
import com.flarego.core.updates.UpdateStore

/** Instrumentation owns all settings and never starts an automatic online check. */
internal class ManualUpdateFixtureStore : UpdateStore {
    private var preferences = UpdatePreferences(automatic = false)
    override fun read() = preferences
    override fun write(preferences: UpdatePreferences) {
        this.preferences = preferences
    }
}
