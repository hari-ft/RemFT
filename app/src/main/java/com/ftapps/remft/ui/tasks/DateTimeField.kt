package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.DateInput
import com.ftapps.remft.domain.tasks.DefaultTime
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

fun Instant.toLocal(zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(this, zone)

fun LocalDateTime.toInstant(zone: ZoneId): Instant = atZone(zone).toInstant()

@Composable
fun DateTimeField(
    tag: String,
    label: String,
    icon: ImageVector,
    value: LocalDateTime?,
    now: () -> LocalDateTime,
    onChanged: (LocalDateTime?) -> Unit,
    modifier: Modifier = Modifier,
    onTimeChanged: ((LocalDateTime) -> Unit)? = null,
    deadline: LocalDateTime? = null,
    anchor: LocalDateTime? = null,
    clearDescription: String? = null,
) {
    val format = rememberDateFormat()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val localAnchor = anchor ?: deadline
    val dateText = value?.let { DateFormats.date(it, format) } ?: stringResource(R.string.dateField_addDate)
    val timeText = value?.let { DateFormats.time(it, format) } ?: stringResource(R.string.dateField_addTime)
    val afterDeadline = value != null && deadline != null && value.isAfter(deadline)
    Column(modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Space.x4))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) {
                val clear = clearDescription ?: stringResource(R.string.task_clearField).fill("field" to label)
                IconButton(onClick = { onChanged(null) }, modifier = Modifier.testTag("$tag-clear")) {
                    Icon(Icons.Outlined.Close, contentDescription = clear)
                }
            }
        }
        Spacer(Modifier.size(Space.x2))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2), modifier = Modifier.fillMaxWidth()) {
            PartButton(
                text = dateText,
                isSet = value != null,
                icon = Icons.Outlined.CalendarToday,
                description = stringResource(R.string.dateField_dateOf).fill("field" to label),
                tag = "$tag-date",
                onClick = { pickingDate = true },
                modifier = Modifier.weight(1f),
            )
            PartButton(
                text = timeText,
                isSet = value != null,
                icon = Icons.Outlined.Schedule,
                description = stringResource(R.string.dateField_timeOf).fill("field" to label),
                tag = "$tag-time",
                onClick = { pickingTime = true },
                modifier = Modifier.weight(1f),
            )
        }
        if (afterDeadline) {
            Row(Modifier.padding(top = Space.x2).testTag("$tag-after-deadline")) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.x2))
                Text(
                    stringResource(R.string.dateField_afterDeadline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (pickingDate) {
        val at = now()
        TaskDatePickerDialog(
            initial = DateInput.initialPickerDate(value, localAnchor, at),
            first = DateInput.pickerFirstDate(at),
            last = DateInput.pickerLastDate(at),
            format = format,
            onDismiss = { pickingDate = false },
            onPicked = { date ->
                pickingDate = false
                onChanged(DateInput.withDate(value, date, deadline, now()))
            },
        )
    }
    if (pickingTime) {
        val at = now()
        TaskTimePickerDialog(
            initial = (value ?: DefaultTime.instant(at)).toLocalTime(),
            is24Hour = DateFormats.spec(format).uses24HourClock,
            onDismiss = { pickingTime = false },
            onPicked = { time ->
                pickingTime = false
                val picked = DateInput.withTime(value, time, localAnchor, now())
                (onTimeChanged ?: onChanged)(picked)
            },
        )
    }
}

@Composable
private fun PartButton(
    text: String,
    isSet: Boolean,
    icon: ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = "$description, $text" }.testTag(tag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.x3),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.x2))
        Text(
            text,
            fontWeight = if (isSet) FontWeight.SemiBold else null,
            color = if (isSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
}

fun pickerLocale(format: DateFormatId): Locale {
    val pattern = DateInput.pickerPattern(format)
    return Locale.Builder()
        .setLanguage("en")
        .setRegion(if (pattern.dayFirst) "GB" else "US")
        .setUnicodeLocaleKeyword("fw", "mon")
        .build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDatePickerDialog(
    initial: LocalDate,
    first: LocalDate,
    last: LocalDate,
    format: DateFormatId,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val from = minOf(first, initial)
    val to = maxOf(last, initial)
    val state = remember {
        DatePickerState(
            locale = pickerLocale(format),
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialDisplayedMonthMillis = null,
            yearRange = from.year..to.year,
            initialDisplayMode = DisplayMode.Picker,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(from) && !day.isAfter(to)
                }

                override fun isSelectableYear(year: Int): Boolean = year in from.year..to.year
            },
        )
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis == null) onDismiss() else onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
                modifier = Modifier.testTag("date-picker-ok"),
            ) { Text(stringResource(R.string.tasks_pickerOk)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskTimePickerDialog(
    initial: LocalTime,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onPicked: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }, modifier = Modifier.testTag("time-picker-ok")) {
                Text(stringResource(R.string.tasks_pickerOk))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}
