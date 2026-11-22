package com.ftapps.remft.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.data.files.FolderRuleException
import com.ftapps.remft.data.files.SubtreeCounts
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ExtraAction(val icon: ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

sealed interface FileDialog {
    data class Actions(val row: FileRowEntity, val extras: List<ExtraAction> = emptyList()) : FileDialog
    data class Rename(val row: FileRowEntity) : FileDialog
    data class Delete(val row: FileRowEntity, val onDeleted: () -> Unit = {}) : FileDialog
    data class MoveFile(val row: FileRowEntity) : FileDialog
    data class NewFolder(val parentId: String?) : FileDialog
    data class FolderActions(val folder: FolderEntity) : FileDialog
    data class RenameFolder(val folder: FolderEntity) : FileDialog
    data class MoveFolder(val folder: FolderEntity) : FileDialog
    data class DeleteFolder(val folder: FolderEntity, val counts: SubtreeCounts, val toBin: Boolean, val onDeleted: () -> Unit) : FileDialog
}

data class ViewerRequest(val ids: List<String>, val index: Int)

@Stable
class FileUiState {
    var dialog by mutableStateOf<FileDialog?>(null)
    var viewer by mutableStateOf<ViewerRequest?>(null)

    fun open(row: FileRowEntity, siblings: List<FileRowEntity>, extras: List<ExtraAction> = emptyList()) {
        if (row.isImage()) {
            val ids = siblings.filter { it.isImage() }.map { it.id }.ifEmpty { listOf(row.id) }
            viewer = ViewerRequest(ids, ids.indexOf(row.id).coerceAtLeast(0))
        } else {
            dialog = FileDialog.Actions(row, extras)
        }
    }
}

@Composable
fun rememberFileUiState(): FileUiState = remember { FileUiState() }

@Composable
fun folderRuleText(code: String): String = stringResource(
    when (code) {
        "FOLDER_NAME_REQUIRED" -> R.string.error_FOLDER_NAME_REQUIRED
        "FOLDER_NAME_TAKEN" -> R.string.error_FOLDER_NAME_TAKEN
        "FOLDER_TOO_DEEP" -> R.string.error_FOLDER_TOO_DEEP
        "FOLDER_CYCLE" -> R.string.error_FOLDER_CYCLE
        else -> R.string.error_unknown
    },
)

@Composable
fun FileDialogsHost(
    state: FileUiState,
    env: FilesEnvironment,
    folders: List<FolderEntity>,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val fetching = stringResource(R.string.mirror_fetching)
    val couldNotRename = stringResource(R.string.file_couldNotRename)
    val couldNotDelete = stringResource(R.string.file_couldNotDelete)
    val couldNotMove = stringResource(R.string.files_couldNotMove)
    val couldNotDeleteFolder = stringResource(R.string.lowdt_folderCouldNotDelete)
    var ruleError by remember { mutableStateOf<String?>(null) }
    val ruleText = ruleError?.let { folderRuleText(it) }
    LaunchedEffect(ruleText) {
        if (ruleText != null) {
            onMessage(ruleText)
            ruleError = null
        }
    }
    fun folderWrite(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: FolderRuleException) {
                ruleError = e.code
            }
        }
    }
    val close = { state.dialog = null }
    when (val dialog = state.dialog) {
        null -> Unit
        is FileDialog.Actions -> FileActionsSheet(
            row = dialog.row,
            env = env,
            extras = dialog.extras,
            onDismiss = close,
            onOpen = {
                close()
                scope.launch {
                    val outcome = FileOpener.open(context, env, dialog.row) { onMessage(fetching) }
                    FileOpener.messageOf(outcome)?.let { onMessage(resources.getString(it)) }
                }
            },
            onRename = { state.dialog = FileDialog.Rename(dialog.row) },
            onDelete = { state.dialog = FileDialog.Delete(dialog.row) },
        )
        is FileDialog.Rename -> NameDialog(
            title = stringResource(R.string.file_renameTitle),
            label = stringResource(R.string.file_nameLabel),
            initial = dialog.row.name,
            onDismiss = close,
            onSave = { name ->
                close()
                if (name.isNotEmpty() && name != dialog.row.name) {
                    scope.launch { runCatching { env.attachments.rename(dialog.row.id, name) }.onFailure { onMessage(couldNotRename) } }
                }
            },
        )
        is FileDialog.Delete -> DeleteFileDialog(dialog.row, env, onDismiss = close) {
            close()
            scope.launch {
                runCatching { env.attachments.delete(dialog.row.id) }
                    .onSuccess { dialog.onDeleted() }
                    .onFailure { onMessage(couldNotDelete) }
            }
        }
        is FileDialog.MoveFile -> MoveTargetSheet(folders, exclude = null, onDismiss = close) { target ->
            close()
            scope.launch { runCatching { env.attachments.move(dialog.row.id, target) }.onFailure { onMessage(couldNotMove) } }
        }
        is FileDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.files_newFolder),
            label = stringResource(R.string.files_folderName),
            initial = "",
            onDismiss = close,
            onSave = { name ->
                close()
                if (name.isNotBlank()) folderWrite { env.files.createFolder(name, dialog.parentId) }
            },
        )
        is FileDialog.FolderActions -> FolderActionsSheet(
            onDismiss = close,
            onRename = { state.dialog = FileDialog.RenameFolder(dialog.folder) },
            onMove = { state.dialog = FileDialog.MoveFolder(dialog.folder) },
            onDelete = {
                close()
                scope.launch {
                    val counts = env.files.subtreeCounts(dialog.folder.id)
                    state.dialog = FileDialog.DeleteFolder(dialog.folder, counts, env.bin.enabled()) {}
                }
            },
        )
        is FileDialog.RenameFolder -> NameDialog(
            title = stringResource(R.string.files_renameFolder),
            label = stringResource(R.string.files_folderName),
            initial = dialog.folder.name,
            onDismiss = close,
            onSave = { name ->
                close()
                if (name.isNotBlank()) folderWrite { env.files.renameFolder(dialog.folder.id, name) }
            },
        )
        is FileDialog.MoveFolder -> MoveTargetSheet(folders, exclude = dialog.folder.id, onDismiss = close) { target ->
            close()
            folderWrite { env.files.moveFolder(dialog.folder.id, target) }
        }
        is FileDialog.DeleteFolder -> {
            val empty = dialog.counts.files == 0 && dialog.counts.folders == 1
            val counts = arrayOf(
                "folders" to pluralStringResource(R.plurals.lowdt_folderCount, dialog.counts.folders, dialog.counts.folders),
                "files" to pluralStringResource(R.plurals.lowdt_fileCount, dialog.counts.files, dialog.counts.files),
            )
            val body = when {
                dialog.toBin && empty -> stringResource(R.string.bin_toBin)
                dialog.toBin -> stringResource(R.string.lowdt_deleteFolderBodyBin).fill(*counts)
                empty -> stringResource(R.string.files_deleteFolderEmpty)
                else -> stringResource(R.string.lowdt_deleteFolderBody).fill(*counts)
            }
            ConfirmDeleteDialog(
                title = stringResource(R.string.files_deleteFolderTitle).fill("name" to dialog.folder.name),
                body = body,
                confirm = stringResource(R.string.common_delete),
                confirmTag = "folder-delete-confirm",
                onDismiss = close,
            ) {
                close()
                scope.launch {
                    runCatching { env.files.deleteFolder(dialog.folder.id) }
                        .onSuccess { dialog.onDeleted() }
                        .onFailure { if (it is CancellationException) throw it else onMessage(couldNotDeleteFolder) }
                }
            }
        }
    }
    state.viewer?.let { request ->
        ImageViewer(request, env, onClose = { state.viewer = null }, onMessage = onMessage)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionsSheet(
    row: FileRowEntity,
    env: FilesEnvironment,
    extras: List<ExtraAction>,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("file-actions")) {
        Column(Modifier.navigationBarsPadding()) {
            ListItem(
                leadingContent = { FileThumb(row, rememberLocalFile(env, row)) },
                headlineContent = { Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(formatBytes(row.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = Space.x4))
            extras.forEach { action ->
                SheetRow(action.icon, action.label, action.tag) {
                    onDismiss()
                    action.onClick()
                }
            }
            SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.file_openDownload), "file-action-open", onClick = onOpen)
            SheetRow(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.file_rename), "file-action-rename", onClick = onRename)
            SheetRow(Icons.Outlined.DeleteOutline, stringResource(R.string.common_delete), "file-action-delete", danger = true, onClick = onDelete)
            Spacer(Modifier.height(Space.x2))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionsSheet(onDismiss: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    RemftBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            SheetRow(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.files_renameFolder), "folder-action-rename", onClick = onRename)
            SheetRow(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.files_moveFolder), "folder-action-move", onClick = onMove)
            SheetRow(Icons.Outlined.DeleteOutline, stringResource(R.string.common_delete), "folder-action-delete", danger = true, onClick = onDelete)
            Spacer(Modifier.height(Space.x2))
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = if (danger) scheme.error else scheme.onSurface
    ListItem(
        modifier = Modifier.testTag(tag).clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null, tint = if (danger) scheme.error else scheme.onSurfaceVariant) },
        headlineContent = { Text(label, color = color, style = MaterialTheme.typography.bodyLarge) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveTargetSheet(folders: List<FolderEntity>, exclude: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val entries = remember(folders, exclude) { FolderTree.flatten(folders, exclude) }
    RemftBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = Space.x4), modifier = Modifier.navigationBarsPadding()) {
            item {
                Text(
                    stringResource(R.string.files_moveTo),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = Space.x4, end = Space.x4, bottom = Space.x2),
                )
            }
            item {
                ListItem(
                    modifier = Modifier.testTag("move-target-root").clickable { onPick(null) },
                    leadingContent = { KindTile(Icons.Outlined.Home, TileTone.Folder) },
                    headlineContent = { Text(stringResource(R.string.files_rootFolder)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            items(entries, key = { it.folder.id }) { entry ->
                ListItem(
                    modifier = Modifier
                        .testTag("move-target-${entry.folder.id}")
                        .clickable { onPick(entry.folder.id) }
                        .padding(start = Space.x4 * entry.depth),
                    leadingContent = { KindTile(Icons.Outlined.Folder, TileTone.Folder) },
                    headlineContent = { Text(entry.folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

object FolderTree {
    data class Entry(val folder: FolderEntity, val depth: Int)

    fun flatten(folders: List<FolderEntity>, exclude: String?): List<Entry> {
        val children = folders.groupBy { it.parentId }
        val excluded = mutableSetOf<String>()
        fun mark(id: String) {
            if (!excluded.add(id)) return
            children[id].orEmpty().forEach { mark(it.id) }
        }
        exclude?.let(::mark)
        val out = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        fun walk(parent: String?, depth: Int) {
            children[parent].orEmpty().forEach { folder ->
                if (folder.id in excluded || !seen.add(folder.id)) return@forEach
                out += Entry(folder, depth)
                walk(folder.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }
}

@Composable
fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    RemftDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= 255) value = it },
                label = { Text(label) },
                singleLine = true,
                shape = MarkedShapes.small,
                modifier = Modifier.testTag("name-field"),
            )
        },
        confirmButton = {
            RemftTextButton(stringResource(R.string.common_save), onClick = { onSave(value.trim()) }, modifier = Modifier.testTag("name-save"))
        },
        dismissButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

@Composable
fun ConfirmDeleteDialog(title: String, body: String, confirm: String, confirmTag: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    RemftDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            RemftTextButton(confirm, onClick = onConfirm, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(confirmTag))
        },
        dismissButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun DeleteFileDialog(row: FileRowEntity, env: FilesEnvironment, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var toBin by remember { mutableStateOf(true) }
    LaunchedEffect(row.id) { toBin = env.bin.enabled() }
    ConfirmDeleteDialog(
        title = stringResource(R.string.file_deleteConfirm).fill("name" to row.name),
        body = stringResource(if (toBin) R.string.bin_toBin else R.string.file_deleteBody),
        confirm = stringResource(R.string.common_delete),
        confirmTag = "file-delete-confirm",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Preview(name = "File sheet light")
@Composable
private fun FileSheetLightPreview() {
    RemftTheme(darkTheme = false) { FileSheetPreviewContent() }
}

@Preview(name = "File sheet dark")
@Composable
private fun FileSheetDarkPreview() {
    RemftTheme(darkTheme = true) { FileSheetPreviewContent() }
}

@Composable
private fun FileSheetPreviewContent() {
    PreviewSurface {
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, MarkedShapes.sheet).padding(vertical = Space.x3)) {
            ListItem(
                leadingContent = { KindTile(Icons.Outlined.Folder, TileTone.Folder) },
                headlineContent = { Text("Travel documents", style = MaterialTheme.typography.titleMedium) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = Space.x4))
            SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open", "p-open") {}
            SheetRow(Icons.Outlined.DriveFileRenameOutline, "Rename", "p-rename") {}
            SheetRow(Icons.Outlined.DeleteOutline, "Delete", "p-delete", danger = true) {}
        }
    }
}
