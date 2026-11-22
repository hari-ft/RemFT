package com.ftapps.remft.domain.backup.bin

import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.text.Whitespace
import com.ftapps.remft.domain.time.UtcText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

data class BinTask(
    val id: String,
    val workspaceId: String,
    val title: String,
    val description: String? = null,
    val status: String = "open",
    val priority: String = "none",
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val alarmsMutedAt: Instant? = null,
    val timezone: String = "Asia/Kolkata",
    val isUrgent: Boolean = false,
    val requiresAcknowledgement: Boolean = false,
    val seriesId: String? = null,
    val occurrenceDate: String? = null,
    val spawnedFromTaskId: String? = null,
    val sortOrder: Long = 0,
    val completedAt: Instant? = null,
    val titleFold: String? = null,
    val descriptionFold: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("workspaceId", workspaceId); str("title", title); str("description", description)
        str("status", status); str("priority", priority); date("dueAt", dueAt); date("remindAt", remindAt)
        date("snoozedUntil", snoozedUntil); date("alarmsMutedAt", alarmsMutedAt); str("timezone", timezone)
        bool("isUrgent", isUrgent); bool("requiresAcknowledgement", requiresAcknowledgement); str("seriesId", seriesId)
        str("occurrenceDate", occurrenceDate); str("spawnedFromTaskId", spawnedFromTaskId); int("sortOrder", sortOrder)
        date("completedAt", completedAt); str("titleFold", titleFold); str("descriptionFold", descriptionFold)
        date("createdAt", createdAt); date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinTask = BinTask(
            id = j.reqStr("id"), workspaceId = j.reqStr("workspaceId"), title = j.reqStr("title"),
            description = j.optStr("description"), status = j.reqStr("status"), priority = j.reqStr("priority"),
            dueAt = j.optDate("dueAt"), remindAt = j.optDate("remindAt"), snoozedUntil = j.optDate("snoozedUntil"),
            alarmsMutedAt = j.optDate("alarmsMutedAt"), timezone = j.reqStr("timezone"), isUrgent = j.reqBool("isUrgent"),
            requiresAcknowledgement = j.reqBool("requiresAcknowledgement"), seriesId = j.optStr("seriesId"),
            occurrenceDate = j.optStr("occurrenceDate"), spawnedFromTaskId = j.optStr("spawnedFromTaskId"),
            sortOrder = j.reqInt("sortOrder"), completedAt = j.optDate("completedAt"), titleFold = j.optStr("titleFold"),
            descriptionFold = j.optStr("descriptionFold"), createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinChecklistItem(
    val id: String,
    val taskId: String,
    val title: String,
    val isDone: Boolean = false,
    val sortOrder: Long = 0,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("taskId", taskId); str("title", title); bool("isDone", isDone); int("sortOrder", sortOrder)
        date("createdAt", createdAt); date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinChecklistItem = BinChecklistItem(
            id = j.reqStr("id"), taskId = j.reqStr("taskId"), title = j.reqStr("title"), isDone = j.reqBool("isDone"),
            sortOrder = j.reqInt("sortOrder"), createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinReminder(
    val id: String,
    val taskId: String,
    val kind: String,
    val snoozeCount: Long = 0,
    val remindAt: Instant,
    val alarmLevel: String = "normal",
    val requiresAcknowledgement: Boolean = false,
    val status: String = "scheduled",
    val snoozedUntil: Instant? = null,
    val acknowledgedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("taskId", taskId); str("kind", kind); int("snoozeCount", snoozeCount); date("remindAt", remindAt)
        str("alarmLevel", alarmLevel); bool("requiresAcknowledgement", requiresAcknowledgement); str("status", status)
        date("snoozedUntil", snoozedUntil); date("acknowledgedAt", acknowledgedAt); date("createdAt", createdAt)
        date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinReminder = BinReminder(
            id = j.reqStr("id"), taskId = j.reqStr("taskId"), kind = j.reqStr("kind"), snoozeCount = j.reqInt("snoozeCount"),
            remindAt = j.reqDate("remindAt"), alarmLevel = j.reqStr("alarmLevel"),
            requiresAcknowledgement = j.reqBool("requiresAcknowledgement"), status = j.reqStr("status"),
            snoozedUntil = j.optDate("snoozedUntil"), acknowledgedAt = j.optDate("acknowledgedAt"),
            createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinNote(
    val id: String,
    val workspaceId: String,
    val title: String,
    val contentMarkdown: String? = null,
    val plainText: String? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val titleFold: String? = null,
    val bodyFold: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("workspaceId", workspaceId); str("title", title); str("contentMarkdown", contentMarkdown)
        str("plainText", plainText); bool("isPinned", isPinned); bool("isArchived", isArchived); str("titleFold", titleFold)
        str("bodyFold", bodyFold); date("createdAt", createdAt); date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinNote = BinNote(
            id = j.reqStr("id"), workspaceId = j.reqStr("workspaceId"), title = j.reqStr("title"),
            contentMarkdown = j.optStr("contentMarkdown"), plainText = j.optStr("plainText"), isPinned = j.reqBool("isPinned"),
            isArchived = j.reqBool("isArchived"), titleFold = j.optStr("titleFold"), bodyFold = j.optStr("bodyFold"),
            createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinNoteLink(val id: String, val noteId: String, val entityType: String, val entityId: String) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("noteId", noteId); str("entityType", entityType); str("entityId", entityId)
    }

    companion object {
        fun fromJson(j: JsonObject): BinNoteLink =
            BinNoteLink(j.reqStr("id"), j.reqStr("noteId"), j.reqStr("entityType"), j.reqStr("entityId"))
    }
}

data class BinFile(
    val id: String,
    val workspaceId: String,
    val targetType: String,
    val targetId: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val folderId: String? = null,
    val nameFold: String? = null,
    val localPath: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("workspaceId", workspaceId); str("targetType", targetType); str("targetId", targetId)
        str("name", name); str("mime", mime); int("sizeBytes", sizeBytes); str("folderId", folderId); str("nameFold", nameFold)
        str("localPath", localPath); date("createdAt", createdAt); date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinFile = BinFile(
            id = j.reqStr("id"), workspaceId = j.reqStr("workspaceId"), targetType = j.reqStr("targetType"),
            targetId = j.reqStr("targetId"), name = j.reqStr("name"), mime = j.reqStr("mime"), sizeBytes = j.reqInt("sizeBytes"),
            folderId = j.optStr("folderId"), nameFold = j.optStr("nameFold"), localPath = j.optStr("localPath"),
            createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinFolder(
    val id: String,
    val workspaceId: String,
    val parentId: String? = null,
    val name: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("workspaceId", workspaceId); str("parentId", parentId); str("name", name)
        date("createdAt", createdAt); date("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinFolder = BinFolder(
            id = j.reqStr("id"), workspaceId = j.reqStr("workspaceId"), parentId = j.optStr("parentId"), name = j.reqStr("name"),
            createdAt = j.optDate("createdAt"), updatedAt = j.optDate("updatedAt"),
        )
    }
}

data class BinSeries(
    val id: String,
    val workspaceId: String,
    val ruleJson: String,
    val templateJson: String,
    val timezone: String,
    val anchorAt: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        str("id", id); str("workspaceId", workspaceId); str("ruleJson", ruleJson); str("templateJson", templateJson)
        str("timezone", timezone); str("anchorAt", anchorAt); str("createdAt", createdAt); str("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinSeries = BinSeries(
            id = j.reqStr("id"), workspaceId = j.reqStr("workspaceId"), ruleJson = j.reqStr("ruleJson"),
            templateJson = j.reqStr("templateJson"), timezone = j.reqStr("timezone"), anchorAt = j.reqStr("anchorAt"),
            createdAt = j.optStr("createdAt"), updatedAt = j.optStr("updatedAt"),
        )
    }
}

data class BinSeriesSkip(val seriesId: String, val occurrenceDate: String, val createdAt: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        str("seriesId", seriesId); str("occurrenceDate", occurrenceDate); str("createdAt", createdAt)
    }

    companion object {
        fun fromJson(j: JsonObject): BinSeriesSkip =
            BinSeriesSkip(j.reqStr("seriesId"), j.reqStr("occurrenceDate"), j.optStr("createdAt"))
    }
}

data class BinPayload(
    val tasks: List<BinTask> = emptyList(),
    val checklist: List<BinChecklistItem> = emptyList(),
    val reminders: List<BinReminder> = emptyList(),
    val notes: List<BinNote> = emptyList(),
    val noteLinks: List<BinNoteLink> = emptyList(),
    val files: List<BinFile> = emptyList(),
    val folders: List<BinFolder> = emptyList(),
    val series: List<BinSeries> = emptyList(),
    val seriesSkips: List<BinSeriesSkip> = emptyList(),
) {
    val localPaths: List<String> get() = files.mapNotNull { it.localPath }

    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    fun localPathsJson(): String = JsonText.encode(JsonArray(localPaths.map { JsonPrimitive(it) }))

    fun toJson(): JsonObject = buildJsonObject {
        put("tasks", JsonArray(tasks.map { it.toJson() }))
        put("checklist", JsonArray(checklist.map { it.toJson() }))
        put("reminders", JsonArray(reminders.map { it.toJson() }))
        put("notes", JsonArray(notes.map { it.toJson() }))
        put("noteLinks", JsonArray(noteLinks.map { it.toJson() }))
        put("files", JsonArray(files.map { it.toJson() }))
        put("folders", JsonArray(folders.map { it.toJson() }))
        if (series.isNotEmpty()) put("series", JsonArray(series.map { it.toJson() }))
        if (seriesSkips.isNotEmpty()) put("seriesSkips", JsonArray(seriesSkips.map { it.toJson() }))
    }

    fun encode(): String = JsonText.encode(toJson())

    companion object {
        fun parse(raw: String): BinPayload {
            val json = JsonText.parse(raw) as? JsonObject ?: throw IllegalArgumentException("payload")
            return fromJson(json)
        }

        fun fromJson(json: JsonObject): BinPayload {
            fun list(key: String): List<JsonObject> {
                val raw = json[key]
                if (JsonText.isNull(raw)) return emptyList()
                if (raw !is JsonArray) throw IllegalArgumentException(key)
                return raw.map { it as? JsonObject ?: throw IllegalArgumentException(key) }
            }
            return BinPayload(
                tasks = list("tasks").map { BinTask.fromJson(it) },
                checklist = list("checklist").map { BinChecklistItem.fromJson(it) },
                reminders = list("reminders").map { BinReminder.fromJson(it) },
                notes = list("notes").map { BinNote.fromJson(it) },
                noteLinks = list("noteLinks").map { BinNoteLink.fromJson(it) },
                files = list("files").map { BinFile.fromJson(it) },
                folders = list("folders").map { BinFolder.fromJson(it) },
                series = list("series").map { BinSeries.fromJson(it) },
                seriesSkips = list("seriesSkips").map { BinSeriesSkip.fromJson(it) },
            )
        }

        fun entryIds(entityId: String, payload: String): Set<String> = try {
            val p = parse(payload)
            buildSet {
                add(entityId)
                p.tasks.forEach { add(it.id) }
                p.notes.forEach { add(it.id) }
                p.files.forEach { add(it.id) }
                p.folders.forEach { add(it.id) }
            }
        } catch (_: Exception) {
            setOf(entityId)
        }

        fun parseLocalPaths(raw: String): List<String> {
            val json = JsonText.parse(raw) as? JsonArray ?: throw IllegalArgumentException("localPaths")
            return json.map { JsonText.stringOf(it) ?: throw IllegalArgumentException("localPaths") }
        }

        fun freeFolderName(name: String, taken: Iterable<String>): String {
            val folded = taken.map { Fold.searchText(Whitespace.trim(it)) }.toSet()
            val base = Whitespace.trim(name)
            if (Fold.searchText(base) !in folded) return base
            var n = 2
            while (true) {
                val candidate = "$base ($n)"
                if (Fold.searchText(candidate) !in folded) return candidate
                n++
            }
        }
    }
}

object BinSettings {
    val DAY_CHOICES: List<Int> = listOf(7, 14, 30, 60, 90)
    const val DEFAULT_DAYS = 30

    data class Values(val enabled: Boolean, val autoDelete: Boolean, val days: Int)

    fun of(enabled: String?, autoDelete: String?, days: String?): Values {
        val n = days?.toIntOrNull()
        return Values(enabled != "false", autoDelete != "false", if (n != null && n in DAY_CHOICES) n else DEFAULT_DAYS)
    }
}

private fun JsonObjectBuilder.str(key: String, value: String?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

private fun JsonObjectBuilder.bool(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.int(key: String, value: Long) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.date(key: String, value: Instant?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(UtcText.format(value)))
}

private fun JsonObject.reqStr(key: String): String = JsonText.stringOf(this[key]) ?: throw IllegalArgumentException(key)

private fun JsonObject.optStr(key: String): String? {
    val raw = this[key]
    if (JsonText.isNull(raw)) return null
    return JsonText.stringOf(raw) ?: throw IllegalArgumentException(key)
}

private fun JsonObject.reqBool(key: String): Boolean = JsonText.boolOf(this[key]) ?: throw IllegalArgumentException(key)

private fun JsonObject.reqInt(key: String): Long =
    JsonText.intOf(this[key]) ?: JsonText.numberOf(this[key])?.toLong() ?: throw IllegalArgumentException(key)

private fun JsonObject.reqDate(key: String): Instant = optDate(key) ?: throw IllegalArgumentException(key)

private fun JsonObject.optDate(key: String): Instant? = binDate(this[key])

internal fun binDate(raw: JsonElement?): Instant? {
    if (JsonText.isNull(raw)) return null
    val text = JsonText.stringOf(raw) ?: throw IllegalArgumentException("date")
    return UtcText.parse(text) ?: throw IllegalArgumentException("date")
}
