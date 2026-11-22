package com.ftapps.remft.ui.quickadd

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.chipColors
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition

private class HelpSection(
    val icon: ImageVector,
    val tone: ChipTone,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

private val helpSections = listOf(
    HelpSection(Icons.Outlined.Event, ChipTone.Today, R.string.task_quickParse_help_datesTitle, R.string.task_quickParse_help_dates),
    HelpSection(Icons.Outlined.Schedule, ChipTone.Primary, R.string.task_quickParse_help_timesTitle, R.string.task_quickParse_help_times),
    HelpSection(Icons.Outlined.Flag, ChipTone.Overdue, R.string.task_quickParse_help_deadlineTitle, R.string.task_quickParse_help_deadline),
    HelpSection(Icons.Outlined.Repeat, ChipTone.Repeat, R.string.task_quickParse_help_repeatTitle, R.string.task_quickParse_help_repeat),
    HelpSection(Icons.Outlined.Sell, ChipTone.Urgent, R.string.task_quickParse_help_marksTitle, R.string.task_quickParse_help_marks),
    HelpSection(Icons.AutoMirrored.Outlined.Undo, ChipTone.Neutral, R.string.task_quickParse_help_undoTitle, R.string.task_quickParse_help_undo),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddHelpSheet(onDismiss: () -> Unit) {
    RemftBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("quick-add-help-sheet"),
    ) {
        QuickAddHelpContent(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        )
    }
}

@Composable
private fun QuickAddHelpContent(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(start = Space.x4, end = Space.x4, bottom = Space.x6)) {
        Text(
            stringResource(R.string.task_quickParse_help_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Space.x2).semantics { heading() },
        )
        Spacer(Modifier.height(Space.x2))
        Text(
            stringResource(R.string.task_quickParse_help_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.x2),
        )
        Spacer(Modifier.height(Space.x4))
        helpSections.forEachIndexed { index, section ->
            GroupedCardItem(groupPosition(index, helpSections.size), color = RemftTheme.tonal.level1) {
                HelpSectionRow(section)
            }
        }
    }
}

@Composable
private fun HelpSectionRow(section: HelpSection) {
    val scheme = MaterialTheme.colorScheme
    val tone = chipColors(section.tone)
    Row(Modifier.fillMaxWidth().padding(Space.x4), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).background(tone.container, MarkedShapes.small), contentAlignment = Alignment.Center) {
            Icon(section.icon, contentDescription = null, tint = tone.content, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
            Text(stringResource(section.title), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            stringResource(section.body).split("\n").forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Preview(name = "Quick add help light", heightDp = 1100)
@Composable
private fun QuickAddHelpLightPreview() {
    RemftTheme(darkTheme = false) { QuickAddHelpPreviewContent() }
}

@Preview(name = "Quick add help dark", heightDp = 1100)
@Composable
private fun QuickAddHelpDarkPreview() {
    RemftTheme(darkTheme = true) { QuickAddHelpPreviewContent() }
}

@Composable
private fun QuickAddHelpPreviewContent() {
    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MarkedShapes.sheet).padding(top = Space.x4)) {
        QuickAddHelpContent()
    }
}
