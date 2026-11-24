package com.ftapps.remft.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
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
    const val SELF_ACTIVITY = "com.ftapps.remft.MainActivity"
    private val unsafe = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    fun displayName(name: String, fallback: String): String = name.replace(unsafe, "_").trim().trim('.').ifBlank { fallback }

    suspend fun prepare(context: Context, env: FilesEnvironment, row: FileRowEntity): File? = withContext(Dispatchers.IO) {
        val source = env.attachments.ensureLocal(row) ?: return@withContext null
        val root = File(context.cacheDir, SHARED_FOLDER)
        TempCopies.pruneStale(root)
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

    sealed interface Prepared {
        data class Ready(val file: File, val uri: Uri) : Prepared
        data class Problem(val outcome: OpenOutcome) : Prepared
    }

    suspend fun fetchCopy(context: Context, env: FilesEnvironment, row: FileRowEntity, onFetching: () -> Unit = {}): Prepared {
        if (env.attachments.needsFetch(row)) onFetching()
        val file = try {
            prepare(context, env, row)
        } catch (e: AttachmentFetchFailed) {
            AppLogs.warn("files.fetch_failed", e, mapOf("reason" to e.problem))
            return Prepared.Problem(outcomeOf(e.problem))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogs.warn("files.open_failed", e)
            null
        } ?: return Prepared.Problem(if (env.attachments.fileOf(row) == null) OpenOutcome.Missing else OpenOutcome.Failed)
        val uri = runCatching { env.uriForFile(context, env.authority, file) }.onFailure { AppLogs.warn("files.open_failed", it) }.getOrNull()
            ?: return Prepared.Problem(OpenOutcome.Failed)
        return Prepared.Ready(file, uri)
    }

    fun shareIntent(uri: Uri, mime: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun open(context: Context, env: FilesEnvironment, row: FileRowEntity, onFetching: () -> Unit = {}): OpenOutcome {
        val uri = when (val prepared = fetchCopy(context, env, row, onFetching)) {
            is Prepared.Problem -> return prepared.outcome
            is Prepared.Ready -> prepared.uri
        }
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
        } catch (e: ActivityNotFoundException) {
            AppLogs.warn("files.no_app_to_open", e)
            OpenOutcome.Failed
        }
    }

    suspend fun share(context: Context, env: FilesEnvironment, row: FileRowEntity, title: String?, onFetching: () -> Unit = {}): OpenOutcome {
        val uri = when (val prepared = fetchCopy(context, env, row, onFetching)) {
            is Prepared.Problem -> return prepared.outcome
            is Prepared.Ready -> prepared.uri
        }
        val chooser = Intent.createChooser(shareIntent(uri, row.mime), title).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context.packageName, SELF_ACTIVITY)))
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            AppLogs.info("files.shared", mapOf("kind" to SaveTarget.of(row.mime).code))
            OpenOutcome.Opened
        } catch (e: ActivityNotFoundException) {
            AppLogs.warn("files.no_app_to_share", e)
            OpenOutcome.Failed
        }
    }
}
