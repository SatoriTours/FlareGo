package com.flarego.platform

import com.flarego.core.ports.*
import java.io.File

/**
 * D1 owns a dedicated directory and its own keys/readers. Only the authenticated
 * record storage mechanism is reused; no KV product handle crosses this API.
 */
class D1EncryptedContentStore(directory: File) : D1ContentStore {
    private val records = KvEncryptedContentStore(directory)
    override fun writer(maxBytes: Long): D1ContentWriter {
        val writer = records.writer(maxBytes)
        return object : D1ContentWriter {
            override fun write(buffer: ByteArray, offset: Int, count: Int) = writer.write(buffer, offset, count)
            override fun close() = writer.close()
            override fun abort() = writer.abort()
            override suspend fun seal(): D1Content = wrap(writer.seal())
        }
    }
    override suspend fun stage(source: ByteSource, maxBytes: Long): D1Content = wrap(records.stage(source, maxBytes))
    override fun releaseAll() = records.releaseAll()
    private fun wrap(content: KvContent): D1Content = object : D1Content {
        override val descriptor = D1ContentDescriptor(content.descriptor.handle, content.descriptor.length)
        override suspend fun openVerified(): ByteSource = content.openVerified()
        override fun close() = content.close()
        override fun toString() = "D1Content(redacted)"
    }
}
