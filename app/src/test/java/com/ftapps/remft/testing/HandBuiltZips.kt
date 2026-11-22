package com.ftapps.remft.testing

import com.ftapps.remft.domain.backup.FileOps
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object HandBuiltZips {
    fun write(out: File, db: File, files: Map<String, ByteArray>, settings: String?, createdAt: String = "2026-11-02T10:15:30.123Z"): File {
        val fileEntries = files.entries.joinToString(",\n") { (name, bytes) ->
            "    {\n      \"path\": \"$name\",\n      \"size\": ${bytes.size},\n      \"sha256\": \"${FileOps.sha256Hex(bytes)}\",\n      \"inZip\": true\n    }"
        }
        val manifest = "{\n" +
            "  \"app\": \"remft\",\n" +
            "  \"format\": 1,\n" +
            "  \"appVersion\": \"1.1.1\",\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"createdAt\": \"$createdAt\",\n" +
            "  \"dbSha256\": \"${FileOps.sha256Hex(db)}\",\n" +
            "  \"dbBytes\": ${db.length()},\n" +
            "  \"fileCount\": ${files.size},\n" +
            "  \"kind\": \"complete\",\n" +
            "  \"files\": [\n$fileEntries\n  ]\n" +
            "}"
        ZipOutputStream(out.outputStream()).use { zip ->
            var stored = true
            for ((name, bytes) in files) {
                zip.putNextEntry(entry("files/$name", bytes, stored))
                zip.write(bytes)
                zip.closeEntry()
                stored = !stored
            }
            val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
            zip.putNextEntry(entry("manifest.json", manifestBytes, stored = false))
            zip.write(manifestBytes)
            zip.closeEntry()
            if (settings != null) {
                val settingsBytes = settings.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(entry("settings.json", settingsBytes, stored = true))
                zip.write(settingsBytes)
                zip.closeEntry()
            }
            val dbBytes = db.readBytes()
            zip.putNextEntry(entry("remft.sqlite", dbBytes, stored = false))
            zip.write(dbBytes)
            zip.closeEntry()
        }
        return out
    }

    private fun entry(name: String, bytes: ByteArray, stored: Boolean): ZipEntry = ZipEntry(name).apply {
        if (stored) {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        }
    }
}
