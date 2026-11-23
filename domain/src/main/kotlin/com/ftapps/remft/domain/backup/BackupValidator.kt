package com.ftapps.remft.domain.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import com.ftapps.remft.domain.backup.oldapp.OldAppArchive
import com.ftapps.remft.domain.backup.oldapp.OldAppImport
import com.ftapps.remft.domain.backup.oldapp.OldAppManifest
import com.ftapps.remft.domain.backup.oldapp.OldAppValidator
import com.ftapps.remft.domain.backup.oldapp.SqlOpener

class ValidatedBackup internal constructor(
    val manifest: BackupManifest,
    val staging: File,
    val settings: Map<String, String>?,
    val oldApp: OldAppImport? = null,
) {
    val dbFile: File get() = File(staging, BackupFormat.DB_NAME)
    val filesDir: File get() = File(staging, "files")

    fun discard() {
        FileOps.deleteDirIfExists(staging)
    }
}

object BackupValidator {
    fun validate(
        zip: File,
        currentSchema: Int,
        scratch: File,
        log: BackupLog = BackupLog.None,
        clock: () -> Instant = Instant::now,
        oldApp: SqlOpener? = null,
    ): ValidatedBackup {
        val staging = newStaging(scratch, clock)
        var archive: ZipFile? = null
        try {
            val entries = LinkedHashMap<String, ZipEntry>()
            val duplicates = HashSet<String>()
            try {
                archive = ZipFile(zip)
                for (entry in archive.entries()) {
                    if (entries.containsKey(entry.name)) duplicates.add(entry.name) else entries[entry.name] = entry
                }
            } catch (e: Exception) {
                throw BackupException(BackupErrorCode.NOT_A_BACKUP, e.toString())
            }
            val opened = archive
            val manifestEntry = entries[BackupFormat.MANIFEST_NAME]
            if (manifestEntry == null || manifestEntry.isDirectory) throw BackupException(BackupErrorCode.NOT_A_BACKUP)
            if (BackupFormat.MANIFEST_NAME in duplicates) throw BackupException(BackupErrorCode.NOT_A_BACKUP, "duplicate entry")
            if (manifestEntry.size > BackupFormat.MAX_MANIFEST_BYTES) throw BackupException(BackupErrorCode.NOT_A_BACKUP)
            val manifestText = try {
                val bytes = readCapped(opened, manifestEntry, BackupFormat.MAX_MANIFEST_BYTES)
                    ?: throw IllegalArgumentException("manifest too large")
                strictUtf8(bytes)
            } catch (e: Exception) {
                throw BackupException(BackupErrorCode.NOT_A_BACKUP, e.toString())
            }
            if (OldAppManifest.isOldApp(manifestText)) {
                return OldAppValidator.validate(
                    OldAppArchive(opened, entries, duplicates),
                    manifestText,
                    currentSchema,
                    staging,
                    oldApp,
                    log,
                )
            }
            val dbName = BackupFormat.DB_NAME
            val dbEntry = entries[dbName]
            if (dbEntry == null || dbEntry.isDirectory) throw BackupException(BackupErrorCode.NOT_A_BACKUP)
            if (dbName in duplicates) throw BackupException(BackupErrorCode.NOT_A_BACKUP, "duplicate entry")
            val manifest = try {
                BackupManifest.parse(manifestText)
            } catch (e: Exception) {
                throw BackupException(BackupErrorCode.NOT_A_BACKUP, e.toString())
            }
            if (manifest.format < BackupFormat.CURRENT) throw BackupException(BackupErrorCode.NOT_A_BACKUP)
            if (manifest.format > BackupFormat.CURRENT || manifest.schemaVersion > currentSchema) {
                throw BackupException(BackupErrorCode.NEWER_VERSION)
            }
            if (manifest.schemaVersion != currentSchema) throw BackupException(BackupErrorCode.NOT_A_BACKUP)
            if (dbEntry.size != manifest.dbBytes) throw BackupException(BackupErrorCode.DAMAGED)

            FileOps.ensureDir(staging)
            var settings: Map<String, String>? = null
            val settingsEntry = entries[BackupFormat.SETTINGS_NAME]
            if (settingsEntry != null && !settingsEntry.isDirectory && settingsEntry.size <= BackupFormat.MAX_SETTINGS_BYTES) {
                try {
                    val bytes = readCapped(opened, settingsEntry, BackupFormat.MAX_SETTINGS_BYTES)
                    if (bytes != null) settings = BackupSettings.parse(strictUtf8(bytes))
                } catch (e: Exception) {
                    log.warn("backup.settings_unreadable", e)
                }
            }
            val backup = ValidatedBackup(manifest, staging, settings)
            val dbOut = backup.dbFile
            extract(opened, dbEntry, dbOut)
            if (dbOut.length() != manifest.dbBytes || FileOps.sha256Hex(dbOut) != manifest.dbSha256) {
                throw BackupException(BackupErrorCode.DAMAGED)
            }
            val head = SqliteHeader.read(dbOut)
            if (!SqliteHeader.hasMagic(head)) throw BackupException(BackupErrorCode.NOT_A_DATABASE)
            if (SqliteHeader.userVersion(head) != manifest.schemaVersion.toLong()) throw BackupException(BackupErrorCode.DAMAGED)

            val listed = HashMap<String, BackupFileEntry>()
            val files = manifest.files
            for (f in files) {
                if (!PathText.isSafeRelative(f.path) || listed.containsKey(f.path)) throw BackupException(BackupErrorCode.DAMAGED)
                listed[f.path] = f
            }

            val inArchive = HashSet<String>()
            for ((name, entry) in entries) {
                if (entry.isDirectory || !name.startsWith(BackupFormat.FILES_PREFIX)) continue
                if (name in duplicates) throw BackupException(BackupErrorCode.DAMAGED, "duplicate entry")
                val rel = name.substring(BackupFormat.FILES_PREFIX.length)
                if (!PathText.isSafeRelative(rel)) throw BackupException(BackupErrorCode.DAMAGED)
                val normal = PathText.posixNormalize(rel)
                val listing = listed[normal]
                if (listing == null || !listing.inZip) throw BackupException(BackupErrorCode.DAMAGED)
                val out = normal.split('/').fold(backup.filesDir) { dir, part -> File(dir, part) }
                FileOps.ensureDir(out.parentFile)
                extract(opened, entry, out)
                if (entry.crc != -1L && FileOps.crc32(out) != entry.crc) throw BackupException(BackupErrorCode.DAMAGED)
                if (out.length() != listing.size || FileOps.sha256Hex(out) != listing.sha256) {
                    throw BackupException(BackupErrorCode.DAMAGED)
                }
                inArchive.add(normal)
            }
            if (manifest.fileCount != inArchive.size) throw BackupException(BackupErrorCode.DAMAGED)
            for (f in files) {
                if (f.inZip && f.path !in inArchive) throw BackupException(BackupErrorCode.DAMAGED)
            }
            return backup
        } catch (e: Exception) {
            runCatching { FileOps.deleteDirIfExists(staging) }
            if (e is BackupException) throw e
            throw BackupException(BackupErrorCode.DAMAGED, e.toString())
        } finally {
            runCatching { archive?.close() }
        }
    }

    private fun newStaging(scratch: File, clock: () -> Instant): File {
        val now = clock()
        var micros = ChronoUnit.MICROS.between(Instant.EPOCH, now)
        var candidate = File(scratch, "restore-$micros")
        while (candidate.exists()) {
            micros++
            candidate = File(scratch, "restore-$micros")
        }
        return candidate
    }

    internal fun extract(archive: ZipFile, entry: ZipEntry, out: File) {
        archive.getInputStream(entry).use { FileOps.copyToFile(it, out) }
    }

    internal fun readCapped(archive: ZipFile, entry: ZipEntry, cap: Long): ByteArray? {
        archive.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    internal fun strictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
}
