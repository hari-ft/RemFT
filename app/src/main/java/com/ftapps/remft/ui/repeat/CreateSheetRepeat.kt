package com.ftapps.remft.ui.repeat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.ftapps.remft.domain.repeat.RepeatDays
import com.ftapps.remft.domain.repeat.RepeatEditor
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.DefaultTime
import com.ftapps.remft.ui.tasks.TaskSheetState
import com.ftapps.remft.ui.tasks.rememberDateFormat
import java.time.Duration
import java.time.LocalDateTime

class RepeatDraft {
    var rule by mutableStateOf<RepeatRule?>(null)
        private set
    private var dueFromRepeat = false

    fun anchor(state: TaskSheetState, now: LocalDateTime): LocalDateTime {
        val due = state.dueAt
        if (due != null) {
            val lateClamp = DefaultTime.isLateTodayDefault(due, now) && due.hour == 23 && due.minute == 59
            return if (lateClamp) DefaultTime.repeatStart(now) else due
        }
        return state.remindAt ?: DefaultTime.repeatStart(now)
    }

    fun choose(state: TaskSheetState, picked: RepeatRule, now: LocalDateTime) {
        rule = picked
        val hadDate = state.dueAt != null || state.remindAt != null
        val first = RepeatEditor.firstRepeatDue(picked, anchor(state, now)) ?: return
        val due = state.dueAt
        val remind = state.remindAt
        when {
            due != null -> {
                if (first == due) return
                val hand = if (remind != null && !state.remindAuto) Duration.between(remind, due) else null
                state.dueAt = first
                if (hand != null) state.remindAt = first.minus(hand)
            }
            remind != null -> {
                if (first != remind) state.remindAt = first
                return
            }
            else -> state.dueAt = first
        }
        dueFromRepeat = !hadDate
        state.syncAutoReminder(now)
    }

    fun clear(state: TaskSheetState, now: LocalDateTime) {
        rule = null
        if (dueFromRepeat) {
            dueFromRepeat = false
            state.changeDue(null, now)
        }
    }

    fun onDueTouched() {
        dueFromRepeat = false
    }
}

@Composable
fun CreateSheetRepeatRow(draft: RepeatDraft, state: TaskSheetState, now: () -> LocalDateTime) {
    val format = rememberDateFormat()
    val sentence = rememberRepeatSentence()
    var editing by remember { mutableStateOf(false) }
    val rule = draft.rule
    RepeatSwitchRow(
        on = rule != null,
        summary = rule?.let { sentence.sentence(it, format, RepeatDays.key(draft.anchor(state, now()).toLocalDate())) },
        onToggle = { wanted -> if (wanted) editing = true else draft.clear(state, now()) },
        onChange = { editing = true },
        switchTag = "task-sheet-repeat",
        summaryTag = "task-sheet-repeat-summary",
        changeTag = "task-sheet-repeat-change",
        modifier = Modifier.testTag("task-sheet-repeat-row"),
    )
    if (editing) {
        val at = now()
        RepeatEditorSheet(
            anchor = draft.anchor(state, at).toLocalDate(),
            initial = rule,
            format = format,
            today = at.toLocalDate(),
            onDismiss = { editing = false },
            onTurnOff = if (rule != null) {
                {
                    editing = false
                    draft.clear(state, now())
                }
            } else {
                null
            },
            onSave = { picked ->
                editing = false
                draft.choose(state, picked, now())
            },
        )
    }
}
