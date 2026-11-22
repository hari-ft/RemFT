package com.ftapps.remft.domain.quickadd

import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.time.DateFormats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class QuickParseOptions(
    val now: LocalDateTime,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val defaultTime: LocalTime? = null,
    val monthFirstSlash: Boolean = false,
    val ignored: Set<String> = emptySet(),
    val zone: ZoneId? = null,
    val kinds: Set<QuickTokenKind> = QuickTokenKind.entries.toSet(),
) {
    companion object {
        val CaptureKinds: Set<QuickTokenKind> = setOf(QuickTokenKind.Priority)

        fun of(
            now: Instant,
            zone: ZoneId,
            dateFormat: DateFormatId,
            ignored: Set<String> = emptySet(),
            kinds: Set<QuickTokenKind> = QuickTokenKind.entries.toSet(),
        ): QuickParseOptions = QuickParseOptions(
            now = LocalDateTime.ofInstant(now, zone),
            monthFirstSlash = DateFormats.spec(dateFormat).monthFirst,
            ignored = ignored,
            zone = zone,
            kinds = kinds,
        )
    }
}

enum class QuickTokenKind(val id: String) { Due("due"), Time("time"), Repeat("repeat"), Priority("priority") }

data class QuickToken(
    val kind: QuickTokenKind,
    val start: Int,
    val end: Int,
    val key: String,
    val text: String,
) {
    val range: IntRange get() = start until end
}

data class QuickChip(val kind: QuickTokenKind, val tokens: List<QuickToken>) {
    val keys: List<String> get() = tokens.map { it.key }
    val id: String get() = keys.joinToString("|")
}

data class QuickParse(
    val title: String,
    val tokens: List<QuickToken>,
    val chips: List<QuickChip>,
    val dueAt: LocalDateTime? = null,
    val dueHasTime: Boolean = false,
    val repeat: QuickRepeatRule? = null,
    val priority: String? = null,
) {
    val hasSchedule: Boolean get() = dueAt != null || repeat != null
    val isEmpty: Boolean get() = tokens.isEmpty()
    val highlightRanges: List<IntRange> get() = tokens.map { it.range }
}

object QuickAdd {
    fun parse(text: String, options: QuickParseOptions): QuickParse = QuickParser(text, options).run()

    fun parse(
        text: String,
        now: Instant,
        zone: ZoneId,
        dateFormat: DateFormatId,
        ignored: Set<String> = emptySet(),
    ): QuickParse = parse(text, QuickParseOptions.of(now, zone, dateFormat, ignored))

    fun ignoring(text: String, options: QuickParseOptions, chip: QuickChip): QuickParse =
        parse(text, options.copy(ignored = options.ignored + chip.keys))
}
