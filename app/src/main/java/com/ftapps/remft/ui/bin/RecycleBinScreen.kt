package com.ftapps.remft.ui.bin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.domain.backup.bin.BinSettings
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.ChipRow
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftListRow
import com.ftapps.remft.ui.components.RemftSnackbar
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.components.RemftTopBar
import com.ftapps.remft.ui.components.StatusChip
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.files.ConfirmDeleteDialog
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.fileDate
import com.ftapps.remft.ui.files.formatBytes
import com.ftapps.remft.ui.files.rememberDateFormat
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

object BinCountdown {
    fun daysLeft(deletedAt: Instant, days: Int, now: Instant): Int {
        val remaining = Duration.between(now, deletedAt.plus(Duration.ofDays(days.toLong())))
        return ceil(remaining.toMinutes() / (24.0 * 60.0)).toInt()
    }
}

private sealed interface BinDialog {
    data object Empty : BinDialog
    data object TurnOff : BinDialog
    data object Days : BinDialog
    data class Purge(val item: TrashItemEntity) : BinDialog
}

private class BinScreenState {
    var dialog by mutableStateOf<BinDialog?>(null)
}

private const val SOON_DAYS = 3

@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val env = LocalFilesEnvironment.current
    val snackbar = remember { SnackbarHostState() }
    val state = remember { BinScreenState() }
    val items by remember(env) { env?.bin?.watch() ?: flowOf(emptyList()) }.collectAsState(null)
    Scaffold(
        containerColor = RemftTheme.tonal.level0,
        snackbarHost = { SnackbarHost(snackbar) { RemftSnackbar(it) } },
        topBar = {
            RemftTopBar(
                title = stringResource(R.string.bin_title),
                navigationIcon = {
                    RemftIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = stringResource(R.string.common_close), onClick = onBack, modifier = Modifier.testTag("back"))
                },
                actions = {
                    if (env != null) {
                        RemftIconButton(icon = Icons.Outlined.DeleteSweep, label = stringResource(R.string.bin_empty), onClick = { state.dialog = BinDialog.Empty }, enabled = !items.isNullOrEmpty(), modifier = Modifier.testTag("bin-empty"))
                    }
                },
            )
        },
        modifier = Modifier.testTag("screen-bin"),
    ) { padding ->
        if (env == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                IllustratedEmptyState(EmptyIllustration.Bin, stringResource(R.string.bin_emptyStateTitle), stringResource(R.string.bin_emptyStateBody))
            }
        } else {
            BinBody(padding, snackbar, items, state)
        }
    }
}

private data class BinPrefs(val enabled: Boolean, val autoDelete: Boolean, val days: Int)

@Composable
private fun BinBody(padding: PaddingValues, snackbar: SnackbarHostState, items: List<TrashItemEntity>?, state: BinScreenState) {
    val env = LocalFilesEnvironment.current ?: return
    val scope = rememberCoroutineScope()
    val format = rememberDateFormat(env)
    val prefs by remember(env) {
        combine(
            env.settings.flow(SettingKeys.BinEnabled),
            env.settings.flow(SettingKeys.BinAutoDelete),
            env.settings.flow(SettingKeys.BinDays),
        ) { enabled, auto, days -> BinSettings.of(enabled, auto, days).let { BinPrefs(it.enabled, it.autoDelete, it.days) } }
    }.collectAsState(BinPrefs(enabled = true, autoDelete = true, days = BinSettings.DEFAULT_DAYS))
    var dialog by state::dialog
    val untitled = stringResource(R.string.bin_untitled)
    val restoredText = stringResource(R.string.bin_restored)
    val couldNotRestore = stringResource(R.string.bin_couldNotRestore)
    val list = items.orEmpty()
    val now = env.clock.instant()
    fun title(item: TrashItemEntity) = item.title.trim().ifEmpty { untitled }

    CenteredList(padding, Breakpoints.settingsMaxWidth, bottomPadding = Space.x6) {
        item {
            BinSettingsCard {
                ListItem(
                    modifier = Modifier.testTag("bin-enabled"),
                    leadingContent = { SettingIcon(Icons.Outlined.DeleteOutline) },
                    headlineContent = { Text(stringResource(R.string.bin_enabled), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(if (prefs.enabled) R.string.bin_enabledOn else R.string.bin_enabledOff)) },
                    trailingContent = {
                        Switch(
                            checked = prefs.enabled,
                            onCheckedChange = { next ->
                                if (!next && list.isNotEmpty()) {
                                    dialog = BinDialog.TurnOff
                                } else {
                                    scope.launch { env.settings.set(SettingKeys.BinEnabled.name, next.toString()) }
                                }
                            },
                            modifier = Modifier.testTag("bin-enabled-switch"),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (prefs.enabled) {
                    SettingDivider()
                    ListItem(
                        modifier = Modifier.testTag("bin-auto-delete"),
                        leadingContent = { SettingIcon(Icons.Outlined.AutoDelete) },
                        headlineContent = { Text(stringResource(R.string.bin_autoDelete), style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                if (prefs.autoDelete) {
                                    stringResource(R.string.bin_autoDeleteOn).fill("n" to prefs.days)
                                } else {
                                    stringResource(R.string.bin_autoDeleteOff)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = prefs.autoDelete,
                                onCheckedChange = { next -> scope.launch { env.settings.set(SettingKeys.BinAutoDelete.name, next.toString()) } },
                                modifier = Modifier.testTag("bin-auto-delete-switch"),
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (prefs.autoDelete) {
                        SettingDivider()
                        ListItem(
                            modifier = Modifier.testTag("bin-days").clickable { dialog = BinDialog.Days },
                            leadingContent = { SettingIcon(Icons.Outlined.Timer) },
                            headlineContent = { Text(stringResource(R.string.bin_daysTitle), style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(stringResource(R.string.bin_days).fill("n" to prefs.days)) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x4))
        }
        if (items != null && list.isEmpty()) {
            item {
                IllustratedEmptyState(EmptyIllustration.Bin,
                    
                    stringResource(R.string.bin_emptyStateTitle),
                    stringResource(R.string.bin_emptyStateBody),
                    Modifier.testTag("bin-empty-state"),
                )
            }
        }
        itemsIndexed(list, key = { _, item -> item.id }) { index, item ->
            val deleted = RecycleBin.deletedAt(item.deletedAt)
            val parts = buildList {
                add(stringResource(kindLabel(item.kind)))
                fileDate(item.deletedAt, format, env.clock.zone)?.let { add(stringResource(R.string.bin_deletedOn).fill("date" to it)) }
                if (item.sizeBytes > 0) add(formatBytes(item.sizeBytes))
            }
            val left = if (prefs.autoDelete && deleted != null) BinCountdown.daysLeft(deleted, prefs.days, now) else null
            val countdown = left?.let {
                if (it < 1) stringResource(R.string.bin_goesToday) else stringResource(R.string.bin_daysLeft).fill("n" to it)
            }
            BinRow(
                id = item.id,
                kind = item.kind,
                title = title(item),
                subtitle = parts.joinToString(" · "),
                countdown = countdown,
                soon = left != null && left <= SOON_DAYS,
                position = groupPosition(index, list.size),
                onRestore = {
                    scope.launch {
                        val ok = env.bin.restore(item.id)
                        snackbar.showSnackbar(if (ok) restoredText.fill("title" to title(item)) else couldNotRestore)
                    }
                },
                onPurge = { dialog = BinDialog.Purge(item) },
            )
        }
    }

    when (val current = dialog) {
        null -> Unit
        BinDialog.Empty -> ConfirmDeleteDialog(
            title = stringResource(R.string.bin_emptyTitle),
            body = pluralStringResource(R.plurals.lowdt_binEmptyBody, list.size, list.size),
            confirm = stringResource(R.string.bin_empty),
            confirmTag = "bin-empty-confirm",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch { env.bin.empty() }
        }
        BinDialog.TurnOff -> ConfirmDeleteDialog(
            title = stringResource(R.string.bin_turnOffTitle),
            body = pluralStringResource(R.plurals.lowdt_binTurnOffBody, list.size, list.size),
            confirm = stringResource(R.string.bin_turnOff),
            confirmTag = "bin-turn-off-confirm",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch {
                env.bin.empty()
                env.settings.set(SettingKeys.BinEnabled.name, "false")
            }
        }
        BinDialog.Days -> DaysDialog(prefs.days, onDismiss = { dialog = null }) { days ->
            dialog = null
            scope.launch { env.settings.set(SettingKeys.BinDays.name, days.toString()) }
        }
        is BinDialog.Purge -> ConfirmDeleteDialog(
            title = stringResource(R.string.bin_purgeTitle).fill("title" to title(current.item)),
            body = stringResource(R.string.bin_purgeBody),
            confirm = stringResource(R.string.bin_purge),
            confirmTag = "bin-purge-confirm",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch { env.bin.purge(current.item.id) }
        }
    }
}

private fun kindLabel(kind: String): Int = when (kind) {
    "task" -> R.string.bin_kind_task
    "note" -> R.string.bin_kind_note
    "folder" -> R.string.bin_kind_folder
    else -> R.string.bin_kind_file
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "task" -> Icons.Outlined.CheckCircleOutline
    "note" -> Icons.AutoMirrored.Outlined.StickyNote2
    "folder" -> Icons.Outlined.Folder
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun BinSettingsCard(content: @Composable () -> Unit) {
    MarkedCard(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun SettingIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp, end = Space.x3))
}

@Composable
private fun BinKindTile(kind: String) {
    val scheme = MaterialTheme.colorScheme
    val semantic = RemftTheme.colors
    val (container, content) = when (kind) {
        "task" -> semantic.done.container to semantic.done.onContainer
        "note" -> semantic.note.container to semantic.note.onContainer
        "folder" -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> semantic.file.container to semantic.file.onContainer
    }
    Box(Modifier.padding(start = Space.x3).size(40.dp).background(container, MarkedShapes.tile), contentAlignment = Alignment.Center) {
        Icon(kindIcon(kind), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun BinRow(
    id: String,
    kind: String,
    title: String,
    subtitle: String,
    countdown: String?,
    soon: Boolean,
    position: GroupPosition,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    GroupedCardItem(position, Modifier.testTag("bin-item-$id")) {
        RemftListRow(
            headline = title,
            supporting = subtitle,
            leading = { BinKindTile(kind) },
            below = if (countdown != null) {
                { ChipRow { StatusChip(countdown, if (soon) ChipTone.Warning else ChipTone.Neutral, icon = Icons.Outlined.Timer) } }
            } else {
                null
            },
            trailing = {
                Box {
                    RemftIconButton(icon = Icons.Outlined.MoreVert, label = stringResource(R.string.bin_actions), onClick = { menu = true }, modifier = Modifier.testTag("bin-menu-$id"))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MarkedShapes.small) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bin_restore)) },
                            leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                            onClick = {
                                menu = false
                                onRestore()
                            },
                            modifier = Modifier.testTag("bin-restore-$id"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bin_purge), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onPurge()
                            },
                            modifier = Modifier.testTag("bin-purge-$id"),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun DaysDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    RemftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.bin_daysTitle),
        text = {
            Column {
                BinSettings.DAY_CHOICES.forEach { days ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.touchTarget)
                            .testTag("bin-days-$days")
                            .selectable(selected = days == current, role = Role.RadioButton, onClick = { onPick(days) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.x2),
                    ) {
                        RadioButton(selected = days == current, onClick = null, modifier = Modifier.padding(start = Space.x1))
                        Spacer(Modifier.width(Space.x1))
                        Text(stringResource(R.string.bin_days).fill("n" to days), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

@Preview(name = "Bin light", widthDp = 411)
@Composable
private fun BinLightPreview() {
    RemftTheme(darkTheme = false) { BinPreviewContent() }
}

@Preview(name = "Bin dark", widthDp = 411)
@Composable
private fun BinDarkPreview() {
    RemftTheme(darkTheme = true) { BinPreviewContent() }
}

@Composable
private fun BinPreviewContent() {
    Column(Modifier.background(RemftTheme.tonal.level0).padding(Space.x4)) {
        BinSettingsCard {
            ListItem(
                leadingContent = { SettingIcon(Icons.Outlined.DeleteOutline) },
                headlineContent = { Text("Recycle bin", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("Deleted items wait here") },
                trailingContent = { Switch(checked = true, onCheckedChange = {}) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            SettingDivider()
            ListItem(
                leadingContent = { SettingIcon(Icons.Outlined.Timer) },
                headlineContent = { Text("Keep items for", style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text("30 days") },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(Space.x4))
        BinRow("a", "file", "lease-agreement.pdf", "File · Deleted 20 Nov · 1.2 MB", "28 days left", false, GroupPosition.First, {}, {})
        BinRow("b", "note", "Packing list", "Note · Deleted 2 Nov", "2 days left", true, GroupPosition.Middle, {}, {})
        BinRow("c", "task", "Renew the library card", "Task · Deleted 1 Nov", "Deleted for good today", true, GroupPosition.Last, {}, {})
    }
}
