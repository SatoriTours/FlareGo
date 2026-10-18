package com.flarego.core.ports

/**
 * Application-supplied private snapshot, not UI-supplied metadata or a provider URI.
 * Implementations bind the digest and length to the same copied bytes and expose at
 * most one source. Transport owns an opened source; release retires only the snapshot.
 * This port does not grant cloud write authority.
 */
interface R2UploadContent {
    val length: Long
    val sha256: String
    suspend fun openSource(): ByteSource
    fun release()
}
