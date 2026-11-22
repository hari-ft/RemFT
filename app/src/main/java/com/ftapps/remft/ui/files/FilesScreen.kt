package com.ftapps.remft.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.files.SourcedFileRow
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.SortChoice
import com.ftapps.remft.domain.tasks.SortState
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object FileSort {
    val choices: List<SortChoice> = listOf(
        SortChoice("date"),
        SortChoice("name", descendingByDefault = false),
        SortChoice("size"),
    )

    const val DEFAULT = "date:desc"

    fun comparator(state: SortState): Comparator<FileRowEntity> {
        val ascending = Comparator<FileRowEntity> { a, b ->
            when (state.id) {
                "name" -> Fold.searchText(a.name).compareTo(Fold.searchText(b.name))
                "size" -> a.sizeBytes.compareTo(b.sizeBytes)
                else -> a.createdAt.orEmpty().compareTo(b.createdAt.orEmpty())
            }
        }
        val ordered = state.comparator(ascending)
        return Comparator { a, b ->
            val result = ordered.compare(a, b)
            if (result != 0) result else b.id.compareTo(a.id)
        }
    }

    fun label(id: String): Int = when (id) {
        "name" -> R.string.sort_fileName
        "size" -> R.string.sort_fileSize
        else -> R.string.sort_fileDate
    }
}

@Composable
fun FilesSection(
    bottomPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val env = LocalFilesEnvironment.current
    if (env == null) {
        SectionScaffold(title = stringResource(R.string.nav_files), onOpenSettings = onOpenSettings) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(bottom = bottomPadding).testTag("screen-files"), contentAlignment = Alignment.Center) {
                IllustratedEmptyState(EmptyIllustration.Files, stringResource(R.string.files_emptyTitle), stringResource(R.string.files_emptyBody))
            }
        }
        return
    }
    FilesScreen(env, bottomPadding, onOpenSettings, onOpenTask, onOpenNote)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    env: FilesEnvironment,
    bottomPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ui = rememberFileUiState()
    val format = rememberDateFormat(env)
    var showSources by rememberSaveable { mutableStateOf(false) }
    var path by rememberSaveable { mutableStateOf(listOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }
    val folders by remember(env) { env.files.watchFolders() }.collectAsState(emptyList())
    val workspaceFiles by remember(env) { env.files.watchWorkspaceFiles() }.collectAsState(emptyList())
    val sources by remember(env) { env.files.watchSources() }.collectAsState(emptyList())
    val jobs by env.uploads.jobs.collectAsState()
    val sortRaw by remember(env) { env.settings.flow(SettingKeys.FilesSort) }.collectAsState(FileSort.DEFAULT)
    val sort = SortState.parse(sortRaw, FileSort.choices)
    val request by env.requests.requests.collectAsState()
    val byId = remember(folders) { folders.associateBy { it.id } }
    val trail = path.mapNotNull { byId[it] }
    val message: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    LaunchedEffect(folders, path) {
        if (folders.isNotEmpty() || path.isNotEmpty()) {
            val valid = path.takeWhile { it in byId }
            if (valid.size != path.size) path = valid
        }
    }
    LaunchedEffect(request) {
        val id = request ?: return@LaunchedEffect
        val row = env.files.file(id)
        if (row == null) {
            env.requests.take(id)
            return@LaunchedEffect
        }
        query = ""
        searching = false
        if (row.targetType == "workspace") {
            showSources = false
            path = env.files.folderChain(row.folderId).map { it.id }
        } else {
            showSources = true
        }
        highlighted = id
        if (row.isImage()) ui.viewer = ViewerRequest(listOf(id), 0)
        env.requests.take(id)
    }

    Scaffold(
        containerColor = RemftTheme.tonal.level0,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomPadding)) { RemftSnackbar(it) } },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.files_searchHint)) },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            shape = MarkedShapes.pill,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = RemftTheme.tonal.level2,
                                unfocusedContainerColor = RemftTheme.tonal.level2,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(end = Space.x1).testTag("files-search"),
                        )
                    } else {
                        Text(stringResource(R.string.nav_files), style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            query = ""
                        }, modifier = Modifier.testTag("files-search-close")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_closeSearch))
                        }
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }, modifier = Modifier.testTag("files-search-open")) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.common_search))
                        }
                    }
                    SortMenu(sort) { next -> scope.launch { env.settings.set(SettingKeys.FilesSort.name, next.encode()) } }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).testTag("screen-files"), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = Breakpoints.detailMaxWidth).fillMaxSize()) {
                val layerColors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.x4, vertical = Space.x1).testTag("files-layer-toggle")) {
                    SegmentedButton(
                        selected = !showSources,
                        onClick = { showSources = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2, baseShape = CircleShape),
                        colors = layerColors,
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("layer-folders"),
                    ) { Text(stringResource(R.string.files_myFolders)) }
                    SegmentedButton(
                        selected = showSources,
                        onClick = { showSources = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2, baseShape = CircleShape),
                        colors = layerColors,
                        icon = { Icon(Icons.Outlined.Attachment, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("layer-sources"),
                    ) { Text(stringResource(R.string.files_sources)) }
                }
                if (showSources) {
                    SourcesLayer(
                        env = env,
                        ui = ui,
                        entries = sources,
                        sort = sort,
                        query = query,
                        format = format,
                        highlighted = highlighted,
                        bottomPadding = bottomPadding,
                        onGoToSource = { entry -> if (entry.sourceType == "task") onOpenTask(entry.file.targetId) else onOpenNote(entry.file.targetId) },
                    )
                } else {
                    FoldersLayer(
                        env = env,
                        ui = ui,
                        trail = trail,
                        folders = folders,
                        workspaceFiles = workspaceFiles,
                        jobs = jobs,
                        query = query,
                        format = format,
                        highlighted = highlighted,
                        bottomPadding = bottomPadding,
                        onPath = { path = it },
                        onMessage = message,
                    )
                }
            }
        }
    }
    FileDialogsHost(ui, env, folders, message)
}

@Composable
private fun SortMenu(sort: SortState, onChange: (SortState) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("files-sort")) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort_tooltip))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.sort_sectionTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x2),
            )
            FileSort.choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(FileSort.label(choice.id))) },
                    trailingIcon = { if (choice.id == sort.id) Icon(Icons.Outlined.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onChange(sort.select(choice))
                    },
                    modifier = Modifier.testTag("files-sort-${choice.id}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_reverse)) },
                leadingIcon = { Icon(Icons.Outlined.SwapVert, contentDescription = null) },
                onClick = {
                    open = false
                    onChange(sort.reversed())
                },
                modifier = Modifier.testTag("files-sort-reverse"),
            )
        }
    }
}

@Composable
private fun FoldersLayer(
    env: FilesEnvironment,
    ui: FileUiState,
    trail: List<FolderEntity>,
    folders: List<FolderEntity>,
    workspaceFiles: List<FileRowEntity>,
    jobs: List<UploadJob>,
    query: String,
    format: DateFormatId,
    highlighted: String?,
    bottomPadding: Dp,
    onPath: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val folderId = trail.lastOrNull()?.id
    val level = folders.filter { it.parentId == folderId && SearchMatch.matchesSearch(query, listOf(it.name)) }
    val files = workspaceFiles.filter { it.folderId == folderId && SearchMatch.matchesSearch(query, listOf(it.name)) }
    val uploads = jobs.filter { it.target.targetType == "workspace" && it.target.folderId == folderId }
    val pickFailed = stringResource(R.string.file_pickFailed)
    val moveText = stringResource(R.string.files_moveTo)
    val listState = rememberLazyListState()
    LaunchedEffect(highlighted, files.size, level.size) {
        val at = files.indexOfFirst { it.id == highlighted }
        if (at >= 0) listState.animateScrollToItem(level.size + uploads.size + at)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crumb(stringResource(R.string.nav_files), current = trail.isEmpty(), tag = "crumb-root") { onPath(emptyList()) }
            trail.forEachIndexed { index, folder ->
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp).padding(horizontal = 2.dp),
                )
                Crumb(folder.name, current = index == trail.lastIndex, tag = "crumb-${folder.id}") { onPath(trail.take(index + 1).map { it.id }) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.x4, vertical = Space.x2),
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            RemftTonalButton(
                stringResource(R.string.files_newFolder),
                onClick = { ui.dialog = FileDialog.NewFolder(folderId) },
                icon = Icons.Outlined.CreateNewFolder,
                modifier = Modifier.testTag("files-new-folder"),
            )
            AttachButton(
                onPicked = { picks ->
                    scope.launch {
                        val ws = env.files.workspace()
                        env.uploads.uploadAll(UploadTarget(ws, "workspace", ws, folderId), picks)
                    }
                },
                onFailed = { onMessage(pickFailed) },
                tag = "files-upload",
                outlined = false,
                icon = Icons.Outlined.UploadFile,
            )
        }
        if (level.isEmpty() && files.isEmpty() && uploads.isEmpty()) {
            if (query.isNotBlank()) {
                IllustratedEmptyState(EmptyIllustration.Files,
                    
                    stringResource(R.string.home_searchEmptyTitle),
                    stringResource(R.string.home_searchEmptyBody).fill("query" to query.trim()),
                    Modifier.testTag("files-empty"),
                )
            } else {
                IllustratedEmptyState(EmptyIllustration.Files, stringResource(R.string.files_emptyTitle), stringResource(R.string.files_emptyBody), Modifier.testTag("files-empty"))
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("files-list"),
            contentPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x1, bottom = bottomPadding + Space.x4),
        ) {
            val fileCount = uploads.size + files.size
            val gap = if (level.isNotEmpty()) Modifier.padding(top = Space.x4) else Modifier
            itemsIndexed(level, key = { _, it -> "folder-${it.id}" }) { index, folder ->
                val count = folders.count { it.parentId == folder.id } + workspaceFiles.count { it.folderId == folder.id }
                FolderRowCard(
                    folder,
                    count,
                    position = groupPosition(index, level.size),
                    onOpen = { onPath(trail.map { it.id } + folder.id) },
                    onMore = { ui.dialog = FileDialog.FolderActions(folder) },
                )
            }
            itemsIndexed(uploads, key = { _, it -> "upload-${it.localId}" }) { index, job ->
                Box(if (index == 0) gap else Modifier) {
                    UploadRowCard(job, env, position = groupPosition(index, fileCount)) { scope.launch { env.uploads.retry(job.localId) } }
                }
            }
            itemsIndexed(files, key = { _, it -> "file-${it.id}" }) { index, row ->
                val at = uploads.size + index
                Box(if (at == 0) gap else Modifier) {
                    FileRowCard(
                        position = groupPosition(at, fileCount),
                        row = row,
                        env = env,
                        format = format,
                        highlighted = row.id == highlighted,
                        onOpen = { ui.open(row, files) },
                        onMore = {
                            ui.dialog = FileDialog.Actions(
                                row,
                                listOf(
                                    ExtraAction(Icons.AutoMirrored.Outlined.DriveFileMove, moveText, "file-action-move") {
                                        ui.dialog = FileDialog.MoveFile(row)
                                    },
                                ),
                            )
                        },
                        onDelete = { ui.dialog = FileDialog.Delete(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRowCard(folder: FolderEntity, count: Int, position: GroupPosition, onOpen: () -> Unit, onMore: () -> Unit) {
    FileCardContainer(position, "folder-row-${folder.id}") {
        FolderRowContent(folder.name, stringResource(R.string.files_folderSubtitle).fill("count" to count), folder.id, onOpen, onMore)
    }
}

@Composable
private fun FolderRowContent(name: String, subtitle: String, id: String, onOpen: () -> Unit, onMore: () -> Unit) {
    FileRowLayout(
        leading = { FolderLeadingTile() },
        title = name,
        onClick = onOpen,
        below = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = {
            IconButton(onClick = onMore, modifier = Modifier.testTag("folder-menu-$id")) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.files_folderActions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun Crumb(label: String, current: Boolean, tag: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = MarkedShapes.pill,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (current) scheme.primaryContainer else RemftTheme.tonal.level2,
            labelColor = if (current) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        ),
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun SourcesLayer(
    env: FilesEnvironment,
    ui: FileUiState,
    entries: List<SourcedFileRow>,
    sort: SortState,
    query: String,
    format: DateFormatId,
    highlighted: String?,
    bottomPadding: Dp,
    onGoToSource: (SourcedFileRow) -> Unit,
) {
    val order = remember(sort) { FileSort.comparator(sort) }
    val visible = remember(entries, order, query) {
        entries.filter { SearchMatch.matchesSearch(query, listOf(it.file.name)) }.sortedWith { a, b -> order.compare(a.file, b.file) }
    }
    val rows = visible.map { it.file }
    val goLabel = stringResource(R.string.files_goToSource)
    val taskBadge = stringResource(R.string.file_sourceTask)
    val noteBadge = stringResource(R.string.file_sourceNote)
    val listState = rememberLazyListState()
    LaunchedEffect(highlighted, visible.size) {
        val at = visible.indexOfFirst { it.file.id == highlighted }
        if (at >= 0) listState.animateScrollToItem(at)
    }
    if (visible.isEmpty()) {
        if (query.isBlank()) {
            IllustratedEmptyState(EmptyIllustration.Files, stringResource(R.string.files_noSourcesTitle), stringResource(R.string.files_noSourcesBody), Modifier.testTag("sources-empty"))
        } else {
            IllustratedEmptyState(EmptyIllustration.Files,
                
                stringResource(R.string.home_searchEmptyTitle),
                stringResource(R.string.home_searchEmptyBody).fill("query" to query.trim()),
                Modifier.testTag("sources-empty"),
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("sources-list"),
        contentPadding = PaddingValues(start = Space.x4, end = Space.x4, top = Space.x2, bottom = bottomPadding + Space.x4),
    ) {
        itemsIndexed(visible, key = { _, it -> it.file.id }) { index, entry ->
            val goToSource = ExtraAction(Icons.AutoMirrored.Outlined.OpenInNew, goLabel, "file-action-source") { onGoToSource(entry) }
            FileRowCard(
                row = entry.file,
                env = env,
                format = format,
                highlighted = entry.file.id == highlighted,
                badge = (if (entry.sourceType == "task") taskBadge else noteBadge).fill("name" to entry.sourceTitle),
                position = groupPosition(index, visible.size),
                onOpen = { ui.open(entry.file, rows, listOf(goToSource)) },
                onMore = { ui.dialog = FileDialog.Actions(entry.file, listOf(goToSource)) },
                onDelete = { ui.dialog = FileDialog.Delete(entry.file) },
            )
        }
    }
}

@Preview(name = "Files light", widthDp = 411, heightDp = 760)
@Composable
private fun FilesLightPreview() {
    RemftTheme(darkTheme = false) { FilesPreviewContent() }
}

@Preview(name = "Files dark", widthDp = 411, heightDp = 760)
@Composable
private fun FilesDarkPreview() {
    RemftTheme(darkTheme = true) { FilesPreviewContent() }
}

@Composable
private fun FilesPreviewContent() {
    PreviewSurface {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crumb("Files", current = false, tag = "p-root") {}
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                Crumb("Home documents", current = true, tag = "p-home") {}
            }
            Spacer(Modifier.size(Space.x3))
            FileCardContainer(GroupPosition.First, "p-f1") { FolderRowContent("Bills", "6 items", "p1", {}, {}) }
            FileCardContainer(GroupPosition.Last, "p-f2") { FolderRowContent("Travel", "3 items", "p2", {}, {}) }
            Spacer(Modifier.size(Space.x4))
            listOf("Electricity bill October.pdf" to "application/pdf", "Train tickets.png" to "image/png").forEachIndexed { index, (name, mime) ->
                FileCardContainer(groupPosition(index, 2), "p-file-$index") {
                    FileRowLayout(leading = { KindTile(kindIcon(mime)) }, title = name, onClick = {}, below = {
                        Text("240 KB · 18 Nov 2026", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                }
            }
        }
    }
}
