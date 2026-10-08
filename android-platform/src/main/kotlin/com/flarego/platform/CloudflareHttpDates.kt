package com.flarego.platform

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** RFC 1123 HTTP-date parsing for Retry-After, injected into the common gateway. */
object CloudflareHttpDates {
    fun parse(value: String): Long? =
        try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
}
