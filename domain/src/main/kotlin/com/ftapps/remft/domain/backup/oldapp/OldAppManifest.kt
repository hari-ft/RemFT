package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupKind
import com.ftapps.remft.domain.backup.JsonText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class OldAppManifest(
    val format: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Instant,
    val dbSha256: String,
    val dbBytes: Long,
    val fileCount: Int,
    val kind: String,
    val files: List<BackupFileEntry>?,
    val unavailable: List<String>,
) {
    companion object {
        fun isOldApp(text: String): Boolean {
            val json = JsonText.parseOrNull(text) as? JsonObject ?: return false
            val app = json["app"]
            if (!JsonText.isNull(app) && JsonText.stringOf(app) != BackupFormat.APP) return false
            val format = JsonText.intOf(json["format"]) ?: return false
            return format in BackupFormat.OLD_APP_FORMATS.map { it.toLong() }
        }

        fun parse(text: String): OldAppManifest {
            val json = JsonText.parse(text) as? JsonObject ?: throw IllegalArgumentException("manifest")
            val rawFiles = json["files"]
            val files = if (JsonText.isNull(rawFiles)) {
                null
            } else {
                if (rawFiles !is JsonArray) throw IllegalArgumentException("files")
                rawFiles.map { BackupFileEntry.fromJson(it) ?: throw IllegalArgumentException("file") }
            }
            val rawUnavailable = json["unavailable"]
            val unavailable = ArrayList<String>()
            if (!JsonText.isNull(rawUnavailable)) {
                if (rawUnavailable !is JsonArray) throw IllegalArgumentException("unavailable")
                for (u in rawUnavailable) unavailable.add(JsonText.stringOf(u) ?: throw IllegalArgumentException("unavailable"))
            }
            val rawKind = json["kind"]
            val kind = if (JsonText.isNull(rawKind)) BackupKind.COMPLETE else JsonText.stringOf(rawKind) ?: throw IllegalArgumentException("kind")
            val createdText = JsonText.stringOf(json["createdAt"]) ?: throw IllegalArgumentException("createdAt")
            return OldAppManifest(
                format = int(json, "format"),
                appVersion = JsonText.stringOf(json["appVersion"]) ?: throw IllegalArgumentException("appVersion"),
                schemaVersion = int(json, "schemaVersion"),
                createdAt = OldDates.fromText(createdText, numbersAreMillis = true) ?: throw IllegalArgumentException("createdAt"),
                dbSha256 = JsonText.stringOf(json["dbSha256"]) ?: throw IllegalArgumentException("dbSha256"),
                dbBytes = JsonText.intOf(json["dbBytes"]) ?: throw IllegalArgumentException("dbBytes"),
                fileCount = int(json, "fileCount"),
                kind = kind,
                files = files,
                unavailable = unavailable,
            )
        }

        private fun int(json: JsonObject, key: String): Int {
            val value = JsonText.intOf(json[key]) ?: throw IllegalArgumentException(key)
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw IllegalArgumentException(key)
            return value.toInt()
        }
    }
}

data class OldAppCounts(
    val tasks: Int,
    val notes: Int,
    val files: Int,
    val folders: Int,
    val series: Int,
    val reminders: Int,
    val binEntries: Int,
)

data class OldAppImport(
    val appVersion: String,
    val format: Int,
    val counts: OldAppCounts,
    val listedOnly: Int,
    val unavailable: Int,
    val adjustments: Map<String, Int>,
)
