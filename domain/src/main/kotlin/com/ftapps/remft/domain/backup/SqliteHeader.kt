package com.ftapps.remft.domain.backup

import java.io.File
import java.io.IOException

object SqliteHeader {
    const val SIZE = 64
    val MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    fun read(file: File): ByteArray {
        val head = ByteArray(SIZE)
        var filled = 0
        file.inputStream().use { input ->
            while (filled < SIZE) {
                val read = input.read(head, filled, SIZE - filled)
                if (read < 0) break
                filled += read
            }
        }
        return head.copyOf(filled)
    }

    fun hasMagic(head: ByteArray): Boolean {
        if (head.size < SIZE) return false
        for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return false
        return true
    }

    fun userVersion(head: ByteArray): Long {
        require(head.size >= SIZE) { "header too short" }
        return ((head[60].toLong() and 0xff) shl 24) or
            ((head[61].toLong() and 0xff) shl 16) or
            ((head[62].toLong() and 0xff) shl 8) or
            (head[63].toLong() and 0xff)
    }

    fun userVersionOf(file: File): Long? {
        val head = read(file)
        return if (hasMagic(head)) userVersion(head) else null
    }

    fun requireDatabase(file: File) {
        if (!file.isFile || !hasMagic(read(file))) throw IOException("not a database: ${file.name}")
    }
}
