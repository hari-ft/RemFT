package com.ftapps.remft.ui.tasks

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.tasks.InboxSort
import com.ftapps.remft.domain.tasks.SortChoice
import com.ftapps.remft.domain.tasks.SortState
import com.ftapps.remft.domain.tasks.TaskSort
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.menuChoice
import com.ftapps.remft.ui.components.menuToggle
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

object SortLabels {
    @StringRes
    fun home(id: String): Int = when (id) {
        "priority" -> R.string.sort_taskPriority
        "title" -> R.string.sort_title
        else -> R.string.sort_taskDate
    }

    @StringRes
    fun inbox(id: String): Int = when (id) {
        "title" -> R.string.sort_title
        "priority" -> R.string.sort_taskPriority
        "updated" -> R.string.sort_updated
        else -> R.string.sort_fileDate
    }
}

@Composable
fun ListSortMenu(
    sort: SortState,
    choices: List<SortChoice>,
    labelOf: (String) -> Int,
    buttonTag: String,
    onChoose: (SortChoice) -> Unit,
    onReverse: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RemftIconButton(label = stringResource(R.string.sort_tooltip), onClick = { open = true }, modifier = Modifier.testTag(buttonTag)) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort_tooltip))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ListSortMenuItems(
                sort = sort,
                choices = choices,
                labelOf = labelOf,
                onChoose = {
                    open = false
                    onChoose(it)
                },
                onReverse = {
                    open = false
                    onReverse()
                },
            )
        }
    }
}

@Composable
private fun ListSortMenuItems(
    sort: SortState,
    choices: List<SortChoice>,
    labelOf: (String) -> Int,
    onChoose: (SortChoice) -> Unit,
    onReverse: () -> Unit,
) {
    Text(
        stringResource(R.string.sort_sectionTitle),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
    )
    choices.forEach { choice ->
        val selected = sort.id == choice.id
        DropdownMenuItem(
            text = { Text(stringResource(labelOf(choice.id))) },
            leadingIcon = {
                if (selected) Icon(Icons.Outlined.Check, contentDescription = null) else Box(Modifier.width(24.dp))
            },
            onClick = { onChoose(choice) },
            modifier = Modifier.menuChoice(selected).testTag("sort-${choice.id}"),
        )
    }
    HorizontalDivider()
    val natural = choices.firstOrNull { it.id == sort.id }?.descendingByDefault ?: false
    val reversed = sort.descending != natural
    DropdownMenuItem(
        text = { Text(stringResource(R.string.sort_reverse)) },
        leadingIcon = { Checkbox(checked = reversed, onCheckedChange = null) },
        onClick = onReverse,
        modifier = Modifier.menuToggle(reversed).testTag("sort-reverse"),
    )
}

@Preview(name = "Inbox sort light", widthDp = 280)
@Composable
private fun InboxSortLightPreview() {
    RemftTheme(darkTheme = false) { SortMenuPreviewContent(InboxSort.choices, SortLabels::inbox, SortState.parse(InboxSort.DEFAULT, InboxSort.choices)) }
}

@Preview(name = "Inbox sort dark", widthDp = 280)
@Composable
private fun InboxSortDarkPreview() {
    RemftTheme(darkTheme = true) { SortMenuPreviewContent(InboxSort.choices, SortLabels::inbox, SortState("title", descending = true)) }
}

@Preview(name = "Home sort large font", widthDp = 280, fontScale = 2f)
@Composable
private fun HomeSortLargeFontPreview() {
    RemftTheme(darkTheme = false) { SortMenuPreviewContent(TaskSort.choices, SortLabels::home, SortState.parse(TaskSort.DEFAULT, TaskSort.choices)) }
}

@Composable
private fun SortMenuPreviewContent(choices: List<SortChoice>, labelOf: (String) -> Int, sort: SortState) {
    Column(Modifier.background(RemftTheme.tonal.level2).padding(vertical = Space.x2)) {
        ListSortMenuItems(sort, choices, labelOf, onChoose = {}, onReverse = {})
    }
}
