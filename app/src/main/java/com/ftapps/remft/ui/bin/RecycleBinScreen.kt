package com.ftapps.remft.ui.bin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.entity.TrashItemEntity
import com.ftapps.remft.domain.backup.bin.BinSettings
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.files.ConfirmDeleteDialog
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.files.fileDate
import com.ftapps.remft.ui.files.formatBytes
import com.ftapps.remft.ui.files.rememberDateFormat
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val env = LocalFilesEnvironment.current
    val snackbar = remember { SnackbarHostState() }
    val state = remember { BinScreenState() }
    val items by remember(env) { env?.bin?.watch() ?: flowOf(emptyList()) }.collectAsState(null)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
                actions = {
                    if (env != null) {
                        IconButton(onClick = { state.dialog = BinDialog.Empty }, enabled = !items.isNullOrEmpty(), modifier = Modifier.testTag("bin-empty")) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.bin_empty))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        modifier = Modifier.testTag("screen-bin"),
    ) { padding ->
        if (env == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.DeleteOutline, stringResource(R.string.bin_emptyStateTitle), stringResource(R.string.bin_emptyStateBody))
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

    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            SettingsCard {
                ListItem(
                    modifier = Modifier.testTag("bin-enabled"),
                    leadingContent = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.bin_enabled)) },
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
                    ListItem(
                        modifier = Modifier.testTag("bin-auto-delete"),
                        leadingContent = { Icon(Icons.Outlined.AutoDelete, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.bin_autoDelete)) },
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
                        ListItem(
                            modifier = Modifier.testTag("bin-days").clickable { dialog = BinDialog.Days },
                            leadingContent = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.bin_daysTitle)) },
                            supportingContent = { Text(stringResource(R.string.bin_days).fill("n" to prefs.days)) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x3))
        }
        if (items != null && list.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.DeleteOutline,
                    stringResource(R.string.bin_emptyStateTitle),
                    stringResource(R.string.bin_emptyStateBody),
                    Modifier.testTag("bin-empty-state"),
                )
            }
        }
        items(list, key = { it.id }) { item ->
            val deleted = RecycleBin.deletedAt(item.deletedAt)
            val parts = buildList {
                add(stringResource(kindLabel(item.kind)))
                fileDate(item.deletedAt, format, env.clock.zone)?.let { add(stringResource(R.string.bin_deletedOn).fill("date" to it)) }
                if (item.sizeBytes > 0) add(formatBytes(item.sizeBytes))
                if (prefs.autoDelete && deleted != null) {
                    val left = BinCountdown.daysLeft(deleted, prefs.days, now)
                    add(if (left < 1) stringResource(R.string.bin_goesToday) else stringResource(R.string.bin_daysLeft).fill("n" to left))
                }
            }
            BinRow(
                item = item,
                title = title(item),
                subtitle = parts.joinToString(" · "),
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
            body = stringResource(R.string.bin_emptyBody).fill("count" to list.size),
            confirm = stringResource(R.string.bin_empty),
            confirmTag = "bin-empty-confirm",
            onDismiss = { dialog = null },
        ) {
            dialog = null
            scope.launch { env.bin.empty() }
        }
        BinDialog.TurnOff -> ConfirmDeleteDialog(
            title = stringResource(R.string.bin_turnOffTitle),
            body = stringResource(R.string.bin_turnOffBody).fill("count" to list.size),
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
private fun BinRow(item: TrashItemEntity, title: String, subtitle: String, onRestore: () -> Unit, onPurge: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x1).testTag("bin-item-${item.id}"),
    ) {
        ListItem(
            leadingContent = { Icon(kindIcon(item.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("bin-menu-${item.id}")) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.bin_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bin_restore)) },
                            onClick = {
                                menu = false
                                onRestore()
                            },
                            modifier = Modifier.testTag("bin-restore-${item.id}"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bin_purge)) },
                            onClick = {
                                menu = false
                                onPurge()
                            },
                            modifier = Modifier.testTag("bin-purge-${item.id}"),
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun DaysDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bin_daysTitle)) },
        text = {
            Column {
                BinSettings.DAY_CHOICES.forEach { days ->
                    ListItem(
                        modifier = Modifier.testTag("bin-days-$days").clickable { onPick(days) },
                        leadingContent = { RadioButton(selected = days == current, onClick = { onPick(days) }) },
                        headlineContent = { Text(stringResource(R.string.bin_days).fill("n" to days)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
