package com.ftapps.remft.domain.backup.restore

import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.MirrorRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

const val RESTORE_JOURNAL_NAME = "restore.journal"

enum class RestorePhase(val wire: String) {
    Staged("staged"),
    SwappingDb("swapping-db"),
    SwappingFiles("swapping-files"),
    Done("done"),
}

enum class RestoreRecovery {
    Nothing,
    DiscardedStaging,
    RolledBack,
    Finished,
    RestoredWithoutJournal;

    val tellUser: Boolean get() = this == RolledBack || this == RestoredWithoutJournal

    val wire: String get() = name.replaceFirstChar { it.lowercase() }
}

class SimulatedProcessKill(val step: String) : RuntimeException("SimulatedProcessKill($step)")

class RestorePaths(val dbFile: File, val filesDir: File) {
    val incoming: File = File(dbFile.path + ".restore")
    val previous: File = File(dbFile.path + ".bak")
    val sidecars: List<File> = listOf(File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
    val filesIncoming: File = File(filesDir.path + ".restore")
    val filesPrevious: File = File(filesDir.path + ".bak")
    val journal: File = File(dbFile.absoluteFile.parentFile, RESTORE_JOURNAL_NAME)
    val journalTmp: File = File(dbFile.absoluteFile.parentFile, "$RESTORE_JOURNAL_NAME.tmp")

    fun parked(sidecar: File): File = File(sidecar.path + ".bak")
}

data class RestoreKeep(val path: String, val size: Long, val sha256: String, val mirror: MirrorRef? = null) {
    val entry: BackupFileEntry get() = BackupFileEntry(path, size, sha256, inZip = false, mirror = mirror)

    fun toJson(): JsonObject = buildJsonObject {
        put("path", path)
        put("size", size)
        put("sha256", sha256)
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
        fun of(f: BackupFileEntry): RestoreKeep = RestoreKeep(f.path, f.size, f.sha256, f.mirror)

        fun fromJson(json: JsonElement?): RestoreKeep? {
            if (json !is JsonObject) return null
            val path = JsonText.stringOf(json["path"]) ?: return null
            val size = JsonText.intOf(json["size"]) ?: return null
            val sha = JsonText.stringOf(json["sha256"]) ?: return null
            if (!PathText.isSafeRelative(path)) return null
            var mirror: MirrorRef? = null
            val raw = json["mirror"]
            if (raw is JsonObject) {
                val id = JsonText.stringOf(raw["itemId"])
                val at = JsonText.stringOf(raw["path"])
                if (id != null && at != null) mirror = MirrorRef(id, at, size, sha)
            }
            return RestoreKeep(path, size, sha, mirror)
        }
    }
}

data class RestoreJournal(
    val phase: RestorePhase,
    val hadDb: Boolean,
    val hadFiles: Boolean,
    val keep: List<RestoreKeep> = emptyList(),
    val settled: Boolean = false,
) {
    fun at(next: RestorePhase): RestoreJournal = copy(phase = next)

    fun settledNow(): RestoreJournal = copy(settled = true)

    fun toJson(): JsonObject = buildJsonObject {
        put("phase", phase.wire)
        put("hadDb", hadDb)
        put("hadFiles", hadFiles)
        if (keep.isNotEmpty()) put("keep", JsonArray(keep.map { it.toJson() }))
        if (settled) put("settled", true)
    }

    fun encode(): String = JsonText.encode(toJson())

    companion object {
        val careful = RestoreJournal(RestorePhase.SwappingFiles, hadDb = true, hadFiles = true)

        fun parse(raw: String): RestoreJournal {
            return try {
                val json = JsonText.parse(raw) as JsonObject
                val wire = JsonText.stringOf(json["phase"]) ?: return careful
                val phase = RestorePhase.entries.firstOrNull { it.wire == wire } ?: return careful
                val hadDb = JsonText.boolOf(json["hadDb"]) ?: return careful
                val hadFiles = JsonText.boolOf(json["hadFiles"]) ?: return careful
                val rawKeep = json["keep"]
                val keep = when {
                    JsonText.isNull(rawKeep) -> emptyList()
                    rawKeep is JsonArray -> rawKeep.mapNotNull { RestoreKeep.fromJson(it) }
                    else -> return careful
                }
                RestoreJournal(phase, hadDb, hadFiles, keep, JsonText.boolOf(json["settled"]) == true)
            } catch (_: Exception) {
                careful
            }
        }
    }
}
