package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.time.UtcText
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object OldDates {
    private const val MILLIS_THRESHOLD = 100_000_000_000L
    private val integer = Regex("^-?[0-9]{1,18}$")
    private val text = Regex(
        "^([0-9]{4}-[0-9]{2}-[0-9]{2})[T ]([0-9]{2}:[0-9]{2})(:[0-9]{2}(\\.[0-9]{1,9})?)?\\s*(Z|z|[+-][0-9]{2}:?[0-9]{2})?$",
    )

    fun fromDbValue(raw: Any?): Instant? = when (raw) {
        null -> null
        is Long -> fromDbNumber(raw)
        is Int -> fromDbNumber(raw.toLong())
        is Double -> if (raw.isFinite()) fromDbNumber(raw.toLong()) else null
        is String -> fromText(raw, numbersAreMillis = false)
        else -> null
    }

    fun fromJsonNumber(millis: Long): Instant = Instant.ofEpochMilli(millis)

    fun fromText(raw: String, numbersAreMillis: Boolean): Instant? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (integer.matches(value)) {
            val n = value.toLong()
            return if (numbersAreMillis) Instant.ofEpochMilli(n) else fromDbNumber(n)
        }
        val match = text.matchEntire(value) ?: return null
        val (day, hm, secondsPart, _, offsetPart) = match.destructured
        val local = runCatching { LocalDateTime.parse("${day}T$hm${secondsPart.ifEmpty { ":00" }}") }.getOrNull() ?: return null
        val offset = when {
            offsetPart.isEmpty() || offsetPart.equals("Z", ignoreCase = true) -> ZoneOffset.UTC
            else -> {
                val digits = offsetPart.substring(1).replace(":", "")
                val id = "${offsetPart[0]}${digits.substring(0, 2)}:${digits.substring(2)}"
                runCatching { ZoneOffset.of(id) }.getOrNull() ?: return null
            }
        }
        return local.toInstant(offset)
    }

    fun format(instant: Instant): String = UtcText.format(instant)

    private fun fromDbNumber(n: Long): Instant =
        if (n >= MILLIS_THRESHOLD || n <= -MILLIS_THRESHOLD) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
}
