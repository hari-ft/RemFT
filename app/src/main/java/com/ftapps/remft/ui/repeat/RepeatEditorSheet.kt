package com.ftapps.remft.ui.repeat

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.repeat.AfterCompletionUnits
import com.ftapps.remft.domain.repeat.ClampHint
import com.ftapps.remft.domain.repeat.EndType
import com.ftapps.remft.domain.repeat.MonthMode
import com.ftapps.remft.domain.repeat.RepeatBasis
import com.ftapps.remft.domain.repeat.RepeatDays
import com.ftapps.remft.domain.repeat.RepeatEditor
import com.ftapps.remft.domain.repeat.RepeatFreq
import com.ftapps.remft.domain.repeat.RepeatPreset
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.RepeatString
import com.ftapps.remft.domain.repeat.RepeatWeekdays
import com.ftapps.remft.domain.repeat.WeekdayPick
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.DateInput
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftFilledButton
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.tasks.ChoiceField
import com.ftapps.remft.ui.tasks.TaskDatePickerDialog
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatEditorSheet(
    anchor: LocalDate,
    initial: RepeatRule?,
    format: DateFormatId,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (RepeatRule) -> Unit,
    previewFrom: LocalDate? = null,
    onTurnOff: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    RemftBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("repeat-sheet"),
    ) {
        RepeatEditorContent(anchor, initial, format, today, onDismiss, onSave, previewFrom, onTurnOff)
    }
}

@Composable
fun RepeatEditorContent(
    anchor: LocalDate,
    initial: RepeatRule?,
    format: DateFormatId,
    today: LocalDate,
    onCancel: () -> Unit,
    onSave: (RepeatRule) -> Unit,
    previewFrom: LocalDate? = null,
    onTurnOff: (() -> Unit)? = null,
) {
    var editor by remember(anchor, initial) { mutableStateOf(RepeatEditor.open(anchor, initial)) }
    val sentence = rememberRepeatSentence()
    val numberErrors = remember(anchor, initial) { NumberFieldErrors() }
    val valid = editor.isValid(previewFrom) && !numberErrors.any
    CompositionLocalProvider(LocalNumberFieldErrors provides numberErrors) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.x6, vertical = Space.x1),
            ) {
                Text(
                    stringResource(R.string.repeat_dialogTitle),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(Space.x4))
                BasisSwitch(editor.basis) { editor = editor.copy(basis = it) }
                Spacer(Modifier.height(Space.x3))
                if (editor.isAfter) {
                    AfterBody(editor, format, today) { editor = it }
                } else {
                    CalendarBody(editor, format, previewFrom) { editor = it }
                }
                Spacer(Modifier.height(Space.x2))
                SentenceBanner(
                    sentence.sentence(editor.rule, format, if (editor.isAfter) null else RepeatDays.key(anchor)),
                    Modifier.testTag("repeat-sentence"),
                )
                Spacer(Modifier.height(Space.x2))
                AdvancedToggle(editor.advanced) { editor = editor.copy(advanced = it) }
                if (editor.advanced) {
                    if (!editor.isAfter) {
                        NumberField(
                            value = editor.interval,
                            label = stringResource(R.string.repeat_intervalLabel),
                            tag = "repeat-interval",
                            onValue = { editor = editor.withInterval(it) },
                        )
                        FreqFields(editor, sentence::weekdayName) { editor = it }
                    }
                    EndFields(editor, format, today) { editor = it }
                }
                Spacer(Modifier.height(Space.x2))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = Space.x6, end = Space.x6, top = Space.x2, bottom = Space.x4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onTurnOff != null) {
                    RemftTextButton(
                        stringResource(R.string.repeat_off),
                        onClick = onTurnOff,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("repeat-off"),
                    )
                }
                Spacer(Modifier.weight(1f))
                RemftTextButton(stringResource(R.string.common_cancel), onClick = onCancel, modifier = Modifier.testTag("repeat-cancel"))
                Spacer(Modifier.width(Space.x2))
                RemftFilledButton(
                    stringResource(R.string.common_save),
                    onClick = { onSave(editor.rule) },
                    enabled = valid,
                    modifier = Modifier.testTag("repeat-save"),
                )
            }
        }
    }
}

private class NumberFieldErrors {
    private val bad = mutableStateMapOf<String, Boolean>()

    val any: Boolean get() = bad.values.any { it }

    fun report(tag: String, invalid: Boolean) {
        if (bad[tag] != invalid) bad[tag] = invalid
    }
}

private val LocalNumberFieldErrors = staticCompositionLocalOf<NumberFieldErrors?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasisSwitch(basis: RepeatBasis, onChange: (RepeatBasis) -> Unit) {
    val options = listOf(
        RepeatBasis.Calendar to stringResource(R.string.repeat_basis_calendar),
        RepeatBasis.Completion to stringResource(R.string.repeat_basis_completion),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = basis == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = repeatSegmentColors(),
                modifier = Modifier.heightIn(min = Sizes.touchTarget).testTag(if (value == RepeatBasis.Calendar) "repeat-basis-calendar" else "repeat-basis-completion"),
            ) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Space.x2).semantics { heading() },
    )
}

@StringRes
private fun presetLabel(preset: RepeatPreset): Int = when (preset) {
    RepeatPreset.Daily -> R.string.repeat_preset_daily
    RepeatPreset.Weekly -> R.string.repeat_preset_weekly
    RepeatPreset.Weekdays -> R.string.repeat_preset_weekdays
    RepeatPreset.Monthly -> R.string.repeat_preset_monthly
    RepeatPreset.Quarterly -> R.string.repeat_preset_quarterly
    RepeatPreset.HalfYearly -> R.string.repeat_preset_halfYearly
    RepeatPreset.Yearly -> R.string.repeat_preset_yearly
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarBody(editor: RepeatEditor, format: DateFormatId, previewFrom: LocalDate?, onChange: (RepeatEditor) -> Unit) {
    Label(stringResource(R.string.repeat_presets))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        RepeatPreset.entries.forEach { preset ->
            FilterChip(
                selected = editor.activePreset == preset,
                onClick = { onChange(editor.applyPreset(preset)) },
                label = { Text(stringResource(presetLabel(preset)), style = MaterialTheme.typography.labelLarge) },
                shape = MarkedShapes.pill,
                colors = repeatChipColors(),
                border = repeatChipBorder(editor.activePreset == preset),
                modifier = Modifier.heightIn(min = 48.dp).testTag("repeat-preset-${preset.key}"),
            )
        }
    }
    DayPicker(editor, onChange)
    Spacer(Modifier.height(Space.x2))
    PreviewCard(stringResource(R.string.repeat_next5), editor.preview(previewFrom), format, "repeat-preview")
}

@Composable
private fun PreviewCard(title: String, days: List<String>, format: DateFormatId, tag: String) {
    MarkedCard(Modifier.fillMaxWidth().testTag(tag), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.x4, vertical = Space.x3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventRepeat, contentDescription = null, tint = RemftTheme.colors.repeat.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.x2))
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.x2))
            days.forEach { day ->
                val date = LocalDate.parse(day)
                Text(
                    DateFormats.date(date.atStartOfDay(), format),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp).testTag("$tag-$day"),
                )
            }
            if (days.isEmpty()) {
                Text(
                    stringResource(R.string.repeat_noUpcoming),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

private fun monthShort(month: Int): String = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

@Composable
private fun DayPicker(editor: RepeatEditor, onChange: (RepeatEditor) -> Unit) {
    val monthly = editor.freq == RepeatFreq.Monthly
    val yearly = editor.freq == RepeatFreq.Yearly
    if (!monthly && !yearly) return
    val byDay = editor.monthMode == MonthMode.MonthDay
    val cycled = monthly && editor.interval == 1 && editor.step > 1
    if (!byDay && !yearly && !cycled) return
    val lastDayText = stringResource(R.string.repeat_on_lastDay)
    Spacer(Modifier.height(Space.x3))
    Row(horizontalArrangement = Arrangement.spacedBy(Space.x3), modifier = Modifier.fillMaxWidth()) {
        if (byDay) {
            ChoiceField(
                label = stringResource(R.string.repeat_monthDayLabel),
                selected = editor.monthDayChoice,
                options = (1..31).toList() + listOf(-1),
                onSelect = { onChange(editor.withMonthDayChoice(it)) },
                tag = "repeat-month-day",
                modifier = Modifier.weight(1f),
            ) { Text(if (it == -1) lastDayText else it.toString()) }
        }
        if (yearly) {
            ChoiceField(
                label = stringResource(R.string.repeat_monthLabel),
                selected = editor.month,
                options = (1..12).toList(),
                onSelect = { onChange(editor.copy(month = it)) },
                tag = "repeat-month",
                modifier = Modifier.weight(1f),
            ) { Text(monthName(it)) }
        }
    }
    if (cycled) {
        Spacer(Modifier.height(Space.x3))
        val choices = editor.cycleStartChoices
        ChoiceField(
            label = stringResource(R.string.repeat_monthsLabel),
            selected = editor.cycleStartChoice,
            options = (1..choices.size).toList(),
            onSelect = { onChange(editor.copy(month = it)) },
            tag = "repeat-months",
            modifier = Modifier.fillMaxWidth(),
        ) { start -> Text(choices[start - 1].joinToString(", ") { monthShort(it) }) }
    }
    val hint = when (editor.clampHint) {
        ClampHint.ShortMonths -> R.string.repeat_clampHint
        ClampHint.ShortMonthYearly -> R.string.repeat_clampHintYearly
        ClampHint.LeapDay -> R.string.repeat_clampHintLeap
        null -> null
    }
    if (hint != null) {
        Text(
            stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.x2).testTag("repeat-clamp-hint"),
        )
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(
        onClick = { onChange(!open) },
        shape = MarkedShapes.pill,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).testTag("repeat-advanced"),
    ) {
        Text(stringResource(R.string.repeat_advanced), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun NumberField(value: Int, label: String, tag: String, range: IntRange = 1..366, onValue: (Int) -> Unit) {
    var text by remember(tag) { mutableStateOf(value.toString()) }
    if (text.toIntOrNull() != null && text.toInt() != value && text.toInt() in range) text = value.toString()
    val errors = LocalNumberFieldErrors.current
    val bad = text.toIntOrNull()?.let { it !in range } ?: true
    SideEffect { errors?.report(tag, bad) }
    DisposableEffect(errors, tag) { onDispose { errors?.report(tag, false) } }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            val digits = next.filter(Char::isDigit).take(4)
            text = digits
            digits.toIntOrNull()?.takeIf { it in range }?.let(onValue)
        },
        label = { Text(label) },
        isError = bad,
        singleLine = true,
        shape = MarkedShapes.small,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = Space.x3).testTag(tag),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FreqFields(editor: RepeatEditor, weekdayName: (String) -> String, onChange: (RepeatEditor) -> Unit) {
    when (editor.freq) {
        RepeatFreq.Daily -> Unit
        RepeatFreq.Weekly -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x1), modifier = Modifier.padding(bottom = Space.x3)) {
            RepeatWeekdays.forEach { day ->
                val on = day in editor.weekdays
                FilterChip(
                    selected = on,
                    onClick = {
                        val next = if (on) editor.weekdays - day else editor.weekdays + day
                        if (next.isNotEmpty()) onChange(editor.copy(weekdays = next))
                    },
                    label = { Text(weekdayName(day), style = MaterialTheme.typography.labelLarge) },
                    shape = MarkedShapes.pill,
                    colors = repeatChipColors(),
                    border = repeatChipBorder(on),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("repeat-weekday-$day"),
                )
            }
        }
        RepeatFreq.Monthly, RepeatFreq.Yearly -> {
            val modes = listOf(
                MonthMode.MonthDay to R.string.repeat_mode_monthDay,
                MonthMode.NthWeekday to R.string.repeat_mode_nthWeekday,
                MonthMode.AfterDay to R.string.repeat_mode_afterDay,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Space.x3)) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = editor.monthMode == mode,
                        onClick = { onChange(editor.copy(monthMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        icon = {},
                        colors = repeatSegmentColors(),
                        modifier = Modifier.heightIn(min = Sizes.touchTarget).testTag("repeat-mode-${mode.name}"),
                    ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
                }
            }
            when (editor.monthMode) {
                MonthMode.MonthDay -> Unit
                MonthMode.NthWeekday -> {
                    val lastText = stringResource(R.string.repeat_on_lastDay)
                    val ordinalTexts = (1..5).associateWith { n -> RepeatString.ordinal(n)?.let { stringResource(RepeatTexts.res(it)) }.orEmpty() }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x3), modifier = Modifier.padding(bottom = Space.x3)) {
                        ChoiceField(
                            label = stringResource(R.string.repeat_ordinalLabel),
                            selected = editor.ordinal,
                            options = (1..5).toList() + listOf(-1),
                            onSelect = { onChange(editor.copy(ordinal = it)) },
                            tag = "repeat-ordinal",
                            modifier = Modifier.weight(1f),
                        ) { Text(if (it == -1) lastText else ordinalTexts[it].orEmpty()) }
                        ChoiceField(
                            label = stringResource(R.string.repeat_weekdayLabel),
                            selected = editor.ordinalWeekday,
                            options = RepeatWeekdays,
                            onSelect = { onChange(editor.copy(ordinalWeekday = it)) },
                            tag = "repeat-ordinal-weekday",
                            modifier = Modifier.weight(1f),
                        ) { Text(weekdayName(it)) }
                    }
                }
                MonthMode.AfterDay -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.x3)) {
                        ChoiceField(
                            label = stringResource(R.string.repeat_weekdayLabel),
                            selected = editor.afterWeekday,
                            options = RepeatWeekdays,
                            onSelect = { onChange(editor.copy(afterWeekday = it)) },
                            tag = "repeat-after-weekday",
                            modifier = Modifier.weight(1f),
                        ) { Text(weekdayName(it)) }
                        ChoiceField(
                            label = stringResource(R.string.repeat_monthDayLabel),
                            selected = editor.afterDay,
                            options = (1..24).toList(),
                            onSelect = { onChange(editor.copy(afterDay = it)) },
                            tag = "repeat-after-day",
                            modifier = Modifier.weight(1f),
                        ) { Text(it.toString()) }
                    }
                    Text(
                        stringResource(R.string.repeat_afterDayHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.x2, bottom = Space.x3),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndFields(editor: RepeatEditor, format: DateFormatId, today: LocalDate, onChange: (RepeatEditor) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Label(stringResource(R.string.repeat_endLabel))
    val options = listOf(
        EndType.Never to R.string.repeat_end_never,
        EndType.Until to R.string.repeat_end_until,
        EndType.Count to R.string.repeat_end_count,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = Space.x3)) {
        options.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = editor.endType == type,
                onClick = {
                    onChange(
                        if (type == EndType.Until && editor.until == null) {
                            editor.copy(endType = type, until = maxOf(editor.anchor, today).plusMonths(1))
                        } else {
                            editor.copy(endType = type)
                        },
                    )
                },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                colors = repeatSegmentColors(),
                modifier = Modifier.heightIn(min = Sizes.touchTarget).testTag("repeat-end-${type.wire}"),
            ) { Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
        }
    }
    when (editor.endType) {
        EndType.Never -> Unit
        EndType.Until -> OutlinedButton(
            onClick = { picking = true },
            shape = MarkedShapes.pill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget).testTag("repeat-until"),
        ) {
            Icon(Icons.Outlined.CalendarToday, contentDescription = null)
            Spacer(Modifier.width(Space.x2))
            val until = editor.until
            Text(
                if (until == null) stringResource(R.string.repeat_endUntilLabel) else DateFormats.date(until.atStartOfDay(), format),
                modifier = Modifier.weight(1f),
            )
        }
        EndType.Count -> NumberField(
            value = editor.count,
            label = stringResource(R.string.repeat_endCountLabel),
            tag = "repeat-count",
            range = 1..1000,
            onValue = { onChange(editor.copy(count = it)) },
        )
    }
    if (picking) {
        val now = LocalDateTime.of(today, java.time.LocalTime.NOON)
        TaskDatePickerDialog(
            initial = editor.until ?: today,
            first = minOf(editor.anchor, DateInput.pickerFirstDate(now)),
            last = DateInput.pickerLastDate(now),
            format = format,
            onDismiss = { picking = false },
            onPicked = {
                picking = false
                onChange(editor.copy(until = it, endType = EndType.Until))
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AfterBody(editor: RepeatEditor, format: DateFormatId, today: LocalDate, onChange: (RepeatEditor) -> Unit) {
    NumberField(
        value = editor.afterInterval,
        label = stringResource(R.string.repeat_after_intervalLabel),
        tag = "repeat-after-interval",
        onValue = { onChange(editor.withAfterInterval(it)) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        AfterCompletionUnits.forEach { unit ->
            val key = when (unit) {
                RepeatFreq.Daily -> "days"
                RepeatFreq.Weekly -> "weeks"
                else -> "months"
            }
            val label = when (unit) {
                RepeatFreq.Daily -> R.string.repeat_after_unit_days
                RepeatFreq.Weekly -> R.string.repeat_after_unit_weeks
                else -> R.string.repeat_after_unit_months
            }
            FilterChip(
                selected = editor.afterFreq == unit,
                onClick = { onChange(editor.copy(afterFreq = unit)) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
                shape = MarkedShapes.pill,
                colors = repeatChipColors(),
                border = repeatChipBorder(editor.afterFreq == unit),
                modifier = Modifier.heightIn(min = 48.dp).testTag("repeat-after-unit-$key"),
            )
        }
    }
    Spacer(Modifier.height(Space.x2))
    PreviewCard(stringResource(R.string.repeat_after_previewTitle), listOfNotNull(editor.afterPreview(today)), format, "repeat-after-preview")
    Text(
        stringResource(R.string.repeat_after_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.x2).testTag("repeat-after-hint"),
    )
}

@Composable
private fun SentenceBanner(text: String, modifier: Modifier = Modifier) {
    val repeat = RemftTheme.colors.repeat
    Row(
        Modifier
            .fillMaxWidth()
            .background(repeat.container, MarkedShapes.small)
            .padding(horizontal = Space.x3, vertical = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Repeat, contentDescription = null, tint = repeat.onContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.x2))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = repeat.onContainer, modifier = modifier.weight(1f))
    }
}

@Composable
private fun repeatChipColors(): SelectableChipColors {
    val repeat = RemftTheme.colors.repeat
    val scheme = MaterialTheme.colorScheme
    return FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = scheme.onSurfaceVariant,
        selectedContainerColor = repeat.container,
        selectedLabelColor = repeat.onContainer,
        selectedLeadingIconColor = repeat.onContainer,
    )
}

@Composable
private fun repeatChipBorder(selected: Boolean): BorderStroke =
    FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        selectedBorderColor = Color.Transparent,
    )

@Composable
private fun repeatSegmentColors(): SegmentedButtonColors {
    val repeat = RemftTheme.colors.repeat
    val scheme = MaterialTheme.colorScheme
    return SegmentedButtonDefaults.colors(
        activeContainerColor = repeat.container,
        activeContentColor = repeat.onContainer,
        activeBorderColor = scheme.outline,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = scheme.onSurfaceVariant,
        inactiveBorderColor = scheme.outline,
    )
}

@Preview(name = "Repeat editor light", heightDp = 900)
@Composable
private fun RepeatEditorLightPreview() {
    RemftTheme(darkTheme = false) { RepeatEditorPreviewContent() }
}

@Preview(name = "Repeat editor dark", heightDp = 900)
@Composable
private fun RepeatEditorDarkPreview() {
    RemftTheme(darkTheme = true) { RepeatEditorPreviewContent() }
}

@Composable
private fun RepeatEditorPreviewContent() {
    val day = LocalDate.of(2026, 11, 22)
    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = Space.x4)) {
        RepeatEditorContent(
            anchor = day,
            initial = RepeatRule(RepeatFreq.Weekly, byWeekday = listOf(WeekdayPick("SU"))),
            format = DateFormatId.System,
            today = day,
            onCancel = {},
            onSave = {},
            onTurnOff = {},
        )
    }
}
