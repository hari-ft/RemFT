package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupManifest
import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.backup.BackupValidator
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.SqliteHeader
import com.ftapps.remft.domain.backup.ValidatedBackup
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class OldAppArchive(val zip: ZipFile, val entries: Map<String, ZipEntry>, val duplicates: Set<String>)

object OldAppValidator {
    const val OLD_DB_FOLDER = "old-app"

    fun validate(
        archive: OldAppArchive,
        manifestText: String,
        currentSchema: Int,
        staging: File,
        opener: SqlOpener?,
        log: BackupLog,
    ): ValidatedBackup {
        val manifest = try {
            OldAppManifest.parse(manifestText)
        } catch (e: Exception) {
            throw BackupException(BackupErrorCode.NOT_A_BACKUP, e.toString())
        }
        log.info(
            "oldbackup.found",
            mapOf("format" to manifest.format, "schema" to manifest.schemaVersion, "kind" to manifest.kind, "appVersion" to manifest.appVersion),
        )
        if (manifest.schemaVersion != BackupFormat.OLD_APP_SCHEMA) {
            throw BackupException(BackupErrorCode.OLD_APP_SCHEMA, "schema ${manifest.schemaVersion}")
        }
        val dbEntry = archive.entries[BackupFormat.DB_NAME]
        if (dbEntry == null || dbEntry.isDirectory) throw BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, "no database entry")
        if (BackupFormat.DB_NAME in archive.duplicates) throw BackupException(BackupErrorCode.DAMAGED, "duplicate entry")
        if (manifest.format >= 3 && manifest.files == null) throw BackupException(BackupErrorCode.DAMAGED, "files")
        if (opener == null || currentSchema != RoomSchemaV1.VERSION) {
            throw BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, "no converter for schema $currentSchema")
        }
        if (dbEntry.size != manifest.dbBytes) throw BackupException(BackupErrorCode.DAMAGED)

        FileOps.ensureDir(staging)
        val settings = readSettings(archive, log)
        val oldDir = File(staging, OLD_DB_FOLDER)
        FileOps.ensureDir(oldDir)
        val oldDb = File(oldDir, BackupFormat.DB_NAME)
        BackupValidator.extract(archive.zip, dbEntry, oldDb)
        if (oldDb.length() != manifest.dbBytes || FileOps.sha256Hex(oldDb) != manifest.dbSha256) {
            throw BackupException(BackupErrorCode.DAMAGED)
        }
        val head = SqliteHeader.read(oldDb)
        if (!SqliteHeader.hasMagic(head)) throw BackupException(BackupErrorCode.NOT_A_DATABASE)
        if (SqliteHeader.userVersion(head) != manifest.schemaVersion.toLong()) throw BackupException(BackupErrorCode.DAMAGED)

        val files = manifest.files
        val listed = HashMap<String, BackupFileEntry>()
        for (f in files.orEmpty()) {
            if (!PathText.isSafeRelative(f.path) || listed.containsKey(f.path)) throw BackupException(BackupErrorCode.DAMAGED)
            listed[f.path] = f
        }
        val filesDir = File(staging, "files")
        val carried = ArrayList<BackupFileEntry>()
        for ((name, entry) in archive.entries) {
            if (entry.isDirectory || !name.startsWith(BackupFormat.FILES_PREFIX)) continue
            if (name in archive.duplicates) throw BackupException(BackupErrorCode.DAMAGED, "duplicate entry")
            val rel = name.substring(BackupFormat.FILES_PREFIX.length)
            if (!PathText.isSafeRelative(rel)) throw BackupException(BackupErrorCode.DAMAGED)
            val normal = PathText.posixNormalize(rel)
            val listing = listed[normal]
            if (files != null && (listing == null || !listing.inZip)) throw BackupException(BackupErrorCode.DAMAGED)
            val out = normal.split('/').fold(filesDir) { dir, part -> File(dir, part) }
            FileOps.ensureDir(out.parentFile)
            BackupValidator.extract(archive.zip, entry, out)
            if (entry.crc != -1L && FileOps.crc32(out) != entry.crc) throw BackupException(BackupErrorCode.DAMAGED)
            val size = out.length()
            val sha = FileOps.sha256Hex(out)
            if (listing != null && (size != listing.size || sha != listing.sha256)) throw BackupException(BackupErrorCode.DAMAGED)
            carried.add(listing ?: BackupFileEntry(normal, size, sha, inZip = true))
        }
        val inArchive = carried.map { it.path }.toSet()
        if (files != null) {
            if (manifest.fileCount != inArchive.size) throw BackupException(BackupErrorCode.DAMAGED)
            for (f in files) if (f.inZip && f.path !in inArchive) throw BackupException(BackupErrorCode.DAMAGED)
        }

        val newDb = File(staging, BackupFormat.DB_NAME)
        val conversion = try {
            OldDatabaseConverter(opener, log).convert(oldDb, newDb)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            log.error("oldbackup.convert_failed", e)
            throw BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, e.toString())
        }
        FileOps.deleteDirIfExists(oldDir)
        for (side in listOf("-wal", "-shm", "-journal")) FileOps.deleteFileIfExists(File(newDb.path + side))

        val entries = files ?: carried.sortedBy { it.path }
        val listedOnly = entries.count { !it.inZip }
        if (listedOnly > 0) log.info("oldbackup.listed_only", mapOf("files" to listedOnly))
        if (manifest.unavailable.isNotEmpty()) log.warn("oldbackup.files_unavailable", fields = mapOf("files" to manifest.unavailable.size))
        val converted = BackupManifest(
            format = BackupFormat.CURRENT,
            appVersion = manifest.appVersion,
            schemaVersion = currentSchema,
            createdAt = manifest.createdAt,
            dbSha256 = FileOps.sha256Hex(newDb),
            dbBytes = newDb.length(),
            fileCount = inArchive.size,
            kind = manifest.kind,
            files = entries,
            unavailable = manifest.unavailable,
        )
        val adjustments = LinkedHashMap(conversion.adjustments)
        for ((key, count) in settings.adjustments) adjustments[key] = (adjustments[key] ?: 0) + count
        val import = OldAppImport(
            appVersion = manifest.appVersion,
            format = manifest.format,
            counts = conversion.counts,
            listedOnly = listedOnly,
            unavailable = manifest.unavailable.size,
            adjustments = adjustments,
        )
        log.info("oldbackup.converted", mapOf<String, Any?>("format" to manifest.format, "files" to inArchive.size) + adjustments)
        return ValidatedBackup(converted, staging, settings.settings, import)
    }

    private fun readSettings(archive: OldAppArchive, log: BackupLog): OldAppSettings.Result {
        val none = OldAppSettings.Result(null, emptyMap())
        val entry = archive.entries[BackupFormat.SETTINGS_NAME] ?: return none
        if (entry.isDirectory || entry.size > BackupFormat.MAX_SETTINGS_BYTES) return none
        return try {
            val bytes = BackupValidator.readCapped(archive.zip, entry, BackupFormat.MAX_SETTINGS_BYTES) ?: return none
            val parsed = BackupSettings.parse(BackupValidator.strictUtf8(bytes)) ?: return none
            OldAppSettings.convert(parsed, log)
        } catch (e: Exception) {
            log.warn("backup.settings_unreadable", e)
            none
        }
    }
}
