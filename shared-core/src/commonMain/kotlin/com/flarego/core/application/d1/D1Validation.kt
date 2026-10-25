package com.flarego.core.application.d1

import com.flarego.core.model.*

object D1Validation {
    fun bookmark(value: String): String {
        utf8(value)
        if (value.isBlank() || value.any { it.code < 0x20 || it.code == 0x7f }) throw invalidInputFailure()
        return value
    }
    fun timestamp(value: String): String {
        if (!value.endsWith('Z')) throw invalidInputFailure()
        try { kotlin.time.Instant.parse(value) } catch (_: Exception) { throw invalidInputFailure() }
        return value
    }
    fun md5(value: String): String {
        if (!Regex("[0-9a-fA-F]{32}").matches(value)) throw invalidInputFailure()
        return value
    }
    fun databaseId(value: String): String {
        if (!UUID.matches(value)) throw invalidInputFailure()
        return value
    }
    fun name(value: String): String {
        if (!NAME.matches(value)) throw invalidInputFailure()
        return value
    }
    fun create(input: D1CreateInput): D1CreateInput { name(input.name); return input }
    fun params(values: List<String>): List<String> {
        if (values.size > 100) throw invalidInputFailure()
        values.forEach(::utf8)
        return values.toList()
    }
    fun sql(value: String): String {
        utf8(value)
        if (value.isBlank() || '\u0000' in value) throw invalidInputFailure()
        // A complete SQL program can contain many statements and triggers. No semicolon split.
        return value
    }
    fun identifier(value: String): String {
        utf8(value)
        if (value.isEmpty() || '\u0000' in value) throw invalidInputFailure()
        return value
    }
    fun exportOptions(input: D1ExportOptions): D1ExportOptions {
        if (input.noData && input.noSchema) throw invalidInputFailure()
        input.tables.forEach(::identifier)
        if (input.tables.distinct().size != input.tables.size) throw invalidInputFailure()
        return input
    }
    fun page(input: D1PageRequest): D1PageRequest {
        if (input.page < 1 || input.perPage !in 10..10000) throw invalidInputFailure()
        input.name?.let(::utf8)
        return input
    }
    internal fun utf8(value: String): ByteArray = try { value.encodeToByteArray(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { throw invalidInputFailure() }
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*")
}
