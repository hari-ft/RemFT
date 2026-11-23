package com.ftapps.remft.domain.backup.oldapp

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

class JdbcSqlPort(file: File) : SqlPort {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}")
    private var depth = 0

    override fun exec(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.execute()
        }
    }

    override fun query(sql: String, args: List<Any?>, each: (Map<String, Any?>) -> Unit) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (i in 1..meta.columnCount) {
                        val value = rs.getObject(i)
                        row[meta.getColumnLabel(i)] = when (value) {
                            is Int -> value.toLong()
                            is Float -> value.toDouble()
                            else -> value
                        }
                    }
                    each(row)
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        connection.autoCommit = false
        depth++
        try {
            val out = block()
            connection.commit()
            return out
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            depth--
            connection.autoCommit = true
        }
    }

    override fun close() {
        connection.close()
    }

    private fun bind(statement: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { index, value ->
            val at = index + 1
            when (value) {
                null -> statement.setNull(at, Types.NULL)
                is Long -> statement.setLong(at, value)
                is Int -> statement.setLong(at, value.toLong())
                is Double -> statement.setDouble(at, value)
                is Boolean -> statement.setLong(at, if (value) 1L else 0L)
                is ByteArray -> statement.setBytes(at, value)
                else -> statement.setString(at, value.toString())
            }
        }
    }

    companion object {
        val opener: SqlOpener = SqlOpener { JdbcSqlPort(it) }
    }
}
