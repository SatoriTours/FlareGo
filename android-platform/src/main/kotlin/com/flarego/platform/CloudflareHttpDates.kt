package com.flarego.platform

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * RFC 1123 HTTP-date parsing for Retry-After, injected into the common gateway.
 *
 * The calendar is resolved strictly, so an impossible date (for example
 * 29 February 2021) is rejected instead of being silently normalized. A date the
 * parser accepts but cannot be represented as epoch milliseconds is also rejected
 * rather than leaking a runtime exception into the retry decision.
 */
object CloudflareHttpDates {
    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String): Long? =
        try {
            ZonedDateTime.parse(value.trim(), FORMATTER).toInstant().toEpochMilli()
        } catch (_: RuntimeException) {
            null
        }
}
