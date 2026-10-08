package com.flarego.core.ports

/** A SHA-256 hex fingerprint of exact bytes. */
fun interface FingerprintPort {
    fun sha256(bytes: ByteArray): String
}
