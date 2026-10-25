package com.flarego.core.ports

/** Safe random locator; never a content hash, document URI, or private file name. */
data class KvContentDescriptor(val handle: String, val length: Long)

/** Owner-scoped, encrypted, immutable content. Implementations never print plaintext or paths. */
interface KvContent : AutoCloseable {
    val descriptor: KvContentDescriptor
    /** Authenticates the ENTIRE sealed content before returning any readable prefix. */
    suspend fun openVerified(): ByteSource
    /** Revokes existing readers as well as future opens; idempotent. */
    override fun close()
}

/** Closing a transport sink is not success; only explicit seal commits a complete response. */
interface KvContentWriter : ByteSink {
    suspend fun seal(): KvContent
    fun abort()
}

interface KvContentStore {
    fun writer(maxBytes: Long): KvContentWriter
    suspend fun stage(source: ByteSource, maxBytes: Long): KvContent
    fun releaseAll()
}
