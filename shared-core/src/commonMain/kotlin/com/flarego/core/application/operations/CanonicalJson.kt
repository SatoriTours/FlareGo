package com.flarego.core.application.operations

import com.flarego.core.model.invalidInputFailure
import kotlinx.serialization.json.*

/**
 * Deterministic JSON encoding of a plan input snapshot.
 *
 * Object keys are sorted, array order is preserved, numbers keep their exact
 * lexical text (no Double round-trip, no money rounding) and null/unknown fields
 * survive. Strings and keys use standard JSON escaping while preserving Unicode,
 * control characters and every UTF-16 surrogate as an explicit `\uXXXX` escape, so
 * two different inputs can never collapse into the same bytes. This is not RFC
 * 8785, not a signature, and not a provider idempotency key.
 */
object CanonicalJson {
    fun encode(value: JsonElement): ByteArray {
        val out = StringBuilder()
        write(value, out)
        return out.toString().encodeToByteArray()
    }

    private fun write(value: JsonElement, out: StringBuilder) {
        when (value) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> writePrimitive(value, out)
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) out.append(',')
                    write(element, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun writePrimitive(value: JsonPrimitive, out: StringBuilder) {
        if (value is JsonNull) {
            out.append("null")
            return
        }
        if (value.isString) {
            writeString(value.content, out)
            return
        }
        val literal = value.content
        if (!isValidJsonNumber(literal)) throw invalidInputFailure()
        out.append(literal)
    }

    /** Numbers keep their lexical form; NaN, Infinity and malformed literals are rejected. */
    private fun isValidJsonNumber(text: String): Boolean {
        if (text == "true" || text == "false" || text == "null") return true
        if (text.isEmpty()) return false
        var index = 0
        if (text[index] == '-') index++
        if (index >= text.length) return false
        if (text[index] == '0') {
            index++
        } else if (text[index] in '1'..'9') {
            while (index < text.length && text[index] in '0'..'9') index++
        } else {
            return false
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || text[index] !in '0'..'9') return false
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || text[index] !in '0'..'9') return false
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return index == text.length
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
                character == '\b' -> out.append("\\b")
                character == '\u000C' -> out.append("\\f")
                character.code < 0x20 -> out.append("\\u").append(hex4(character.code))
                // Surrogates are always escaped explicitly, so distinct UTF-16 inputs
                // never share bytes and no platform encoder can substitute a replacement.
                character.code in 0xD800..0xDFFF -> out.append("\\u").append(hex4(character.code))
                else -> out.append(character)
            }
            index++
        }
        out.append('"')
    }

    private fun hex4(code: Int): String = code.toString(16).padStart(4, '0')
}
