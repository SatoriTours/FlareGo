package com.flarego.platform

import com.flarego.core.ports.FingerprintPort
import java.security.MessageDigest

/** JDK SHA-256 fingerprint; no Android or product dependency. */
class AndroidFingerprint : FingerprintPort {
    override fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}
