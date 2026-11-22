package com.ftapps.remft.ui.quickadd

import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.domain.quickadd.QuickAdd
import com.ftapps.remft.domain.quickadd.QuickParse
import com.ftapps.remft.domain.quickadd.QuickParseOptions
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.TaskDefaults
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.tasks.TaskStatus
import java.time.LocalDateTime

class QuickAddCreator(
    private val repository: TasksRepository,
    private val series: SeriesRepository? = null,
) {
    fun options(format: DateFormatId, ignored: Set<String> = emptySet()): QuickParseOptions =
        QuickParseOptions.of(repository.now(), repository.zone, format, ignored)

    fun parse(text: String, format: DateFormatId, ignored: Set<String> = emptySet()): QuickParse =
        QuickAdd.parse(text, options(format, ignored))

    fun draftFor(parsed: QuickParse, fallbackDue: LocalDateTime? = null, capture: Boolean = false): TaskDraft {
        val withDefaults = !capture || parsed.dueAt != null
        val due = (parsed.dueAt ?: fallbackDue)?.atZone(repository.zone)?.toInstant()
        return TaskDraft(
            title = parsed.title.trim(),
            status = if (capture) TaskStatus.Inbox.db else null,
            priority = parsed.priority ?: if (withDefaults) TaskPriority.Medium.db else null,
            dueAt = due,
            remindAt = TaskDefaults.autoReminderFor(due, repository.now()),
            isUrgent = withDefaults,
        )
    }

    suspend fun createFromQuickAdd(parsed: QuickParse, fallbackDue: LocalDateTime? = null, capture: Boolean = false): String {
        val draft = draftFor(parsed, fallbackDue, capture)
        val id = repository.create(draft)
        val repeat = parsed.repeat
        val anchor = parsed.dueAt?.atZone(repository.zone)?.toInstant()
        if (series != null && repeat != null && anchor != null) {
            RepeatRule.fromJson(repeat.toJson())?.let { rule -> series.start(id, rule, anchor) }
        }
        return id
    }
}
