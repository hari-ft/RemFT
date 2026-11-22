package com.ftapps.remft.ui.notes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.R
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.notes.ExternalOpenRefusal
import com.ftapps.remft.data.notes.ExternalOpenResult
import com.ftapps.remft.data.notes.ImportResult
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.domain.notes.ExternalDocHandle
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.empty.EmptyIllustration
import com.ftapps.remft.ui.empty.IllustratedEmptyState
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.notes.export.NoteExportHost
import com.ftapps.remft.ui.notes.export.NoteExportViewModel
import com.ftapps.remft.ui.notes.markdown.LocalMarkdownImages
import com.ftapps.remft.ui.notes.markdown.MarkdownImageResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.internal.GeneratedComponentManagerHolder
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val MarkdownMimeTypes = arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun rememberHiltAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.findActivity() is GeneratedComponentManagerHolder }
}

@HiltViewModel
class NotesHostViewModel @Inject constructor(
    private val external: ExternalDocumentStore,
    private val repository: NotesRepository,
) : ViewModel() {
    val recents: StateFlow<List<ExternalDocHandle>> = external.recents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val images = MarkdownImageResolver { repository.attachmentFile(it) }

    fun import(uri: Uri, onResult: (ImportResult) -> Unit) {
        viewModelScope.launch {
            val result = external.readImport(uri)
            if (result is ImportResult.Ok) external.setPendingImport(result.document)
            onResult(result)
        }
    }

    fun openExternal(uri: Uri, onResult: (ExternalOpenResult) -> Unit) {
        viewModelScope.launch { onResult(external.open(uri, fresh = true)) }
    }

    fun openRecent(handle: ExternalDocHandle, onResult: (ExternalOpenResult) -> Unit) {
        viewModelScope.launch { onResult(external.reopen(handle)) }
    }
}

@Composable
private fun refusalText(reason: ExternalOpenRefusal): String = stringResource(
    when (reason) {
        ExternalOpenRefusal.Gone -> R.string.note_extGone
        ExternalOpenRefusal.Denied -> R.string.note_extDenied
        ExternalOpenRefusal.TooLarge -> R.string.note_extTooLarge
        ExternalOpenRefusal.LostAccess -> R.string.note_extLostAccess
    },
)

@Composable
fun NotesHost(
    bottomPadding: Dp,
    onOpenSettings: () -> Unit,
    onOpenNote: (String?) -> Unit,
    onOpenExternal: () -> Unit,
    onOpenImport: () -> Unit,
) {
    if (!rememberHiltAvailable()) {
        SectionScaffold(title = stringResource(R.string.nav_notes), onOpenSettings = onOpenSettings) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(bottom = bottomPadding).testTag("screen-notes"), contentAlignment = Alignment.Center) {
                IllustratedEmptyState(EmptyIllustration.Notes, stringResource(R.string.note_empty), stringResource(R.string.note_captureFirst))
            }
        }
        return
    }
    val viewModel: NotesViewModel = hiltViewModel()
    val host: NotesHostViewModel = hiltViewModel()
    val exportViewModel: NoteExportViewModel = hiltViewModel()
    val recents by host.recents.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val texts = ExternalTexts(
        importFailed = stringResource(R.string.note_mdImportFailed),
        tooLarge = stringResource(R.string.note_mdTooLarge),
        gone = refusalText(ExternalOpenRefusal.Gone),
        denied = refusalText(ExternalOpenRefusal.Denied),
        large = refusalText(ExternalOpenRefusal.TooLarge),
        lost = refusalText(ExternalOpenRefusal.LostAccess),
    )
    val onOpened: (ExternalOpenResult) -> Unit = { result ->
        when (result) {
            is ExternalOpenResult.Opened -> onOpenExternal()
            is ExternalOpenResult.Refused -> scope.launch { snackbar.showSnackbar(texts.refusal(result.reason)) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            host.import(uri) { result ->
                when (result) {
                    is ImportResult.Ok -> onOpenImport()
                    is ImportResult.TooLarge -> scope.launch { snackbar.showSnackbar(texts.tooLarge.fill("size" to result.megabytes)) }
                    ImportResult.Failed -> scope.launch { snackbar.showSnackbar(texts.importFailed) }
                }
            }
        }
    }
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) host.openExternal(uri, onOpened)
    }
    NotesScreen(
        viewModel = viewModel,
        bottomPadding = bottomPadding,
        onOpenSettings = onOpenSettings,
        onOpenNote = onOpenNote,
        onExport = exportViewModel::exportNote,
        onEditExternal = { editLauncher.launch(MarkdownMimeTypes) },
        onImport = { importLauncher.launch(MarkdownMimeTypes) },
        onOpenRecent = { host.openRecent(it, onOpened) },
        recents = recents,
        snackbar = snackbar,
    )
    NoteExportHost(exportViewModel, snackbar)
}

private class ExternalTexts(
    val importFailed: String,
    val tooLarge: String,
    val gone: String,
    val denied: String,
    val large: String,
    val lost: String,
) {
    fun refusal(reason: ExternalOpenRefusal) = when (reason) {
        ExternalOpenRefusal.Gone -> gone
        ExternalOpenRefusal.Denied -> denied
        ExternalOpenRefusal.TooLarge -> large
        ExternalOpenRefusal.LostAccess -> lost
    }
}

@Composable
fun NoteEditorHost(onBack: () -> Unit) {
    if (!rememberHiltAvailable()) {
        SectionScaffold(title = stringResource(R.string.nav_notes), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).testTag("screen-note-editor"))
        }
        return
    }
    val viewModel: NoteEditorViewModel = hiltViewModel()
    val host: NotesHostViewModel = hiltViewModel()
    val exportViewModel: NoteExportViewModel = hiltViewModel()
    CompositionLocalProvider(LocalMarkdownImages provides host.images) {
        NoteEditorScreen(viewModel, exportViewModel, onBack)
    }
}

@Composable
fun MarkdownImportHost(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val viewModel: MarkdownImportViewModel = hiltViewModel()
    MarkdownImportScreen(viewModel, onBack, onSaved)
}
