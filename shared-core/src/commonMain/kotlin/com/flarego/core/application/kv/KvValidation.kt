package com.flarego.core.application.kv

import com.flarego.core.model.*
import kotlinx.serialization.json.Json

object KvValidation {
    fun key(value: String): String {
        val bytes = utf8Length(value)
        if (bytes !in 1..KV_MAX_KEY_BYTES || value == "." || value == ".." ||
            value.any { it.isWhitespace() || it.code <= 31 || it.code in 127..159 }) throw invalidInputFailure()
        return value
    }
    fun namespace(value: String): String {
        if (value.isBlank() || value.length > 32 || value == "." || value == ".." ||
            value.any { it.code <= 31 || it.code in 127..159 }) throw invalidInputFailure()
        utf8Length(value)
        return value
    }
    fun title(value: String): String {
        if (value.isBlank() || value.length > 512 || value.any { it.code <= 31 || it.code in 127..159 }) throw invalidInputFailure()
        utf8Length(value)
        return value
    }
    fun metadata(value: String): KvMetadata {
        // Bounded before parsing; serialized form supplies the API byte bound.
        if (utf8Length(value) > 16384) throw invalidInputFailure()
        val json = try { Json.parseToJsonElement(value) } catch (_: Exception) { throw invalidInputFailure() }
        if (utf8Length(json.toString()) > KV_MAX_METADATA_BYTES) throw invalidInputFailure()
        return KvMetadata(json)
    }
    fun expiry(value: KvExpiry, nowSeconds: Long): KvExpiry {
        if (nowSeconds < 0) throw invalidInputFailure()
        when (value) {
            KvExpiry.Never -> Unit
            is KvExpiry.Ttl -> if (value.seconds < 60 || value.seconds > Long.MAX_VALUE - nowSeconds) throw invalidInputFailure()
            is KvExpiry.Absolute -> if (nowSeconds > Long.MAX_VALUE - 60 || value.epochSeconds < nowSeconds + 60) throw invalidInputFailure()
        }
        return value
    }
    fun keys(values: List<String>, max: Int): List<String> {
        if (max !in 1..10000 || values.size !in 1..max) throw invalidInputFailure()
        val copy = values.toList()
        copy.forEach(::key)
        if (copy.toSet().size != copy.size) throw invalidInputFailure()
        return copy
    }
    private fun utf8Length(value: String): Int = try { value.encodeToByteArray(throwOnInvalidSequence = true).size }
        catch (_: Exception) { throw invalidInputFailure() }
}
