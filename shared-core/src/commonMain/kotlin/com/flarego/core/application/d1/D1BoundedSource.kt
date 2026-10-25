package com.flarego.core.application.d1

import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.ByteSource

/** Counts actual bytes with subtraction, including the byte beyond a Long-sized limit. */
class D1BoundedSource(private val source: ByteSource, private val maxBytes: Long) : ByteSource {
    var count = 0L
        private set
    private var closed = false
    init { if (maxBytes < 0) { source.close(); throw invalidInputFailure() } }
    override fun read(buffer: ByteArray): Int {
        if (closed || buffer.isEmpty()) throw invalidInputFailure()
        val n = source.read(buffer)
        if (n == -1) return -1
        if (n !in 1..buffer.size || n.toLong() > maxBytes - count) throw invalidInputFailure()
        count += n
        return n
    }
    override fun close() { if (!closed) { closed = true; source.close() } }
}

object D1ImportText {
    suspend fun validate(content: com.flarego.core.ports.D1Content) = scanD1(content) { it.sqlText() }
}
