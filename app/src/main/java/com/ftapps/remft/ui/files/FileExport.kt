package com.ftapps.remft.ui.files

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.entity.FileRowEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class SaveTarget(val code: String, val relativePath: String, @StringRes val label: Int, @StringRes val saved: Int) {
    Pictures("image", "Pictures/RemFT", R.string.sharesave_saveToGallery, R.string.sharesave_savedPictures),
    Movies("video", "Movies/RemFT", R.string.sharesave_saveToGallery, R.string.sharesave_savedMovies),
    Downloads("file", "Download/RemFT", R.string.sharesave_saveToDownloads, R.string.sharesave_savedDownloads),
    ;

    @RequiresApi(Build.VERSION_CODES.Q)
    fun collection(): Uri = when (this) {
        Pictures -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Movies -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        Downloads -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    companion object {
        fun of(mime: String): SaveTarget = when {
            mime.startsWith("image/") -> Pictures
            mime.startsWith("video/") -> Movies
            else -> Downloads
        }
    }
}

fun interface DeviceSaver {
    fun save(source: File, name: String, mime: String, target: SaveTarget)
}

@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSaver(private val resolver: ContentResolver) : DeviceSaver {
    override fun save(source: File, name: String, mime: String, target: SaveTarget) {
        val uri = resolver.insert(target.collection(), pendingValues(name, mime, target)) ?: throw IOException("media store refused the insert")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("media store gave no output stream")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    companion object {
        fun pendingValues(name: String, mime: String, target: SaveTarget): ContentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }
}

data class SaveRequest(val name: String, val mime: String)

object CreateDocumentFor : ActivityResultContract<SaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveRequest): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = input.mime
        putExtra(Intent.EXTRA_TITLE, input.name)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }

    fun copy(resolver: ContentResolver, uri: Uri, source: File) {
        val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream for the chosen document")
        out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
    }
}

object FileExport {
    @StringRes
    fun shareMessageOf(outcome: OpenOutcome): Int? = if (outcome == OpenOutcome.Failed) R.string.sharesave_couldNotShare else FileOpener.messageOf(outcome)

    @StringRes
    fun saveMessageOf(outcome: OpenOutcome): Int? = if (outcome == OpenOutcome.Failed) R.string.sharesave_saveFailed else FileOpener.messageOf(outcome)

    fun saverFor(env: FilesEnvironment, context: Context): DeviceSaver? =
        env.deviceSaver ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreSaver(context.contentResolver) else null

    suspend fun saveWith(saver: DeviceSaver, source: File, row: FileRowEntity): Int {
        val target = SaveTarget.of(row.mime)
        return try {
            withContext(Dispatchers.IO) { saver.save(source, source.name, row.mime, target) }
            AppLogs.info("files.saved_to_device", mapOf("destination" to target.code))
            target.saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogs.warn("files.save_failed", e, mapOf("destination" to target.code))
            R.string.sharesave_saveFailed
        }
    }
}

@Stable
class FileExporter internal constructor(
    private val onShare: (FileRowEntity) -> Unit,
    private val onSave: (FileRowEntity) -> Unit,
) {
    fun share(row: FileRowEntity) = onShare(row)

    fun save(row: FileRowEntity) = onSave(row)
}

@Composable
fun rememberFileExporter(env: FilesEnvironment, onMessage: (String) -> Unit): FileExporter {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val message by rememberUpdatedState(onMessage)
    var waiting by remember { mutableStateOf<Pair<File, String>?>(null) }
    val picker = rememberLauncherForActivityResult(CreateDocumentFor) { uri ->
        val (file, code) = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { CreateDocumentFor.copy(context.contentResolver, uri, file) }
                AppLogs.info("files.saved_to_device", mapOf("destination" to code))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogs.warn("files.save_failed", e, mapOf("destination" to code))
                false
            }
            message(resources.getString(if (ok) R.string.sharesave_savedChosen else R.string.sharesave_saveFailed))
        }
    }
    return remember(env, context) {
        val fetching = { message(resources.getString(R.string.mirror_fetching)) }
        FileExporter(
            onShare = { row ->
                scope.launch {
                    val outcome = FileOpener.share(context, env, row, resources.getString(R.string.sharesave_share), fetching)
                    FileExport.shareMessageOf(outcome)?.let { message(resources.getString(it)) }
                }
            },
            onSave = { row ->
                scope.launch {
                    val ready = when (val prepared = FileOpener.fetchCopy(context, env, row, fetching)) {
                        is FileOpener.Prepared.Problem -> {
                            FileExport.saveMessageOf(prepared.outcome)?.let { message(resources.getString(it)) }
                            return@launch
                        }
                        is FileOpener.Prepared.Ready -> prepared.file
                    }
                    val saver = FileExport.saverFor(env, context)
                    if (saver != null) {
                        message(resources.getString(FileExport.saveWith(saver, ready, row)))
                        return@launch
                    }
                    waiting = ready to SaveTarget.of(row.mime).code
                    try {
                        picker.launch(SaveRequest(ready.name, row.mime))
                    } catch (e: ActivityNotFoundException) {
                        waiting = null
                        AppLogs.warn("files.save_failed", e, mapOf("destination" to SaveTarget.of(row.mime).code))
                        message(resources.getString(R.string.sharesave_saveFailed))
                    }
                }
            },
        )
    }
}
