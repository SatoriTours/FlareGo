package com.flarego.core.cloudflare.kv

import com.flarego.core.application.kv.KvValidation
import com.flarego.core.cloudflare.encodePath

/**
 * Only KV's final key parameter is opaque URL-encoded data. Decode exactly once,
 * require canonical UTF-8 encoding and a valid key, then mask that ONE segment for
 * structural URL checks. Account/namespace/query still undergo ordinary checks.
 * The original encoded URL is sent unchanged; decoded key text never becomes a URL.
 */
object KvEncodedKeyPath {
    fun maskForValidation(raw: String): String? {
        val path = raw.substringBefore('?')
        val parts = path.split('/')
        if (parts.size != 9 || parts[0] != "" || parts[1] != "accounts" ||
            parts[3] != "storage" || parts[4] != "kv" || parts[5] != "namespaces" ||
            parts[7] !in setOf("values", "metadata")) return null
        return try {
            val encoded = parts.last()
            val bytes = ByteArray(encoded.length)
            var at = 0; var size = 0
            while (at < encoded.length) {
                if (encoded[at] == '%') {
                    if (at + 2 >= encoded.length) return null
                    bytes[size++] = encoded.substring(at + 1, at + 3).toInt(16).toByte(); at += 3
                } else {
                    if (encoded[at].code !in 0x21..0x7e) return null
                    bytes[size++] = encoded[at++].code.toByte()
                }
            }
            val key = bytes.decodeToString(0, size, throwOnInvalidSequence = true)
            KvValidation.key(key)
            if (encodePath(key) != encoded) return null
            parts.dropLast(1).joinToString("/") + "/_kv_key_" +
                if ('?' in raw) "?" + raw.substringAfter('?') else ""
        } catch (_: Exception) { null }
    }
}
