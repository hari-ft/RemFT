package com.ftapps.remft.ui.notes.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.notes.NotesSheetTitle
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val format: NoteExportFormat) : ExportState
    data class Ready(val result: NoteExportResult) : ExportState
    data object Failed : ExportState
}

@HiltViewModel
class NoteExportViewModel @Inject constructor(
    val exporter: NoteExporter,
    private val repository: NotesRepository,
    private val settings: SettingsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = mutableState

    fun exportNote(noteId: String, format: NoteExportFormat) {
        viewModelScope.launch {
            val note = repository.get(noteId) ?: return@launch
            run(format, note.title, note.contentMarkdown.orEmpty(), note.updatedAt)
        }
    }

    fun exportContent(format: NoteExportFormat, title: String, markdown: String, updatedAt: String?) {
        viewModelScope.launch { run(format, title, markdown, updatedAt) }
    }

    private suspend fun run(format: NoteExportFormat, title: String, markdown: String, updatedAt: String?) {
        mutableState.value = ExportState.Working(format)
        val dateFormat = DateFormatId.parse(settings.get(SettingKeys.DateFormat.name))
        mutableState.value = runCatching {
            ExportState.Ready(exporter.export(format, repository.titleFor(title), markdown, updatedAt, dateFormat))
        }.onSuccess { AppLogs.info("notes.exported", mapOf("format" to format, "bytes" to it.result.file.length())) }
            .getOrElse { AppLogs.warn("notes.export_failed", it, mapOf("format" to format)); ExportState.Failed }
    }

    fun dismiss() {
        mutableState.value = ExportState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteExportHost(viewModel: NoteExportViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.note_exportSaved)
    val failedText = stringResource(R.string.note_exportFailed)
    val ready = state as? ExportState.Ready
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ready?.result?.format?.mime ?: "application/octet-stream"),
    ) { uri ->
        val result = (viewModel.state.value as? ExportState.Ready)?.result
        if (uri != null && result != null) {
            scope.launch {
                val ok = viewModel.exporter.saveTo(result, uri)
                snackbar.showSnackbar(if (ok) savedText else failedText)
            }
            viewModel.dismiss()
        }
    }
    when (val current = state) {
        ExportState.Idle -> Unit
        is ExportState.Working -> RemftDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("note-export-progress")) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(Space.x4))
                    Text(
                        stringResource(R.string.note_exporting).fill("format" to stringResource(current.format.formatLabel)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        )
        ExportState.Failed -> RemftDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { RemftTextButton(stringResource(R.string.common_close), onClick = viewModel::dismiss) },
            text = { Text(failedText, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
        )
        is ExportState.Ready -> {
            val result = current.result
            RemftBottomSheet(onDismissRequest = viewModel::dismiss, modifier = Modifier.testTag("note-export-sheet")) {
                Column(Modifier.fillMaxWidth().padding(bottom = Space.x6), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    NotesSheetTitle(
                        stringResource(R.string.note_exportReady).fill("format" to stringResource(result.format.formatLabel)),
                        Modifier.padding(horizontal = Space.x6),
                    )
                    Text(
                        stringResource(R.string.note_exportFile).fill("name" to result.displayName, "size" to ((result.file.length() + 1023) / 1024)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Space.x6, end = Space.x6, top = Space.x1, bottom = Space.x3),
                    )
                    SheetAction(Icons.Outlined.Share, R.string.note_exportShare, R.string.note_exportShareHint, "export-share") {
                        context.startActivity(viewModel.exporter.shareIntent(result))
                    }
                    SheetAction(Icons.Outlined.SaveAlt, R.string.note_exportSave, R.string.note_exportSaveHint, "export-save") {
                        saveLauncher.launch(result.displayName)
                    }
                    if (result.format == NoteExportFormat.Pdf) {
                        SheetAction(Icons.Outlined.Print, R.string.note_exportPrint, R.string.note_exportPrintHint, "export-print") {
                            viewModel.exporter.print(context, result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, hint: Int, tag: String, onClick: () -> Unit) {
    val tone = RemftTheme.colors.note
    ListItem(
        headlineContent = { Text(stringResource(title), style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Box(Modifier.size(40.dp).background(tone.container, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tone.onContainer, modifier = Modifier.size(20.dp))
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(horizontal = Space.x2),
    )
}

@Composable
private fun ExportSheetPreviewContent() {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = Space.x4), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        NotesSheetTitle(stringResource(R.string.note_exportReady).fill("format" to "PDF"), Modifier.padding(horizontal = Space.x6))
        SheetAction(Icons.Outlined.Share, R.string.note_exportShare, R.string.note_exportShareHint, "preview-share") {}
        SheetAction(Icons.Outlined.SaveAlt, R.string.note_exportSave, R.string.note_exportSaveHint, "preview-save") {}
        SheetAction(Icons.Outlined.Print, R.string.note_exportPrint, R.string.note_exportPrintHint, "preview-print") {}
    }
}

@Preview(name = "Export sheet light", widthDp = 380)
@Composable
private fun ExportSheetLightPreview() {
    RemftTheme(darkTheme = false) { ExportSheetPreviewContent() }
}

@Preview(name = "Export sheet dark", widthDp = 380)
@Composable
private fun ExportSheetDarkPreview() {
    RemftTheme(darkTheme = true) { ExportSheetPreviewContent() }
}
