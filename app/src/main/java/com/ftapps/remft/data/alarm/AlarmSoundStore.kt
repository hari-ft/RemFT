package com.ftapps.remft.data.alarm

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.R
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.db.entity.FolderEntity
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.ResolvedSound
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.time.UtcText
import java.io.File
import java.time.Clock

data class RingtoneFile(val id: String, val name: String)

class AlarmSoundStore(
    private val context: Context,
    private val database: () -> RemftDatabase,
    private val clock: Clock,
    private val filesDir: File = context.filesDir,
) {
    val soundsDir: File get() = File(filesDir, AlarmSounds.SOUNDS_DIRECTORY)
    private val attachmentsDir: File get() = File(filesDir, "files")

    fun installedNames(): List<String> = runCatching { soundsDir.listFiles()?.filter { it.isFile }?.map { it.name } }
        .getOrNull().orEmpty()

    fun resolve(choice: SoundChoice): ResolvedSound {
        val resolved = AlarmSounds.resolve(choice, installedNames())
        return resolved.copy(name = resolved.name?.let(::localAuthority))
    }

    fun installedFile(fileId: String): File? =
        AlarmSounds.installedNameFor(fileId, installedNames())?.let { File(soundsDir, it) }

    fun playbackUri(choice: SoundChoice): Uri? = when (choice) {
        SoundChoice.Os -> rawUri(AlarmSounds.inAppRawFor(choice))
        is SoundChoice.Bundled -> rawUri(AlarmSounds.inAppRawFor(choice))
        is SoundChoice.File -> installedFile(choice.fileId)?.let(Uri::fromFile)
    }

    fun previewUri(choice: SoundChoice): Uri? = when (choice) {
        SoundChoice.Os -> android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        else -> playbackUri(choice)
    }

    fun rawUri(name: String): Uri? = rawId(name)?.let { "android.resource://${context.packageName}/$it".toUri() }

    suspend fun ringtones(workspaceId: String): List<RingtoneFile> {
        val dao = database().alarmDao()
        val folder = dao.rootFolder(workspaceId, RINGTONE_FOLDER) ?: return emptyList()
        return dao.filesInFolder(folder.id).map { RingtoneFile(it.id, it.name) }
    }

    suspend fun ensureInstalled(fileId: String): Boolean {
        if (installedFile(fileId) != null) return true
        val row = database().alarmDao().fileRow(fileId) ?: return false
        val stored = row.localPath?.let { File(attachmentsDir, it) }?.takeIf { it.exists() } ?: return false
        return runCatching {
            soundsDir.mkdirs()
            stored.copyTo(File(soundsDir, "$fileId.${extensionOf(row.name)}"), overwrite = true)
            true
        }.getOrDefault(false)
    }

    suspend fun upload(workspaceId: String, source: Uri): String? {
        val resolver = context.contentResolver
        val (name, size) = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    (cursor.getString(0) ?: "sound") to (if (cursor.isNull(1)) -1L else cursor.getLong(1))
                } else {
                    null
                }
            } ?: ("sound" to -1L)
        if (size > MAX_BYTES) return null
        val id = Ulid.next(clock.millis())
        val ext = extensionOf(name)
        val storedName = "$id.$ext"
        attachmentsDir.mkdirs()
        soundsDir.mkdirs()
        val part = File(attachmentsDir, "$storedName.part")
        val stored = File(attachmentsDir, storedName)
        val written = runCatching {
            resolver.openInputStream(source)!!.use { input -> part.outputStream().use { input.copyTo(it) } }
            if (part.length() > MAX_BYTES) error("too large")
            if (!part.renameTo(stored)) error("rename failed")
            stored.copyTo(File(soundsDir, storedName), overwrite = true)
            stored.length()
        }.getOrElse {
            part.delete()
            stored.delete()
            return null
        }
        val stamp = UtcText.format(clock.instant())
        return try {
            val db = database()
            db.withTransaction {
                val dao = db.alarmDao()
                val folder = dao.rootFolder(workspaceId, RINGTONE_FOLDER) ?: FolderEntity(
                    id = Ulid.next(clock.millis()),
                    workspaceId = workspaceId,
                    name = RINGTONE_FOLDER,
                    createdAt = stamp,
                    updatedAt = stamp,
                ).also { dao.insertFolder(it) }
                dao.insertFileRow(
                    FileRowEntity(
                        id = id,
                        workspaceId = workspaceId,
                        targetType = "workspace",
                        targetId = workspaceId,
                        name = name,
                        mime = resolver.getType(source) ?: "audio/*",
                        sizeBytes = written,
                        folderId = folder.id,
                        nameFold = Fold.searchText(name),
                        localPath = storedName,
                        createdAt = stamp,
                        updatedAt = stamp,
                    ),
                )
            }
            id
        } catch (_: Exception) {
            stored.delete()
            File(soundsDir, storedName).delete()
            null
        }
    }

    companion object {
        const val RINGTONE_FOLDER = "Ringtones"
        const val MAX_BYTES = 100L * 1024 * 1024
        private val extPattern = Regex("^[a-z0-9]{1,10}$")

        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            val ext = if (dot < 0) "" else name.substring(dot + 1).lowercase()
            return if (extPattern.matches(ext)) ext else "m4a"
        }

        fun localAuthority(name: String): String {
            val prefix = "content://${AlarmSounds.SOUND_AUTHORITY}/"
            return if (name.startsWith(prefix)) "content://${BuildConfig.SOUNDS_AUTHORITY}/" + name.removePrefix(prefix) else name
        }

        fun rawId(name: String): Int? = when (name) {
            "remft_alarm" -> R.raw.remft_alarm
            "remft_chime" -> R.raw.remft_chime
            "remft_ping" -> R.raw.remft_ping
            else -> null
        }
    }
}
