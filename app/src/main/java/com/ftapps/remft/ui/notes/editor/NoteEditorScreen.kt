package com.ftapps.remft.ui.notes.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.domain.notes.editing.MdActionId
import com.ftapps.remft.domain.notes.editing.MdActions
import com.ftapps.remft.domain.notes.markdown.Outline
import com.ftapps.remft.domain.notes.markdown.OutlineHeading
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.NoteAttachments
import com.ftapps.remft.ui.files.NoteAttachmentsSheet
import com.ftapps.remft.ui.files.NoteMediaButtons
import com.ftapps.remft.ui.notes.ExportMenuItems
import com.ftapps.remft.ui.notes.export.NoteExportHost
import com.ftapps.remft.ui.notes.export.NoteExportViewModel
import com.ftapps.remft.ui.notes.markdown.MarkdownView
import com.ftapps.remft.ui.notes.markdown.rememberMarkdownDocument
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    exportViewModel: NoteExportViewModel,
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.closed) { if (viewModel.closed) onBack() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.flush() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { EditorTopBar(viewModel, exportViewModel, onBack) },
        modifier = Modifier.testTag("screen-note-editor"),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (viewModel.load) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                LoadState.NotFound -> EmptyState(
                    Icons.Outlined.Search,
                    stringResource(R.string.state_noteNotFound),
                    stringResource(R.string.state_notFoundBody),
                    Modifier.align(Alignment.Center).testTag("note-not-found"),
                )
                LoadState.Ready -> EditorBody(viewModel)
            }
        }
    }
    NoteExportHost(exportViewModel, snackbar)
    ExternalMessages(viewModel, snackbar)
}

@Composable
private fun ExternalMessages(viewModel: NoteEditorViewModel, snackbar: SnackbarHostState) {
    val message = viewModel.externalMessage
    val text = when (message) {
        ExternalMessage.Saved -> stringResource(R.string.note_extSaved)
        ExternalMessage.SavedAsNote -> stringResource(R.string.note_extSavedAsNote)
        ExternalMessage.SaveFailed -> stringResource(R.string.note_extSaveFailed)
        ExternalMessage.LostAccess -> stringResource(R.string.note_extLostAccess)
        ExternalMessage.CopySaved -> stringResource(R.string.note_exportSaved)
        null -> null
    }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.externalMessage = null
        }
    }
    if (viewModel.conflict) ConflictDialog(viewModel)
}

@Composable
private fun ConflictDialog(viewModel: NoteEditorViewModel) {
    val session by viewModel.externalSession.collectAsStateWithLifecycle()
    val name = session?.handle?.displayName.orEmpty()
    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) viewModel.saveExternalCopy(uri) else viewModel.dismissConflict()
    }
    AlertDialog(
        onDismissRequest = viewModel::dismissConflict,
        confirmButton = { TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.common_cancel)) } },
        title = { Text(stringResource(R.string.note_extConflictTitle)) },
        text = {
            Column {
                Text(stringResource(R.string.note_extConflictBody).fill("name" to name))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_extReload)) },
                    supportingContent = { Text(stringResource(R.string.note_extReloadHint)) },
                    modifier = Modifier.clickableRow { viewModel.reloadExternal() }.testTag("conflict-reload"),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_extSaveCopy)) },
                    supportingContent = { Text(stringResource(R.string.note_extSaveCopyHint)) },
                    modifier = Modifier.clickableRow { copyLauncher.launch(name.ifBlank { "document.md" }) }.testTag("conflict-copy"),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.note_extOverwrite), color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.note_extOverwriteHint)) },
                    modifier = Modifier.clickableRow { viewModel.saveExternal(force = true) }.testTag("conflict-overwrite"),
                )
            }
        },
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(viewModel: NoteEditorViewModel, exportViewModel: NoteExportViewModel, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var binOn by remember { mutableStateOf(true) }
    var attachmentsFor by remember { mutableStateOf<String?>(null) }
    val filesEnv = LocalFilesEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by viewModel.externalSession.collectAsStateWithLifecycle()
    TopAppBar(
        title = { Text(stringResource(if (viewModel.isExternal) R.string.note_extTitle else R.string.note_detailTitle)) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
            }
        },
        actions = {
            if (viewModel.isExternal) {
                if (session?.writable == true) {
                    IconButton(onClick = { viewModel.saveExternal(force = false) }, modifier = Modifier.testTag("external-save")) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.note_extSave))
                    }
                }
                IconButton(onClick = viewModel::saveExternalAsNote, modifier = Modifier.testTag("external-save-as-note")) {
                    Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = stringResource(R.string.note_extSaveAsNote))
                }
            } else {
                IconButton(onClick = viewModel::togglePinned, modifier = Modifier.testTag("note-pin")) {
                    Icon(
                        if (viewModel.isPinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(if (viewModel.isPinned) R.string.note_unpin else R.string.note_pin),
                        tint = if (viewModel.isPinned) RemftThemeExtras.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::toggleArchived, modifier = Modifier.testTag("note-archive")) {
                    Icon(
                        if (viewModel.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = stringResource(if (viewModel.isArchived) R.string.note_unarchive else R.string.note_archive),
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        binOn = viewModel.binEnabled()
                        confirmDelete = true
                    }
                }, modifier = Modifier.testTag("note-delete")) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.note_deleteTooltip))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("note-quick-menu")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.note_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ExportMenuItems { format ->
                            menu = false
                            exportViewModel.exportContent(format, viewModel.titleForExport(), viewModel.source.text, viewModel.updatedAt)
                        }
                        if (filesEnv != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.task_attachments)) },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    scope.launch { attachmentsFor = viewModel.ensureNoteId() }
                                },
                                modifier = Modifier.testTag("note-attachments"),
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
    attachmentsFor?.let { id ->
        NoteAttachmentsSheet(id, onDismiss = { attachmentsFor = null }) { toast(context, it) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.note_deleteTitle)) },
            text = { Text(stringResource(if (binOn) R.string.bin_toBin else R.string.note_deleteBody)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }, modifier = Modifier.testTag("note-delete-confirm")) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun SaveIndicator(state: SaveState, external: Boolean, dirty: Boolean) {
    val text = when {
        external && dirty -> stringResource(R.string.note_extUnsaved)
        external -> ""
        state == SaveState.Saving -> stringResource(R.string.note_saving)
        state == SaveState.Saved -> stringResource(R.string.note_saved)
        state == SaveState.Failed -> stringResource(R.string.note_saveFailed)
        else -> ""
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (state == SaveState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Space.x2).semantics { liveRegion = LiveRegionMode.Polite }.testTag("save-indicator"),
    )
}

@Composable
private fun ExternalBand(viewModel: NoteEditorViewModel) {
    val session by viewModel.externalSession.collectAsStateWithLifecycle()
    val current = session ?: return
    val text = when {
        current.encoding == com.ftapps.remft.domain.notes.ExternalEncoding.NotText -> stringResource(R.string.note_extNotUtf8)
        !current.writable -> stringResource(R.string.note_extReadOnly)
        else -> stringResource(R.string.note_extExternal)
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = Space.x4, vertical = Space.x2).testTag("external-band"),
    ) {
        Text(current.handle.displayName, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun EditorBody(viewModel: NoteEditorViewModel) {
    val styling by viewModel.styling.collectAsStateWithLifecycle()
    var finding by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(true) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    val tableHeader = stringResource(R.string.note_action_tableHeader)
    val context = LocalContext.current
    val filesEnv = LocalFilesEnvironment.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var collapsed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    val document = rememberMarkdownDocument(viewModel.source.text)
    val counts = remember(viewModel.source.text) { NoteText.countText(viewModel.source.text) }
    val countText = stringResource(R.string.note_counts).fill("words" to counts.words, "characters" to counts.characters)
    val slashToken = remember(viewModel.source.text, viewModel.source.selection) {
        if (viewModel.source.selection.collapsed) MdActions.slashTokenAt(viewModel.source.text, viewModel.source.selection.start) else null
    }
    val slashMatches = remember(slashToken) { slashToken?.let(MdActions::matchSlash).orEmpty() }

    val shortcuts = EditorShortcuts(
        onPalette = { palette = true },
        onFind = {
            finding = true
            replacing = false
        },
        onReplace = {
            finding = true
            replacing = true
        },
        onBold = { viewModel.applyAction(MdActionId.Bold, tableHeader) },
        onItalic = { viewModel.applyAction(MdActionId.Italic, tableHeader) },
        onLink = { viewModel.applyAction(MdActionId.Link, tableHeader) },
        onIndent = viewModel::indent,
        onPaste = { pasteImage(context, viewModel, filesEnv, scope) || smartPaste(context, viewModel) },
    )

    fun jumpTo(heading: OutlineHeading) {
        if (viewModel.mode == EditorMode.Reading || (split)) {
            collapsed = collapsed - heading.blockIndex
            scope.launch { listState.animateScrollToItem(heading.blockIndex + 1) }
        }
        if (viewModel.mode == EditorMode.Source) {
            val lines = viewModel.source.text.split('\n')
            val offset = lines.take(heading.startLine.coerceAtLeast(0)).sumOf { it.length + 1 }
            viewModel.select(offset, offset)
            runCatching { focusRequester.requestFocus() }
        }
    }

    val onAnchor: (String) -> Unit = { anchor ->
        document?.let { doc -> Outline.forAnchor(doc.headings, anchor)?.let(::jumpTo) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= Breakpoints.splitView
        Column(Modifier.fillMaxSize()) {
            if (viewModel.isExternal) ExternalBand(viewModel)
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.x3, vertical = Space.x1), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f, fill = false)) {
                    EditorMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = viewModel.mode == mode,
                            onClick = { viewModel.mode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, EditorMode.entries.size),
                            modifier = Modifier.testTag("mode-${mode.name.lowercase()}"),
                        ) { Text(stringResource(if (mode == EditorMode.Source) R.string.note_modeSource else R.string.note_modeReading)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                SaveIndicator(viewModel.saveState, viewModel.isExternal, viewModel.dirty)
                IconButton(onClick = { outline = true }, modifier = Modifier.testTag("open-outline")) {
                    Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = stringResource(R.string.note_outline))
                }
                if (viewModel.mode == EditorMode.Source) {
                    IconButton(onClick = {
                        finding = !finding
                        replacing = finding
                    }, modifier = Modifier.testTag("open-find")) {
                        Icon(Icons.Outlined.FindReplace, contentDescription = stringResource(R.string.note_find))
                    }
                }
                if (wide) {
                    IconButton(onClick = { split = !split }, modifier = Modifier.testTag("toggle-split")) {
                        Icon(Icons.Outlined.VerticalSplit, contentDescription = stringResource(if (split) R.string.note_splitOff else R.string.note_splitOn))
                    }
                    IconButton(onClick = { focusMode = !focusMode }, modifier = Modifier.testTag("toggle-focus")) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = stringResource(if (focusMode) R.string.note_focusOff else R.string.note_focusOn))
                    }
                }
            }
            if (finding && viewModel.mode == EditorMode.Source) {
                FindReplaceBar(
                    text = viewModel.source.text,
                    showReplace = replacing,
                    onSelect = viewModel::select,
                    onReplaceText = viewModel::replaceSource,
                    onClose = { finding = false },
                )
            }
            if (viewModel.mode == EditorMode.Source) {
                SlashSuggestions(slashMatches) { viewModel.applySlash(it, tableHeader) }
                FormattingToolbar({ viewModel.applyAction(it, tableHeader) }, countText) {
                    if (filesEnv != null && !viewModel.isExternal) {
                        NoteMediaButtons(viewModel::ensureNoteId, viewModel::insertText) { toast(context, it) }
                    }
                }
                HorizontalDivider(color = RemftThemeExtras.colors.hairline)
            }
            val readingPane: @Composable (Modifier) -> Unit = { modifier ->
                MarkdownView(
                    document = document,
                    modifier = modifier,
                    listState = listState,
                    contentPadding = PaddingValues(start = Space.x5, end = Space.x5, top = Space.x2, bottom = 160.dp),
                    collapsed = collapsed,
                    onToggleFold = { index -> collapsed = if (index in collapsed) collapsed - index else collapsed + index },
                    onAnchor = onAnchor,
                    header = {
                        Text(
                            viewModel.title.text.ifBlank { stringResource(R.string.note_untitled) },
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = Space.x4).testTag("reading-title"),
                        )
                    },
                )
            }
            val sourcePane: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    TitleField(viewModel)
                    SourceEditor(
                        value = viewModel.source,
                        onValueChange = viewModel::onSourceChange,
                        styling = styling,
                        shortcuts = shortcuts,
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    viewModel.mode == EditorMode.Source && wide && split && !focusMode -> {
                        sourcePane(Modifier.weight(1f).fillMaxHeight())
                        VerticalDivider(color = RemftThemeExtras.colors.hairline)
                        readingPane(Modifier.weight(1f).fillMaxHeight())
                    }
                    viewModel.mode == EditorMode.Source -> Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        sourcePane(Modifier.widthIn(max = if (focusMode) Breakpoints.detailMaxWidth else 1400.dp).fillMaxHeight())
                    }
                    else -> {
                        if (wide && !focusMode && document != null) {
                            OutlineList(document.headings, ::jumpTo, Modifier.width(260.dp).fillMaxHeight().testTag("outline-panel"))
                            VerticalDivider(color = RemftThemeExtras.colors.hairline)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            readingPane(Modifier.widthIn(max = Breakpoints.detailMaxWidth).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
    if (palette) {
        CommandPalette(onPick = {
            palette = false
            viewModel.applyAction(it, tableHeader)
        }, onDismiss = { palette = false })
    }
    if (outline) {
        OutlineSheet(document?.headings.orEmpty(), onPick = {
            outline = false
            jumpTo(it)
        }, onDismiss = { outline = false })
    }
}

@Composable
private fun TitleField(viewModel: NoteEditorViewModel) {
    val style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = viewModel.title,
        onValueChange = viewModel::onTitleChange,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(start = Space.x5, end = Space.x5, top = Space.x2).testTag("note-title"),
        decorationBox = { inner ->
            Box {
                if (viewModel.title.text.isEmpty()) Text(stringResource(R.string.note_titleHint), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                inner()
            }
        },
    )
}

private fun toast(context: android.content.Context, text: String) {
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
}

private fun pasteImage(
    context: android.content.Context,
    viewModel: NoteEditorViewModel,
    env: FilesEnvironment?,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    if (env == null || viewModel.isExternal) return false
    val pick = NoteAttachments.clipboardImage(context) ?: return false
    scope.launch {
        val noteId = viewModel.ensureNoteId() ?: return@launch
        NoteAttachments.store(env, noteId, pick)?.let(viewModel::insertText)
    }
    return true
}

private fun smartPaste(context: android.content.Context, viewModel: NoteEditorViewModel): Boolean {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return false
    val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false
    val html = item.htmlText
    val pasted = if (!html.isNullOrEmpty()) com.ftapps.remft.domain.notes.editing.MdEditing.htmlToMarkdown(html) else item.coerceToText(context)?.toString().orEmpty()
    if (pasted.isEmpty()) return false
    val selection = viewModel.source.selection
    viewModel.applyEdit(com.ftapps.remft.domain.notes.editing.MdEditing.pasteOverSelection(viewModel.source.text, selection.min, selection.max, pasted))
    return true
}
