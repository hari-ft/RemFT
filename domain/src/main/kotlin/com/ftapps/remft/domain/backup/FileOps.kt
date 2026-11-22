package com.ftapps.remft.domain.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32

object FileOps {
    private const val BUFFER = 64 * 1024

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xf])
        }
        return out.toString()
    }

    fun copyToFile(input: InputStream, out: File) {
        FileOutputStream(out).use { sink -> input.copyTo(sink, BUFFER) }
    }

    fun rename(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun move(from: File, to: File) {
        try {
            rename(from, to)
            return
        } catch (_: IOException) {
        }
        if (from.isFile) {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            deleteFile(from)
            return
        }
        from.walkTopDown().filter { it.isFile }.forEach { entity ->
            val target = File(to, entity.relativeTo(from).path)
            target.parentFile?.mkdirs()
            Files.copy(entity.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        to.mkdirs()
        deleteTree(from)
    }

    fun fileExists(file: File): Boolean = file.isFile

    fun dirExists(dir: File): Boolean = dir.isDirectory

    fun deleteFileIfExists(file: File) {
        if (file.isFile) deleteFile(file)
    }

    fun deleteDirIfExists(dir: File) {
        if (dir.isDirectory) deleteTree(dir)
    }

    fun deleteFile(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    fun deleteTree(entity: File) {
        if (!entity.exists()) return
        if (!entity.deleteRecursively() && entity.exists()) throw IOException("Cannot delete ${entity.path}")
    }

    fun writeDurably(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    fun ensureDir(dir: File) {
        if (!dir.isDirectory) Files.createDirectories(dir.toPath())
    }
}
