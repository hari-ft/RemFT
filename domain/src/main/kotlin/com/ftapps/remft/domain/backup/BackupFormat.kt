package com.ftapps.remft.domain.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import com.ftapps.remft.domain.time.UtcText

object BackupFormat {
    const val CURRENT = 1
    const val APP = "remft"
    const val MANIFEST_NAME = "manifest.json"
    const val DB_NAME = "remft.sqlite"
    const val SETTINGS_NAME = "settings.json"
    const val FILES_PREFIX = "files/"
    const val MAX_MANIFEST_BYTES = 1024L * 1024L
    const val MAX_SETTINGS_BYTES = 64L * 1024L
    const val ZIP_MIME = "application/zip"
}

object BackupKind {
    const val COMPLETE = "complete"
    const val MIRRORED = "mirrored"
    const val COMPLETE_PARTIAL = "complete-partial"
}

object BackupErrorCode {
    const val NOT_A_BACKUP = "not_a_backup"
    const val NEWER_VERSION = "newer_version"
    const val DAMAGED = "damaged"
    const val NOT_A_DATABASE = "not_a_database"

    val all: List<String> = listOf(NOT_A_BACKUP, NEWER_VERSION, DAMAGED, NOT_A_DATABASE)

    fun stringKey(code: String): String = "backup.error.$code"
}

class BackupException(val code: String, val detail: String? = null) : Exception(
    if (detail == null) "BackupException($code)" else "BackupException($code: $detail)",
)

data class MirrorRef(
    val itemId: String,
    val remotePath: String,
    val size: Long,
    val sha256: String,
)

data class BackupFileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val inZip: Boolean,
    val mirror: MirrorRef? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("path", path)
        put("size", size)
        put("sha256", sha256)
        put("inZip", inZip)
        val m = mirror
        if (m != null) {
            put(
                "mirror",
                buildJsonObject {
                    put("itemId", m.itemId)
                    put("path", m.remotePath)
                },
            )
        }
    }

    companion object {
        private val sha = Regex("^[0-9a-f]{64}$")

        fun fromJson(json: JsonElement?): BackupFileEntry? {
            if (json !is JsonObject) return null
            val path = JsonText.stringOf(json["path"])
            val size = JsonText.intOf(json["size"])
            val hash = JsonText.stringOf(json["sha256"])
            val inZip = JsonText.boolOf(json["inZip"])
            if (path == null || size == null || hash == null || inZip == null) return null
            if (size < 0 || !sha.matches(hash)) return null
            var mirror: MirrorRef? = null
            val rawMirror = json["mirror"]
            if (rawMirror is JsonObject && rawMirror.containsKey("itemId") && rawMirror.containsKey("path")) {
                val id = JsonText.stringOf(rawMirror["itemId"])
                val at = JsonText.stringOf(rawMirror["path"])
                if (id == null || at == null) return null
                mirror = MirrorRef(id, at, size, hash)
            }
            if (!inZip) {
                if (mirror == null || (mirror.itemId.isEmpty() && !mirror.remotePath.startsWith("Files/"))) return null
            }
            return BackupFileEntry(path, size, hash, inZip, mirror)
        }
    }
}

data class BackupManifest(
    val format: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Instant,
    val dbSha256: String,
    val dbBytes: Long,
    val fileCount: Int,
    val kind: String = BackupKind.COMPLETE,
    val files: List<BackupFileEntry> = emptyList(),
    val unavailable: List<String> = emptyList(),
) {
    val listedOnly: List<BackupFileEntry> get() = files.filter { !it.inZip }

    fun toJson(): JsonObject = buildJsonObject {
        put("app", BackupFormat.APP)
        put("format", format)
        put("appVersion", appVersion)
        put("schemaVersion", schemaVersion)
        put("createdAt", UtcText.format(createdAt))
        put("dbSha256", dbSha256)
        put("dbBytes", dbBytes)
        put("fileCount", fileCount)
        put("kind", kind)
        put("files", JsonArray(files.map { it.toJson() }))
        if (unavailable.isNotEmpty()) put("unavailable", JsonArray(unavailable.map { JsonPrimitive(it) }))
    }

    fun toPrettyJson(): String = JsonText.encodePretty(toJson())

    companion object {
        fun parse(text: String): BackupManifest {
            val json = JsonText.parse(text)
            if (json !is JsonObject) throw IllegalArgumentException("manifest")
            return fromJson(json)
        }

        fun fromJson(json: JsonObject): BackupManifest {
            if (JsonText.stringOf(json["app"]) != BackupFormat.APP) throw IllegalArgumentException("app")
            val rawFiles = json["files"]
            if (rawFiles !is JsonArray) throw IllegalArgumentException("files")
            val files = rawFiles.map { BackupFileEntry.fromJson(it) ?: throw IllegalArgumentException("file") }
            val rawUnavailable = json["unavailable"]
            val unavailable = ArrayList<String>()
            if (!JsonText.isNull(rawUnavailable)) {
                if (rawUnavailable !is JsonArray) throw IllegalArgumentException("unavailable")
                for (u in rawUnavailable) unavailable.add(JsonText.stringOf(u) ?: throw IllegalArgumentException("unavailable"))
            }
            val rawKind = json["kind"]
            val kind = if (JsonText.isNull(rawKind)) BackupKind.COMPLETE else JsonText.stringOf(rawKind) ?: throw IllegalArgumentException("kind")
            return BackupManifest(
                format = requiredInt(json, "format"),
                appVersion = requiredString(json, "appVersion"),
                schemaVersion = requiredInt(json, "schemaVersion"),
                createdAt = UtcText.parse(requiredString(json, "createdAt")) ?: throw IllegalArgumentException("createdAt"),
                dbSha256 = requiredString(json, "dbSha256"),
                dbBytes = JsonText.intOf(json["dbBytes"]) ?: throw IllegalArgumentException("dbBytes"),
                fileCount = requiredInt(json, "fileCount"),
                kind = kind,
                files = files,
                unavailable = unavailable,
            )
        }

        private fun requiredInt(json: JsonObject, key: String): Int {
            val value = JsonText.intOf(json[key]) ?: throw IllegalArgumentException(key)
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw IllegalArgumentException(key)
            return value.toInt()
        }

        private fun requiredString(json: JsonObject, key: String): String =
            JsonText.stringOf(json[key]) ?: throw IllegalArgumentException(key)
    }
}
