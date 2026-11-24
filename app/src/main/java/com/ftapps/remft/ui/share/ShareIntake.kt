package com.ftapps.remft.ui.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.ui.files.FileOpener
import com.ftapps.remft.ui.files.PickedFile
import com.ftapps.remft.ui.files.PickedFiles
import com.ftapps.remft.ui.files.TempCopies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class IncomingShare(val uris: List<Uri>, val text: String?) {
    val isEmpty: Boolean get() = uris.isEmpty() && text == null
}

object ShareIntents {
    fun parse(intent: Intent?): IncomingShare? {
        if (intent == null) return null
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
            else -> return null
        }
        val uris = streams.ifEmpty { clipUris(intent) }.distinct()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return IncomingShare(uris, text.takeIf { uris.isEmpty() })
    }

    fun atLaunch(intent: Intent?, restored: Boolean): IncomingShare? {
        if (restored || intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        return parse(intent)
    }

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}

enum class IntakeProblem(val code: String) { Unreadable("unreadable"), TooLarge("too_large"), Unsupported("unsupported") }

data class SharedItem(val file: File, val name: String, val mime: String, val sizeBytes: Long) {
    val isImage: Boolean get() = mime.startsWith("image/")

    fun toPick(): PickedFile = PickedFile(
        name = name,
        sizeBytes = sizeBytes,
        mime = mime,
        open = { file.inputStream() },
        cleanup = { file.parentFile?.deleteRecursively() },
    )
}

data class Intake(val items: List<SharedItem>, val problems: List<IntakeProblem>, val text: String?) {
    val isEmpty: Boolean get() = items.isEmpty() && text == null
}

class TooLargeToShare : IOException("shared item is over the size limit")

class ShareCopier(
    private val resolver: () -> ContentResolver,
    private val describe: (Uri) -> PickedFile,
    private val root: File,
    private val maxBytes: Long = AttachmentStore.MAX_BYTES,
) {
    constructor(context: Context, root: File = File(context.cacheDir, FOLDER)) :
        this({ context.contentResolver }, { PickedFiles.fromUri(context, it) }, root)

    suspend fun copy(share: IncomingShare): Intake = withContext(Dispatchers.IO) {
        TempCopies.pruneStale(root, maxAgeMs = STALE_MS)
        val session = File(root, UUID.randomUUID().toString())
        val items = mutableListOf<SharedItem>()
        val problems = mutableListOf<IntakeProblem>()
        try {
            share.uris.forEachIndexed { index, uri ->
                ensureActive()
                when (val result = copyOne(uri, File(session, index.toString()))) {
                    is SharedItem -> items += result
                    is IntakeProblem -> problems += result
                }
            }
            ensureActive()
        } catch (e: CancellationException) {
            session.deleteRecursively()
            throw e
        }
        if (items.isEmpty()) session.deleteRecursively()
        problems.forEach { AppLogs.warn("files.share_in_skipped", fields = mapOf("reason" to it.code)) }
        Intake(items, problems, share.text)
    }

    private fun copyOne(uri: Uri, dir: File): Any {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return IntakeProblem.Unsupported
        val described = try {
            describe(uri)
        } catch (e: Exception) {
            AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "describe"))
            return IntakeProblem.Unreadable
        }
        if ((described.sizeBytes ?: 0) > maxBytes) return IntakeProblem.TooLarge
        val target = File(dir.apply { mkdirs() }, FileOpener.displayName(described.name, "file"))
        return try {
            val written = (resolver().openInputStream(uri) ?: throw IOException("no input stream")).use { input ->
                target.outputStream().use { output -> copyCapped(input, output, maxBytes) }
            }
            SharedItem(target, target.name, described.resolvedMime, written)
        } catch (e: TooLargeToShare) {
            dir.deleteRecursively()
            IntakeProblem.TooLarge
        } catch (e: Exception) {
            dir.deleteRecursively()
            AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "copy"))
            IntakeProblem.Unreadable
        }
    }

    fun discard(intake: Intake) {
        intake.items.forEach { runCatching { it.file.parentFile?.deleteRecursively() } }
        root.listFiles().orEmpty().filter { it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
    }

    companion object {
        const val FOLDER = "share-in"
        const val STALE_MS: Long = 24L * 60 * 60 * 1000

        fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return total
                total += read
                if (total > maxBytes) throw TooLargeToShare()
                output.write(buffer, 0, read)
            }
        }
    }
}

sealed interface ShareState {
    data class Copying(val count: Int) : ShareState
    data class Ready(val intake: Intake) : ShareState
}

class ShareInbox(
    private val scope: CoroutineScope,
    private val copy: suspend (IncomingShare) -> Intake,
    private val discardFiles: (Intake) -> Unit,
) {
    constructor(scope: CoroutineScope, copier: ShareCopier) : this(scope, copier::copy, copier::discard)

    private val current = MutableStateFlow<ShareState?>(null)
    val state: StateFlow<ShareState?> = current.asStateFlow()
    private var job: Job? = null

    fun receive(share: IncomingShare) {
        drop()
        AppLogs.info("files.share_in_received", mapOf("items" to share.uris.size, "kind" to if (share.uris.isEmpty()) "text" else "files"))
        current.value = ShareState.Copying(share.uris.size)
        job = scope.launch {
            val intake = try {
                copy(share)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogs.warn("files.share_in_failed", e, mapOf("stage" to "intake"))
                Intake(emptyList(), share.uris.map { IntakeProblem.Unreadable }, share.text)
            }
            current.value = ShareState.Ready(intake)
        }
    }

    fun dismiss() = drop()

    fun handOff(): List<PickedFile> {
        val ready = current.value as? ShareState.Ready ?: return emptyList()
        current.value = null
        return ready.intake.items.map { it.toPick() }
    }

    private fun drop() {
        job?.cancel()
        job = null
        val ready = current.value as? ShareState.Ready
        current.value = null
        ready?.let { discardFiles(it.intake) }
    }
}
