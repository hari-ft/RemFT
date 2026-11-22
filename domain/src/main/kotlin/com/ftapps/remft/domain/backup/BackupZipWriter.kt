package com.ftapps.remft.domain.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuiltBackup(val zip: File, val manifest: BackupManifest) {
    val unavailable: List<String> get() = manifest.unavailable
}

object BackupZipWriter {
    val dbSidecars: List<String> = listOf("-wal", "-shm", "-journal")

    fun build(
        dbSnapshot: File,
        out: File,
        appVersion: String,
        schemaVersion: Int,
        referenced: Set<String>,
        filesDir: File? = null,
        now: Instant = Instant.now(),
        listOnly: Map<String, MirrorRef> = emptyMap(),
        kind: String? = null,
        settings: Map<String, String>? = null,
        log: BackupLog = BackupLog.None,
        beforeEachFile: ((String) -> Unit)? = null,
    ): BuiltBackup {
        out.parentFile?.let { FileOps.ensureDir(it) }
        var stream: ZipOutputStream? = null
        try {
            for (side in dbSidecars) FileOps.deleteFile(File(dbSnapshot.path + side))
            val dbSha = FileOps.sha256Hex(dbSnapshot)
            val dbBytes = dbSnapshot.length()

            val zip = ZipOutputStream(FileOutputStream(out).buffered(64 * 1024))
            stream = zip
            var fileCount = 0
            val entries = ArrayList<BackupFileEntry>()
            val carried = HashSet<String>()
            if (filesDir != null && filesDir.isDirectory) {
                for (entity in listRecursively(filesDir)) {
                    val rel = entity.relativeTo(filesDir).path.replace('\\', '/')
                    if (listOnly.containsKey(rel)) continue
                    if (rel.endsWith(".part")) continue
                    beforeEachFile?.invoke(rel)
                    val written = addFile(zip, entity, BackupFormat.FILES_PREFIX + rel) ?: continue
                    entries.add(BackupFileEntry(rel, written.first, written.second, inZip = true))
                    carried.add(rel)
                    fileCount++
                }
            }
            for ((rel, ref) in listOnly) {
                entries.add(BackupFileEntry(rel, ref.size, ref.sha256, inZip = false, mirror = ref))
            }
            val unavailable = referenced.filter { it !in carried && !listOnly.containsKey(it) }.sorted()
            if (unavailable.isNotEmpty()) log.warn("backup.files_unavailable", fields = mapOf("files" to unavailable.size))
            val manifest = BackupManifest(
                format = BackupFormat.CURRENT,
                appVersion = appVersion,
                schemaVersion = schemaVersion,
                createdAt = now,
                dbSha256 = dbSha,
                dbBytes = dbBytes,
                fileCount = fileCount,
                kind = kind ?: if (listOnly.isEmpty()) BackupKind.COMPLETE else BackupKind.MIRRORED,
                files = entries,
                unavailable = unavailable,
            )
            addText(zip, BackupFormat.MANIFEST_NAME, manifest.toPrettyJson())
            if (settings != null) addText(zip, BackupFormat.SETTINGS_NAME, BackupSettings.encode(settings))
            zip.putNextEntry(ZipEntry(BackupFormat.DB_NAME).apply { time = now.toEpochMilli() })
            dbSnapshot.inputStream().use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
            zip.close()
            stream = null
            return BuiltBackup(out, manifest)
        } catch (e: Throwable) {
            runCatching { stream?.close() }
            runCatching { FileOps.deleteFile(out) }
            throw e
        }
    }

    private fun listRecursively(dir: File): List<File> {
        val out = ArrayList<File>()
        val pending = ArrayDeque<File>()
        pending.add(dir)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) pending.add(child) else if (child.isFile) out.add(child)
            }
        }
        return out
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String): Pair<Long, String>? {
        val input = try {
            file.inputStream()
        } catch (_: IOException) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        DigestInputStream(input, digest).use { stream ->
            zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
                size += read
            }
            zip.closeEntry()
        }
        return size to FileOps.hex(digest.digest())
    }

    private fun addText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
