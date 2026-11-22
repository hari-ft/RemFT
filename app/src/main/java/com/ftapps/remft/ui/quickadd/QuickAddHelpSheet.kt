package com.ftapps.remft.ui.quickadd

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.Space

private class HelpSection(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val body: Int)

private val helpSections = listOf(
    HelpSection(Icons.Outlined.Event, R.string.task_quickParse_help_datesTitle, R.string.task_quickParse_help_dates),
    HelpSection(Icons.Outlined.Schedule, R.string.task_quickParse_help_timesTitle, R.string.task_quickParse_help_times),
    HelpSection(Icons.Outlined.Flag, R.string.task_quickParse_help_deadlineTitle, R.string.task_quickParse_help_deadline),
    HelpSection(Icons.Outlined.Repeat, R.string.task_quickParse_help_repeatTitle, R.string.task_quickParse_help_repeat),
    HelpSection(Icons.Outlined.Sell, R.string.task_quickParse_help_marksTitle, R.string.task_quickParse_help_marks),
    HelpSection(Icons.AutoMirrored.Outlined.Undo, R.string.task_quickParse_help_undoTitle, R.string.task_quickParse_help_undo),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddHelpSheet(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        containerColor = scheme.surfaceContainerLow,
        sheetMaxWidth = Breakpoints.sheetMaxWidth,
        modifier = Modifier.testTag("quick-add-help-sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Space.x6, end = Space.x6, bottom = Space.x6),
        ) {
            Text(stringResource(R.string.task_quickParse_help_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Space.x2))
            Text(
                stringResource(R.string.task_quickParse_help_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            helpSections.forEach { section ->
                Spacer(Modifier.height(Space.x5))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(section.icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.x2))
                    Text(stringResource(section.title), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(Space.x1))
                stringResource(section.body).split("\n").forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 28.dp, top = Space.x1))
                }
            }
        }
    }
}
