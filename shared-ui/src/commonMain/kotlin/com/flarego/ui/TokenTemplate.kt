package com.flarego.ui

import androidx.compose.ui.platform.ClipEntry
import com.flarego.core.application.connection.ReadFeature
import com.flarego.core.application.connection.TokenUpgradePlan
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val UserTokenManagementUrl = "https://dash.cloudflare.com/profile/api-tokens"

internal fun tokenTemplateUrl(plan: TokenUpgradePlan? = null): String {
    val features = plan?.features ?: ReadFeature.entries
    val keys = features.map { it.key }.distinct()
    val permissions = buildJsonArray {
        keys.forEach { key ->
            add(buildJsonObject {
                put("key", key)
                put("type", "read")
            })
        }
    }
    val account = (plan?.accountId ?: "*").encodeQueryValue()
    val zone = (plan?.zoneId ?: "all").encodeQueryValue()
    return UserTokenManagementUrl +
        "?permissionGroupKeys=${permissions.toString().encodeQueryValue()}&accountId=$account&zoneId=$zone&name=FlareGo%20Read%20Only"
}

private fun String.encodeQueryValue(): String = buildString {
    val hex = "0123456789ABCDEF"
    for (byte in encodeToByteArray()) {
        val number = byte.toInt() and 0xff
        val char = number.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") append(char)
        else append('%').append(hex[number shr 4]).append(hex[number and 15])
    }
}

internal expect fun ClipEntry.plainTokenText(): String?
