package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ftapps.remft.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthCalendar(
    today: LocalDate,
    markedDays: Set<LocalDate>,
    selectedDay: LocalDate?,
    onDaySelected: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selectedDay ?: today).toString()) }
    val month = YearMonth.parse(monthText)
    LaunchedEffect(selectedDay) {
        if (selectedDay != null && YearMonth.from(selectedDay) != YearMonth.parse(monthText)) {
            monthText = YearMonth.from(selectedDay).toString()
        }
    }
    val scheme = MaterialTheme.colorScheme
    val title = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val firstCell = first.minusDays(leading.toLong())
    val todayLabel = stringResource(R.string.calendar_a11yToday)
    val hasTasksLabel = stringResource(R.string.calendar_a11yHasTasks)
    Column(modifier.testTag("month-calendar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("calendar-title"),
            )
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }, modifier = Modifier.testTag("calendar-previous")) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previousMonth), tint = scheme.onSurfaceVariant)
            }
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }, modifier = Modifier.testTag("calendar-next")) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_nextMonth), tint = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            DayOfWeek.entries.forEach { dow ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        for (week in 0 until 6) {
            Row {
                for (dow in 0 until 7) {
                    val day = firstCell.plusDays((week * 7 + dow).toLong())
                    val inMonth = day.month == month.month
                    val isToday = day == today
                    val isSelected = day == selectedDay
                    val hasTasks = day in markedDays
                    val label = buildList {
                        add(day.dayOfMonth.toString())
                        if (isToday) add(todayLabel)
                        if (hasTasks) add(hasTasksLabel)
                    }.joinToString(", ")
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .selectable(selected = isSelected, onClick = { onDaySelected(if (isSelected) null else day) })
                            .clearAndSetSemantics {
                                contentDescription = label
                                selected = isSelected
                                role = Role.Button
                                testTag = "calendar-day-$day"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            var circle = Modifier.size(32.dp)
                            if (isSelected) circle = circle.background(scheme.primary, CircleShape)
                            if (isToday && !isSelected) circle = circle.border(1.5.dp, scheme.primary, CircleShape)
                            Box(circle, contentAlignment = Alignment.Center) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isSelected -> scheme.onPrimary
                                        isToday -> scheme.primary
                                        inMonth -> scheme.onSurface
                                        else -> scheme.onSurfaceVariant
                                    },
                                )
                            }
                            Box(Modifier.height(6.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
                                if (hasTasks) {
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .background(if (isSelected) scheme.primary else scheme.tertiary, CircleShape)
                                            .testTag("calendar-dot-$day"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
