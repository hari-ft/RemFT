package com.ftapps.remft.domain.repeat

import com.ftapps.remft.domain.Ulid
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class SeriesWindow(val from: String, val to: String)

data class SeriesRollResult(val seriesId: String, val changes: SeriesChangeSet?, val error: Throwable?)

class SeriesEngine(
    private val now: Instant,
    private val newId: () -> String = { Ulid.next(now.toEpochMilli()) },
) {
    companion object {
        const val HorizonDays: Int = 365
        private const val CountCeiling = 100000
        private val Finished = setOf("completed", "cancelled", "archived")
        private val Done = setOf("completed", "cancelled")

        fun ruleFollowingDay(rule: RepeatRule, day: String, from: String): RepeatRule? {
            val date = RepeatDays.parseKey(day)
            val year = date.year
            val month = date.monthValue
            val dom = date.dayOfMonth
            val fromMonth = from.split('-')[1].toInt()
            val weekday = RepeatDays.weekdayCode(date)
            val monthDays = rule.byMonthDay
            val weekdays = rule.byWeekday

            if (rule.isAfterCompletion) return null
            if (rule.freq == RepeatFreq.Daily) return rule
            if (monthDays.size > 1 || weekdays.size > 1) return null
            if (monthDays.isNotEmpty() && weekdays.isNotEmpty()) return null

            if (rule.freq == RepeatFreq.Weekly) {
                if (weekdays.isNotEmpty() && weekdays.first().ordinal != null) return null
                return rule.copy(byWeekday = listOf(WeekdayPick(weekday)))
            }

            val shift = Math.floorMod(month - fromMonth, 12)
            var next = rule.copy(
                byMonth = when {
                    rule.byMonth.isNotEmpty() -> rule.byMonth.map { (it - 1 + shift) % 12 + 1 }.toSortedSet().toList()
                    rule.freq == RepeatFreq.Yearly -> listOf(month)
                    else -> emptyList()
                },
            )
            val last = RepeatDays.lastDayOfMonth(year, month)
            if (weekdays.isNotEmpty()) {
                val ordinal = weekdays.first().ordinal ?: return next.copy(byWeekday = listOf(WeekdayPick(weekday)))
                val nth = (dom + 6) / 7
                val isLast = dom + 7 > last
                return next.copy(
                    byWeekday = listOf(WeekdayPick(weekday, if ((ordinal == -1 && isLast) || nth == 5) -1 else nth)),
                )
            }
            val wasLast = monthDays.isNotEmpty() && monthDays.first() == -1
            next = next.copy(byMonthDay = listOf(if (wasLast && dom == last) -1 else dom))
            return next
        }

        internal fun shiftedStart(rule: RepeatRule, anchor: String, day: String): String {
            if (day <= anchor) return day
            val n = rule.interval
            fun back(k: Int): String = when (rule.freq) {
                RepeatFreq.Daily -> RepeatDays.addDays(day, -k * n)
                RepeatFreq.Weekly -> RepeatDays.addDays(day, -k * n * 7)
                RepeatFreq.Monthly -> RepeatDays.addMonths(day, -k * n)
                RepeatFreq.Yearly -> RepeatDays.addMonths(day, -k * n * 12)
            }
            val a = RepeatDays.parseKey(anchor)
            val d = RepeatDays.parseKey(day)
            val between = ChronoUnit.DAYS.between(a, d).toInt()
            var k = when (rule.freq) {
                RepeatFreq.Daily -> between / n
                RepeatFreq.Weekly -> between / (7 * n)
                RepeatFreq.Monthly -> ((d.year - a.year) * 12 + d.monthValue - a.monthValue) / n + 1
                RepeatFreq.Yearly -> (d.year - a.year) / n + 1
            }
            while (k > 0 && back(k) < anchor) k--
            return back(k)
        }
    }

    fun window(series: SeriesRecord): SeriesWindow {
        val today = WallClock.wallDay(now, series.timezone)
        return SeriesWindow(RepeatDays.later(today, series.anchorDay), RepeatDays.addDays(today, HorizonDays))
    }

    fun plannedDays(series: SeriesRecord): List<String> {
        val w = window(series)
        val days = Recurrence.expand(series.rule, anchor = series.anchorDay, from = w.from, to = w.to, max = MaxOccurrences + 1)
        if (days.size > MaxOccurrences) throw SeriesTooDenseException()
        return days
    }

    fun create(
        state: SeriesState,
        workspaceId: String,
        rule: RepeatRule,
        anchorAt: Instant,
        timezone: String,
        template: SeriesTemplate? = null,
        fromTaskId: String? = null,
        seriesId: String = newId(),
    ): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        val source = fromTaskId?.let { w.tasks[it] }
        val copied = template ?: source?.let { SeriesTemplate.fromTask(it, anchorAt) } ?: SeriesTemplate()
        val record = SeriesRecord.create(seriesId, workspaceId, rule, copied, timezone, anchorAt, now)
        w.putSeries(record)
        plannedDays(record)
        if (fromTaskId != null) adopt(w, record, fromTaskId)
        materialize(w, record)
        return w.changes()
    }

    fun materialize(state: SeriesState, seriesId: String): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        val s = w.series[seriesId] ?: return w.changes()
        materialize(w, s)
        return w.changes()
    }

    fun rollAll(state: SeriesState): List<SeriesRollResult> = state.series.map { s ->
        try {
            SeriesRollResult(s.id, materialize(state, s.id), null)
        } catch (error: RuntimeException) {
            SeriesRollResult(s.id, null, error)
        }
    }

    fun updateRule(state: SeriesState, seriesId: String, rule: RepeatRule): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        val s = w.series[seriesId] ?: return w.changes()
        val next = s.copy(ruleJson = rule.toJsonString(), updatedAt = now)
        w.putSeries(next)
        rebuildFuture(w, next)
        return w.changes()
    }

    fun stop(state: SeriesState, seriesId: String, fromDay: String? = null, keepTaskId: String? = null): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        val s = w.series[seriesId]
        if (s == null) {
            w.deleteSeries(seriesId)
            return w.changes()
        }
        val from = fromDay ?: window(s).from
        val keepHead = s.isCompletionSeries && fromDay == null
        val rows = w.rowsOf(s.id).toMutableList()
        val detach: (OccurrenceRow) -> OccurrenceRow = { it.copy(seriesId = null, occurrenceDate = null, spawnedFromTaskId = null) }
        if (keepTaskId != null && rows.any { it.id == keepTaskId }) {
            w.writeRow(keepTaskId, change = detach)
            rows.removeAll { it.id == keepTaskId }
        }
        if (keepHead) {
            rows.filter { it.status !in Finished }.forEach { w.writeRow(it.id, change = detach) }
        } else {
            retire(w, rows.filter { it.status !in Done && it.occurrenceDate != null && it.occurrenceDate >= from })
        }
        w.skipsOf(s.id).forEach { w.deleteSkip(s.id, it) }
        w.deleteSeries(s.id)
        return w.changes()
    }

    fun recordUserDelete(task: OccurrenceRow, state: SeriesState = SeriesState()): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        val seriesId = task.seriesId
        val day = task.occurrenceDate
        if (seriesId != null && day != null) w.insertSkipIfAbsent(seriesId, day)
        return w.changes()
    }

    fun onStatusChange(state: SeriesState, before: OccurrenceRow, after: OccurrenceRow): SeriesChangeSet {
        val w = SeriesWorking(state.withTask(after), now)
        if (after.seriesId == null) return w.changes()
        if (before.status != "completed" && after.status == "completed") {
            spawnAfterCompletion(w, after)
        } else if (before.status == "completed" && after.status != "completed") {
            retractSpawn(w, after)
        }
        return w.changes()
    }

    fun spawnAfterCompletion(state: SeriesState, task: OccurrenceRow): SeriesChangeSet {
        val w = SeriesWorking(state.withTask(task), now)
        spawnAfterCompletion(w, task)
        return w.changes()
    }

    fun retractSpawn(state: SeriesState, task: OccurrenceRow): SeriesChangeSet {
        val w = SeriesWorking(state, now)
        retractSpawn(w, task)
        return w.changes()
    }

    fun propagateScope(
        state: SeriesState,
        task: OccurrenceRow,
        edit: TaskEdit,
        scope: EditScope,
        before: OccurrenceRow? = null,
    ): SeriesChangeSet {
        val w = SeriesWorking(state.withTask(task), now)
        ScopePropagation(w, task, edit, scope, before).run()
        return w.changes()
    }

    private fun atWallTime(day: String, clock: LocalTime, zone: String): Instant = WallClock.toInstant(zone, day, clock)

    private fun clockOf(instant: Instant, zone: String): LocalTime = WallClock.wallTime(instant, zone)

    private fun minutesBefore(due: Instant, minutes: Int): Instant = due.minus(Duration.ofMinutes(minutes.toLong()))

    private fun patternPlans(s: SeriesRecord, day: String): Boolean {
        val anchor = s.anchorDay
        if (day < anchor) return false
        return Recurrence.expand(s.rule, anchor = anchor, from = day, to = day, max = 1).isNotEmpty()
    }

    private data class Columns(
        val title: String,
        val description: String?,
        val priority: String,
        val dueAt: Instant,
        val remindAt: Instant?,
        val timezone: String,
        val isUrgent: Boolean,
        val requiresAcknowledgement: Boolean,
    )

    private fun templateColumns(s: SeriesRecord, t: SeriesTemplate, dueAt: Instant): Columns = Columns(
        title = t.title,
        description = t.description,
        priority = t.priority,
        dueAt = dueAt,
        remindAt = t.remindMinutesBefore?.let { minutesBefore(dueAt, it) },
        timezone = s.timezone,
        isUrgent = t.isUrgent,
        requiresAcknowledgement = t.requiresAcknowledgement,
    )

    private fun insertOccurrence(w: SeriesWorking, series: SeriesRecord, day: String, columns: Columns, spawnedFrom: String? = null): String {
        val id = newId()
        w.insertTask(
            OccurrenceRow(
                id = id,
                workspaceId = series.workspaceId,
                title = columns.title,
                description = columns.description,
                status = "open",
                priority = columns.priority,
                dueAt = columns.dueAt,
                remindAt = columns.remindAt,
                timezone = columns.timezone,
                isUrgent = columns.isUrgent,
                requiresAcknowledgement = columns.requiresAcknowledgement,
                seriesId = series.id,
                occurrenceDate = day,
                spawnedFromTaskId = spawnedFrom,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    private fun ownContent(w: SeriesWorking, rows: List<OccurrenceRow>, template: SeriesTemplate? = null): Set<String> {
        if (rows.isEmpty()) return emptySet()
        val owned = rows.filter { it.hasAttachedContent }.map { it.id }.toMutableSet()
        for (r in rows) {
            val seriesId = r.seriesId ?: continue
            if (r.id in owned) continue
            val t = template ?: w.series[seriesId]?.template ?: continue
            if (r.title != t.title || r.description != t.description) owned.add(r.id)
        }
        return owned
    }

    private fun retire(w: SeriesWorking, rows: List<OccurrenceRow>, template: SeriesTemplate? = null): Int {
        if (rows.isEmpty()) return 0
        val owned = ownContent(w, rows, template)
        rows.forEach { w.retire(it, it.id in owned) }
        return rows.size
    }

    private fun materialize(w: SeriesWorking, s: SeriesRecord): List<String> {
        if (s.isCompletionSeries) return materializeCompletionHead(w, s)
        val days = plannedDays(s)
        if (days.isEmpty()) return emptyList()
        val taken = w.rowsOf(s.id).mapNotNull { it.occurrenceDate }.toMutableSet().apply { addAll(w.skipsOf(s.id)) }
        val template = s.template
        val clock = clockOf(s.anchorAt, s.timezone)
        val created = mutableListOf<String>()
        for (day in days) {
            if (day in taken) continue
            insertOccurrence(w, s, day, templateColumns(s, template, atWallTime(day, clock, s.timezone)))
            created.add(day)
        }
        return created
    }

    private fun materializeCompletionHead(w: SeriesWorking, s: SeriesRecord): List<String> {
        if (w.rowsOf(s.id).isNotEmpty()) return emptyList()
        if (w.skipsOf(s.id).isNotEmpty()) return emptyList()
        val day = s.anchorDay
        if (Recurrence.expand(s.rule, anchor = day).isEmpty()) return emptyList()
        val clock = clockOf(s.anchorAt, s.timezone)
        insertOccurrence(w, s, day, templateColumns(s, s.template, atWallTime(day, clock, s.timezone)))
        return listOf(day)
    }

    private fun freeSlot(w: SeriesWorking, s: SeriesRecord, day: String): String {
        val slots = w.rowsOf(s.id).mapNotNull { it.occurrenceDate }.toMutableSet().apply { addAll(w.skipsOf(s.id)) }
        if (day !in slots) return day
        return RepeatDays.addDays(slots.fold(day, RepeatDays::later), 1)
    }

    private fun spawnAfterCompletion(w: SeriesWorking, task: OccurrenceRow): String? {
        val seriesId = task.seriesId ?: return null
        val completedAt = task.completedAt ?: return null
        val s = w.series[seriesId] ?: return null
        if (!s.isCompletionSeries) return null
        val rows = w.rowsOf(s.id)
        if (rows.any { it.spawnedFromTaskId == task.id }) return null
        val slotDay = task.occurrenceDate
        val laterOpen = rows.any { r ->
            r.id != task.id && r.status !in Finished &&
                (slotDay == null || (r.occurrenceDate != null && r.occurrenceDate > slotDay))
        }
        if (laterOpen) return null
        val timing = AfterCompletion.spawnTiming(
            rule = s.rule,
            completedAt = completedAt,
            zone = s.timezone,
            anchorAt = s.anchorAt,
            taskDueAt = task.dueAt,
            taskRemindAt = task.remindAt,
            templateRemindMinutesBefore = s.template.remindMinutesBefore,
            emitted = rows.size,
        ) ?: return null
        return insertOccurrence(
            w,
            s,
            freeSlot(w, s, timing.day),
            Columns(
                title = task.title,
                description = task.description,
                priority = task.priority,
                dueAt = timing.dueAt,
                remindAt = timing.remindAt,
                timezone = s.timezone,
                isUrgent = task.isUrgent,
                requiresAcknowledgement = task.requiresAcknowledgement,
            ),
            spawnedFrom = task.id,
        )
    }

    private fun retractSpawn(w: SeriesWorking, task: OccurrenceRow): Int =
        retire(w, w.tasks.values.filter { it.spawnedFromTaskId == task.id && it.status !in Done })

    private fun rebuildCompletionHead(w: SeriesWorking, s: SeriesRecord) {
        val fromDay = window(s).from
        val rows = w.rowsOf(s.id)
        val open = rows.filter { it.status !in Finished }.sortedBy { it.occurrenceDate ?: "" }
        retire(w, open.drop(1).filter { (it.occurrenceDate ?: fromDay) >= fromDay })
        if (open.isNotEmpty()) return
        val skips = w.skipsOf(s.id)
        val liveSlots = rows.mapNotNull { it.occurrenceDate }
        val latestLive = liveSlots.maxOrNull() ?: ""
        val latestSkip = skips.maxOrNull() ?: ""
        if (latestSkip.isNotEmpty() && latestSkip > latestLive) return
        val rule = s.rule
        val end = rule.end
        if (end is RepeatEnd.Count && rows.size >= end.count) return
        if (end is RepeatEnd.Until && fromDay > end.until) return
        val clock = clockOf(s.anchorAt, s.timezone)
        insertOccurrence(w, s, freeSlot(w, s, fromDay), templateColumns(s, s.template, atWallTime(fromDay, clock, s.timezone)))
    }

    private fun adopt(w: SeriesWorking, s: SeriesRecord, taskId: String): String? {
        val task = w.tasks[taskId] ?: return null
        if (task.seriesId != null) return null
        val completion = s.isCompletionSeries
        val tz = s.timezone
        val instant = task.dueAt ?: task.remindAt ?: task.createdAt ?: now
        val day = WallClock.wallDay(instant, tz)
        var slot = day
        var move: (OccurrenceRow) -> OccurrenceRow = { it }
        if (!completion && !patternPlans(s, day)) {
            val planned = plannedDays(s)
            if (planned.isEmpty()) return null
            slot = planned.firstOrNull { it >= day } ?: planned.first()
            val clock = clockOf(s.anchorAt, tz)
            val due = task.dueAt
            val remind = task.remindAt
            if (due != null) {
                val newDue = atWallTime(slot, clock, tz)
                val newRemind = remind?.let { newDue.minus(Duration.between(it, due)) }
                move = { it.copy(dueAt = newDue, remindAt = newRemind, snoozedUntil = null) }
            } else if (remind != null) {
                val newRemind = atWallTime(slot, clock, tz)
                move = { it.copy(remindAt = newRemind, snoozedUntil = null) }
            }
        }
        if (!completion && task.dueAt == null && task.remindAt == null) {
            val previous = move
            val dueAt = atWallTime(slot, clockOf(s.anchorAt, tz), tz)
            move = { previous(it).copy(dueAt = dueAt) }
        }
        val finalSlot = slot
        w.writeRow(task.id) { move(it).copy(seriesId = s.id, occurrenceDate = finalSlot) }
        if (completion && task.status == "completed" && task.completedAt != null) {
            spawnAfterCompletion(w, w.tasks.getValue(task.id))
        }
        return task.id
    }

    private fun rebuildFuture(w: SeriesWorking, s: SeriesRecord) {
        if (s.isCompletionSeries) return rebuildCompletionHead(w, s)
        val fromDay = window(s).from
        val planned = plannedDays(s).toSet()
        val future = w.rowsOf(s.id).filter { it.status !in Done && it.occurrenceDate != null && it.occurrenceDate >= fromDay }
        retire(w, future.filter { it.occurrenceDate !in planned })
        materialize(w, s)
    }

    private inner class ScopePropagation(
        private val w: SeriesWorking,
        private val task: OccurrenceRow,
        private val edit: TaskEdit,
        private val scope: EditScope,
        private val before: OccurrenceRow?,
    ) {
        fun run() {
            val seriesId = task.seriesId ?: return
            val fromDay = task.occurrenceDate ?: return
            if (scope == EditScope.This) return
            val s = w.series[seriesId] ?: return
            Plan(s, fromDay).execute()
        }

        private inner class Plan(private val s: SeriesRecord, private val fromDay: String) {
            private val tz = s.timezone
            private val rowPatch: LinkedHashMap<String, JsonElement> = LinkedHashMap<String, JsonElement>().apply {
                edit.title?.let { put(SeriesTemplate.Title, JsonPrimitive(it.value)) }
                edit.description?.let { put(SeriesTemplate.Description, it.value?.let(::JsonPrimitive) ?: JsonNull) }
                edit.priority?.let { put(SeriesTemplate.Priority, JsonPrimitive(it.value)) }
                edit.isUrgent?.let { put(SeriesTemplate.IsUrgent, JsonPrimitive(it.value)) }
                edit.requiresAcknowledgement?.let { put(SeriesTemplate.RequiresAcknowledgement, JsonPrimitive(it.value)) }
            }
            private val newDue: Instant? = edit.dueAt?.value
            private val time: LocalTime? = newDue?.let { clockOf(it, tz) }
            private val remindEdited = edit.remindAt != null
            private val newRemind: Instant? = edit.remindAt?.value
            private val clock: LocalTime = time ?: clockOf(task.dueAt ?: s.anchorAt, tz)
            private var template: SeriesTemplate = s.template
            private var remindBefore: Int? = null
            private val rule = s.rule
            private val oldTemplate = s.template
            private val anchorDay = s.anchorDay
            private val today = WallClock.wallDay(now, tz)
            private val moved: String? = newDue?.let { WallClock.wallDay(it, tz) }?.takeIf { it != fromDay }
            private val followed: RepeatRule? = moved?.let { ruleFollowingDay(rule, it, fromDay) }
            private val completion = rule.isAfterCompletion

            private fun timeFor(day: String): Instant = atWallTime(day, clock, tz)

            private fun sameDay(a: Instant, b: Instant): Boolean = WallClock.wallDay(a, tz) == WallClock.wallDay(b, tz)

            private fun seriesTimeOn(day: String): Instant = atWallTime(day, clockOf(s.anchorAt, tz), tz)

            fun execute() {
                if (rowPatch.isEmpty() && time == null && !remindEdited) return
                rowPatch.forEach { (key, value) -> template = template.with(key, value) }
                if (remindEdited) {
                    template = if (newRemind == null) {
                        template.without(SeriesTemplate.RemindMinutesBefore)
                    } else {
                        val due = newDue ?: task.dueAt ?: seriesTimeOn(fromDay)
                        template.with(SeriesTemplate.RemindMinutesBefore, JsonPrimitive(minutesBetween(newRemind, due)))
                    }
                }
                val seriesBefore = template.remindMinutesBefore
                if (!remindEdited && newDue != null && seriesBefore != null) {
                    val oldDue = seriesTimeOn(fromDay)
                    val remind = minutesBefore(oldDue, seriesBefore)
                    if (!ReminderFollow.follows(oldDue, newDue, remind, ::sameDay)) {
                        template = template.with(SeriesTemplate.RemindMinutesBefore, JsonPrimitive(minutesBetween(remind, newDue)))
                    }
                }
                remindBefore = template.remindMinutesBefore
                if (!remindEdited && newDue != null) {
                    val oldDue = before?.dueAt ?: seriesTimeOn(fromDay)
                    val remind = if (before != null) before.remindAt else task.remindAt
                    val next = if (remind == null) {
                        remindBefore?.let { minutesBefore(newDue, it) }
                    } else if (ReminderFollow.follows(oldDue, newDue, remind, ::sameDay)) {
                        remind.plus(Duration.between(oldDue, newDue))
                    } else {
                        remind
                    }
                    if (next != null) w.writeRow(task.id) { it.copy(remindAt = next) }
                }
                if (scope == EditScope.All) all() else future()
            }

            private fun minutesBetween(from: Instant, to: Instant): Int = Duration.between(from, to).toMinutes().toInt()

            private fun movable(from: String? = null): List<OccurrenceRow> = w.rowsOf(s.id).filter { r ->
                r.id != task.id && r.status !in Finished &&
                    (from == null || (r.occurrenceDate != null && r.occurrenceDate >= from))
            }

            private fun restamp(r: OccurrenceRow, toDay: String? = null, toSeries: String? = null) {
                val day = toDay ?: if (completion && r.dueAt != null) WallClock.wallDay(r.dueAt, tz) else r.occurrenceDate
                val oldDue = r.dueAt
                val due = if (time != null && day != null) timeFor(day) else oldDue
                val remind = r.remindAt
                val dueMoved = oldDue != null && due != null && due != oldDue
                val setDue = time != null && day != null
                var remindChange: Edit<Instant?>? = null
                val before = remindBefore
                if (remindEdited) {
                    if (due != null) remindChange = Edit(before?.let { minutesBefore(due, it) })
                } else if (dueMoved && remind != null) {
                    if (ReminderFollow.follows(oldDue!!, due!!, remind, ::sameDay)) {
                        remindChange = Edit(remind.plus(Duration.between(oldDue, due)))
                    }
                } else if (time != null && due != null && before != null) {
                    remindChange = Edit(minutesBefore(due, before))
                }
                w.writeRow(r.id) { row ->
                    var next = row
                    edit.title?.let { next = next.copy(title = it.value) }
                    edit.description?.let { next = next.copy(description = it.value) }
                    edit.priority?.let { next = next.copy(priority = it.value) }
                    edit.isUrgent?.let { next = next.copy(isUrgent = it.value) }
                    edit.requiresAcknowledgement?.let { next = next.copy(requiresAcknowledgement = it.value) }
                    if (setDue) next = next.copy(dueAt = due)
                    remindChange?.let { next = next.copy(remindAt = it.value) }
                    if (toSeries != null) next = next.copy(seriesId = toSeries)
                    if (toDay != null) next = next.copy(occurrenceDate = toDay)
                    next
                }
            }

            private fun carry(open: List<OccurrenceRow>, target: SeriesRecord, taken: MutableSet<String>, skipsFrom: String) {
                val skips = w.skipsOf(s.id).filter { it >= skipsFrom }
                var planner: (String) -> Boolean = { true }
                var mapper: (String) -> String? = { null }
                val horizon = RepeatDays.addDays(today, HorizonDays)
                if (moved != null) {
                    val days = (listOf(fromDay, moved, today, horizon) + open.map { it.occurrenceDate!! } + skips).sorted()
                    val lo = days.first()
                    val hi = days.last()
                    val span = Math.abs(ChronoUnit.DAYS.between(RepeatDays.parseKey(fromDay), RepeatDays.parseKey(moved)).toInt())
                    val nStart = target.anchorDay
                    val nDays = Recurrence.expand(target.rule, anchor = nStart, to = RepeatDays.addDays(hi, span + 400), max = CountCeiling)
                    val nSet = nDays.toSet()
                    planner = { it in nSet }
                    if (followed != null) {
                        val oDays = Recurrence.expand(rule.copy(end = RepeatEnd.Never), anchor = anchorDay, from = lo, to = hi, max = CountCeiling)
                        val oIndex = oDays.withIndex().associate { it.value to it.index }
                        val fromIdx = oIndex[fromDay]
                        val movedIdx = nDays.indexOf(moved)
                        if (fromIdx != null && movedIdx >= 0) {
                            mapper = { day ->
                                oIndex[day]?.let { i ->
                                    val k = movedIdx + i - fromIdx
                                    if (k >= 0 && k < nDays.size) nDays[k] else null
                                }
                            }
                        }
                    }
                }
                val sorted = open.sortedBy { it.occurrenceDate!! }
                val place = LinkedHashMap<String, String>()
                val rest = mutableListOf<OccurrenceRow>()
                for (r in sorted) {
                    val d = r.occurrenceDate!!
                    if (moved == null || (planner(d) && d !in taken)) {
                        place[r.id] = d
                        taken.add(d)
                    } else {
                        rest.add(r)
                    }
                }
                val owned = ownContent(w, rest, oldTemplate)
                val toRetire = mutableListOf<OccurrenceRow>()
                for (r in rest) {
                    val m = mapper(r.occurrenceDate!!)
                    if (m != null && m !in taken && (m <= horizon || r.id in owned)) {
                        place[r.id] = m
                        taken.add(m)
                    } else {
                        toRetire.add(r)
                    }
                }
                retire(w, toRetire, oldTemplate)
                for (r in sorted) {
                    val d = place[r.id] ?: continue
                    restamp(
                        r,
                        toDay = if (d == r.occurrenceDate) null else d,
                        toSeries = if (r.seriesId == target.id) null else target.id,
                    )
                }
                if (skips.isEmpty()) return
                skips.forEach { w.deleteSkip(s.id, it) }
                for (d in skips) {
                    val to = if (moved == null || planner(d)) d else mapper(d)
                    if (to != null) w.insertSkipIfAbsent(target.id, to)
                }
            }

            private fun all() {
                val carried = if (followed == null) emptyList() else movable(from = today)
                val carriedIds = carried.map { it.id }.toSet()
                movable().filter { it.id !in carriedIds }.forEach { restamp(it) }
                val startDay = if (followed == null) anchorDay else shiftedStart(rule, anchorDay, moved!!)
                val current = w.series.getValue(s.id)
                w.putSeries(
                    current.copy(
                        templateJson = template.toJsonString(),
                        ruleJson = followed?.toJsonString() ?: current.ruleJson,
                        anchorAt = if (time == null) current.anchorAt else timeFor(startDay),
                        updatedAt = now,
                    ),
                )
                if (followed != null) {
                    w.writeRow(task.id) { it.copy(occurrenceDate = moved) }
                    if (rule.end is RepeatEnd.Count) recountMoved(startDay, carriedIds)
                    carry(
                        open = carried,
                        target = w.series.getValue(s.id),
                        taken = w.rowsOf(s.id).filter { it.id !in carriedIds }.mapNotNull { it.occurrenceDate }.toMutableSet(),
                        skipsFrom = today,
                    )
                    materialize(w, w.series.getValue(s.id))
                }
            }

            private fun recountMoved(startDay: String, carried: Set<String>) {
                val followed = followed!!
                val total = (rule.end as RepeatEnd.Count).count
                val yesterday = RepeatDays.addDays(today, -1)
                val history = if (today <= anchorDay) {
                    0
                } else {
                    Recurrence.expand(rule, anchor = anchorDay, to = yesterday, max = CountCeiling).count { it != fromDay }
                }
                val spent = if (today <= startDay) {
                    0
                } else {
                    Recurrence.expand(followed.copy(end = RepeatEnd.Never), anchor = startDay, to = yesterday, max = CountCeiling).size
                }
                var keptOff = 0
                for (r in w.rowsOf(s.id)) {
                    val day = r.occurrenceDate
                    if (r.id == task.id || r.id in carried) continue
                    if (day == null || day < today) continue
                    if (!Recurrence.plans(followed, startDay, day)) keptOff++
                }
                val editedOff = if (moved!! < today) 1 else 0
                val count = spent + total - history - keptOff - editedOff
                val current = w.series.getValue(s.id)
                w.putSeries(current.copy(ruleJson = followed.copy(end = RepeatEnd.Count(if (count < 1) 1 else count)).toJsonString()))
            }

            private fun future() {
                val cut = if (moved != null && moved < fromDay) moved else fromDay
                val later = w.rowsOf(s.id).filter { it.id != task.id && it.occurrenceDate != null && it.occurrenceDate >= cut }
                val finishedLater = later.filter { it.status in Finished }
                val openLater = later.filter { it.status !in Finished }
                val nextRule = followed ?: rule
                val nextStart = moved ?: fromDay
                var nextEnd = nextRule.end
                val end = rule.end
                if (end is RepeatEnd.Count) {
                    val used = if (cut <= anchorDay) {
                        0
                    } else {
                        Recurrence.expand(rule, anchor = anchorDay, to = RepeatDays.addDays(cut, -1), max = CountCeiling).size
                    }
                    val extra = finishedLater.count { !Recurrence.plans(nextRule, nextStart, it.occurrenceDate!!) }
                    val left = end.count - used - extra
                    nextEnd = RepeatEnd.Count(if (left < 1) 1 else left)
                }
                val current = w.series.getValue(s.id)
                w.putSeries(
                    current.copy(
                        ruleJson = rule.copy(end = RepeatEnd.Until(RepeatDays.addDays(cut, -1))).toJsonString(),
                        updatedAt = now,
                    ),
                )
                val nextId = newId()
                w.putSeries(
                    SeriesRecord.create(
                        id = nextId,
                        workspaceId = s.workspaceId,
                        rule = nextRule.copy(end = nextEnd),
                        template = template,
                        timezone = tz,
                        anchorAt = timeFor(nextStart),
                        now = now,
                    ),
                )
                w.writeRow(task.id) { it.copy(seriesId = nextId, occurrenceDate = moved ?: it.occurrenceDate) }
                finishedLater.forEach { r -> w.writeRow(r.id, touch = false) { it.copy(seriesId = nextId) } }
                carry(
                    open = openLater,
                    target = w.series.getValue(nextId),
                    taken = (listOf(moved ?: fromDay) + finishedLater.map { it.occurrenceDate!! }).toMutableSet(),
                    skipsFrom = cut,
                )
                materialize(w, w.series.getValue(nextId))
            }
        }
    }
}

internal fun SeriesState.withTask(row: OccurrenceRow): SeriesState =
    if (tasks.any { it.id == row.id }) copy(tasks = tasks.map { if (it.id == row.id) row else it }) else copy(tasks = tasks + row)
