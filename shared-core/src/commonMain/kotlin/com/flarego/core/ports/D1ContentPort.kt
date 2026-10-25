package com.flarego.core.ports

/** A random owner-scoped locator, never a URI, path or content hash. */
data class D1ContentDescriptor(val handle: String, val length: Long)
interface D1Content : AutoCloseable {
    val descriptor: D1ContentDescriptor
    /** Entire sealed content is authenticated before a prefix is made readable. */
    suspend fun openVerified(): ByteSource
    override fun close()
}
interface D1ContentWriter : ByteSink {
    suspend fun seal(): D1Content
    fun abort()
}
interface D1ContentStore {
    fun writer(maxBytes: Long): D1ContentWriter
    suspend fun stage(source: ByteSource, maxBytes: Long): D1Content
    fun releaseAll()
}
