package com.ftapps.remft.domain.quickadd

import com.ftapps.remft.domain.Fold
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

internal class QuickParser(private val text: String, private val o: QuickParseOptions) {

    private class Word(val raw: String, val start: Int, val end: Int) {
        val norm: String = normWord(raw)
        val bare: String = norm.replace("'", "")
    }

    private class DateHit(val day: LocalDate, val len: Int, val weekdayToday: Boolean = false)

    private class Cand(
        val kind: QuickTokenKind,
        val len: Int,
        val date: DateHit? = null,
        val hour: Int? = null,
        val minute: Int? = null,
        val rule: QuickRepeatRule? = null,
        val priority: String? = null,
    )

    private enum class TimeShape { Bare, Colon, AmPm }

    private class TimeForm(val hour: Int, val minute: Int, val len: Int, val shape: TimeShape)

    private class Placed(val token: QuickToken, val cand: Cand)

    private val words: List<Word> = nonSpace.findAll(text).map { Word(it.value, it.range.first, it.range.last + 1) }.toList()
    private val today: LocalDate = o.now.toLocalDate()

    private fun n(i: Int): String = if (i < words.size) words[i].norm else ""
    private fun b(i: Int): String = if (i < words.size) words[i].bare else ""

    private fun plusDays(d: LocalDate, days: Int): LocalDate = d.plusDays(days.toLong())

    private fun plusMonths(d: LocalDate, months: Int): LocalDate = d.plusMonths(months.toLong())

    private val dow: Int get() = today.dayOfWeek.value
    private val weekStart: Int get() = o.weekStart.value

    private fun weekStartDay(): LocalDate = plusDays(today, -((dow - weekStart + 7) % 7))

    private fun nextWeek(weekday: Int): LocalDate {
        val start = plusDays(weekStartDay(), 7)
        return plusDays(start, (weekday - weekStart + 7) % 7)
    }

    private fun weekday(i: Int, keyworded: Boolean = false): Int? {
        val word = n(i)
        if (word.isEmpty()) return null
        return weekdays[word] ?: if (keyworded) weekdaysKeyworded[word] else null
    }

    private fun plainWeekday(i: Int): Int? = weekday(i, keyworded = true)

    private fun month(i: Int): Int? = months[n(i)]

    private fun dayNum(i: Int): Int? {
        val m = dayNumPattern.matchEntire(n(i)) ?: return null
        val d = m.groupValues[1].toInt()
        return if (d in 1..31) d else null
    }

    private fun year(i: Int): Int? {
        if (!fourDigits.matches(n(i))) return null
        val y = n(i).toInt()
        return if (y in 1970..2199) y else null
    }

    private fun lastDay(year: Int, month: Int): Int = YearMonth.of(year, month).lengthOfMonth()

    private fun resolve(day: Int, month: Int, year: Int?): LocalDate? {
        if (month < 1 || month > 12 || day < 1) return null
        if (year != null) {
            if (day > lastDay(year, month)) return null
            return LocalDate.of(year, month, day)
        }
        var y = today.year
        if (day > lastDay(y, month)) return null
        if (LocalDate.of(y, month, day).isBefore(today)) {
            y += 1
            if (day > lastDay(y, month)) return null
        }
        return LocalDate.of(y, month, day)
    }

    private fun dates(i: Int, keyworded: Boolean = false): List<DateHit> = buildList {
        numericDate(i)?.let { add(it) }
        addAll(namedDates(i))
        addAll(relative(i))
        addAll(weekdayDates(i, keyworded))
    }

    private fun numericDate(i: Int): DateHit? {
        val word = n(i)
        if (word.isEmpty()) return null
        val iso = isoPattern.matchEntire(word)
        if (iso != null) {
            val g = iso.groupValues
            val day = resolve(g[3].toInt(), g[2].toInt(), g[1].toInt())
            return day?.let { DateHit(it, 1) }
        }
        val m = numericPattern.matchEntire(word) ?: return null
        val first = m.groupValues[1]
        val sep = m.groupValues[2]
        val second = m.groupValues[3]
        val yearText = m.groups[4]?.value
        if (yearText != null && !word.contains("$sep$yearText")) return null
        if (yearText == null && first.length < 2 && second.length < 2) return null
        val a = first.toInt()
        val c = second.toInt()
        val year = yearText?.let { if (it.length == 2) it.toInt() + 2000 else it.toInt() }
        val day = when {
            sep == "." -> resolve(a, c, year)
            a > 12 && c <= 12 -> resolve(a, c, year)
            c > 12 && a <= 12 -> resolve(c, a, year)
            o.monthFirstSlash -> resolve(c, a, year)
            else -> resolve(a, c, year)
        } ?: return null
        return DateHit(day, 1)
    }

    private fun namedDates(i: Int): List<DateHit> = buildList {
        val d = dayNum(i)
        if (d != null) {
            var at = i + 1
            if (n(at) == "of") at += 1
            val month = month(at)
            if (month != null) {
                val year = year(at + 1)
                val day = resolve(d, month, year)
                if (day != null) add(DateHit(day, at - i + 1 + if (year != null) 1 else 0))
            }
        }
        val month = month(i)
        if (month != null) {
            val day2 = dayNum(i + 1)
            if (day2 != null) {
                val year = year(i + 2)
                val day = resolve(day2, month, year)
                if (day != null) add(DateHit(day, if (year != null) 3 else 2))
            }
        }
    }

    private fun relative(i: Int): List<DateHit> {
        val out = mutableListOf<DateHit>()
        val w = n(i)
        val w1 = n(i + 1)
        val w2 = n(i + 2)
        fun add(day: LocalDate, len: Int) = out.add(DateHit(day, len))

        if (w == "today") add(today, 1)
        if (w == "tomorrow" || w == "tmrw") add(plusDays(today, 1), 1)
        if (w == "day" && w1 == "after" && w2 == "tomorrow") add(plusDays(today, 2), 3)
        if (w == "the" && w1 == "day" && w2 == "after" && n(i + 3) == "tomorrow") add(plusDays(today, 2), 4)

        fun plus(count: Int, unit: String): LocalDate? = when (unit) {
            "day", "days" -> plusDays(today, count)
            "week", "weeks" -> plusDays(today, count * 7)
            "month", "months" -> plusMonths(today, count)
            else -> null
        }
        if (w == "in") {
            val c = count(w1)
            val day = c?.let { plus(it, w2) }
            if (day != null) add(day, 3)
        }
        val count = count(w)
        if (count != null && w2 == "from" && n(i + 3) == "now") {
            val day = plus(count, w1)
            if (day != null) add(day, 4)
        }

        if (w == "next" && w1 == "week") add(nextWeek(weekStart), 2)

        val saturday = plusDays(today, (6 - dow + 7) % 7)
        val at = if (w == "this") i + 1 else i
        if (n(at) == "weekend") add(saturday, at - i + 1)

        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        if (w == "end" && w1 == "of") {
            if (w2 == "month") add(monthEnd, 3)
            if (w2 == "the" && n(i + 3) == "month") add(monthEnd, 4)
        }
        return out
    }

    private fun weekdayDates(i: Int, keyworded: Boolean): List<DateHit> {
        val out = mutableListOf<DateHit>()
        val prefixes = mutableListOf(0 to 0)
        val w = n(i)
        val w1 = n(i + 1)
        if (w == "this" || w == "on") prefixes.add(1 to 0)
        if (w == "next") prefixes.add(1 to 1)
        if (w == "next" && w1 == "week") prefixes.add(2 to 1)
        if (w == "coming") prefixes.add(1 to 2)
        for ((plen, mode) in prefixes) {
            val weekday = weekday(i + plen, keyworded = keyworded || plen > 0) ?: continue
            var isToday = false
            val day = when (mode) {
                1 -> nextWeek(weekday)
                2 -> {
                    val delta = (weekday - dow + 7) % 7
                    plusDays(today, if (delta == 0) 7 else delta)
                }
                else -> {
                    val delta = (weekday - dow + 7) % 7
                    isToday = delta == 0
                    plusDays(today, delta)
                }
            }
            out.add(DateHit(day, plen + 1, weekdayToday = isToday))
        }
        return out
    }

    private fun schedule(i: Int): List<Cand> {
        val out = mutableListOf<Cand>()
        val w = n(i)
        if (w in dueKeywords) {
            val skip = if (n(i + 1) == "by" || n(i + 1) == "on") 2 else 1
            for (hit in dates(i + skip, keyworded = true)) out.add(Cand(QuickTokenKind.Due, skip + hit.len, date = hit))
        }
        if (w == "on") {
            val hits = listOfNotNull(numericDate(i + 1)) + namedDates(i + 1)
            for (hit in hits) out.add(Cand(QuickTokenKind.Due, 1 + hit.len, date = hit))
        }
        for (hit in dates(i)) out.add(Cand(QuickTokenKind.Due, hit.len, date = hit))
        return out
    }

    private fun timeForm(i: Int): TimeForm? {
        val word = n(i)
        if (word.isEmpty()) return null
        val ampm = ampmPattern.matchEntire(word)
        if (ampm != null) {
            return ampm(ampm.groupValues[1], ampm.groups[2]?.value, ampm.groupValues[3], 1)
        }
        val m = clockPattern.matchEntire(word) ?: return null
        if (markerPattern.matches(n(i + 1))) return ampm(m.groupValues[1], m.groups[3]?.value, n(i + 1), 2)
        val hour = m.groupValues[1].toInt()
        val minute = m.groups[3]?.value?.toInt() ?: 0
        if (hour > 23 || minute > 59) return null
        val shape = if (m.groups[2]?.value == ":") TimeShape.Colon else TimeShape.Bare
        return TimeForm(hour, minute, 1, shape)
    }

    private fun ampm(h: String, mm: String?, marker: String, len: Int): TimeForm? {
        var hour = h.toInt()
        val minute = mm?.toInt() ?: 0
        if (hour < 1 || hour > 12 || minute > 59) return null
        val pm = marker.startsWith("p")
        if (hour == 12) hour = 0
        if (pm) hour += 12
        return TimeForm(hour, minute, len, TimeShape.AmPm)
    }

    private fun period(i: Int): Pair<String, Int>? {
        if (n(i) == "in" && n(i + 1) == "the") {
            val p = n(i + 2)
            if (p == "morning" || p == "afternoon" || p == "evening") return p to 3
        }
        if (n(i) == "at" && n(i + 1) == "night") return "night" to 2
        return null
    }

    private fun times(i: Int): List<Cand> {
        val out = mutableListOf<Cand>()
        fun add(h: Int, m: Int, len: Int) = out.add(Cand(QuickTokenKind.Time, len, hour = h, minute = m))
        val w = n(i)
        val w1 = n(i + 1)

        val lead = if (w == "at") 1 else 0
        val t = timeForm(i + lead)
        if (t != null) {
            var hour = t.hour
            var end = i + lead + t.len
            var tagged = lead == 1 || t.shape != TimeShape.Bare
            if (t.shape != TimeShape.AmPm) {
                if (b(end) == "oclock") {
                    end += 1
                    tagged = true
                }
                val period = period(end)
                if (period != null) {
                    val (name, plen) = period
                    val adjusted = when (name) {
                        "morning" -> if (hour == 12) 0 else if (hour < 12) hour else null
                        "afternoon", "evening" -> if (hour < 12) hour + 12 else hour
                        else -> if (hour == 12) 0 else if (hour in 6..11) hour + 12 else hour
                    }
                    if (adjusted != null) {
                        hour = adjusted
                        end += plen
                        tagged = true
                    }
                }
            }
            if (tagged) add(hour, t.minute, end - i)
        }

        if (w == "at") {
            if (w1 == "noon") add(12, 0, 2)
            if (w1 == "midnight") add(0, 0, 2)
        }
        if (w == "noon" || w == "midday") add(12, 0, 1)
        if (w == "midnight") add(0, 0, 1)
        return out
    }

    private fun repeats(i: Int): List<Cand> {
        val out = mutableListOf<Cand>()
        fun add(rule: QuickRepeatRule, len: Int) {
            if (QuickRepeatDays.isValid(rule)) out.add(Cand(QuickTokenKind.Repeat, len, rule = rule))
        }
        fun rule(freq: QuickRepeatFreq, interval: Int = 1, byMonthDay: List<Int> = emptyList()) =
            QuickRepeatRule(freq = freq, interval = interval, byMonthDay = byMonthDay)
        fun days(list: List<Int>) = QuickRepeatRule(
            freq = QuickRepeatFreq.Weekly,
            byWeekday = list.map { QuickWeekdayPick(QuickRepeatRule.weekdayCodes[it - 1]) },
        )
        val weekdaysRule = days(listOf(1, 2, 3, 4, 5))
        val weekendRule = days(listOf(6, 7))

        val w = n(i)
        val w1 = n(i + 1)
        val w2 = n(i + 2)

        fun unit(s: String): QuickRepeatFreq? = when (s) {
            "day", "days" -> QuickRepeatFreq.Daily
            "week", "weeks" -> QuickRepeatFreq.Weekly
            "month", "months" -> QuickRepeatFreq.Monthly
            "year", "years" -> QuickRepeatFreq.Yearly
            else -> null
        }

        fun weekdayList(at: Int): Pair<List<Int>, Int>? {
            val first = plainWeekday(at) ?: return null
            val picked = mutableListOf(first)
            var end = at + 1
            while (true) {
                var next = end
                if (n(next) == "and" || n(next) == "&") next += 1
                val day = plainWeekday(next) ?: break
                if (day !in picked) picked.add(day)
                end = next + 1
            }
            picked.sort()
            return picked to (end - at)
        }

        fun everyMonths(interval: Int, len: Int) {
            add(rule(QuickRepeatFreq.Monthly, interval), len)
            if (n(i + len) == "on" && n(i + len + 1) == "the") {
                val d = dayNum(i + len + 2)
                if (d != null) add(rule(QuickRepeatFreq.Monthly, interval, listOf(d)), len + 3)
            }
        }

        if (w == "quarterly") everyMonths(3, 1)
        if (w == "half-yearly" || w == "halfyearly" || w == "semiannually" || w == "semi-annually") everyMonths(6, 1)
        if (w == "half" && w1 == "yearly") everyMonths(6, 2)
        if (w == "twice" && w1 == "a" && w2 == "year") everyMonths(6, 3)
        if (w == "daily" || w == "everyday") add(rule(QuickRepeatFreq.Daily), 1)
        if (w == "weekly") add(rule(QuickRepeatFreq.Weekly), 1)
        if (w == "yearly" || w == "annually") add(rule(QuickRepeatFreq.Yearly), 1)
        if (w == "monthly") {
            add(rule(QuickRepeatFreq.Monthly), 1)
            if (w1 == "on" && w2 == "the") {
                val d = dayNum(i + 3)
                if (d != null) add(rule(QuickRepeatFreq.Monthly, byMonthDay = listOf(d)), 4)
            }
        }
        if (w == "once" && w1 == "a") {
            val f = unit(w2)
            if (f != null && f != QuickRepeatFreq.Daily && !w2.endsWith("s")) add(rule(f), 3)
        }
        if (w == "every") {
            val f1 = unit(w1)
            if (f1 != null && !w1.endsWith("s")) {
                add(rule(f1), 2)
                if (f1 == QuickRepeatFreq.Monthly && w2 == "on" && n(i + 3) == "the") {
                    val d = dayNum(i + 4)
                    if (d != null) add(rule(QuickRepeatFreq.Monthly, byMonthDay = listOf(d)), 5)
                }
            }
            val c = if (w1 == "other") 2 else count(w1)
            val f2 = unit(w2)
            if (c != null && f2 != null && w1 != "a") add(rule(f2, c), 3)
            if (w1 == "quarter") everyMonths(3, 2)
            if (w1 == "half" && w2 == "year") everyMonths(6, 3)
            if (c != null && f2 == QuickRepeatFreq.Monthly && w1 != "a" && n(i + 3) == "on" && n(i + 4) == "the") {
                val d = dayNum(i + 5)
                if (d != null) add(rule(QuickRepeatFreq.Monthly, c, listOf(d)), 6)
            }
            if (w1 == "weekday") add(weekdaysRule, 2)
            if (w1 == "weekend") add(weekendRule, 2)
            val nth = if (ordinalDayPattern.matches(w1)) dayNum(i + 1) else null
            if (nth != null) {
                var len = 2
                if (w2 == "of" && n(i + 3) == "the" && n(i + 4) == "month") len = 5
                if (w2 == "of" && n(i + 3) == "month") len = 4
                add(rule(QuickRepeatFreq.Monthly, byMonthDay = listOf(nth)), len)
            }
            if (w1 == "last" && w2 == "day" && n(i + 3) == "of" && n(i + 4) == "the" && n(i + 5) == "month") {
                add(rule(QuickRepeatFreq.Monthly, byMonthDay = listOf(-1)), 6)
            }
            val list = weekdayList(i + 1)
            if (list != null) add(days(list.first), 1 + list.second)
        }
        run {
            val at = if (w == "on") i + 1 else i
            val word = n(at)
            val len = at - i + 1
            if (word == "weekdays") add(weekdaysRule, len)
            if (word == "weekends") add(weekendRule, len)
            if (word.endsWith("days")) {
                val day = weekdays[word.dropLast(1)]
                if (day != null) add(days(listOf(day)), len)
            }
        }
        return out
    }

    private fun marks(i: Int): List<Cand> {
        val priority = priorityWords[n(i)] ?: return emptyList()
        return listOf(Cand(QuickTokenKind.Priority, 1, priority = priority))
    }

    fun run(): QuickParse {
        val consumed = arrayOfNulls<QuickTokenKind>(words.size)
        val placed = mutableListOf<Placed>()
        val filled = mutableSetOf<QuickTokenKind>()

        fun keyOf(kind: QuickTokenKind, from: Int, len: Int): String {
            val src = unifyApostrophes(text.substring(words[from].start, words[from + len - 1].end))
            return "${kind.id}:${Fold.searchText(src)}"
        }

        var i = 0
        while (i < words.size) {
            val cands = (repeats(i) + schedule(i) + times(i) + marks(i))
                .filter { i + it.len <= words.size && it.kind !in filled }
            if (cands.isEmpty()) {
                i += 1
                continue
            }
            var best = cands.first()
            for (c in cands) if (c.len > best.len) best = c
            val key = keyOf(best.kind, i, best.len)
            if (key in o.ignored) {
                i += best.len
                continue
            }
            val start = words[i].start
            val end = words[i + best.len - 1].end
            placed.add(Placed(QuickToken(best.kind, start, end, key, text.substring(start, end)), best))
            for (k in i until i + best.len) consumed[k] = best.kind
            filled.add(best.kind)
            i += best.len
        }

        fun candOf(kind: QuickTokenKind): Cand? = placed.firstOrNull { it.token.kind == kind }?.cand
        fun tokenOf(kind: QuickTokenKind): QuickToken? = placed.firstOrNull { it.token.kind == kind }?.token

        val dueCand = candOf(QuickTokenKind.Due)
        val timeCand = candOf(QuickTokenKind.Time)
        val repeatCand = candOf(QuickTokenKind.Repeat)

        fun at(day: LocalDate): LocalDateTime {
            val hour = timeCand?.hour ?: o.defaultTime?.hour ?: return QuickDefaultTime.on(day, o.now, o.zone)
            val minute = timeCand?.minute ?: o.defaultTime?.minute ?: 0
            return day.atTime(hour, minute)
        }

        var dueAt: LocalDateTime? = null
        val rule = repeatCand?.rule
        if (rule != null) {
            val anchor = dueCand?.date?.day ?: today
            val lateDayOnly = timeCand == null && o.defaultTime == null && QuickDefaultTime.pastMidnight(o.now, o.zone)
            for (day in QuickRepeatDays.firstDays(rule, anchor, 3)) {
                val candidate = at(day)
                val ahead = if (timeCand == null) {
                    if (lateDayOnly) day.isAfter(today) else !day.isBefore(today)
                } else {
                    candidate.isAfter(o.now)
                }
                if (ahead) {
                    dueAt = candidate
                    break
                }
            }
            if (dueAt == null) dueAt = at(anchor)
        } else if (dueCand != null) {
            val hit = dueCand.date!!
            var value = at(hit.day)
            if (timeCand != null && hit.weekdayToday && !value.isAfter(o.now)) value = at(plusDays(hit.day, 7))
            dueAt = value
        } else if (timeCand != null) {
            var value = at(today)
            if (!value.isAfter(o.now)) value = at(plusDays(today, 1))
            dueAt = value
        }

        val chips = mutableListOf<QuickChip>()
        val timeToken = tokenOf(QuickTokenKind.Time)
        val dueToken = tokenOf(QuickTokenKind.Due)
        val repeatToken = tokenOf(QuickTokenKind.Repeat)
        if (dueToken != null) {
            chips.add(QuickChip(QuickTokenKind.Due, listOfNotNull(dueToken, timeToken)))
        } else if (timeToken != null && repeatToken == null) {
            chips.add(QuickChip(QuickTokenKind.Due, listOf(timeToken)))
        }
        for (p in placed) {
            when (p.token.kind) {
                QuickTokenKind.Due, QuickTokenKind.Time -> Unit
                QuickTokenKind.Repeat -> chips.add(
                    QuickChip(QuickTokenKind.Repeat, listOfNotNull(p.token, if (dueToken == null) timeToken else null)),
                )
                else -> chips.add(QuickChip(p.token.kind, listOf(p.token)))
            }
        }

        var title = words.indices.filter { consumed[it] == null }.joinToString(" ") { words[it].raw }
        title = title.replaceFirst(titleLead, "").replaceFirst(titleTrail, "")
        if (title.isEmpty()) title = text.replace(edgeSpace, "").replace(spaceRun, " ")

        return QuickParse(
            title = title,
            tokens = placed.map { it.token },
            chips = chips,
            dueAt = dueAt,
            dueHasTime = dueAt != null && timeCand != null,
            repeat = rule,
            priority = candOf(QuickTokenKind.Priority)?.priority,
        )
    }

    companion object {
        private const val SPACE = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
        private val nonSpace = Regex("[^$SPACE]+")
        private val spaceRun = Regex("[$SPACE]+")
        private val edgeSpace = Regex("^[$SPACE]+|[$SPACE]+$")
        private val titleLead = Regex("^[$SPACE,;:\\-–—]+")
        private val titleTrail = Regex("[$SPACE,;:\\-–—]+$")
        private val apostrophes = Regex("[’ʼ`´‘]")
        private val trailingPunct = Regex("[,;.!?:)\\]]+$")
        private val leadingPunct = Regex("^[(\\[]+")
        private val bangWord = Regex("^![A-Za-z0-9_]+$")
        private val countPattern = Regex("^\\d{1,3}$")
        private val dayNumPattern = Regex("^(\\d{1,2})(?:st|nd|rd|th)?$")
        private val ordinalDayPattern = Regex("^\\d{1,2}(st|nd|rd|th)$")
        private val fourDigits = Regex("^\\d{4}$")
        private val isoPattern = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
        private val numericPattern = Regex("^(\\d{1,2})([./])(\\d{1,2})(?:[./](\\d{4}|\\d{2}))?$")
        private val ampmPattern = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|a\\.m|p\\.m)$")
        private val clockPattern = Regex("^(\\d{1,2})(?:([:.])(\\d{2}))?$")
        private val markerPattern = Regex("^(am|pm|a\\.m|p\\.m)$")

        private fun unifyApostrophes(s: String): String = s.replace(apostrophes, "'")

        private fun normWord(raw: String): String {
            var s = Fold.searchText(unifyApostrophes(raw))
            s = s.replaceFirst(leadingPunct, "")
            if (!bangWord.matches(s)) s = s.replaceFirst(trailingPunct, "")
            return s
        }

        private val numberWords = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "a" to 1,
        )

        private fun count(norm: String): Int? {
            if (countPattern.matches(norm)) {
                val n = norm.toInt()
                return if (n >= 1) n else null
            }
            return numberWords[norm]
        }

        private val weekdays = mapOf(
            "monday" to 1, "mon" to 1,
            "tuesday" to 2, "tue" to 2, "tues" to 2,
            "wednesday" to 3, "wed" to 3,
            "thursday" to 4, "thu" to 4, "thur" to 4, "thurs" to 4,
            "friday" to 5, "fri" to 5,
            "saturday" to 6,
            "sunday" to 7,
        )

        private val weekdaysKeyworded = mapOf("sat" to 6, "sun" to 7)

        private val months = mapOf(
            "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
            "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
            "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
            "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
        )

        private val dueKeywords = setOf("due", "by", "deadline", "until", "till")

        private val priorityWords = mapOf(
            "p1" to "high", "p2" to "medium", "p3" to "low", "p4" to "none",
            "!1" to "high", "!2" to "medium", "!3" to "low", "!4" to "none",
            "!urgent" to "urgent", "!high" to "high", "!medium" to "medium", "!low" to "low",
        )
    }
}
