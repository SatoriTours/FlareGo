package com.flarego.ui

import androidx.compose.ui.platform.ClipEntry
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Short keys from Cloudflare's official user-token template reference, not API group IDs.
// Registrar is intentionally absent: the reference has no documented short key for it.
internal enum class TokenPurpose(val tag: String, val title: String, vararg val keys: String) {
    DOMAINS("domains", "域名与 DNS", "zone", "dns"),
    WORKERS("workers", "Workers 脚本", "workers_scripts"),
    STORAGE("storage", "KV 与 R2 存储", "workers_kv_storage", "workers_r2"),
    BILLING("billing", "账单历史", "billing");

    val bit: Int get() = 1 shl ordinal
}

internal fun tokenTemplateUrl(purposes: Collection<TokenPurpose>): String? {
    if (purposes.isEmpty()) return null
    val keys = (listOf("account_settings") + purposes.flatMap { it.keys.asList() }).distinct()
    val permissions = buildJsonArray {
        keys.forEach { key ->
            add(buildJsonObject {
                put("key", key)
                put("type", "read")
            })
        }
    }
    return "https://dash.cloudflare.com/profile/api-tokens" +
        "?permissionGroupKeys=${permissions.toString().encodeQueryValue()}&accountId=%2A&zoneId=all&name=FlareGo%20Read%20Only"
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
