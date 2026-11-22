package com.ftapps.remft.domain.backup.auto

import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.time.UtcText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

data class BackupDataSize(val tasks: Long, val notes: Long, val files: Long, val fileBytes: Long) {
    val items: Long get() = tasks + notes + files

    fun isBigDropFrom(previous: BackupDataSize): Boolean {
        val itemsDropped = previous.items >= MEANINGFUL_ITEMS && items * 2 < previous.items
        val bytesDropped = previous.fileBytes >= MEANINGFUL_BYTES && fileBytes * 2 < previous.fileBytes
        return itemsDropped || bytesDropped
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("tasks", tasks)
        put("notes", notes)
        put("files", files)
        put("fileBytes", fileBytes)
    }

    fun encode(): String = JsonText.encode(toJson())

    companion object {
        const val MEANINGFUL_ITEMS = 10L
        const val MEANINGFUL_BYTES = 1024L * 1024L

        fun fromJson(json: JsonElement?): BackupDataSize? {
            if (json !is JsonObject) return null
            fun n(key: String): Long? = JsonText.intOf(json[key]) ?: JsonText.numberOf(json[key])?.toLong()
            val tasks = n("tasks") ?: return null
            val notes = n("notes") ?: return null
            val files = n("files") ?: return null
            val bytes = n("fileBytes") ?: return null
            return BackupDataSize(tasks, notes, files, bytes)
        }

        fun parse(raw: String?): BackupDataSize? = raw?.let { JsonText.parseOrNull(it) }?.let { fromJson(it) }
    }
}

object PauseRule {
    fun shouldPause(current: BackupDataSize, previous: BackupDataSize?, dropAccepted: Boolean): Boolean =
        !dropAccepted && previous != null && current.isBigDropFrom(previous)

    fun dropAccepted(raw: String?): Boolean = raw == "true"

    const val ACCEPTED_VALUE = "true"
}

data class AutoBackupPause(val at: Instant, val previous: BackupDataSize, val current: BackupDataSize) {
    fun encode(): String = JsonText.encode(
        buildJsonObject {
            put("at", UtcText.format(at))
            put("previous", previous.toJson())
            put("current", current.toJson())
        },
    )

    companion object {
        fun parse(raw: String?): AutoBackupPause? {
            if (raw == null) return null
            val json = JsonText.parseOrNull(raw) as? JsonObject ?: return null
            val previous = BackupDataSize.fromJson(json["previous"]) ?: return null
            val current = BackupDataSize.fromJson(json["current"]) ?: return null
            val at = UtcText.parse(JsonText.stringOf(json["at"]) ?: return null) ?: return null
            return AutoBackupPause(at, previous, current)
        }
    }
}

data class DestinationStatus(val at: Instant, val ok: Boolean, val error: String? = null) {
    fun encode(): String = JsonText.encode(
        buildJsonObject {
            put("at", UtcText.format(at))
            put("ok", ok)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
        },
    )

    companion object {
        fun key(destination: String): String = "remft_auto_backup_status_$destination"

        fun parse(raw: String?): DestinationStatus? {
            if (raw == null) return null
            val json = JsonText.parseOrNull(raw) as? JsonObject ?: return null
            val at = UtcText.parse(JsonText.stringOf(json["at"]) ?: return null) ?: return null
            val rawError = json["error"]
            val error = if (JsonText.isNull(rawError)) null else JsonText.stringOf(rawError) ?: return null
            return DestinationStatus(at, JsonText.boolOf(json["ok"]) == true, error)
        }
    }
}
