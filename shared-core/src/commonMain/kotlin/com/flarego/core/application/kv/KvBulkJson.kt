package com.flarego.core.application.kv

import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*

object KvBulkJson {
    suspend fun prepareWrite(content: KvContent, nowSeconds: Long): KvBulkWriteInput = scan(content, KV_MAX_BULK_BYTES - 1) { reader ->
        val keys = mutableListOf<String>(); val unique = mutableSetOf<String>()
        reader.array {
            if (keys.size == 10000) invalid()
            var key: String? = null; var value: StringMeasure? = null; var base64 = false
            var expiry: KvExpiry = KvExpiry.Never; var expirySeen = false
            reader.obj { name ->
                when (name) {
                    "key" -> key = KvValidation.key(reader.string(KV_MAX_KEY_BYTES))
                    "value" -> value = reader.measureString(4 * ((KV_MAX_VALUE_BYTES + 2) / 3))
                    "base64" -> base64 = reader.boolean()
                    "metadata" -> {
                        val json = reader.capture(16384) { reader.value() } ?: invalid()
                        KvValidation.metadata(json.decodeToString(throwOnInvalidSequence = true))
                    }
                    "expiration", "expiration_ttl" -> {
                        if (expirySeen) invalid()
                        expirySeen = true
                        val seconds = reader.long()
                        expiry = if (name == "expiration") KvExpiry.Absolute(seconds) else KvExpiry.Ttl(seconds)
                    }
                    else -> invalid()
                }
            }
            val actualKey = key ?: invalid(); val actualValue = value ?: invalid()
            if (!unique.add(actualKey)) invalid()
            if (base64) {
                if (!actualValue.validBase64 || actualValue.decodedBase64Bytes > KV_MAX_VALUE_BYTES) invalid()
            } else if (actualValue.utf8Bytes > KV_MAX_VALUE_BYTES) invalid()
            KvValidation.expiry(expiry, nowSeconds)
            keys.add(actualKey)
        }
        if (keys.isEmpty()) invalid()
        reader.end()
        KvBulkWriteInput(content, keys)
    }
    suspend fun prepareDelete(content: KvContent): List<String> = scan(content, KV_MAX_BULK_BYTES - 1) { reader ->
        val keys = mutableListOf<String>()
        reader.array { if (keys.size == 10000) invalid(); keys.add(reader.string(KV_MAX_KEY_BYTES)) }
        reader.end(); KvValidation.keys(keys, 10000)
    }
    suspend fun validateRead(content: KvContent, input: KvBulkReadInput) { read(content, input, null, 0) }
    /** Full response remains encrypted; selected raw JSON only, bounded before allocation. */
    suspend fun previewRead(content: KvContent, input: KvBulkReadInput, key: String, maxBytes: Int = KV_EDITOR_BYTES): String? {
        if (key !in input.keys || maxBytes !in 1..KV_EDITOR_BYTES) invalid()
        return read(content, input, key, maxBytes)
    }

    private suspend fun read(content: KvContent, input: KvBulkReadInput, selected: String?, max: Int): String? = scan(content, 16L * 1024 * 1024 * 1024) { reader ->
        val requested = KvValidation.keys(input.keys, 100).toSet()
        val found = mutableSetOf<String>()
        var success: Boolean? = null; var result = false; var values = false; var preview: String? = null
        fun item() {
            if (input.withMetadata && reader.peekValue() != 'n'.code) {
                var value = false; var metadata = false
                reader.obj { name ->
                    when (name) {
                        "value" -> { value = true; reader.readValueType(input.type) }
                        "metadata" -> { metadata = true; reader.value() }
                        "expiration" -> if (reader.long() < 0) invalid()
                        else -> reader.value()
                    }
                }
                if (!value || !metadata) invalid()
            } else reader.readValueType(input.type)
        }
        reader.obj { name ->
            when (name) {
                "success" -> success = reader.boolean()
                "result" -> {
                    result = true
                    reader.obj { field ->
                        if (field == "values") {
                            values = true
                            reader.obj(KV_MAX_KEY_BYTES) { key ->
                                if (key !in requested || !found.add(key)) invalid()
                                if (key == selected) preview = reader.capture(max) { item() }?.decodeToString(throwOnInvalidSequence = true)
                                else item()
                            }
                        } else reader.value()
                    }
                }
                else -> reader.value()
            }
        }
        reader.end()
        if (success != true || !result || !values || found != requested) invalid()
        preview
    }
    private fun JsonCursor.readValueType(type: KvBulkReadType) {
        if (type == KvBulkReadType.text && peekValue() !in setOf('"'.code, 'n'.code)) invalid()
        value()
    }
    private suspend fun <T> scan(content: KvContent, max: Long, action: (JsonCursor) -> T): T = withContext(Dispatchers.Default) {
        if (content.descriptor.length !in 1..max) invalid()
        val source = content.openVerified()
        val context = currentCoroutineContext()
        try { action(JsonCursor(source, max) { context.ensureActive() }) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { invalid() }
        finally { source.close() }
    }
}

private fun invalid(): Nothing = throw invalidInputFailure()

private data class StringMeasure(val utf8Bytes: Long, val validBase64: Boolean, val decodedBase64Bytes: Long)

/** Strict UTF-8 JSON scanner. Only short field names/keys/explicit previews are accumulated. */
private class JsonCursor(private val source: ByteSource, private val max: Long, private val checkActive: () -> Unit) {
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
    fun end() { whitespace(); if (peek() != -1) invalid() }
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
    fun measureString(maxBytes: Long): StringMeasure {
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
        return StringMeasure(bytes, valid, chars / 4 * 3 - padding)
    }
    private fun stringPoints(consume: (Int) -> Unit) {
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
        if (capture != null || maxBytes < 0 || maxBytes > KV_EDITOR_BYTES) invalid()
        whitespace(); val bytes = ByteArray(maxBytes)
        capture = bytes; captured = 0; exceeded = false
        try { action(); return if (exceeded) null else bytes.copyOf(captured) }
        finally { capture = null; bytes.fill(0) }
    }
    private fun utf8Size(cp: Int) = when { cp <= 127 -> 1; cp <= 2047 -> 2; cp <= 65535 -> 3; else -> 4 }
    private companion object { const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" }
}
