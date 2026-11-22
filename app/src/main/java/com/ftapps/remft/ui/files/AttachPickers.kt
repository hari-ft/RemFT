package com.ftapps.remft.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ftapps.remft.R
import com.ftapps.remft.data.files.MimeTypes
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class AttachSource { Photos, Camera, Files, Images, Videos }

val AttachMenuSources = listOf(AttachSource.Photos, AttachSource.Camera, AttachSource.Files)

class AttachLauncher(val launch: (AttachSource) -> Unit)

object PickedFiles {
    private val captureStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun cameraName(now: LocalDateTime): String = "IMG_${captureStamp.format(now)}.jpg"

    fun fromUri(context: Context, uri: Uri): PickedFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val base = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        val withExtension = if (base.contains('.')) base else MimeTypes.extensionFor(mime)?.let { "$base.$it" } ?: base
        return PickedFile(
            name = withExtension,
            sizeBytes = size?.takeIf { it >= 0 },
            mime = mime,
            open = { resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString()) },
        )
    }

    fun fromCapture(file: File, now: LocalDateTime): PickedFile = PickedFile(
        name = cameraName(now),
        sizeBytes = file.length(),
        mime = "image/jpeg",
        open = { file.inputStream() },
        cleanup = { file.delete() },
    )
}

@Composable
fun rememberAttachLauncher(onPicked: (List<PickedFile>) -> Unit, onFailed: () -> Unit): AttachLauncher {
    val context = LocalContext.current
    val env = LocalFilesEnvironment.current
    val scope = rememberCoroutineScope()
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onFailed)
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) picked(uris.map { PickedFiles.fromUri(context, it) })
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) picked(uris.map { PickedFiles.fromUri(context, it) })
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = capturePath?.let(::File)
        capturePath = null
        if (file == null) return@rememberLauncherForActivityResult
        if (saved && file.isFile && file.length() > 0) {
            picked(listOf(PickedFiles.fromCapture(file, LocalDateTime.now())))
        } else {
            file.delete()
        }
    }
    return remember(context, env) {
        AttachLauncher { source ->
            val override = env?.pickOverride
            if (override != null) {
                scope.launch { runCatching { override(source) }.onSuccess { if (it.isNotEmpty()) picked(it) }.onFailure { failed() } }
                return@AttachLauncher
            }
            try {
                when (source) {
                    AttachSource.Photos -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    AttachSource.Images -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    AttachSource.Videos -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    AttachSource.Files -> documents.launch(arrayOf("*/*"))
                    AttachSource.Camera -> {
                        val authority = env?.authority ?: throw ActivityNotFoundException()
                        val dir = File(context.cacheDir, CAMERA_FOLDER).apply { mkdirs() }
                        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                        capturePath = file.path
                        camera.launch(FileProvider.getUriForFile(context, authority, file))
                    }
                }
            } catch (_: ActivityNotFoundException) {
                capturePath = null
                failed()
            } catch (_: IllegalArgumentException) {
                capturePath = null
                failed()
            }
        }
    }
}

private const val CAMERA_FOLDER = "camera"

private fun AttachSource.icon(): ImageVector = when (this) {
    AttachSource.Photos, AttachSource.Images -> Icons.Outlined.PhotoLibrary
    AttachSource.Camera -> Icons.Outlined.PhotoCamera
    AttachSource.Videos -> Icons.Outlined.VideoLibrary
    AttachSource.Files -> Icons.Outlined.FolderOpen
}

private fun AttachSource.label(): Int = when (this) {
    AttachSource.Photos -> R.string.file_fromPhotos
    AttachSource.Camera -> R.string.file_fromCamera
    AttachSource.Files -> R.string.file_fromFiles
    AttachSource.Images -> R.string.file_insertImage
    AttachSource.Videos -> R.string.file_insertVideo
}

private fun AttachSource.tag(): String = when (this) {
    AttachSource.Photos, AttachSource.Images -> "attach-source-photos"
    AttachSource.Camera -> "attach-source-camera"
    AttachSource.Videos -> "attach-source-video"
    AttachSource.Files -> "attach-source-files"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSourceSheet(onPick: (AttachSource) -> Unit, onDismiss: () -> Unit) {
    RemftBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.file_attachTitle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Space.x5, end = Space.x5, bottom = Space.x2),
            )
            AttachMenuSources.forEach { source ->
                ListItem(
                    modifier = Modifier.testTag(source.tag()).clickable { onPick(source) },
                    leadingContent = { KindTile(source.icon()) },
                    headlineContent = { Text(stringResource(source.label())) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(Space.x2))
        }
    }
}

@Composable
fun AttachButton(
    onPicked: (List<PickedFile>) -> Unit,
    onFailed: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    icon: ImageVector = Icons.Outlined.AttachFile,
    hoisted: AttachLauncher? = null,
) {
    var sheet by remember { mutableStateOf(false) }
    val own = rememberAttachLauncher(onPicked, onFailed)
    val launcher = hoisted ?: own
    val label = stringResource(R.string.file_add)
    if (outlined) {
        OutlinedButton(
            onClick = { sheet = true },
            shape = MarkedShapes.pill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.heightIn(min = Sizes.touchTarget).testTag(tag),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Space.x2))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        RemftTonalButton(label, onClick = { sheet = true }, icon = icon, modifier = modifier.testTag(tag))
    }
    if (sheet) {
        AttachSourceSheet(onPick = {
            sheet = false
            launcher.launch(it)
        }, onDismiss = { sheet = false })
    }
}
