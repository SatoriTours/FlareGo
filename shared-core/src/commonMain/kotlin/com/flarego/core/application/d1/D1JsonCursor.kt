package com.flarego.core.application.d1

import com.flarego.core.model.D1_EDITOR_BYTES
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.ports.ByteSource

private fun invalid(): Nothing = throw invalidInputFailure()

internal data class D1StringMeasure(val utf8Bytes: Long, val validBase64: Boolean, val decodedBase64Bytes: Long)

/** Strict UTF-8 JSON scanner. Only short field names/keys/explicit previews are accumulated. */
internal class D1JsonCursor(private val source: ByteSource, private val max: Long, private val checkActive: () -> Unit) {
    private val buffer = ByteArray(8192)
    private var at = 0; private var size = 0; private var eof = false; private var total = 0L
    private var capture: ByteArray? = null; private var captured = 0; private var exceeded = false
    private fun peek(): Int {
        if (at == size && !eof) {
            checkActive(); val n = source.read(buffer)
            if (n == -1) eof = true else { if (n !in 1..buffer.size) invalid(); at = 0; size = n }
        }
        return if (eof) -1 else buffer[at].toInt() and 255
    }
    private fun take(): Int {
        val next = peek(); if (next == -1) invalid()
        at++; if (++total > max) invalid()
        capture?.let { if (captured < it.size) it[captured++] = next.toByte() else exceeded = true }
        return next
    }
    private fun whitespace() { while (peek() in listOf(32, 9, 10, 13)) take() }
    fun peekValue(): Int { whitespace(); return peek() }
    private fun expect(value: Char) { whitespace(); if (take() != value.code) invalid() }
    private fun optional(value: Char): Boolean { whitespace(); if (peek() != value.code) return false; take(); return true }
    fun end() { whitespace(); if (peek() != -1 || total != max) invalid() }
    /** Full UTF-8 SQL file validation without changing quotes, comments or semicolons. */
    fun sqlText() {
        var nonblank = false
        while (peek() != -1) {
            val first = take()
            val cp = if (first <= 127) first else {
                val count = when (first) { in 0xc2..0xdf -> 1; in 0xe0..0xef -> 2; in 0xf0..0xf4 -> 3; else -> invalid() }
                var point = first and (0x7f shr count + 1)
                repeat(count) { val next = take(); if (next !in 128..191) invalid(); point = (point shl 6) or (next and 63) }
                if (point < when (count) { 1 -> 128; 2 -> 2048; else -> 65536 } || point > 0x10ffff || point in 0xd800..0xdfff) invalid()
                point
            }
            if (cp == 0) invalid()
            if (cp > 65535 || !cp.toChar().isWhitespace()) nonblank = true
        }
        if (!nonblank || total != max) invalid()
    }
    fun array(item: () -> Unit) {
        expect('['); if (optional(']')) return
        while (true) { item(); if (optional(']')) break; expect(',') }
    }
    fun obj(maxKeyBytes: Int = 512, field: (String) -> Unit) {
        expect('{'); if (optional('}')) return
        val names = mutableSetOf<String>()
        while (true) {
            val name = string(maxKeyBytes)
            if (!names.add(name) || names.size > 10000) invalid()
            expect(':'); field(name)
            if (optional('}')) break
            expect(',')
        }
    }
    fun boolean(): Boolean = when (peekValue()) {
        't'.code -> { literal("true"); true }
        'f'.code -> { literal("false"); false }
        else -> invalid()
    }
    private fun literal(text: String) { for (c in text) if (take() != c.code) invalid() }
    fun long(): Long = number(24)?.toLongOrNull() ?: invalid()
    private fun number(maxText: Int = 0): String? {
        whitespace(); val text = if (maxText > 0) StringBuilder() else null
        fun digit() = peek() in '0'.code..'9'.code
        fun consume() { val n = take(); text?.let { if (it.length == maxText) invalid(); it.append(n.toChar()) } }
        if (peek() == '-'.code) consume()
        if (peek() == '0'.code) { consume(); if (digit()) invalid() }
        else { if (peek() !in '1'.code..'9'.code) invalid(); while (digit()) consume() }
        if (peek() == '.'.code) { consume(); if (!digit()) invalid(); while (digit()) consume() }
        if (peek() in listOf('e'.code, 'E'.code)) {
            consume(); if (peek() in listOf('+'.code, '-'.code)) consume()
            if (!digit()) invalid(); while (digit()) consume()
        }
        return text?.toString()
    }
    fun string(maxBytes: Int): String {
        val text = StringBuilder(); var bytes = 0
        stringPoints { cp ->
            bytes += utf8Size(cp); if (bytes > maxBytes) invalid()
            if (cp <= 65535) text.append(cp.toChar()) else {
                val n = cp - 65536; text.append((0xd800 + (n shr 10)).toChar()); text.append((0xdc00 + (n and 1023)).toChar())
            }
        }
        return text.toString()
    }
    fun measureString(maxBytes: Long): D1StringMeasure {
        var bytes = 0L; var chars = 0L; var valid = true; var padding = 0; var lastValue = 0
        stringPoints { cp ->
            bytes += utf8Size(cp); if (bytes > maxBytes) invalid()
            chars++
            when {
                cp == '='.code -> { padding++; if (padding > 2) valid = false }
                else -> {
                    val index = BASE64.indexOf(cp.toChar()).takeIf { cp < 128 } ?: -1
                    if (index < 0 || padding != 0) valid = false
                    lastValue = index
                }
            }
        }
        if (chars % 4 != 0L || padding > 0 && chars == 0L) valid = false
        if (padding == 1 && lastValue and 3 != 0 || padding == 2 && lastValue and 15 != 0) valid = false
        return D1StringMeasure(bytes, valid, chars / 4 * 3 - padding)
    }
    fun stringPoints(consume: (Int) -> Unit) {
        expect('"')
        while (true) {
            when (val c = take()) {
                '"'.code -> return
                '\\'.code -> {
                    val cp = when (take()) {
                        '"'.code -> '"'.code; '\\'.code -> '\\'.code; '/'.code -> '/'.code
                        'b'.code -> 8; 'f'.code -> 12; 'n'.code -> 10; 'r'.code -> 13; 't'.code -> 9
                        'u'.code -> {
                            val first = hex4()
                            if (first in 0xd800..0xdbff) {
                                if (take() != '\\'.code || take() != 'u'.code) invalid()
                                val low = hex4(); if (low !in 0xdc00..0xdfff) invalid()
                                65536 + ((first - 0xd800) shl 10) + low - 0xdc00
                            } else { if (first in 0xdc00..0xdfff) invalid(); first }
                        }
                        else -> invalid()
                    }
                    consume(cp)
                }
                in 0..31 -> invalid()
                in 32..127 -> consume(c)
                else -> {
                    val count = when (c) { in 0xc2..0xdf -> 1; in 0xe0..0xef -> 2; in 0xf0..0xf4 -> 3; else -> invalid() }
                    var cp = c and (0x7f shr count + 1)
                    repeat(count) { val next = take(); if (next !in 128..191) invalid(); cp = (cp shl 6) or (next and 63) }
                    if (cp < when (count) { 1 -> 128; 2 -> 2048; else -> 65536 } || cp > 0x10ffff || cp in 0xd800..0xdfff) invalid()
                    consume(cp)
                }
            }
        }
    }
    private fun hex4(): Int {
        var n = 0
        repeat(4) { n = n * 16 + (take().toChar().digitToIntOrNull(16) ?: invalid()) }
        return n
    }
    fun value(depth: Int = 0) {
        if (depth > 64) invalid()
        when (peekValue()) {
            '"'.code -> stringPoints { }
            '{'.code -> {
                expect('{'); if (optional('}')) return
                while (true) { stringPoints { }; expect(':'); value(depth + 1); if (optional('}')) break; expect(',') }
            }
            '['.code -> array { value(depth + 1) }
            'n'.code -> literal("null")
            't'.code, 'f'.code -> boolean()
            else -> number()
        }
    }
    fun capture(maxBytes: Int, action: () -> Unit): ByteArray? {
        if (capture != null || maxBytes < 0 || maxBytes > D1_EDITOR_BYTES) invalid()
        whitespace(); val bytes = ByteArray(maxBytes)
        capture = bytes; captured = 0; exceeded = false
        try { action(); return if (exceeded) null else bytes.copyOf(captured) }
        finally { capture = null; bytes.fill(0) }
    }
    private fun utf8Size(cp: Int) = when { cp <= 127 -> 1; cp <= 2047 -> 2; cp <= 65535 -> 3; else -> 4 }
    private companion object { const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" }
}
