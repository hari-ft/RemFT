package com.ftapps.remft.data.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.notes.ExternalDecoded
import com.ftapps.remft.domain.notes.ExternalDocHandle
import com.ftapps.remft.domain.notes.ExternalDocuments
import com.ftapps.remft.domain.notes.ExternalEncoding
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

data class ImportedMarkdown(val name: String, val markdown: String)

sealed interface ImportResult {
    data class Ok(val document: ImportedMarkdown) : ImportResult
    data class TooLarge(val megabytes: String) : ImportResult
    data object Failed : ImportResult
}

data class ExternalSession(
    val handle: ExternalDocHandle,
    val markdown: String,
    val encoding: ExternalEncoding,
    val sha256: String,
    val writable: Boolean,
)

enum class ExternalOpenRefusal { Gone, Denied, TooLarge, LostAccess }

sealed interface ExternalOpenResult {
    data class Opened(val session: ExternalSession) : ExternalOpenResult
    data class Refused(val reason: ExternalOpenRefusal) : ExternalOpenResult
}

sealed interface ExternalSaveOutcome {
    data class Saved(val session: ExternalSession) : ExternalSaveOutcome
    data object Conflict : ExternalSaveOutcome
    data object LostAccess : ExternalSaveOutcome
    data object Failed : ExternalSaveOutcome
}

class TooLargeException(val size: Long) : Exception()

class ExternalDocumentStore(private val context: Context, private val settings: SettingsStore) {
    private val resolver get() = context.contentResolver
    private val mutableSession = MutableStateFlow<ExternalSession?>(null)
    val session: StateFlow<ExternalSession?> = mutableSession.asStateFlow()
    private val mutableImport = MutableStateFlow<ImportedMarkdown?>(null)
    val pendingImport: StateFlow<ImportedMarkdown?> = mutableImport.asStateFlow()

    val recents: Flow<List<ExternalDocHandle>> = settings.flow(SettingKeys.ExternalRecents.name).map { ExternalDocuments.parseRecents(it) }

    fun setPendingImport(document: ImportedMarkdown?) {
        mutableImport.value = document
    }

    fun closeSession() {
        mutableSession.value = null
    }

    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "document.md"
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull() ?: fallback
    }

    private fun declaredSize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun providerAllowsWrite(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
            } else {
                true
            }
        } ?: true
    }.getOrDefault(true)

    private fun readBytes(uri: Uri, limit: Int): ByteArray {
        val declared = declaredSize(uri)
        if (declared != null && declared > limit) throw TooLargeException(declared)
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw TooLargeException(total)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    suspend fun readImport(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bytes = readBytes(uri, NoteText.MARKDOWN_MAX_BYTES)
            val decoded = ExternalDocuments.decode(bytes)
            ImportResult.Ok(ImportedMarkdown(displayName(uri), decoded.text))
        } catch (e: TooLargeException) {
            ImportResult.TooLarge(String.format(java.util.Locale.ENGLISH, "%.1f", e.size / (1024.0 * 1024.0)))
        } catch (_: Exception) {
            ImportResult.Failed
        }
    }

    private fun persist(uri: Uri): Boolean {
        val both = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            resolver.takePersistableUriPermission(uri, both)
            true
        } catch (_: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            false
        }
    }

    private fun hasWriteGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    suspend fun open(uri: Uri, fresh: Boolean): ExternalOpenResult = withContext(Dispatchers.IO) {
        val writeGranted = if (fresh) persist(uri) else hasWriteGrant(uri)
        val bytes = try {
            readBytes(uri, NoteText.MARKDOWN_MAX_BYTES)
        } catch (_: TooLargeException) {
            return@withContext ExternalOpenResult.Refused(ExternalOpenRefusal.TooLarge)
        } catch (_: SecurityException) {
            return@withContext ExternalOpenResult.Refused(if (fresh) ExternalOpenRefusal.Denied else ExternalOpenRefusal.LostAccess)
        } catch (_: FileNotFoundException) {
            return@withContext ExternalOpenResult.Refused(ExternalOpenRefusal.Gone)
        } catch (_: Exception) {
            return@withContext ExternalOpenResult.Refused(ExternalOpenRefusal.Denied)
        }
        val decoded: ExternalDecoded = ExternalDocuments.decode(bytes)
        val handle = ExternalDocHandle(uri.toString(), ExternalDocuments.KIND_ANDROID_URI, displayName(uri))
        val session = ExternalSession(
            handle = handle,
            markdown = decoded.text,
            encoding = decoded.encoding,
            sha256 = ExternalDocuments.sha256(bytes),
            writable = writeGranted && providerAllowsWrite(uri) && decoded.encoding != ExternalEncoding.NotText,
        )
        remember(handle)
        mutableSession.value = session
        ExternalOpenResult.Opened(session)
    }

    suspend fun reopen(handle: ExternalDocHandle): ExternalOpenResult {
        val result = open(handle.token.toUri(), fresh = false)
        if (result is ExternalOpenResult.Refused && result.reason != ExternalOpenRefusal.TooLarge) forget(handle.token)
        return result
    }

    suspend fun save(markdown: String, force: Boolean): ExternalSaveOutcome = withContext(Dispatchers.IO) {
        val current = mutableSession.value ?: return@withContext ExternalSaveOutcome.Failed
        if (!current.writable) return@withContext ExternalSaveOutcome.Failed
        val uri = current.handle.token.toUri()
        try {
            val onDisk = readBytes(uri, Int.MAX_VALUE - 8)
            if (!force && ExternalDocuments.sha256(onDisk) != current.sha256) return@withContext ExternalSaveOutcome.Conflict
            val bytes = ExternalDocuments.encode(markdown, current.encoding)
            val stream = resolver.openOutputStream(uri, "wt") ?: return@withContext ExternalSaveOutcome.Failed
            stream.use { it.write(bytes) }
            val saved = current.copy(markdown = markdown, sha256 = ExternalDocuments.sha256(bytes))
            mutableSession.value = saved
            ExternalSaveOutcome.Saved(saved)
        } catch (_: SecurityException) {
            ExternalSaveOutcome.LostAccess
        } catch (_: FileNotFoundException) {
            ExternalSaveOutcome.LostAccess
        } catch (_: Exception) {
            ExternalSaveOutcome.Failed
        }
    }

    suspend fun reload(): ExternalOpenResult? {
        val current = mutableSession.value ?: return null
        return open(current.handle.token.toUri(), fresh = false)
    }

    suspend fun saveCopy(target: Uri, markdown: String): Boolean = withContext(Dispatchers.IO) {
        val encoding = mutableSession.value?.encoding ?: ExternalEncoding.Utf8
        runCatching {
            resolver.openOutputStream(target, "wt")?.use { it.write(ExternalDocuments.encode(markdown, encoding)) } != null
        }.getOrDefault(false)
    }

    private suspend fun remember(handle: ExternalDocHandle) {
        val current = ExternalDocuments.parseRecents(settings.get(SettingKeys.ExternalRecents.name))
        settings.set(SettingKeys.ExternalRecents.name, ExternalDocuments.encodeRecents(ExternalDocuments.push(current, handle)))
    }

    suspend fun forget(token: String) {
        val current = ExternalDocuments.parseRecents(settings.get(SettingKeys.ExternalRecents.name))
        settings.set(SettingKeys.ExternalRecents.name, ExternalDocuments.encodeRecents(ExternalDocuments.drop(current, token)))
    }
}
