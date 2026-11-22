package com.ftapps.remft.ui.repeat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.repeat.RepeatDays
import com.ftapps.remft.domain.repeat.RepeatEditor
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.DefaultTime
import com.ftapps.remft.ui.tasks.TaskSheetState
import com.ftapps.remft.ui.tasks.rememberDateFormat
import com.ftapps.remft.ui.tasks.successSwitchColors
import com.ftapps.remft.ui.theme.Space
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
    Column(Modifier.fillMaxWidth().testTag("task-sheet-repeat-row")) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.repeat_switchTitle)) },
            supportingContent = { Text(stringResource(R.string.repeat_switchSubtitle)) },
            leadingContent = { Icon(Icons.Outlined.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = {
                Switch(
                    checked = rule != null,
                    onCheckedChange = { wanted -> if (wanted) editing = true else draft.clear(state, now()) },
                    colors = successSwitchColors(),
                    modifier = Modifier.testTag("task-sheet-repeat"),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (rule != null) {
            Row(Modifier.fillMaxWidth().padding(start = Space.x4, end = Space.x2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sentence.sentence(rule, format, RepeatDays.key(draft.anchor(state, now()).toLocalDate())),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).testTag("task-sheet-repeat-summary"),
                )
                TextButton(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("task-sheet-repeat-change")) {
                    Text(stringResource(R.string.repeat_change))
                }
            }
        }
    }
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
