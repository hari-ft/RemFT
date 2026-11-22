package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.theme.Brand
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@Composable
private fun cappedSize(size: TextUnit, maxScale: Float): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale <= maxScale) size else (size.value * maxScale / scale).sp
}

@Composable
fun WeekStrip(
    today: LocalDate,
    markedDays: Set<LocalDate>,
    selectedDay: LocalDate?,
    onDaySelected: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayLabel = stringResource(R.string.calendar_a11yToday)
    val hasTasksLabel = stringResource(R.string.calendar_a11yHasTasks)
    Row(modifier.fillMaxWidth().testTag("week-strip"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (offset in 0 until 7) {
            val day = today.plusDays(offset.toLong())
            val isToday = day == today
            val isSelected = day == selectedDay
            val hasTasks = day in markedDays
            val label = buildList {
                add(day.dayOfMonth.toString())
                if (isToday) add(todayLabel)
                if (hasTasks) add(hasTasksLabel)
            }.joinToString(", ")
            WeekDay(
                day = day,
                isToday = isToday,
                isSelected = isSelected,
                hasTasks = hasTasks,
                modifier = Modifier
                    .weight(1f)
                    .clip(MarkedShapes.tile)
                    .selectable(selected = isSelected, onClick = { onDaySelected(if (isSelected) null else day) })
                    .clearAndSetSemantics {
                        contentDescription = label
                        selected = isSelected
                        role = Role.Button
                        testTag = "calendar-day-$day"
                    },
            )
        }
    }
}

@Composable
private fun WeekDay(day: LocalDate, isToday: Boolean, isSelected: Boolean, hasTasks: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tile = when {
        isToday -> Modifier.background(RemftTheme.gradients.today.diagonal, MarkedShapes.tile)
        isSelected -> Modifier.background(scheme.primaryContainer, MarkedShapes.tile)
        else -> Modifier
    }
    val ring = if (isToday && isSelected) Modifier.border(2.dp, scheme.primary, MarkedShapes.tile) else Modifier
    val ink = when {
        isToday -> Brand.TodayInk
        isSelected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    val soft = when {
        isToday -> ink
        isSelected -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    val dot = when {
        isToday -> ink
        isSelected -> scheme.onPrimaryContainer
        else -> scheme.primary
    }
    Box(modifier.then(tile).then(ring).heightIn(min = 68.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.ENGLISH),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = cappedSize(14.sp, 1.5f)),
                color = soft,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = cappedSize(22.sp, 1.3f)),
                color = ink,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(6.dp), contentAlignment = Alignment.Center) {
                if (hasTasks) {
                    Box(Modifier.size(6.dp).background(dot, CircleShape).testTag("calendar-dot-$day"))
                }
            }
        }
    }
}

@Preview(name = "Week strip light")
@Composable
private fun WeekStripLightPreview() {
    RemftTheme(darkTheme = false) { WeekStripPreviewContent() }
}

@Preview(name = "Week strip dark")
@Composable
private fun WeekStripDarkPreview() {
    RemftTheme(darkTheme = true) { WeekStripPreviewContent() }
}

@Composable
private fun WeekStripPreviewContent() {
    val today = LocalDate.of(2026, 11, 22)
    PreviewSurface {
        WeekStrip(today, setOf(today, today.plusDays(1), today.plusDays(3), today.plusDays(5)), null, onDaySelected = {})
    }
}
