package com.ftapps.remft.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.NoteSortField
import com.ftapps.remft.domain.notes.NotesFilter
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.ErrorState
import com.ftapps.remft.ui.notes.export.NoteExportFormat
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.tasks.SwipeToReveal
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

const val UNDO_WINDOW_MS = 3_000L

fun formatNoteDate(text: String?, format: DateFormatId): String {
    val instant = UtcText.parse(text) ?: return "—"
    return DateFormats.date(instant.atZone(ZoneId.systemDefault()).toLocalDateTime(), format)
}

suspend fun SnackbarHostState.showUndo(message: String, actionLabel: String): Boolean {
    val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
        showSnackbar(message, actionLabel = actionLabel, withDismissAction = false, duration = SnackbarDuration.Indefinite)
    }
    if (result == null) currentSnackbarData?.dismiss()
    return result == SnackbarResult.ActionPerformed
}

fun deleteWithUndo(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    message: String,
    undoLabel: String,
    hide: () -> Unit,
    undo: () -> Unit,
    commit: () -> Unit,
) {
    hide()
    scope.launch {
        var undone = false
        try {
            undone = snackbar.showUndo(message, undoLabel)
        } finally {
            if (undone) undo() else commit()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    bottomPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenNote: (String?) -> Unit,
    onExport: (String, NoteExportFormat) -> Unit,
    onEditExternal: () -> Unit,
    onImport: () -> Unit,
    onOpenRecent: (com.ftapps.remft.domain.notes.ExternalDocHandle) -> Unit,
    recents: List<com.ftapps.remft.domain.notes.ExternalDocHandle>,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.common_undo)
    val deletedTemplate = stringResource(R.string.note_deleted)

    val delete: (NoteRow) -> Unit = { note ->
        deleteWithUndo(
            scope, snackbar, deletedTemplate.fill("title" to note.title), undoLabel,
            hide = { viewModel.hide(note.id) },
            undo = { viewModel.undoDelete(note.id) },
            commit = { viewModel.commitDelete(note.id) },
        )
    }
    val actions = NoteRowActions(
        open = { onOpenNote(it.id) },
        togglePin = viewModel::togglePinned,
        archive = { viewModel.setArchived(it, !it.isArchived) },
        delete = delete,
        export = { note, format -> onExport(note.id, format) },
    )

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.testTag("screen-notes"),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomPadding)) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onOpenNote(null) },
                modifier = Modifier.padding(bottom = bottomPadding).testTag("new-note"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = androidx.compose.foundation.shape.CircleShape,
            ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.shell_fabNewNote)) }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query.search,
                            onValueChange = viewModel::setSearch,
                            placeholder = { Text(stringResource(R.string.note_searchHint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("notes-search"),
                        )
                    } else {
                        Text(stringResource(R.string.nav_notes))
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            viewModel.setSearch("")
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.notes_closeSearch)) }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }, modifier = Modifier.testTag("notes-search-open")) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.common_search))
                        }
                    }
                    SortMenu(
                        sortField = sort.field,
                        viewMode = viewMode,
                        onSelect = viewModel::selectSort,
                        onReverse = viewModel::reverseSort,
                        onViewMode = viewModel::setViewMode,
                    )
                    ExternalMenu(onEditExternal, onImport, onOpenRecent, recents)
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.shell_settingsTooltip))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.x4, vertical = Space.x2),
                horizontalArrangement = Arrangement.spacedBy(Space.x2),
            ) {
                listOf(
                    NotesFilter.All to R.string.note_filterAll,
                    NotesFilter.Pinned to R.string.note_filterPinned,
                    NotesFilter.Archived to R.string.note_filterArchived,
                ).forEach { (filter, label) ->
                    FilterChip(
                        selected = query.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("filter-${filter.name.lowercase()}"),
                    )
                }
            }
            val listPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x2, bottom = bottomPadding + 88.dp)
            when (val current = state) {
                NotesListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                NotesListState.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ErrorState(onRetry = viewModel::retry) }
                is NotesListState.Ready -> if (current.notes.isEmpty()) {
                    val archived = query.filter == NotesFilter.Archived
                    Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
                        EmptyState(
                            if (archived) Icons.Outlined.Archive else Icons.Outlined.Description,
                            stringResource(if (archived) R.string.note_archiveEmpty else R.string.note_empty),
                            stringResource(if (archived) R.string.note_archivedHere else R.string.note_captureFirst),
                            Modifier.testTag("notes-empty"),
                        )
                    }
                } else if (viewMode == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = listPadding,
                        horizontalArrangement = Arrangement.spacedBy(Space.x3),
                        verticalArrangement = Arrangement.spacedBy(Space.x3),
                        modifier = Modifier.fillMaxSize().testTag("notes-grid"),
                    ) {
                        items(current.notes, key = { it.id }) { note -> NoteCard(note, dateFormat, actions) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("notes-list"), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(Space.x2)) {
                        items(current.notes, key = { it.id }) { note -> NoteTile(note, dateFormat, actions) }
                    }
                }
            }
        }
    }
}

class NoteRowActions(
    val open: (NoteRow) -> Unit,
    val togglePin: (NoteRow) -> Unit,
    val archive: (NoteRow) -> Unit,
    val delete: (NoteRow) -> Unit,
    val export: (NoteRow, NoteExportFormat) -> Unit,
)

@Composable
private fun PinButton(note: NoteRow, actions: NoteRowActions) {
    IconButton(onClick = { actions.togglePin(note) }, modifier = Modifier.testTag("pin-${note.id}")) {
        Icon(
            if (note.isPinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(if (note.isPinned) R.string.note_unpin else R.string.note_pin),
            tint = if (note.isPinned) RemftThemeExtras.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteTile(note: NoteRow, dateFormat: DateFormatId, actions: NoteRowActions) {
    val meta = stringResource(R.string.note_edited).fill("date" to formatNoteDate(note.updatedAt, dateFormat)) + " · " +
        stringResource(R.string.note_created).fill("date" to formatNoteDate(note.createdAt, dateFormat))
    SwipeToReveal(
        id = note.id,
        deleteDescription = stringResource(R.string.note_deleteFromList),
        onDelete = { actions.delete(note) },
        modifier = Modifier.testTag("note-${note.id}"),
    ) {
        Card(
            shape = RoundedCornerShape(Radius.card),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
            modifier = Modifier.fillMaxWidth().clickable { actions.open(note) },
        ) {
            Row(Modifier.padding(vertical = Space.x2, horizontal = Space.x1), verticalAlignment = Alignment.CenterVertically) {
                PinButton(note, actions)
                Column(Modifier.weight(1f).padding(vertical = Space.x1)) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (note.snippet.isEmpty()) meta else "${note.snippet}\n$meta",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NoteMenu(note, actions)
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteRow, dateFormat: DateFormatId, actions: NoteRowActions) {
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        modifier = Modifier.aspectRatio(210f / 297f).clickable { actions.open(note) }.testTag("note-${note.id}"),
    ) {
        Column(Modifier.fillMaxSize().padding(start = Space.x3, end = Space.x1, bottom = Space.x3, top = Space.x1)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = Space.x3),
                )
                PinButton(note, actions)
                NoteMenu(note, actions)
            }
            Text(
                note.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Space.x1),
            )
            Spacer(Modifier.height(Space.x1))
            Text(
                stringResource(R.string.note_edited).fill("date" to formatNoteDate(note.updatedAt, dateFormat)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoteMenu(note: NoteRow, actions: NoteRowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("note-menu-${note.id}")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.note_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ExportMenuItems { format ->
                open = false
                actions.export(note, format)
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (note.isArchived) R.string.note_unarchive else R.string.note_archive)) },
                leadingIcon = { Icon(if (note.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null) },
                onClick = {
                    open = false
                    actions.archive(note)
                },
                modifier = Modifier.testTag("note-archive-${note.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.note_deleteFromList)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    actions.delete(note)
                },
                modifier = Modifier.testTag("note-delete-${note.id}"),
            )
        }
    }
}

@Composable
fun ExportMenuItems(onExport: (NoteExportFormat) -> Unit) {
    NoteExportFormat.entries.forEach { format ->
        DropdownMenuItem(
            text = { Text(stringResource(format.menuLabel)) },
            leadingIcon = { Icon(format.icon, contentDescription = null) },
            onClick = { onExport(format) },
            modifier = Modifier.testTag("export-${format.extension}"),
        )
    }
}

@Composable
private fun SortMenu(
    sortField: NoteSortField,
    viewMode: String,
    onSelect: (NoteSortField) -> Unit,
    onReverse: () -> Unit,
    onViewMode: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("notes-sort")) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort_tooltip))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(stringResource(R.string.sort_sectionTitle))
            listOf(
                NoteSortField.Updated to R.string.sort_updated,
                NoteSortField.Created to R.string.sort_created,
                NoteSortField.Title to R.string.sort_title,
            ).forEach { (field, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (field == sortField) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onSelect(field)
                    },
                    modifier = Modifier.testTag("sort-${field.id}"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_reverse)) },
                leadingIcon = { Icon(Icons.Outlined.SwapVert, contentDescription = null) },
                onClick = {
                    open = false
                    onReverse()
                },
                modifier = Modifier.testTag("sort-reverse"),
            )
            HorizontalDivider()
            MenuHeader(stringResource(R.string.sort_viewSection))
            listOf("list" to R.string.sort_viewList, "grid" to R.string.sort_viewGrid).forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (mode == viewMode) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onViewMode(mode)
                    },
                    modifier = Modifier.testTag("view-$mode"),
                )
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
    )
}

@Composable
private fun ExternalMenu(
    onEdit: () -> Unit,
    onImport: () -> Unit,
    onRecent: (com.ftapps.remft.domain.notes.ExternalDocHandle) -> Unit,
    recents: List<com.ftapps.remft.domain.notes.ExternalDocHandle>,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("notes-external")) {
            Icon(Icons.Outlined.FileOpen, contentDescription = stringResource(R.string.note_mdOpen))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.note_extEditFile)) }, onClick = {
                open = false
                onEdit()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.note_extImportNote)) }, onClick = {
                open = false
                onImport()
            }, modifier = Modifier.testTag("notes-import"))
            HorizontalDivider()
            MenuHeader(stringResource(R.string.note_extRecents))
            if (recents.isEmpty()) {
                Text(
                    stringResource(R.string.note_extRecentsEmpty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
                )
            }
            recents.forEach { handle ->
                DropdownMenuItem(text = { Text(handle.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                    open = false
                    onRecent(handle)
                })
            }
        }
    }
}
