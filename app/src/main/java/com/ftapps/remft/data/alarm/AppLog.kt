package com.ftapps.remft.data.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

enum class AppLogLevel { INFO, WARN, ERROR }

data class LogCode(val value: String) {
    val safe: String get() = if (token.matches(value)) value else REDACTED

    companion object {
        const val REDACTED = "<text>"
        private val token = Regex("^[A-Za-z0-9_.:+\\-]{1,48}$")
    }
}

data class AppLogEntry(
    val at: Instant,
    val level: AppLogLevel,
    val event: String,
    val fields: Map<String, String> = emptyMap(),
    val errorType: String? = null,
) {
    fun format(): String = buildString {
        append(at.toString()).append(' ').append(level.name).append(' ').append(event)
        fields.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        errorType?.let { append(" error=").append(it) }
    }
}

class AppLog(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val maxBytes: Long = 192L * 1024,
    private val maxAge: Duration = Duration.ofDays(7),
) {
    private val writer = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "remft-app-log").apply { isDaemon = true } }
    private val lock = Any()

    fun info(event: String, fields: Map<String, Any?> = emptyMap()) = add(AppLogLevel.INFO, event, fields, null)

    fun warn(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        add(AppLogLevel.WARN, event, fields, error)

    fun error(event: String, error: Throwable?, fields: Map<String, Any?> = emptyMap()) =
        add(AppLogLevel.ERROR, event, fields, error)

    private fun add(level: AppLogLevel, event: String, fields: Map<String, Any?>, error: Throwable?) {
        val entry = AppLogEntry(
            at = clock.instant(),
            level = level,
            event = LogCode(event).safe,
            fields = fields.entries.associate { (k, v) -> LogCode(k).safe to redact(v) },
            errorType = error?.javaClass?.simpleName,
        )
        writer.execute { runCatching { write(entry) } }
    }

    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    private val current: File get() = File(directory, CURRENT)
    private val previous: File get() = File(directory, PREVIOUS)

    private fun write(entry: AppLogEntry) = synchronized(lock) {
        directory.mkdirs()
        expire()
        val line = encode(entry) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (current.exists() && current.length() + bytes.size > maxBytes) {
            previous.delete()
            current.renameTo(previous)
        }
        current.appendBytes(bytes)
    }

    private fun expire() {
        val cutoff = clock.millis() - maxAge.toMillis()
        for (file in listOf(previous, current)) {
            if (file.exists() && file.lastModified() < cutoff) file.delete()
        }
    }

    fun read(): List<AppLogEntry> {
        flush()
        return synchronized(lock) {
            val cutoff = clock.instant().minus(maxAge)
            val out = ArrayList<AppLogEntry>()
            for (file in listOf(previous, current)) {
                if (!file.exists()) continue
                runCatching { file.readLines() }.getOrDefault(emptyList()).forEach { line ->
                    decode(line)?.takeIf { it.at.isAfter(cutoff) }?.let(out::add)
                }
            }
            out.reversed()
        }
    }

    fun clear() {
        flush()
        synchronized(lock) {
            current.delete()
            previous.delete()
        }
    }

    fun exportText(entries: List<AppLogEntry> = read()): String = entries.joinToString("\n") { it.format() }

    companion object {
        const val CURRENT = "app.log"
        const val PREVIOUS = "app.1.log"

        fun redact(value: Any?): String = when (value) {
            null -> "null"
            is Boolean, is Int, is Long, is Short, is Byte, is Double, is Float -> value.toString()
            is Instant -> value.toString()
            is Duration -> value.toMillis().toString()
            is Enum<*> -> value.name
            is LogCode -> value.safe
            else -> LogCode.REDACTED
        }

        fun filter(entries: List<AppLogEntry>, min: AppLogLevel): List<AppLogEntry> = entries.filter { it.level >= min }

        private fun encode(entry: AppLogEntry): String {
            val map = LinkedHashMap<String, JsonElement>()
            map["t"] = JsonPrimitive(entry.at.toString())
            map["l"] = JsonPrimitive(entry.level.name.lowercase())
            map["e"] = JsonPrimitive(entry.event)
            if (entry.fields.isNotEmpty()) map["f"] = JsonObject(entry.fields.mapValues { JsonPrimitive(it.value) })
            entry.errorType?.let { map["x"] = JsonPrimitive(it) }
            return JsonObject(map).toString()
        }

        private fun decode(line: String): AppLogEntry? = try {
            val obj = Json.parseToJsonElement(line).jsonObject
            AppLogEntry(
                at = Instant.parse(obj["t"]!!.jsonPrimitive.content),
                level = AppLogLevel.valueOf(obj["l"]!!.jsonPrimitive.content.uppercase()),
                event = obj["e"]!!.jsonPrimitive.content,
                fields = (obj["f"] as? JsonObject)?.mapValues { (_, v) -> primitiveText(v) }.orEmpty(),
                errorType = (obj["x"] as? JsonPrimitive)?.contentOrNull,
            )
        } catch (_: Exception) {
            null
        }

        private fun primitiveText(element: JsonElement): String = when (element) {
            is JsonNull -> "null"
            is JsonPrimitive -> element.booleanOrNull?.toString() ?: element.longOrNull?.toString() ?: element.content
            else -> element.toString()
        }
    }
}
