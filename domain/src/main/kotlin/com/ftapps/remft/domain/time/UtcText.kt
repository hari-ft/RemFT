package com.ftapps.remft.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object UtcText {
    private val writer: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = writer.format(instant)

    fun formatMillis(epochMillis: Long): String = format(Instant.ofEpochMilli(epochMillis))

    fun formatDay(date: LocalDate): String = date.toString()

    fun parseDay(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    fun parse(text: String?): Instant? {
        if (text == null) return null
        return runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
    }

    fun parseMillis(text: String?): Long? = parse(text)?.toEpochMilli()
}
