package com.ftapps.remft.ui.notes.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.R
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
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
        }.getOrElse { ExportState.Failed }
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
        is ExportState.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("note-export-progress")) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(Space.x4))
                    Text(stringResource(R.string.note_exporting).fill("format" to stringResource(current.format.formatLabel)))
                }
            },
        )
        ExportState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.common_close)) } },
            text = { Text(failedText) },
        )
        is ExportState.Ready -> {
            val result = current.result
            ModalBottomSheet(onDismissRequest = viewModel::dismiss, modifier = Modifier.testTag("note-export-sheet")) {
                Column(Modifier.fillMaxWidth().padding(bottom = Space.x6), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                    Text(
                        stringResource(R.string.note_exportReady).fill("format" to stringResource(result.format.formatLabel)),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Space.x6),
                    )
                    Text(
                        stringResource(R.string.note_exportFile).fill("name" to result.displayName, "size" to ((result.file.length() + 1023) / 1024)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.x6, vertical = Space.x2),
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
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick),
    )
}
