package com.ftapps.remft.data.mirror

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.onedrive.LocalFileGone
import com.ftapps.remft.data.onedrive.MirrorMove
import com.ftapps.remft.data.onedrive.MirrorRemote
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.onedrive.RemoteEntry
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.backup.mirror.MirrorPathInput
import com.ftapps.remft.domain.backup.mirror.MirrorPaths
import com.ftapps.remft.domain.backup.mirror.MirrorPlan
import com.ftapps.remft.domain.backup.mirror.MirrorRecord
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.backup.mirror.QuickXorHash
import com.ftapps.remft.domain.backup.mirror.RemoteItem
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class NeededFile(val storedName: String, val remotePath: String)

data class MirrorCatchUp(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val remaining: Int = 0,
    val stoppedBy: String? = null,
    val movedToReview: Int = 0,
)

data class MirrorCounts(val files: Int, val mirrored: Int, val toDownload: Int, val notFound: Int = 0)

data class PendingDownload(val row: MirrorRecord) {
    val storedName: String get() = row.localPath
}

data class FileHashes(val sha256: String, val quickXor: String, val size: Long)

interface BackupMirror {
    suspend fun catchUp(deadline: Instant? = null): MirrorCatchUp
    suspend fun planForBackup(backupAt: Instant): Map<String, MirrorRef>
}

class FileMirror(
    private val sql: MirrorSql,
    private val remote: MirrorRemote,
    private val filesDir: File,
    private val store: SettingsStore,
    private val clock: () -> Instant = Instant::now,
    private val log: BackupLog = AndroidBackupLog,
) : BackupMirror {
    private val catchUpLock = Mutex()
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun neededFiles(): Map<String, NeededFile> = io {
        val folders = sql.query("SELECT id, name, parent_id FROM folders") { c ->
            c.getString(0) to (c.getString(1) to c.stringOrNull(2))
        }.toMap()
        val taskTitles = sql.query(
            "SELECT id, title FROM tasks WHERE id IN (SELECT target_id FROM file_rows WHERE target_type = 'task')",
        ) { c -> c.getString(0) to c.getString(1) }.toMap()
        val noteTitles = sql.query(
            "SELECT id, title FROM notes WHERE id IN (SELECT target_id FROM file_rows WHERE target_type = 'note')",
        ) { c -> c.getString(0) to c.getString(1) }.toMap()
        fun chain(folderId: String?): List<String> {
            val out = ArrayList<String>()
            val seen = HashSet<String>()
            var id = folderId
            while (id != null && seen.add(id) && out.size < MAX_FOLDER_DEPTH) {
                val folder = folders[id] ?: break
                out.add(0, folder.first)
                id = folder.second
            }
            return out
        }
        val out = LinkedHashMap<String, NeededFile>()
        sql.query("SELECT id, name, target_type, target_id, folder_id, local_path FROM file_rows") { c ->
            val stored = c.stringOrNull(5)
            if (stored.isNullOrEmpty()) return@query
            val name = PathText.basename(stored)
            val targetType = c.getString(2)
            val targetId = c.getString(3)
            out[name] = NeededFile(
                name,
                MirrorPaths.pathFor(
                    MirrorPathInput(
                        id = c.getString(0),
                        name = c.getString(1),
                        storedName = name,
                        targetType = targetType,
                        folderChain = if (targetType == "workspace") chain(c.stringOrNull(4)) else emptyList(),
                        ownerId = targetId,
                        ownerTitle = when (targetType) {
                            "task" -> taskTitles[targetId]
                            "note" -> noteTitles[targetId]
                            else -> null
                        },
                    ),
                ),
            )
        }
        sql.query("SELECT local_paths FROM trash_items") { c -> c.stringOrNull(0) }.forEach { raw ->
            val kept = raw?.let { JsonText.parseOrNull(it) } as? JsonArray ?: return@forEach
            for (k in kept) {
                val stored = JsonText.stringOf(k)
                if (stored.isNullOrEmpty()) continue
                val name = PathText.basename(stored)
                out.getOrPut(name) { NeededFile(name, MirrorPaths.binnedPath(name)) }
            }
        }
        out
    }

    suspend fun rows(): Map<String, MirrorRecord> = io {
        sql.query(
            "SELECT local_path, sha256, size_bytes, quick_xor_hash, state, remote_path, item_id, attempts, last_error, mirrored_at, listed_at, updated_at FROM file_mirrors",
        ) { c ->
            MirrorRecord(
                localPath = c.getString(0),
                sha256 = c.getString(1),
                sizeBytes = c.getLong(2),
                quickXorHash = c.stringOrNull(3),
                state = c.getString(4),
                remotePath = c.stringOrNull(5),
                itemId = c.stringOrNull(6),
                attempts = c.getInt(7),
                lastError = c.stringOrNull(8),
                mirroredAt = UtcText.parse(c.stringOrNull(9)),
                listedAt = UtcText.parse(c.stringOrNull(10)),
                updatedAt = UtcText.parse(c.stringOrNull(11)),
            )
        }.associateBy { it.localPath }
    }

    private suspend fun row(storedName: String): MirrorRecord? = rows()[storedName]

    fun local(storedName: String): File = File(filesDir, PathText.basename(storedName))

    private fun incoming(): File = File(filesDir.path + INCOMING_SUFFIX).also { it.mkdirs() }

    suspend fun counts(): MirrorCounts {
        val needed = neededFiles()
        val rows = rows()
        var mirrored = 0
        var toDownload = 0
        var notFound = 0
        for (name in needed.keys) {
            val isMirrored = rows[name]?.state == MirrorState.MIRRORED
            if (isMirrored) mirrored++
            if (local(name).isFile) continue
            if (isMirrored) toDownload++ else notFound++
        }
        return MirrorCounts(needed.size, mirrored, toDownload, notFound)
    }

    override suspend fun catchUp(deadline: Instant?): MirrorCatchUp = catchUpLock.withLock { runCatchUp(deadline) }

    private suspend fun runCatchUp(deadline: Instant?): MirrorCatchUp {
        val needed = neededFiles()
        val rows = rows()
        val todo = needed.values.filter { rows[it.storedName]?.state != MirrorState.MIRRORED && local(it.storedName).isFile }
        var uploaded = 0
        var failed = 0
        for ((i, f) in todo.withIndex()) {
            if (deadline != null && clock().isAfter(deadline)) return MirrorCatchUp(uploaded, failed, todo.size - i)
            try {
                if (uploadOne(f.storedName, rows[f.storedName]?.remotePath ?: f.remotePath)) uploaded++ else failed++
            } catch (e: OneDriveException) {
                if (e.code in MirrorPlan.STOP_CODES) return MirrorCatchUp(uploaded, failed, todo.size - i, e.code)
                failed++
            }
        }
        if (uploaded > 0 || failed > 0) log.info("mirror.catch_up", mapOf("uploaded" to uploaded, "failed" to failed))
        return try {
            MirrorCatchUp(uploaded, failed, movedToReview = reviewGone(needed, deadline))
        } catch (e: OneDriveException) {
            log.warn("mirror.review_failed", fields = mapOf("code" to e.code))
            MirrorCatchUp(uploaded, failed, stoppedBy = e.code.takeIf { it in MirrorPlan.STOP_CODES })
        } catch (e: Exception) {
            log.warn("mirror.review_failed", e)
            MirrorCatchUp(uploaded, failed)
        }
    }

    suspend fun uploadOne(storedName: String, remotePath: String): Boolean {
        check(MirrorPaths.isMirrorPath(remotePath)) { "Not a mirror path" }
        val file = local(storedName)
        val hashes = try {
            io { hashFile(file) }
        } catch (e: java.io.IOException) {
            log.warn("mirror.upload_failed", e, mapOf("code" to OneDriveErrors.LOCAL_GONE))
            return false
        }
        val previous = row(storedName)
        val now = UtcText.format(clock())
        io {
            sql.exec(
                "INSERT OR REPLACE INTO file_mirrors (local_path, sha256, size_bytes, quick_xor_hash, state, remote_path, item_id, attempts, last_error, mirrored_at, listed_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)",
                arrayOf<Any?>(
                    storedName, hashes.sha256, hashes.size, hashes.quickXor, MirrorState.UPLOADING, remotePath,
                    previous?.itemId, (previous?.attempts ?: 0) + 1,
                    previous?.mirroredAt?.let { UtcText.format(it) }, previous?.listedAt?.let { UtcText.format(it) }, now,
                ),
            )
        }
        val item: RemoteItem = try {
            remote.putFile(file, remotePath)
        } catch (e: OneDriveException) {
            log.warn("mirror.upload_failed", fields = mapOf("code" to e.code))
            fail(storedName, e.code)
            throw e
        } catch (e: LocalFileGone) {
            log.warn("mirror.upload_failed", e, mapOf("code" to OneDriveErrors.LOCAL_GONE))
            fail(storedName, OneDriveErrors.LOCAL_GONE)
            return false
        }
        val matches = MirrorPlan.confirms(item, hashes.size, hashes.quickXor)
        if (!matches && MirrorPlan.heldRewritten(item, item.id, remotePath)) {
            log.info("mirror.upload_rewritten", mapOf("size" to (item.size == hashes.size)))
        } else if (!matches) {
            log.warn("mirror.upload_mismatch", fields = mapOf("size" to (item.size == hashes.size)))
            fail(storedName, OneDriveErrors.MISMATCH)
            return false
        }
        val at = UtcText.format(clock())
        io {
            sql.exec(
                "UPDATE file_mirrors SET state = ?, item_id = ?, last_error = NULL, mirrored_at = ?, updated_at = ? WHERE local_path = ?",
                arrayOf(MirrorState.MIRRORED, item.id, at, at, storedName),
            )
        }
        return true
    }

    private suspend fun fail(storedName: String, code: String) = io {
        sql.exec(
            "UPDATE file_mirrors SET state = ?, last_error = ?, updated_at = ? WHERE local_path = ?",
            arrayOf(MirrorState.FAILED, code, UtcText.format(clock()), storedName),
        )
    }

    override suspend fun planForBackup(backupAt: Instant): Map<String, MirrorRef> {
        val needed = neededFiles()
        val rows = rows()
        val (candidates, hasLocal) = MirrorPlan.candidates(needed.keys, rows) { local(it).isFile }
        if (candidates.isEmpty()) return emptyMap()
        val items = remote.itemsById(candidates.mapNotNull { it.itemId })
        val found = LinkedHashMap<String, RemoteItem>()
        for ((k, v) in items) if (v != null) found[k] = v
        val plan = MirrorPlan.classify(candidates, hasLocal, found, backupAt) { row ->
            runCatching { local(row.localPath).length() == row.sizeBytes }.getOrDefault(false)
        }
        if (plan.otherAccount) {
            log.warn("mirror.other_account", fields = mapOf("files" to candidates.size))
            throw OneDriveException(OneDriveErrors.OTHER_ACCOUNT)
        }
        val now = UtcText.format(clock())
        val listedAt = MirrorPlan.listedStamp(backupAt)
        io {
            sql.inTransaction {
                for (name in plan.reupload) {
                    sql.exec(
                        "UPDATE file_mirrors SET state = ?, last_error = ?, updated_at = ? WHERE local_path = ?",
                        arrayOf(MirrorState.MISSING, MirrorPlan.MISSING_IN_ONEDRIVE, now, name),
                    )
                }
                for (name in plan.notThere) {
                    markRemoteMissingSync(name, (rows[name]?.attempts ?: 0) + 1, now)
                }
                for (name in plan.refound) {
                    sql.exec(
                        "UPDATE file_mirrors SET state = ?, attempts = 0, last_error = NULL, updated_at = ? WHERE local_path = ?",
                        arrayOf(MirrorState.MIRRORED, now, name),
                    )
                }
                stampListedSync(plan.plan.keys, listedAt, rows)
            }
        }
        if (plan.rewrittenHeld > 0) log.info("mirror.rewritten_held", mapOf("files" to plan.rewrittenHeld))
        if (plan.reupload.isNotEmpty() || plan.notThere.isNotEmpty()) {
            log.warn("mirror.missing_in_onedrive", fields = mapOf("files" to plan.reupload.size, "notOnPhone" to plan.notThere.size))
        }
        return plan.plan
    }

    private fun markRemoteMissingSync(name: String, attempts: Int, now: String) {
        sql.exec(
            "UPDATE file_mirrors SET state = ?, attempts = ?, last_error = ?, updated_at = ? WHERE local_path = ?",
            arrayOf<Any?>(MirrorState.REMOTE_MISSING, attempts, MirrorPlan.MISSING_IN_ONEDRIVE, now, name),
        )
    }

    private fun stampListedSync(names: Collection<String>, at: Instant, rows: Map<String, MirrorRecord>) {
        val text = UtcText.format(at)
        for (name in names) {
            if (!MirrorPlan.shouldStampListed(rows[name]?.listedAt, at)) continue
            sql.exec("UPDATE file_mirrors SET listed_at = ? WHERE local_path = ?", arrayOf(text, name))
        }
    }

    suspend fun refsForMissingLocal(): Map<String, MirrorRef> {
        val needed = neededFiles()
        val rows = rows()
        val out = MirrorPlan.refsForMissingLocal(needed.keys, rows) { local(it).isFile }
        if (out.isNotEmpty()) io { sql.inTransaction { stampListedSync(out.keys, MirrorPlan.manualListStamp(clock()), rows) } }
        return out
    }

    suspend fun reviewGone(needed: Map<String, NeededFile>? = null, deadline: Instant? = null): Int {
        val need = needed ?: neededFiles()
        val rows = rows()
        val stale = clock().minus(UPLOAD_STALE)
        fun past() = deadline != null && clock().isAfter(deadline)
        var folderId: String? = null
        val folder: suspend () -> String = {
            folderId ?: remote.ensureFolder(MirrorPaths.REVIEW_FOLDER).also { folderId = it }
        }
        var moved = 0
        for (row in rows.values) {
            if (need.containsKey(row.localPath)) continue
            if (row.state == MirrorState.UPLOADING && row.updatedAt?.isAfter(stale) == true) continue
            if (past()) return reviewed(moved)
            val itemId = row.itemId?.takeIf { it.isNotEmpty() }
            val path = row.remotePath
            val byPath = path?.takeIf { MirrorPaths.isMirrorPath(it) && !MirrorPaths.isReviewPath(it) }
            if (itemId != null || byPath != null) {
                if (moveToReview(itemId, byPath, PathText.basename(path ?: row.localPath), folder)) moved++
            }
            io { sql.exec("DELETE FROM file_mirrors WHERE local_path = ?", arrayOf(row.localPath)) }
        }
        if (store.get(SettingKeys.MirrorReviewSweptAt.name) == null) {
            if (need.isEmpty()) {
                log.info("mirror.review_sweep_skipped")
            } else {
                val (count, complete) = sweepUnaccounted(need, folder, ::past)
                moved += count
                if (complete) store.set(SettingKeys.MirrorReviewSweptAt.name, UtcText.format(clock()))
            }
        }
        return reviewed(moved)
    }

    private fun reviewed(moved: Int): Int {
        if (moved > 0) log.info("mirror.moved_to_review", mapOf("files" to moved))
        return moved
    }

    private suspend fun sweepUnaccounted(
        need: Map<String, NeededFile>,
        folder: suspend () -> String,
        past: () -> Boolean,
    ): Pair<Int, Boolean> {
        val rows = rows()
        val keepIds = rows.values.mapNotNull { it.itemId?.takeIf { id -> id.isNotEmpty() } }.toSet()
        val keepPaths = (rows.values.mapNotNull { it.remotePath?.lowercase() } + need.values.map { it.remotePath.lowercase() }).toSet()
        var moved = 0
        val folders = ArrayDeque(listOf(MirrorPaths.ROOT))
        while (folders.isNotEmpty()) {
            val at = folders.removeFirst()
            val entries: List<RemoteEntry> = remote.listFolder(at).orEmpty()
            for (e in entries) {
                val path = "$at/${e.name}"
                if (e.isFolder) {
                    if (path.lowercase() != MirrorPaths.REVIEW_FOLDER.lowercase()) folders.addLast(path)
                    continue
                }
                if (e.id in keepIds || path.lowercase() in keepPaths) continue
                if (past()) return moved to false
                if (moveToReview(e.id, null, e.name, folder)) moved++
            }
        }
        return moved to true
    }

    private suspend fun moveToReview(itemId: String?, path: String?, name: String, folder: suspend () -> String): Boolean {
        val folderId = folder()
        var tryId = itemId != null
        if (itemId != null) {
            val parent = remote.parentOf(itemId)
            if (parent == folderId) return false
            if (parent == null) tryId = false
        }
        for (n in 1..MAX_REVIEW_NAMES) {
            val candidate = MirrorPaths.reviewName(name, n)
            var outcome = MirrorMove.Gone
            if (tryId) {
                outcome = remote.moveInto(itemId = itemId, folderId = folderId, name = candidate)
                if (outcome == MirrorMove.Gone) tryId = false
            }
            if (!tryId && path != null && outcome == MirrorMove.Gone) {
                outcome = remote.moveInto(path = path, folderId = folderId, name = candidate)
            }
            when (outcome) {
                MirrorMove.Moved -> return true
                MirrorMove.Gone -> return false
                MirrorMove.NameTaken -> continue
            }
        }
        log.warn("mirror.review_name_taken")
        return false
    }

    suspend fun pendingDownloads(): List<PendingDownload> {
        val needed = neededFiles()
        val rows = rows()
        return needed.keys.mapNotNull { name ->
            val row = rows[name] ?: return@mapNotNull null
            if (canFetch(row) && !local(name).isFile) PendingDownload(row) else null
        }
    }

    suspend fun pendingFor(fileId: String): PendingDownload? {
        val stored = io {
            sql.query("SELECT local_path FROM file_rows WHERE id = ?", arrayOf(fileId)) { it.stringOrNull(0) }.firstOrNull()
        }
        if (stored.isNullOrEmpty()) return null
        val name = PathText.basename(stored)
        if (local(name).isFile) return null
        val row = row(name) ?: return null
        return if (canFetch(row)) PendingDownload(row) else null
    }

    suspend fun download(d: PendingDownload, onProgress: ((Long) -> Unit)? = null): File {
        val lock = downloadLocks.getOrPut(d.storedName) { Mutex() }
        return lock.withLock { downloadLocked(d, onProgress) }
    }

    private suspend fun downloadLocked(d: PendingDownload, onProgress: ((Long) -> Unit)?): File {
        val target = local(d.storedName)
        if (target.isFile) return target
        val part = File(incoming(), "${d.storedName}.part")
        val found = remote.download(d.row.itemId, d.row.remotePath, d.row.sizeBytes, part, onProgress)
        if (!found) {
            val current = row(d.storedName)
            io { markRemoteMissingSync(d.storedName, (current?.attempts ?: d.row.attempts) + 1, UtcText.format(clock())) }
            throw OneDriveException(OneDriveErrors.MIRROR_MISSING)
        }
        val got = io { hashFile(part) }
        if (got.size != d.row.sizeBytes || got.sha256 != d.row.sha256) {
            io { FileOps.deleteFileIfExists(part) }
            throw OneDriveException(OneDriveErrors.MIRROR_DAMAGED)
        }
        io {
            target.parentFile?.mkdirs()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                FileOps.deleteFileIfExists(part)
            }
        }
        if (d.row.state != MirrorState.MIRRORED) {
            io {
                sql.exec(
                    "UPDATE file_mirrors SET state = ?, attempts = 0, last_error = NULL, updated_at = ? WHERE local_path = ?",
                    arrayOf(MirrorState.MIRRORED, UtcText.format(clock()), d.storedName),
                )
            }
        }
        return target
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val INCOMING_SUFFIX = "-incoming"
        private const val MAX_FOLDER_DEPTH = 12
        private const val MAX_REVIEW_NAMES = 100
        private val UPLOAD_STALE: Duration = Duration.ofHours(6)

        fun canFetch(row: MirrorRecord): Boolean =
            row.state in MirrorState.fetchable && (!row.itemId.isNullOrEmpty() || MirrorPaths.isMirrorPath(row.remotePath ?: ""))

        fun hashFile(file: File): FileHashes {
            val xor = QuickXorHash()
            val sha = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    xor.add(buffer, 0, read)
                    sha.update(buffer, 0, read)
                    size += read
                }
            }
            return FileHashes(FileOps.hex(sha.digest()), xor.base64Digest(), size)
        }
    }
}
