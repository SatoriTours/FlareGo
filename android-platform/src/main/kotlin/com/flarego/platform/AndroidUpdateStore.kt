package com.flarego.platform

import android.content.Context
import com.flarego.core.updates.*

class AndroidUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)

    override fun read() =
        UpdatePreferences(
            UpdateChannel.entries.find { it.key == prefs.getString("channel", "release") }
                ?: UpdateChannel.RELEASE,
            prefs.getBoolean("automatic", true),
            prefs.getLong("last_attempt", 0),
        )

    override fun write(preferences: UpdatePreferences) {
        check(
            prefs
                .edit()
                .putString("channel", preferences.channel.key)
                .putBoolean("automatic", preferences.automatic)
                .putLong("last_attempt", preferences.lastAttemptMillis)
                .commit()
        ) {
            "无法保存更新设置"
        }
    }
}
