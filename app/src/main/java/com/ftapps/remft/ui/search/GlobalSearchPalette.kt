package com.ftapps.remft.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.domain.search.GlobalSearch
import com.ftapps.remft.domain.search.GlobalSearchGroup
import com.ftapps.remft.domain.search.GlobalSearchItem
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.PaletteEntry
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.StatusChip
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import kotlinx.coroutines.delay

private const val DEBOUNCE_MS = 150L

private class PaletteResults(val query: String, val groups: List<GlobalSearchGroup>)

@Composable
fun GlobalSearchPalette(
    search: SearchEnvironment,
    wide: Boolean,
    onDismiss: () -> Unit,
    onChoose: (PaletteEntry) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(PaletteResults("", emptyList())) }
    var active by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val recentKeys by remember(search) { search.recents.recents() }.collectAsStateWithLifecycle(emptyList())
    val recentItems by produceState(emptyList<GlobalSearchItem>(), recentKeys) {
        value = runCatching { search.repository.recentItems(recentKeys) }.getOrDefault(emptyList())
    }
    LaunchedEffect(text) {
        delay(DEBOUNCE_MS)
        query = text.trim()
        active = 0
    }
    LaunchedEffect(query) {
        results = if (query.isEmpty()) {
            PaletteResults("", emptyList())
        } else {
            PaletteResults(query, runCatching { search.repository.global(query) }.getOrDefault(emptyList()))
        }
    }
    val searching = query.isNotEmpty()
    val loading = searching && results.query != query
    val groups = if (searching && !loading) results.groups else emptyList()
    val entries = GlobalSearch.entries(text, if (searching) emptyList() else recentItems, groups)
    if (active >= entries.size) active = 0
    val choose: (PaletteEntry) -> Unit = { entry ->
        if (!chosen) {
            chosen = true
            onChoose(entry)
        }
    }
    val field: @Composable (Modifier) -> Unit = { modifier ->
        SearchField(
            query = text,
            onQueryChange = { text = it },
            hint = stringResource(R.string.search_global_hint),
            tag = "global-search-field",
            focusRequester = focus,
            container = RemftTheme.tonal.level1,
            onSearch = { entries.getOrNull(active)?.let(choose) },
            modifier = modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || entries.isEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        active = (active + 1) % entries.size
                        true
                    }
                    Key.DirectionUp -> {
                        active = (active - 1 + entries.size) % entries.size
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        entries.getOrNull(active)?.let(choose)
                        true
                    }
                    else -> false
                }
            },
        )
    }
    val total = groups.sumOf { it.total }
    val body: @Composable ColumnScope.() -> Unit = {
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = Space.x4)) {
            if (loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = RemftTheme.tonal.level2,
                )
            }
        }
        Box(Modifier.weight(1f, fill = !wide).fillMaxWidth()) {
            PaletteBody(
                text = text,
                query = query,
                searching = searching,
                loading = loading,
                recents = if (searching) emptyList() else recentItems,
                groups = groups,
                entries = entries,
                active = active,
                wide = wide,
                onChoose = choose,
            )
        }
        PaletteFooter(
            count = if (searching && !loading) stringResource(R.string.search_global_resultCount).fill("count" to total) else "",
            showKeys = wide && entries.isNotEmpty(),
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        if (wide) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    .safeDrawingPadding()
                    .padding(start = Space.x6, end = Space.x6, top = 72.dp, bottom = Space.x6),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    shape = MarkedShapes.large,
                    color = RemftTheme.tonal.level0,
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .heightIn(min = 240.dp, max = 560.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .testTag("global-search-dialog"),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        field(Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x4, bottom = Space.x2))
                        body()
                    }
                }
            }
        } else {
            Surface(
                color = RemftTheme.tonal.level0,
                modifier = Modifier.fillMaxSize().testTag("global-search-fullscreen"),
            ) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Space.x1, top = Space.x2, end = Space.x4, bottom = Space.x2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.testTag("global-search-close")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                        }
                        Spacer(Modifier.width(Space.x1))
                        field(Modifier.weight(1f))
                    }
                    body()
                }
            }
        }
    }
}

@Composable
private fun PaletteFooter(count: String, showKeys: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.x5, end = Space.x5, top = Space.x1, bottom = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            count,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("global-search-count"),
        )
        if (showKeys) {
            Text(
                stringResource(R.string.searchux_keysHint),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = Space.x2)
                    .background(RemftTheme.tonal.level2, MarkedShapes.pill)
                    .padding(horizontal = Space.x3, vertical = Space.x1),
            )
        }
    }
}

@Composable
private fun PaletteBody(
    text: String,
    query: String,
    searching: Boolean,
    loading: Boolean,
    recents: List<GlobalSearchItem>,
    groups: List<GlobalSearchGroup>,
    entries: List<PaletteEntry>,
    active: Int,
    wide: Boolean,
    onChoose: (PaletteEntry) -> Unit,
) {
    val create = entries.lastOrNull() as? PaletteEntry.Create
    val showRecents = !searching && text.trim().isEmpty() && recents.isNotEmpty()
    when {
        searching && !loading && groups.isEmpty() -> Column(Modifier.fillMaxWidth()) {
            EmptyState(
                Icons.Outlined.SearchOff,
                stringResource(R.string.search_global_noResultsTitle),
                stringResource(R.string.search_global_noResultsBody).fill("query" to query),
                Modifier.testTag("global-search-empty"),
            )
            if (create != null) {
                Box(Modifier.padding(horizontal = Space.x4)) {
                    PaletteRow(create, active == entries.lastIndex, query, GroupPosition.Single) { onChoose(create) }
                }
            }
        }
        entries.isEmpty() -> PaletteIntro(wide)
        else -> LazyColumn(
            state = remember(query, groups) { LazyListState() },
            modifier = Modifier.fillMaxWidth().testTag("global-search-results"),
            contentPadding = PaddingValues(start = Space.x4, end = Space.x4, bottom = Space.x3),
        ) {
            var index = 0
            fun LazyListScope.row(entry: PaletteEntry, position: GroupPosition) {
                val current = index++
                val key = when (entry) {
                    is PaletteEntry.Create -> "create"
                    is PaletteEntry.Item -> "${entry.item.kind.id}-${entry.item.id}"
                }
                item(key = key) { PaletteRow(entry, current == active, query, position) { onChoose(entry) } }
            }
            if (showRecents) {
                item(key = "header-recent") {
                    GroupHeader(stringResource(R.string.search_global_recent), Icons.Outlined.History, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recents.forEachIndexed { i, item -> row(PaletteEntry.Item(item), groupPosition(i, recents.size)) }
            }
            groups.forEach { group ->
                item(key = "header-${group.kind.id}") {
                    val label = stringResource(groupLabel(group.kind))
                    val headerText = if (group.total > group.items.size) {
                        "$label · " + stringResource(R.string.search_global_groupCount).fill("shown" to group.items.size, "total" to group.total)
                    } else {
                        label
                    }
                    GroupHeader(headerText, groupIcon(group.kind), kindAccent(group.kind))
                }
                group.items.forEachIndexed { i, item -> row(PaletteEntry.Item(item), groupPosition(i, group.items.size)) }
            }
            if (create != null) {
                item(key = "create-gap") { Spacer(Modifier.height(Space.x3)) }
                row(create, GroupPosition.Single)
            }
        }
    }
}

@Composable
private fun PaletteIntro(wide: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.x6, vertical = Space.x8).testTag("global-search-intro"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).background(scheme.primaryContainer, MarkedShapes.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(Space.x4))
        Text(
            stringResource(R.string.search_global_emptyHint),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        if (wide) {
            Spacer(Modifier.height(Space.x3))
            Text(
                stringResource(R.string.search_global_shortcutTip).fill("shortcut" to stringResource(R.string.search_global_shortcut)),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .background(RemftTheme.tonal.level2, MarkedShapes.pill)
                    .padding(horizontal = Space.x3, vertical = Space.x1),
            )
        }
    }
}

private fun groupLabel(kind: GlobalSearchKind): Int = when (kind) {
    GlobalSearchKind.Task -> R.string.search_global_groups_tasks
    GlobalSearchKind.Note -> R.string.search_global_groups_notes
    GlobalSearchKind.File -> R.string.search_global_groups_files
}

private fun kindLabel(kind: GlobalSearchKind): Int = when (kind) {
    GlobalSearchKind.Task -> R.string.search_global_kinds_task
    GlobalSearchKind.Note -> R.string.search_global_kinds_note
    GlobalSearchKind.File -> R.string.search_global_kinds_file
}

private fun groupIcon(kind: GlobalSearchKind): ImageVector = when (kind) {
    GlobalSearchKind.Task -> Icons.Outlined.RadioButtonUnchecked
    GlobalSearchKind.Note -> Icons.Outlined.Description
    GlobalSearchKind.File -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

private fun kindIcon(item: GlobalSearchItem): ImageVector = when (item.kind) {
    GlobalSearchKind.Task -> if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked
    GlobalSearchKind.Note -> Icons.Outlined.Description
    GlobalSearchKind.File -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun kindAccent(kind: GlobalSearchKind): Color = when (kind) {
    GlobalSearchKind.Task -> MaterialTheme.colorScheme.primary
    GlobalSearchKind.Note -> RemftTheme.colors.note.color
    GlobalSearchKind.File -> RemftTheme.colors.file.color
}

private data class TileColors(val container: Color, val content: Color)

@Composable
private fun tileColors(item: GlobalSearchItem?): TileColors {
    val scheme = MaterialTheme.colorScheme
    val semantic = RemftTheme.colors
    return when {
        item == null -> TileColors(scheme.primary, scheme.onPrimary)
        item.kind == GlobalSearchKind.Task && item.done -> TileColors(semantic.done.container, semantic.done.onContainer)
        item.kind == GlobalSearchKind.Task -> TileColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        item.kind == GlobalSearchKind.Note -> TileColors(semantic.note.container, semantic.note.onContainer)
        else -> TileColors(semantic.file.container, semantic.file.onContainer)
    }
}

@Composable
private fun GroupHeader(label: String, icon: ImageVector, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.x1, end = Space.x1, top = Space.x4, bottom = Space.x2)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.x2))
        Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, active: Boolean, query: String, position: GroupPosition, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val item = (entry as? PaletteEntry.Item)?.item
    val title: String
    val icon: ImageVector
    val tag: String
    var badge: String? = null
    var badgeTone = ChipTone.Neutral
    var semantic: String
    if (item == null) {
        title = stringResource(R.string.search_global_createTask).fill("title" to (entry as PaletteEntry.Create).title)
        icon = Icons.Outlined.AddTask
        tag = "global-search-create"
        semantic = title
    } else {
        title = item.title
        icon = kindIcon(item)
        tag = "global-search-${item.kind.id}-${item.id}"
        if (item.done) {
            badge = stringResource(R.string.search_global_completed)
            badgeTone = ChipTone.Done
        }
        if (item.archived) {
            badge = stringResource(R.string.search_global_archived)
            badgeTone = ChipTone.Neutral
        }
        semantic = "${stringResource(kindLabel(item.kind))}: ${item.title}"
        badge?.let { semantic += ", $it" }
    }
    val rest = RemftTheme.tonal.level1
    val highlight = scheme.primaryContainer.copy(alpha = 0.7f).compositeOver(rest)
    val container by animateColorAsState(if (active) highlight else rest, RemftTheme.motion.quick(), label = "palette-row")
    val tile = tileColors(item)
    GroupedCardItem(position, color = container) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClickLabel = semantic, onClick = onClick)
                .padding(start = Space.x3, end = Space.x3, top = Space.x2, bottom = Space.x2)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(Modifier.size(40.dp).background(tile.container, MarkedShapes.tile), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tile.content, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Space.x3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (item == null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) scheme.onPrimaryContainer else scheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    HighlightedText(title, query, style = MaterialTheme.typography.titleMedium)
                    item.context?.takeIf { it.isNotEmpty() }?.let {
                        HighlightedText(it, query, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
            badge?.let {
                Spacer(Modifier.width(Space.x2))
                StatusChip(it, badgeTone)
            }
            Spacer(Modifier.width(Space.x2))
            Box(Modifier.width(20.dp)) {
                if (active) Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private val previewRecents = listOf(
    GlobalSearchItem(GlobalSearchKind.Note, "n1", "Holiday packing list", context = "Sunscreen, adapters, chargers"),
    GlobalSearchItem(GlobalSearchKind.Task, "t1", "Renew the library card", done = true),
)

private val previewGroups = listOf(
    GlobalSearchGroup(
        GlobalSearchKind.Task,
        listOf(
            GlobalSearchItem(GlobalSearchKind.Task, "t2", "Pack the passport and the tickets"),
            GlobalSearchItem(GlobalSearchKind.Task, "t3", "Photocopy the passport", done = true),
        ),
        2,
    ),
    GlobalSearchGroup(
        GlobalSearchKind.Note,
        listOf(GlobalSearchItem(GlobalSearchKind.Note, "n2", "Travel documents", context = "Passport expires in March")),
        1,
    ),
    GlobalSearchGroup(
        GlobalSearchKind.File,
        listOf(GlobalSearchItem(GlobalSearchKind.File, "f1", "passport-scan.pdf", context = "Travel")),
        6,
    ),
)

@Composable
private fun PalettePreviewContent(searching: Boolean) {
    val text = if (searching) "passport" else ""
    val groups = if (searching) previewGroups else emptyList()
    val recents = if (searching) emptyList() else previewRecents
    val entries = GlobalSearch.entries(text, recents, groups)
    Surface(color = RemftTheme.tonal.level0, shape = MarkedShapes.large, modifier = Modifier.widthIn(max = 640.dp)) {
        Column(Modifier.fillMaxWidth()) {
            SearchField(
                query = text,
                onQueryChange = {},
                hint = "Search tasks, notes and files",
                tag = "preview-search",
                container = RemftTheme.tonal.level1,
                modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x4, bottom = Space.x2),
            )
            Box(Modifier.heightIn(max = 620.dp)) {
                PaletteBody(text, text, searching, false, recents, groups, entries, 0, true) {}
            }
            PaletteFooter(if (searching) "10 results" else "", showKeys = true)
        }
    }
}

@Preview(name = "Search palette light", widthDp = 640, heightDp = 760)
@Composable
private fun PaletteLightPreview() {
    RemftTheme(darkTheme = false) { PalettePreviewContent(searching = true) }
}

@Preview(name = "Search palette dark", widthDp = 640, heightDp = 760)
@Composable
private fun PaletteDarkPreview() {
    RemftTheme(darkTheme = true) { PalettePreviewContent(searching = true) }
}

@Preview(name = "Search recents light", widthDp = 411, heightDp = 480)
@Composable
private fun PaletteRecentsLightPreview() {
    RemftTheme(darkTheme = false) { PalettePreviewContent(searching = false) }
}

@Preview(name = "Search recents dark", widthDp = 411, heightDp = 480)
@Composable
private fun PaletteRecentsDarkPreview() {
    RemftTheme(darkTheme = true) { PalettePreviewContent(searching = false) }
}
