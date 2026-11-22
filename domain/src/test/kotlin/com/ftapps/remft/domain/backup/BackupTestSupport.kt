package com.ftapps.remft.domain.backup

import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object BackupTestSupport {
    const val SCHEMA = 1
    val NOW: Instant = Instant.parse("2026-11-02T10:00:00Z")

    fun fakeDbBytes(schema: Int = SCHEMA, content: String = ""): ByteArray {
        val body = content.toByteArray(Charsets.UTF_8)
        val out = ByteArray(maxOf(128, 64 + body.size))
        SqliteHeader.MAGIC.copyInto(out, 0)
        out[60] = (schema ushr 24).toByte()
        out[61] = (schema ushr 16).toByte()
        out[62] = (schema ushr 8).toByte()
        out[63] = schema.toByte()
        body.copyInto(out, 64)
        return out
    }

    fun writeFakeDb(file: File, content: String, schema: Int = SCHEMA): File {
        file.parentFile?.mkdirs()
        file.writeBytes(fakeDbBytes(schema, content))
        return file
    }

    fun dbContent(file: File): String {
        val bytes = file.readBytes()
        return String(bytes, 64, bytes.size - 64, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun writeFile(dir: File, rel: String, bytes: ByteArray): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    fun buildZip(
        root: File,
        dbContent: String,
        filesDir: File? = null,
        out: File = File(root, "out-${System.nanoTime()}.zip"),
        listOnly: Map<String, MirrorRef> = emptyMap(),
        referenced: Set<String> = emptySet(),
        kind: String? = null,
        settings: Map<String, String>? = null,
        schema: Int = SCHEMA,
    ): BuiltBackup {
        val snapshot = writeFakeDb(File(root, "snapshot-${System.nanoTime()}.sqlite"), dbContent, schema)
        try {
            return BackupZipWriter.build(
                dbSnapshot = snapshot,
                out = out,
                appVersion = "9.9.9",
                schemaVersion = schema,
                referenced = referenced,
                filesDir = filesDir,
                now = NOW,
                listOnly = listOnly,
                kind = kind,
                settings = settings,
            )
        } finally {
            snapshot.delete()
        }
    }

    fun readEntries(zip: File): List<Pair<String, ByteArray>> = ZipFile(zip).use { z ->
        z.entries().toList().map { e -> e.name to z.getInputStream(e).use { it.readBytes() } }
    }

    fun writeZip(out: File, entries: List<Pair<String, ByteArray>>): File {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out
    }

    fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    fun rewrite(
        zip: File,
        out: File,
        manifestEdit: ((MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit)? = null,
        transform: (List<Pair<String, ByteArray>>) -> List<Pair<String, ByteArray>> = { it },
    ): File {
        val entries = readEntries(zip).map { (name, bytes) ->
            if (name == BackupFormat.MANIFEST_NAME && manifestEdit != null) {
                val json = (JsonText.parse(bytes.toString(Charsets.UTF_8)) as JsonObject).toMutableMap()
                manifestEdit(json)
                name to JsonText.encode(JsonObject(json)).toByteArray(Charsets.UTF_8)
            } else {
                name to bytes
            }
        }
        return writeZip(out, transform(entries))
    }

    fun patchCentralCrc(bytes: ByteArray, entryName: String, crc: Long): ByteArray {
        val out = bytes.copyOf()
        val name = entryName.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i + 46 <= out.size) {
            if (u32(out, i) == 0x02014b50L) {
                val nameLength = u16(out, i + 28)
                val extraLength = u16(out, i + 30)
                val commentLength = u16(out, i + 32)
                val entry = out.copyOfRange(i + 46, i + 46 + nameLength)
                if (entry.contentEquals(name)) {
                    for (k in 0 until 4) out[i + 16 + k] = (crc ushr (8 * k)).toByte()
                    return out
                }
                i += 46 + nameLength + extraLength + commentLength
            } else {
                i++
            }
        }
        error("entry not found: $entryName")
    }

    fun replaceAll(bytes: ByteArray, from: String, to: String): ByteArray {
        val a = from.toByteArray(Charsets.UTF_8)
        val b = to.toByteArray(Charsets.UTF_8)
        require(a.size == b.size)
        val out = bytes.copyOf()
        var i = 0
        while (i + a.size <= out.size) {
            if (out.copyOfRange(i, i + a.size).contentEquals(a)) {
                b.copyInto(out, i)
                i += a.size
            } else {
                i++
            }
        }
        return out
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
}
