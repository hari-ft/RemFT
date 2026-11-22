package com.ftapps.remft.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.notes.ImportedMarkdown
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.notes.markdown.MarkdownView
import com.ftapps.remft.ui.notes.markdown.rememberMarkdownDocument
import com.ftapps.remft.ui.theme.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MarkdownImportViewModel @Inject constructor(
    private val external: ExternalDocumentStore,
    private val repository: NotesRepository,
) : ViewModel() {
    val document: StateFlow<ImportedMarkdown?> = external.pendingImport

    suspend fun save(): String? {
        val pending = document.value ?: return null
        val split = NoteText.splitMarkdownTitle(pending.markdown, NoteText.fileStem(pending.name))
        val id = runCatching { repository.create(split.title, split.body) }.getOrNull() ?: return null
        external.setPendingImport(null)
        return id
    }
}

@Composable
fun MarkdownImportScreen(viewModel: MarkdownImportViewModel, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val pending by viewModel.document.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.note_mdImportFailed)
    Box(Modifier.fillMaxSize().testTag("screen-md-import")) {
        SectionScaffold(
            title = pending?.name ?: stringResource(R.string.note_mdViewerTitle),
            onBack = onBack,
            floatingActionButton = {
                if (pending != null) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            scope.launch {
                                val id = viewModel.save()
                                if (id != null) onSaved(id) else snackbar.showSnackbar(failed)
                            }
                        },
                        icon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                        text = { Text(stringResource(R.string.note_mdImport)) },
                        modifier = Modifier.testTag("md-import-save"),
                    )
                }
            },
        ) { padding ->
            val current = pending
            if (current == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.Description, stringResource(R.string.note_mdViewerTitle), stringResource(R.string.note_mdNothingOpen))
                }
            } else {
                val document = rememberMarkdownDocument(current.markdown)
                MarkdownView(
                    document = document,
                    modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                    contentPadding = PaddingValues(start = Space.x5, end = Space.x5, top = Space.x2, bottom = 120.dp),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
