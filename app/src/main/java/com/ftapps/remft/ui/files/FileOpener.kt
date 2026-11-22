package com.ftapps.remft.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.files.AttachmentFetchFailed
import com.ftapps.remft.data.files.FetchProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class OpenOutcome { Opened, Missing, Failed, NeedsOneDrive, Offline, FetchFailed }

object FileOpener {
    private const val SHARED_FOLDER = "shared"
    private val unsafe = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun displayName(name: String, fallback: String): String = name.replace(unsafe, "_").trim().trim('.').ifBlank { fallback }

    suspend fun prepare(context: Context, env: FilesEnvironment, row: FileRowEntity): File? = withContext(Dispatchers.IO) {
        val source = env.attachments.ensureLocal(row) ?: return@withContext null
        val root = File(context.cacheDir, SHARED_FOLDER)
        root.listFiles()?.forEach { it.deleteRecursively() }
        val copy = File(File(root, row.id).apply { mkdirs() }, displayName(row.name, source.name))
        source.copyTo(copy, overwrite = true)
        copy
    }

    @StringRes
    fun messageOf(outcome: OpenOutcome): Int? = when (outcome) {
        OpenOutcome.Opened -> null
        OpenOutcome.Missing -> R.string.error_FILE_MISSING
        OpenOutcome.Failed -> R.string.file_couldNotOpen
        OpenOutcome.NeedsOneDrive -> R.string.mirror_fetchNeedsOneDrive
        OpenOutcome.Offline -> R.string.mirror_fetchOffline
        OpenOutcome.FetchFailed -> R.string.mirror_fetchFailed
    }

    @StringRes
    fun fetchMessageOf(problem: FetchProblem): Int = messageOf(outcomeOf(problem)) ?: R.string.mirror_fetchFailed

    private fun outcomeOf(problem: FetchProblem): OpenOutcome = when (problem) {
        FetchProblem.NeedsOneDrive -> OpenOutcome.NeedsOneDrive
        FetchProblem.Offline -> OpenOutcome.Offline
        FetchProblem.Failed -> OpenOutcome.FetchFailed
    }

    suspend fun open(context: Context, env: FilesEnvironment, row: FileRowEntity, onFetching: () -> Unit = {}): OpenOutcome {
        if (env.attachments.needsFetch(row)) onFetching()
        val file = try {
            prepare(context, env, row)
        } catch (e: AttachmentFetchFailed) {
            return outcomeOf(e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return if (env.attachments.fileOf(row) == null) OpenOutcome.Missing else OpenOutcome.Failed
        val uri = runCatching { FileProvider.getUriForFile(context, env.authority, file) }.getOrNull() ?: return OpenOutcome.Failed
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, row.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = row.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, null).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(send))
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            OpenOutcome.Opened
        } catch (_: ActivityNotFoundException) {
            OpenOutcome.Failed
        }
    }
}
