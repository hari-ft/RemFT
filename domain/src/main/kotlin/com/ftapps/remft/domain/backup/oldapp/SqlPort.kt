package com.ftapps.remft.domain.backup.oldapp

import java.io.File

interface SqlPort : AutoCloseable {
    fun exec(sql: String, args: List<Any?> = emptyList())

    fun query(sql: String, args: List<Any?> = emptyList(), each: (Map<String, Any?>) -> Unit)

    fun <T> transaction(block: () -> T): T

    fun queryLong(sql: String, args: List<Any?> = emptyList()): Long? {
        var out: Long? = null
        query(sql, args) { row -> if (out == null) out = (row.values.firstOrNull() as? Number)?.toLong() }
        return out
    }
}

fun interface SqlOpener {
    fun open(file: File): SqlPort
}
