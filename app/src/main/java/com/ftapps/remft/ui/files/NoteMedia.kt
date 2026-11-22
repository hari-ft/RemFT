package com.ftapps.remft.ui.files

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.editing.MdEdit
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch

object NoteAttachments {
    suspend fun store(env: FilesEnvironment, noteId: String, pick: PickedFile): String? {
        val target = UploadTarget(env.files.workspace(), "note", noteId)
        val id = env.uploads.upload(target, pick, wantsLink = true) ?: return null
        return FileLinks.markdown(id, pick.name, pick.resolvedMime)
    }

    fun appendLink(text: String, link: String): MdEdit {
        val joined = when {
            text.isEmpty() -> link
            text.endsWith('\n') -> text + link
            else -> text + '\n' + link
        }
        return MdEdit(joined, joined.length)
    }

    fun clipboardImage(context: Context): PickedFile? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        val description = clip.description
        for (index in 0 until clip.itemCount) {
            val uri = clip.getItemAt(index).uri ?: continue
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val typed = (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
            if (mime?.startsWith("image/") == true || (mime == null && typed)) {
                val picked = PickedFiles.fromUri(context, uri)
                val name = if (picked.name.contains('.')) picked.name else PASTE_NAME
                return picked.copy(name = name, mime = mime ?: picked.mime ?: "image/png")
            }
        }
        return null
    }

    private const val PASTE_NAME = "pano.png"
}

@Composable
fun NoteMediaButtons(ensureNote: suspend () -> String?, onInsert: (String) -> Unit, onMessage: (String) -> Unit) {
    val env = LocalFilesEnvironment.current ?: return
    val scope = rememberCoroutineScope()
    val pickFailed = stringResource(R.string.file_pickFailed)
    val uploadFailed = stringResource(R.string.file_uploadFailed)
    val tooLarge = stringResource(R.string.error_FILE_TOO_LARGE)
    val launcher = rememberAttachLauncher(
        onPicked = { picks ->
            scope.launch {
                val noteId = ensureNote() ?: return@launch
                picks.forEach { pick ->
                    val markdown = NoteAttachments.store(env, noteId, pick)
                    if (markdown != null) {
                        onInsert(markdown)
                    } else {
                        val failed = env.uploads.jobs.value.lastOrNull { it.target.targetId == noteId && it.name == pick.name }
                        onMessage(if (failed?.errorCode == "FILE_TOO_LARGE") tooLarge else uploadFailed)
                        failed?.let { env.uploads.dismiss(it.localId) }
                    }
                }
            }
        },
        onFailed = { onMessage(pickFailed) },
    )
    RemftIconButton(icon = Icons.Outlined.Image, label = stringResource(R.string.file_insertImage), onClick = { launcher.launch(AttachSource.Images) }, modifier = Modifier.testTag("note-insert-image"))
    RemftIconButton(icon = Icons.Outlined.Movie, label = stringResource(R.string.file_insertVideo), onClick = { launcher.launch(AttachSource.Videos) }, modifier = Modifier.testTag("note-insert-video"))
}

@Composable
fun PendingNoteLinks(noteId: String?, onLink: (String) -> Unit) {
    val env = LocalFilesEnvironment.current ?: return
    val deliver by rememberUpdatedState(onLink)
    LaunchedEffect(env, noteId) {
        val id = noteId ?: return@LaunchedEffect
        env.uploads.unclaimedLinks("note", id).collect { done ->
            done.forEach { upload ->
                env.uploads.claim(upload.localId)?.let { deliver(FileLinks.markdown(it.fileId, it.name, it.mime)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteAttachmentsSheet(noteId: String, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("note-attachments-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = Space.x4, vertical = Space.x2)) {
            Text(stringResource(R.string.task_attachments), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Space.x3))
            AttachmentsSection("note", noteId, onMessage)
        }
    }
}
