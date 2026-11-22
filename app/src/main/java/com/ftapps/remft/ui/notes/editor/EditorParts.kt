package com.ftapps.remft.ui.notes.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.editing.FindReplace
import com.ftapps.remft.domain.notes.editing.MdActionId
import com.ftapps.remft.domain.notes.editing.MdActions
import com.ftapps.remft.domain.notes.markdown.OutlineHeading
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.notes.NotesSheetTitle
import com.ftapps.remft.ui.theme.JetBrainsMono
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

data class ActionLook(val icon: ImageVector, @param:StringRes val label: Int)

fun lookOf(id: MdActionId): ActionLook = when (id) {
    MdActionId.Bold -> ActionLook(Icons.Outlined.FormatBold, R.string.note_action_bold)
    MdActionId.Italic -> ActionLook(Icons.Outlined.FormatItalic, R.string.note_action_italic)
    MdActionId.Highlight -> ActionLook(Icons.Outlined.FormatColorFill, R.string.note_action_highlight)
    MdActionId.Strike -> ActionLook(Icons.Outlined.StrikethroughS, R.string.note_action_strike)
    MdActionId.Code -> ActionLook(Icons.Outlined.Code, R.string.note_action_code)
    MdActionId.Link -> ActionLook(Icons.Outlined.Link, R.string.note_action_link)
    MdActionId.H1 -> ActionLook(Icons.Outlined.Title, R.string.note_action_h1)
    MdActionId.H2 -> ActionLook(Icons.Outlined.TextFields, R.string.note_action_h2)
    MdActionId.H3 -> ActionLook(Icons.Outlined.TextFormat, R.string.note_action_h3)
    MdActionId.Bullet -> ActionLook(Icons.AutoMirrored.Outlined.FormatListBulleted, R.string.note_action_bullet)
    MdActionId.Numbered -> ActionLook(Icons.Outlined.FormatListNumbered, R.string.note_action_numbered)
    MdActionId.Todo -> ActionLook(Icons.Outlined.Checklist, R.string.note_action_todo)
    MdActionId.Quote -> ActionLook(Icons.Outlined.FormatQuote, R.string.note_action_quote)
    MdActionId.CodeBlock -> ActionLook(Icons.Outlined.DataObject, R.string.note_action_codeBlock)
    MdActionId.Table -> ActionLook(Icons.Outlined.TableChart, R.string.note_action_table)
    MdActionId.Divider -> ActionLook(Icons.Outlined.HorizontalRule, R.string.note_action_divider)
}

@Composable
fun FormattingToolbar(onAction: (MdActionId) -> Unit, counts: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .clip(MarkedShapes.pill)
            .background(RemftTheme.tonal.level2)
            .testTag("note-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = Space.x1)) {
                MdActionId.entries.forEach { id ->
                    val look = lookOf(id)
                    RemftIconButton(label = stringResource(look.label), onClick = { onAction(id) }, modifier = Modifier.testTag("action-${id.name.lowercase()}")) {
                        Icon(look.icon, contentDescription = stringResource(look.label))
                    }
                }
                trailing()
            }
        }
        Text(
            counts,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = Space.x2, end = Space.x4).testTag("note-counts"),
        )
    }
}

@Composable
fun SlashSuggestions(matches: List<MdActionId>, onPick: (MdActionId) -> Unit) {
    if (matches.isEmpty()) return
    val note = RemftTheme.colors.note
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.x3).testTag("slash-menu"),
        horizontalArrangement = Arrangement.spacedBy(Space.x2),
    ) {
        matches.forEach { id ->
            SuggestionChip(
                onClick = { onPick(id) },
                label = {
                    Text(
                        "${id.slash}  ${stringResource(lookOf(id).label)}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                icon = { Icon(lookOf(id).icon, contentDescription = null) },
                shape = MarkedShapes.pill,
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = note.container,
                    labelColor = note.onContainer,
                    iconContentColor = note.onContainer,
                ),
                border = null,
                modifier = Modifier.heightIn(min = 48.dp).testTag("slash-${id.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun notesFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = RemftTheme.tonal.level1,
    unfocusedContainerColor = RemftTheme.tonal.level1,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)

@Composable
private fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = MarkedShapes.pill,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = notesFieldColors(),
        modifier = modifier,
    )
}

@Composable
fun FindReplaceBar(
    text: String,
    showReplace: Boolean,
    onSelect: (Int, Int) -> Unit,
    onReplaceText: (String, Int) -> Unit,
    onClose: () -> Unit,
) {
    var needle by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    var jumps by remember { mutableIntStateOf(0) }
    val matches = remember(text, needle) { FindReplace.matches(text, needle) }
    val current = if (matches.isEmpty()) 0 else index.coerceIn(0, matches.size - 1)
    val latestMatches by rememberUpdatedState(matches)
    val select by rememberUpdatedState(onSelect)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(needle, index, jumps) {
        val found = latestMatches
        val at = found.getOrNull(index.coerceIn(0, (found.size - 1).coerceAtLeast(0)))
        if (at != null) select(at, at + needle.length)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.x3, vertical = Space.x1)
            .clip(MarkedShapes.medium)
            .background(RemftTheme.tonal.level2)
            .padding(horizontal = Space.x2, vertical = Space.x2)
            .testTag("find-bar"),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotesField(
                    value = needle,
                    onValueChange = {
                        needle = it
                        index = 0
                    },
                    placeholder = stringResource(R.string.note_find),
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag("find-field"),
                )
                Text(
                    if (needle.isEmpty()) "" else if (matches.isEmpty()) stringResource(R.string.note_noMatches) else
                        stringResource(R.string.note_matchCount).fill("index" to current + 1, "total" to matches.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.x2).testTag("find-count"),
                )
                RemftIconButton(label = stringResource(R.string.common_previous), onClick = { if (matches.isNotEmpty()) index = (current - 1 + matches.size) % matches.size }) {
                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.common_previous))
                }
                RemftIconButton(label = stringResource(R.string.common_next), onClick = { if (matches.isNotEmpty()) index = (current + 1) % matches.size }, modifier = Modifier.testTag("find-next")) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.common_next))
                }
                RemftIconButton(label = stringResource(R.string.notes_findClose), onClick = onClose, modifier = Modifier.testTag("find-close")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.notes_findClose))
                }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NotesField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        placeholder = stringResource(R.string.note_replace),
                        modifier = Modifier.weight(1f).testTag("replace-field"),
                    )
                    RemftTextButton(stringResource(R.string.note_replace), onClick = {
                        matches.getOrNull(current)?.let { at ->
                            val edit = FindReplace.replaceAt(text, at, needle.length, replacement)
                            onReplaceText(edit.text, edit.selection)
                            index = if (matches.size > 1) current % (matches.size - 1) else 0
                            jumps++
                        }
                    }, modifier = Modifier.testTag("replace-one"))
                    RemftTextButton(
                        stringResource(R.string.note_replaceAll),
                        onClick = { if (matches.isNotEmpty()) onReplaceText(FindReplace.replaceAll(text, needle, replacement), 0) },
                        modifier = Modifier.testTag("replace-all"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineSheet(headings: List<OutlineHeading>, onPick: (OutlineHeading) -> Unit, onDismiss: () -> Unit) {
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("outline-sheet")) {
        OutlineList(headings, onPick)
    }
}

@Composable
fun OutlineList(headings: List<OutlineHeading>, onPick: (OutlineHeading) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = Space.x6)) {
        NotesSheetTitle(stringResource(R.string.note_outline), Modifier.padding(horizontal = Space.x6, vertical = Space.x2))
        if (headings.isEmpty()) {
            Text(
                stringResource(R.string.note_outlineEmpty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.x6, vertical = Space.x3),
            )
        }
        LazyColumn {
            items(headings) { heading ->
                val major = heading.level <= 2
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(heading) }
                        .padding(start = Space.x6 + 16.dp * (heading.level - 1).coerceAtMost(5), end = Space.x6)
                        .testTag("outline-${heading.slug}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(if (major) 8.dp else 6.dp)
                            .background(if (major) RemftTheme.colors.note.color else MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Spacer(Modifier.width(Space.x3))
                    Text(
                        heading.text,
                        style = if (major) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (major) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun CommandPalette(onPick: (MdActionId) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val labels = MdActionId.entries.associateWith { stringResource(lookOf(it).label) }
    val matches = remember(query) { MdActions.match(query) { labels[it].orEmpty() } }
    val focus = remember { FocusRequester() }
    val note = RemftTheme.colors.note
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { RemftTextButton(stringResource(R.string.common_close), onClick = onDismiss) },
        shape = MarkedShapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.note_paletteHint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = MarkedShapes.pill,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = RemftTheme.tonal.level2,
                    unfocusedContainerColor = RemftTheme.tonal.level2,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("palette-field"),
            )
        },
        text = {
            if (matches.isEmpty()) {
                Text(stringResource(R.string.note_paletteEmpty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(matches) { id ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(MarkedShapes.small).clickable { onPick(id) }.padding(horizontal = Space.x1),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(36.dp).background(note.container, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(lookOf(id).icon, contentDescription = null, tint = note.onContainer, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(Space.x3))
                            Text(labels[id].orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(
                                id.slash,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EditorPartsPreviewContent() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
            FormattingToolbar(onAction = {}, counts = "128 words")
            SlashSuggestions(listOf(MdActionId.H1, MdActionId.Todo, MdActionId.Table)) {}
            FindReplaceBar(text = "Plan the weekend plan", showReplace = true, onSelect = { _, _ -> }, onReplaceText = { _, _ -> }, onClose = {})
        }
    }
}

@Preview(name = "Editor parts light", widthDp = 400)
@Composable
private fun EditorPartsLightPreview() {
    RemftTheme(darkTheme = false) { EditorPartsPreviewContent() }
}

@Preview(name = "Editor parts dark", widthDp = 400)
@Composable
private fun EditorPartsDarkPreview() {
    RemftTheme(darkTheme = true) { EditorPartsPreviewContent() }
}
