package com.ftapps.remft.data.mirror

import com.ftapps.remft.data.onedrive.MirrorMove
import com.ftapps.remft.data.onedrive.MirrorRemote
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.onedrive.RemoteEntry
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.mirror.MirrorFolder
import com.ftapps.remft.domain.backup.mirror.MirrorFolders
import com.ftapps.remft.domain.backup.mirror.MirrorPaths
import com.ftapps.remft.domain.backup.mirror.MirrorPlan
import com.ftapps.remft.domain.backup.mirror.MirrorRecord
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

class MirrorStop(val code: String?) : Exception()

class MirrorPass(
    private val sql: MirrorSql,
    private val remote: MirrorRemote,
    private val store: SettingsStore,
    private val filesDir: File,
    private val clock: () -> Instant,
    private val log: BackupLog,
    private val past: () -> Boolean,
) {
    private var folders: LinkedHashMap<String, MirrorFolder>? = null
    private val ids = HashMap<String, String>()
    private val vacated = LinkedHashSet<String>()
    private var lookups = 0
    var foldersMoved = 0
        private set
    var filesMoved = 0
        private set
    var reviewed = 0
        private set
    var failed = 0
        private set

    suspend fun load(): Map<String, MirrorFolder> {
        folders = try {
            val raw = store.read(SettingKeys.MirrorFolders.name)
            if (raw == null) firstFolderMap()
            MirrorFolders.decode(raw)
        } catch (e: SettingsUnavailable) {
            log.warn("mirror.folders_unreadable")
            null
        }
        vacated.addAll(MirrorFolders.decodePaths(store.get(SettingKeys.MirrorBinLeftovers.name)))
        return placed()
    }

    private suspend fun firstFolderMap() {
        if (store.get(SettingKeys.MirrorReviewSweptAt.name) != null) {
            store.remove(SettingKeys.MirrorReviewSweptAt.name)
            log.info("mirror.review_sweep_reset")
        }
        store.set(SettingKeys.MirrorFolders.name, MirrorFolders.encode(emptyMap()))
    }

    suspend fun flush() {
        val raw = MirrorFolders.encodePaths(vacated)
        val before = store.get(SettingKeys.MirrorBinLeftovers.name)
        if (vacated.isEmpty()) {
            if (before != null) store.remove(SettingKeys.MirrorBinLeftovers.name)
        } else if (before != raw) {
            store.set(SettingKeys.MirrorBinLeftovers.name, raw)
        }
    }

    private fun vacate(path: String?) {
        if (path == null) return
        val parent = MirrorPaths.parentPath(path)
        if (collectable(parent)) vacated.add(parent)
    }

    private fun collectable(path: String): Boolean =
        MirrorPaths.isMirrorPath(path) && !MirrorPaths.isReviewPath(path) && FIXED_FOLDERS.none { it.equals(path, ignoreCase = true) }

    fun placed(): Map<String, MirrorFolder> = folders.orEmpty()

    private suspend fun save() {
        val f = folders ?: return
        store.set(SettingKeys.MirrorFolders.name, MirrorFolders.encode(f))
    }

    private fun stopIfPast() {
        if (past()) throw MirrorStop(null)
    }

    private suspend fun attempt(event: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: OneDriveException) {
        if (e.code in MirrorPlan.STOP_CODES) throw MirrorStop(e.code)
        failed++
        log.warn(event, fields = mapOf("code" to e.code))
        false
    }

    private fun trackedAt(path: String, except: String?): String? =
        folders?.entries?.firstOrNull { it.key != except && it.value.path.equals(path, ignoreCase = true) }?.key

    private suspend fun folderIdFor(path: String): String {
        val lower = path.lowercase()
        ids[lower]?.let { return it }
        folders?.values?.firstOrNull { it.path.equals(path, ignoreCase = true) }?.let {
            ids[lower] = it.itemId
            return it.itemId
        }
        return remote.ensureFolder(path).also { ids[lower] = it }
    }

    private suspend fun rebase(from: String, to: String) {
        if (from == to) return
        vacate(from)
        io {
            val rows = sql.query("SELECT local_path, remote_path FROM file_mirrors WHERE remote_path IS NOT NULL") { c ->
                c.getString(0) to c.getString(1)
            }
            val changes = rows.mapNotNull { (name, path) -> MirrorPaths.rebase(path, from, to)?.takeIf { it != path }?.let { name to it } }
            if (changes.isNotEmpty()) {
                sql.inTransaction {
                    for ((name, path) in changes) {
                        sql.exec("UPDATE file_mirrors SET remote_path = ? WHERE local_path = ?", arrayOf(path, name))
                    }
                }
            }
        }
        folders?.let { f ->
            for ((key, st) in f.entries.toList()) {
                MirrorPaths.rebase(st.path, from, to)?.let { f[key] = st.copy(path = it) }
            }
        }
        ids.keys.removeAll { MirrorPaths.isUnder(it, from.lowercase()) }
    }

    suspend fun placeFolders(layout: MirrorLayout) {
        if (folders == null) return
        val parents = layout.files.values.map { it.parentPath }
        val blocked = ArrayList<String>()
        val shifts = ArrayList<Pair<String, String>>()
        for ((key, desired) in layout.folders) {
            if (blocked.any { MirrorPaths.isUnder(desired, it) }) continue
            val target = shifts.firstNotNullOfOrNull { (from, to) -> MirrorPaths.rebase(desired, from, to) } ?: desired
            val ok = attempt("mirror.folder_failed") { place(key, target, desired, layout, parents) }
            if (!ok) blocked.add(desired)
            val placed = folders?.get(key)
            if (ok && placed != null && placed.path != desired) shifts.add(0, desired to placed.path)
        }
    }

    private suspend fun place(key: String, want: String, desired: String, layout: MirrorLayout, parents: List<String>): Boolean {
        val f = folders ?: return false
        val st = f[key]
        if (st != null) {
            if (st.path == want) {
                if (st.want != desired) {
                    f[key] = st.copy(want = desired)
                    save()
                }
                return true
            }
            if (st.want == desired && trackedAt(want, key) != null) return true
            stopIfPast()
            return moveFolder(key, st, want, desired, layout, true)
        }
        stopIfPast()
        val other = trackedAt(want, key)
        if (other != null) {
            if (other in layout.folders || holdsNeeded(other, layout)) return createRenamed(key, want, desired)
            goneToReview(other)
        }
        if (MirrorPaths.isBinPath(want)) {
            val home = MirrorPaths.ROOT + want.substring(MirrorPaths.BIN_FOLDER.length)
            val free = MirrorPaths.isMirrorPath(home) &&
                layout.folders.values.none { it.equals(home, ignoreCase = true) } &&
                parents.none { MirrorPaths.isUnder(it, home) } &&
                trackedAt(home, null) == null
            if (free) {
                val found = remote.folderAt(home)
                if (found != null) {
                    val adopted = MirrorFolder(found, home, home)
                    f[key] = adopted
                    return moveFolder(key, adopted, want, desired, layout, true)
                }
            }
        }
        f[key] = MirrorFolder(folderIdFor(want), want, desired)
        save()
        return true
    }

    private suspend fun holdsNeeded(key: String, layout: MirrorLayout): Boolean {
        val f = folders ?: return true
        val path = f[key]?.path ?: return false
        val folderInside = f.any { (k, st) -> k != key && k in layout.folders && MirrorPaths.isUnder(st.path, path) }
        if (folderInside) return true
        val rows = io { rowsNow() }
        return rows.values.any { r -> r.localPath in layout.files && r.remotePath?.let { MirrorPaths.isUnder(it, path) } == true }
    }

    private suspend fun createRenamed(key: String, want: String, desired: String): Boolean {
        val f = folders ?: return false
        val parent = MirrorPaths.parentPath(want)
        val leaf = MirrorPaths.leafName(want)
        for (n in 2..MAX_NAMES) {
            val candidate = "$parent/${MirrorPaths.folderName(leaf, n)}"
            if (trackedAt(candidate, key) != null) continue
            f[key] = MirrorFolder(folderIdFor(candidate), candidate, desired)
            save()
            return true
        }
        return false
    }

    private suspend fun moveFolder(key: String, st: MirrorFolder, want: String, desired: String, layout: MirrorLayout, retry: Boolean): Boolean {
        val f = folders ?: return false
        val parentId = folderIdFor(MirrorPaths.parentPath(want))
        when (remote.moveInto(itemId = st.itemId, folderId = parentId, name = MirrorPaths.leafName(want))) {
            MirrorMove.Moved -> {
                rebase(st.path, want)
                f[key] = MirrorFolder(st.itemId, want, desired)
                foldersMoved++
                save()
                return true
            }
            MirrorMove.Gone -> {
                f.remove(key)
                ids.remove(st.path.lowercase())
                f[key] = MirrorFolder(folderIdFor(want), want, desired)
                save()
                return true
            }
            MirrorMove.NameTaken -> {
                val other = trackedAt(want, key)
                if (other == null) return merge(key, st, want, desired)
                if (other in layout.folders || holdsNeeded(other, layout)) return renameMove(key, st, want, desired)
                goneToReview(other)
                return retry && moveFolder(key, st, want, desired, layout, false)
            }
        }
    }

    private suspend fun renameMove(key: String, st: MirrorFolder, want: String, desired: String): Boolean {
        val f = folders ?: return false
        val parent = MirrorPaths.parentPath(want)
        val parentId = folderIdFor(parent)
        val leaf = MirrorPaths.leafName(want)
        for (n in 2..MAX_NAMES) {
            val name = MirrorPaths.folderName(leaf, n)
            val candidate = "$parent/$name"
            if (candidate.equals(st.path, ignoreCase = true)) {
                f[key] = st.copy(want = desired)
                save()
                return true
            }
            if (trackedAt(candidate, key) != null) continue
            when (remote.moveInto(itemId = st.itemId, folderId = parentId, name = name)) {
                MirrorMove.Moved -> {
                    rebase(st.path, candidate)
                    f[key] = MirrorFolder(st.itemId, candidate, desired)
                    foldersMoved++
                    save()
                    return true
                }
                MirrorMove.NameTaken -> continue
                MirrorMove.Gone -> {
                    f.remove(key)
                    save()
                    return false
                }
            }
        }
        return false
    }

    private suspend fun merge(key: String, st: MirrorFolder, want: String, desired: String): Boolean {
        val f = folders ?: return false
        val targetId = folderIdFor(want)
        if (targetId != st.itemId) {
            val children: List<RemoteEntry> = remote.childrenOf(st.itemId).orEmpty()
            for (child in children) {
                val to = moveChild(child, targetId, want) ?: continue
                if (child.isFolder) {
                    movedFolder(child.id, "${st.path}/${child.name}", to)
                } else {
                    io { sql.exec("UPDATE file_mirrors SET remote_path = ? WHERE item_id = ?", arrayOf(to, child.id)) }
                }
            }
            vacate(st.path)
            f[key] = MirrorFolder(targetId, want, desired)
            save()
            val shell = toReview(st.itemId, null, MirrorPaths.reviewPath(st.path), folder = true)
            if (shell != null) reviewed++
        } else {
            rebase(st.path, want)
            f[key] = MirrorFolder(targetId, want, desired)
            save()
        }
        foldersMoved++
        return true
    }

    private suspend fun movedFolder(itemId: String, from: String, to: String) {
        val f = folders ?: return
        val tracked = f.entries.firstOrNull { it.value.itemId == itemId }
        val recorded = tracked?.value?.path ?: from
        rebase(recorded, to)
        if (tracked != null) f[tracked.key] = tracked.value.copy(path = to)
    }

    private suspend fun moveChild(child: RemoteEntry, targetId: String, targetPath: String): String? {
        for (n in 1..MAX_NAMES) {
            val name = if (child.isFolder) MirrorPaths.folderName(child.name, n) else MirrorPaths.reviewName(child.name, n)
            when (remote.moveInto(itemId = child.id, folderId = targetId, name = name)) {
                MirrorMove.Moved -> return "$targetPath/$name"
                MirrorMove.NameTaken -> continue
                MirrorMove.Gone -> return null
            }
        }
        return null
    }

    private suspend fun goneToReview(key: String) {
        val f = folders ?: return
        val st = f[key] ?: return
        val moved = toReview(st.itemId, st.path, MirrorPaths.reviewPath(st.path), folder = true)
        if (moved != null) {
            rebase(st.path, moved)
            reviewed++
        }
        f.remove(key)
        save()
    }

    private suspend fun toReview(itemId: String?, path: String?, target: String, folder: Boolean): String? {
        val parent = MirrorPaths.parentPath(target)
        val leaf = MirrorPaths.leafName(target)
        val folderId = folderIdFor(parent)
        var tryId = !itemId.isNullOrEmpty()
        val byPath = path?.takeIf { MirrorPaths.isMirrorPath(it) && !MirrorPaths.isReviewPath(it) }
        for (n in 1..MAX_NAMES) {
            val name = if (folder) MirrorPaths.folderName(leaf, n) else MirrorPaths.reviewName(leaf, n)
            var outcome = MirrorMove.Gone
            if (tryId) {
                outcome = remote.moveInto(itemId = itemId, folderId = folderId, name = name)
                if (outcome == MirrorMove.Gone) tryId = false
            }
            if (!tryId && byPath != null && outcome == MirrorMove.Gone) {
                outcome = remote.moveInto(path = byPath, folderId = folderId, name = name)
            }
            when (outcome) {
                MirrorMove.Moved -> return "$parent/$name"
                MirrorMove.Gone -> return null
                MirrorMove.NameTaken -> continue
            }
        }
        log.warn("mirror.review_name_taken")
        return null
    }

    suspend fun placeFiles(needed: Map<String, String>, rows: Map<String, MirrorRecord>) {
        if (folders == null) return
        val stale = clock().minus(UPLOAD_STALE)
        for ((name, want) in needed) {
            val row = rows[name] ?: continue
            val lost = lost(row)
            if (row.remotePath == want && !lost) continue
            if (lost && !dueAgain(row)) continue
            if (row.state == MirrorState.UPLOADING && row.updatedAt?.isAfter(stale) == true) continue
            val itemId = row.itemId?.takeIf { it.isNotEmpty() }
            val byPath = row.remotePath?.takeIf { MirrorPaths.isMirrorPath(it) }
            if (itemId == null && byPath == null) {
                setPath(name, want)
                continue
            }
            stopIfPast()
            attempt("mirror.move_failed") { moveFile(row, itemId, byPath, want, rows) }
        }
    }

    private fun dueAgain(row: MirrorRecord): Boolean {
        val last = row.updatedAt ?: return true
        val steps = row.attempts.coerceIn(0, 10)
        val wait = LOST_RETRY.multipliedBy(1L shl steps).let { if (it > LOST_RETRY_MAX) LOST_RETRY_MAX else it }
        return !clock().isBefore(last.plus(wait))
    }

    private suspend fun forgetFolder(path: String, id: String) {
        ids.remove(path.lowercase())
        val f = folders ?: return
        val stale = f.entries.filter { it.value.itemId == id }.map { it.key }
        if (stale.isEmpty()) return
        stale.forEach { f.remove(it) }
        save()
    }

    private suspend fun moveFile(row: MirrorRecord, itemId: String?, byPath: String?, want: String, rows: Map<String, MirrorRecord>): Boolean {
        val parent = MirrorPaths.parentPath(want)
        var parentId = folderIdFor(parent)
        var checkedParent = false
        val leaf = MirrorPaths.leafName(want)
        var round = 0
        while (round < 2) {
            var outcome = MirrorMove.Gone
            if (itemId != null) outcome = remote.moveInto(itemId = itemId, folderId = parentId, name = leaf)
            if (outcome == MirrorMove.Gone && byPath != null) outcome = remote.moveInto(path = byPath, folderId = parentId, name = leaf)
            when (outcome) {
                MirrorMove.Moved -> {
                    if (row.state == MirrorState.REMOTE_MISSING || row.state == MirrorState.MISSING) {
                        refound(row.localPath, want, null)
                    } else {
                        setPath(row.localPath, want)
                    }
                    if (row.remotePath != want) {
                        vacate(row.remotePath)
                        filesMoved++
                    }
                    return true
                }
                MirrorMove.Gone -> {
                    if (!checkedParent && itemId != null && remote.itemsById(listOf(itemId))[itemId] != null) {
                        checkedParent = true
                        forgetFolder(parent, parentId)
                        parentId = remote.ensureFolder(parent).also { ids[parent.lowercase()] = it }
                        continue
                    }
                    if (lost(row) && lookUp(row, parentId, want)) return true
                    markGone(row, want)
                    return true
                }
                MirrorMove.NameTaken -> {
                    val ours = rows.values.any { it.localPath != row.localPath && it.remotePath.equals(want, ignoreCase = true) }
                    if (round > 0 || ours) break
                    if (toReview(null, want, MirrorPaths.reviewPath(want), folder = false) != null) reviewed++
                    round++
                }
            }
        }
        failed++
        log.warn("mirror.move_blocked")
        return false
    }

    private suspend fun setPath(name: String, path: String) = io {
        sql.exec("UPDATE file_mirrors SET remote_path = ? WHERE local_path = ?", arrayOf(path, name))
    }

    private suspend fun refound(name: String, path: String, itemId: String?) {
        val now = UtcText.format(clock())
        io {
            if (itemId == null) {
                sql.exec(
                    "UPDATE file_mirrors SET remote_path = ?, state = ?, attempts = 0, last_error = NULL, updated_at = ? WHERE local_path = ?",
                    arrayOf(path, MirrorState.MIRRORED, now, name),
                )
            } else {
                sql.exec(
                    "UPDATE file_mirrors SET remote_path = ?, item_id = ?, state = ?, attempts = 0, last_error = NULL, updated_at = ? WHERE local_path = ?",
                    arrayOf(path, itemId, MirrorState.MIRRORED, now, name),
                )
            }
        }
    }

    private fun lost(row: MirrorRecord): Boolean =
        row.state == MirrorState.REMOTE_MISSING || (row.state == MirrorState.MISSING && !File(filesDir, row.localPath).isFile)

    private suspend fun lookUp(row: MirrorRecord, parentId: String, want: String): Boolean {
        if (lookups >= MAX_LOOKUPS) return false
        lookups++
        val names = listOfNotNull(MirrorPaths.leafName(want), row.remotePath?.let { MirrorPaths.leafName(it) })
        val flat = MirrorPaths.leafName(MirrorPaths.binnedPath(row.localPath))
        val recorded = row.remotePath?.takeIf { MirrorPaths.isMirrorPath(it) }?.let { MirrorPaths.fallbackFolders(it) }.orEmpty()
        val places = (MirrorPaths.fallbackFolders(want) + recorded + MirrorPaths.BIN_FOLDER).distinctBy { it.lowercase() }
        for (place in places) {
            val match = remote.listFolder(place).orEmpty().firstOrNull { e ->
                !e.isFolder && e.size == row.sizeBytes &&
                    (names.any { MirrorPaths.isReviewNameOf(e.name, it) } || e.name.equals(flat, ignoreCase = true))
            } ?: continue
            if (remote.moveInto(itemId = match.id, folderId = parentId, name = MirrorPaths.leafName(want)) != MirrorMove.Moved) continue
            vacate(place + "/" + match.name)
            refound(row.localPath, want, match.id)
            filesMoved++
            log.info("mirror.found_again")
            return true
        }
        return false
    }

    private suspend fun markGone(row: MirrorRecord, want: String) {
        val now = UtcText.format(clock())
        io {
            if (row.state == MirrorState.MIRRORED) {
                val state = if (File(filesDir, row.localPath).isFile) MirrorState.MISSING else MirrorState.REMOTE_MISSING
                sql.exec(
                    "UPDATE file_mirrors SET remote_path = ?, state = ?, last_error = ?, updated_at = ? WHERE local_path = ?",
                    arrayOf(want, state, MirrorPlan.MISSING_IN_ONEDRIVE, now, row.localPath),
                )
            } else if (lost(row)) {
                sql.exec(
                    "UPDATE file_mirrors SET remote_path = ?, attempts = ?, updated_at = ? WHERE local_path = ?",
                    arrayOf<Any?>(want, row.attempts + 1, now, row.localPath),
                )
            } else {
                sql.exec("UPDATE file_mirrors SET remote_path = ? WHERE local_path = ?", arrayOf(want, row.localPath))
            }
        }
    }

    suspend fun review(layout: MirrorLayout, needed: Map<String, String>, rows: Map<String, MirrorRecord>) {
        goneFolders(layout, needed, rows)
        goneRows(layout, needed)
        val current = if (sweepOnce(layout, needed)) placeAgain(layout) else needed
        tidyBin(layout, current)
    }

    private suspend fun placeAgain(layout: MirrorLayout): Map<String, String> {
        placeFolders(layout)
        val now = layout.files.mapValues { layout.pathOf(it.value, placed()) }
        placeFiles(now, io { rowsNow() })
        return now
    }

    private suspend fun tidyBin(layout: MirrorLayout, needed: Map<String, String>) {
        if (vacated.isEmpty()) return
        val wanted = layout.folders.values + needed.values + placed().values.map { it.path }
        for (start in vacated.sortedByDescending { MirrorPaths.depth(it) }) {
            if (start !in vacated) continue
            stopIfPast()
            val ok = attempt("mirror.bin_tidy_failed") {
                var top: String? = null
                var at = start
                while (collectable(at) && emptyTree(at, wanted, 0)) {
                    top = at
                    at = MirrorPaths.parentPath(at)
                }
                val found = top
                if (found != null) {
                    val id = remote.folderAt(found)
                    if (id != null && toReview(id, found, MirrorPaths.reviewPath(found), folder = true) != null) reviewed++
                    ids.keys.removeAll { MirrorPaths.isUnder(it, found.lowercase()) }
                    vacated.removeAll { MirrorPaths.isUnder(it, found) }
                }
                true
            }
            if (ok) vacated.remove(start)
        }
    }

    private suspend fun emptyTree(path: String, wanted: List<String>, depth: Int): Boolean {
        if (depth > MAX_TREE_DEPTH) return false
        if (wanted.any { MirrorPaths.isUnder(it, path) }) return false
        val entries = remote.listFolder(path) ?: return false
        for (e in entries) {
            if (!e.isFolder) return false
            if (!emptyTree(path + "/" + e.name, wanted, depth + 1)) return false
        }
        return true
    }

    private suspend fun goneFolders(layout: MirrorLayout, needed: Map<String, String>, rows: Map<String, MirrorRecord>) {
        val f = folders ?: return
        if (!layout.complete) return
        val stale = clock().minus(UPLOAD_STALE)
        val gone = f.entries.filter { it.key !in layout.folders }.sortedBy { MirrorPaths.depth(it.value.path) }.map { it.key }
        for (key in gone) {
            val st = f[key] ?: continue
            if (MirrorPaths.isReviewPath(st.path)) {
                f.remove(key)
                save()
                continue
            }
            if (holdsNeeded(key, layout)) continue
            if (needed.values.any { MirrorPaths.isUnder(it, st.path) }) {
                f.remove(key)
                save()
                continue
            }
            val busy = rows.values.any { r ->
                r.state == MirrorState.UPLOADING && r.updatedAt?.isAfter(stale) == true &&
                    r.remotePath?.let { MirrorPaths.isUnder(it, st.path) } == true
            }
            if (busy) continue
            stopIfPast()
            attempt("mirror.review_failed") {
                goneToReview(key)
                true
            }
        }
    }

    private suspend fun goneRows(layout: MirrorLayout, needed: Map<String, String>) {
        if (!layout.complete) return
        val rows = io { rowsNow() }
        val stale = clock().minus(UPLOAD_STALE)
        for (row in rows.values) {
            if (needed.containsKey(row.localPath)) continue
            if (row.state == MirrorState.UPLOADING && row.updatedAt?.isAfter(stale) == true) continue
            val path = row.remotePath
            if (path == null || !MirrorPaths.isReviewPath(path)) {
                val itemId = row.itemId?.takeIf { it.isNotEmpty() }
                val byPath = path?.takeIf { MirrorPaths.isMirrorPath(it) }
                if (itemId != null || byPath != null) {
                    stopIfPast()
                    val ok = attempt("mirror.review_failed") {
                        if (toReview(itemId, byPath, MirrorPaths.reviewPath(path ?: row.localPath), folder = false) != null) {
                            vacate(path)
                            reviewed++
                        }
                        true
                    }
                    if (!ok) continue
                }
            }
            io { sql.exec("DELETE FROM file_mirrors WHERE local_path = ?", arrayOf(row.localPath)) }
        }
    }

    private suspend fun sweepOnce(layout: MirrorLayout, needed: Map<String, String>): Boolean {
        if (store.get(SettingKeys.MirrorReviewSweptAt.name) != null) return false
        if (needed.isEmpty()) {
            log.info("mirror.review_sweep_skipped")
            return false
        }
        val corrected = sweep(layout, needed)
        store.set(SettingKeys.MirrorReviewSweptAt.name, UtcText.format(clock()))
        return corrected
    }

    private suspend fun sweep(layout: MirrorLayout, needed: Map<String, String>): Boolean {
        val rows = io { rowsNow() }
        val byItem = rows.values.filter { !it.itemId.isNullOrEmpty() }.associateBy { it.itemId!! }
        val kept = rows.values.mapNotNull { it.remotePath } + needed.values
        val keepPaths = kept.map { it.lowercase() }.toSet()
        val strays = ArrayList<Pair<String, String>>()
        val shells = LinkedHashMap<String, String>()
        val holding = HashSet<String>()
        var corrected = false
        val pending = ArrayDeque(listOf(MirrorPaths.ROOT))
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            val entries: List<RemoteEntry> = remote.listFolder(at).orEmpty()
            for (e in entries) {
                val path = "$at/${e.name}"
                if (e.isFolder) {
                    pending.addLast(path)
                    if (correctFolder(e.id, path)) corrected = true
                    if (collectable(path)) shells[path] = e.id
                    continue
                }
                val known = byItem[e.id]
                if (known != null) {
                    if (known.remotePath != path) {
                        setPath(known.localPath, path)
                        corrected = true
                    }
                    holding.add(at)
                    continue
                }
                if (MirrorPaths.isReviewPath(path) || path.lowercase() in keepPaths) {
                    holding.add(at)
                    continue
                }
                strays.add(e.id to path)
            }
        }
        val wanted = layout.folders.values + placed().values.map { it.path } + kept
        val moved = ArrayList<String>()
        for ((path, id) in shells.entries.sortedBy { MirrorPaths.depth(it.key) }) {
            if (moved.any { MirrorPaths.isUnder(path, it) }) continue
            if (holding.any { MirrorPaths.isUnder(it, path) } || wanted.any { MirrorPaths.isUnder(it, path) }) continue
            stopIfPast()
            if (toReview(id, path, MirrorPaths.reviewPath(path), folder = true) != null) {
                moved.add(path)
                reviewed++
                ids.keys.removeAll { MirrorPaths.isUnder(it, path.lowercase()) }
                vacated.removeAll { MirrorPaths.isUnder(it, path) }
            }
        }
        for ((id, path) in strays) {
            if (moved.any { MirrorPaths.isUnder(path, it) }) continue
            stopIfPast()
            if (toReview(id, null, MirrorPaths.reviewPath(path), folder = false) != null) {
                vacate(path)
                reviewed++
            }
        }
        return corrected
    }

    private suspend fun correctFolder(itemId: String, path: String): Boolean {
        val f = folders ?: return false
        val key = f.entries.firstOrNull { it.value.itemId == itemId }?.key ?: return false
        val st = f[key] ?: return false
        if (st.path == path) return false
        f[key] = st.copy(path = path)
        save()
        return true
    }

    private fun rowsNow(): Map<String, MirrorRecord> = sql.query(
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

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val MAX_NAMES = 100
        private const val MAX_LOOKUPS = 20
        private const val MAX_TREE_DEPTH = 12
        private val FIXED_FOLDERS = listOf(
            MirrorPaths.ROOT,
            MirrorPaths.BIN_FOLDER,
            MirrorPaths.REVIEW_FOLDER,
            MirrorPaths.ownerPath("task", null, null).let { MirrorPaths.parentPath(it) },
            MirrorPaths.ownerPath("note", null, null).let { MirrorPaths.parentPath(it) },
            MirrorPaths.ownerPath("other", null, null),
        )
        private val LOST_RETRY: Duration = Duration.ofMinutes(10)
        private val LOST_RETRY_MAX: Duration = Duration.ofDays(1)
        val UPLOAD_STALE: Duration = Duration.ofHours(6)
    }
}
