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
    val category: AppLogCategory = AppLogCategory.of(event),
) {
    val message: String get() = AppLogText.message(this)

    fun format(): String = buildString {
        append(at.toString()).append(' ').append(level.name).append(' ').append(category.label).append(' ').append(event)
        append(": ").append(message)
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
    private val unwritten = ArrayDeque<AppLogEntry>()

    fun info(event: String, fields: Map<String, Any?> = emptyMap()) = add(AppLogLevel.INFO, event, fields, null)

    fun warn(event: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        add(AppLogLevel.WARN, event, fields, error)

    fun error(event: String, error: Throwable?, fields: Map<String, Any?> = emptyMap()) =
        add(AppLogLevel.ERROR, event, fields, error)

    fun add(
        level: AppLogLevel,
        event: String,
        fields: Map<String, Any?>,
        error: Throwable?,
        category: AppLogCategory? = null,
        at: Instant? = null,
    ) {
        try {
            val code = LogCode(event).safe
            val entry = AppLogEntry(
                at = at ?: clock.instant(),
                level = level,
                event = code,
                fields = fields.entries.associate { (k, v) -> LogCode(k).safe to redact(v) },
                errorType = error?.let { errorName(it) },
                category = category ?: AppLogCategory.of(code),
            )
            writer.execute { runCatching { write(entry) } }
        } catch (_: Throwable) {
        }
    }

    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    private val current: File get() = File(directory, CURRENT)
    private val previous: File get() = File(directory, PREVIOUS)

    private fun write(entry: AppLogEntry) = synchronized(lock) {
        unwritten.addLast(entry)
        while (unwritten.size > MAX_UNWRITTEN) unwritten.removeFirst()
        directory.mkdirs()
        expire()
        while (unwritten.isNotEmpty()) {
            append(unwritten.first())
            unwritten.removeFirst()
        }
    }

    private fun append(entry: AppLogEntry) {
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
            out.addAll(unwritten)
            out.reversed()
        }
    }

    fun clear() {
        flush()
        synchronized(lock) {
            unwritten.clear()
            current.delete()
            previous.delete()
        }
    }

    fun exportText(entries: List<AppLogEntry> = read()): String = entries.joinToString("\n") { it.format() }

    companion object {
        const val CURRENT = "app.log"
        const val PREVIOUS = "app.1.log"
        private const val MAX_UNWRITTEN = 256

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

        private fun errorName(error: Throwable): String = LogCode(error.javaClass.simpleName.ifEmpty { "Throwable" }).safe

        private fun encode(entry: AppLogEntry): String {
            val map = LinkedHashMap<String, JsonElement>()
            map["t"] = JsonPrimitive(entry.at.toString())
            map["l"] = JsonPrimitive(entry.level.name.lowercase())
            map["e"] = JsonPrimitive(entry.event)
            if (entry.fields.isNotEmpty()) map["f"] = JsonObject(entry.fields.mapValues { JsonPrimitive(it.value) })
            entry.errorType?.let { map["x"] = JsonPrimitive(it) }
            if (entry.category != AppLogCategory.of(entry.event)) map["c"] = JsonPrimitive(entry.category.name.lowercase())
            return JsonObject(map).toString()
        }

        private fun decode(line: String): AppLogEntry? = try {
            val obj = Json.parseToJsonElement(line).jsonObject
            val event = obj["e"]!!.jsonPrimitive.content
            AppLogEntry(
                at = Instant.parse(obj["t"]!!.jsonPrimitive.content),
                level = AppLogLevel.valueOf(obj["l"]!!.jsonPrimitive.content.uppercase()),
                event = event,
                fields = (obj["f"] as? JsonObject)?.mapValues { (_, v) -> primitiveText(v) }.orEmpty(),
                errorType = (obj["x"] as? JsonPrimitive)?.contentOrNull,
                category = (obj["c"] as? JsonPrimitive)?.contentOrNull?.let { AppLogCategory.parse(it) } ?: AppLogCategory.of(event),
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
