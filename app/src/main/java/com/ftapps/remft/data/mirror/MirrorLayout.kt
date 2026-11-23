package com.ftapps.remft.data.mirror

import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.bin.BinFile
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.backup.mirror.MirrorFolder
import com.ftapps.remft.domain.backup.mirror.MirrorFolders
import com.ftapps.remft.domain.backup.mirror.MirrorPaths

data class FileSpot(val storedName: String, val parentKey: String?, val parentPath: String, val name: String)

data class MirrorLayout(
    val folders: Map<String, String>,
    val files: Map<String, FileSpot>,
    val complete: Boolean,
) {
    fun parentOf(spot: FileSpot, placed: Map<String, MirrorFolder>): String {
        val key = spot.parentKey ?: return spot.parentPath
        val want = folders[key] ?: return spot.parentPath
        return placed[key]?.takeIf { it.want == want }?.path ?: want
    }

    fun pathOf(spot: FileSpot, placed: Map<String, MirrorFolder>): String = "${parentOf(spot, placed)}/${spot.name}"

    companion object {
        private const val MAX_FOLDER_DEPTH = 12

        fun read(sql: MirrorSql): MirrorLayout {
            val live = sql.query("SELECT id, name, parent_id FROM folders") { c ->
                c.getString(0) to (c.getString(1) to c.stringOrNull(2))
            }.toMap()
            val payloads = ArrayList<BinPayload>()
            val unreadable = ArrayList<String>()
            var complete = true
            sql.query("SELECT payload, local_paths FROM trash_items") { c -> c.stringOrNull(0) to c.stringOrNull(1) }.forEach { (payload, paths) ->
                val parsed = payload?.let { runCatching { BinPayload.parse(it) }.getOrNull() }
                if (parsed != null) {
                    payloads.add(parsed)
                } else {
                    complete = false
                    paths?.let { runCatching { BinPayload.parseLocalPaths(it) }.getOrNull() }?.let { unreadable.addAll(it) }
                }
            }
            val binnedFolders = LinkedHashMap<String, Pair<String, String?>>()
            for (p in payloads) for (f in p.folders) if (f.id !in live) binnedFolders.putIfAbsent(f.id, f.name to f.parentId)

            fun chain(folderId: String?): List<String> {
                val out = ArrayList<String>()
                val seen = HashSet<String>()
                var id = folderId
                while (id != null && seen.add(id) && out.size < MAX_FOLDER_DEPTH) {
                    val folder = live[id] ?: binnedFolders[id] ?: break
                    out.add(0, folder.first)
                    id = folder.second
                }
                return out
            }

            val desired = LinkedHashMap<String, String>()
            for (id in live.keys) desired[MirrorFolders.folderKey(id)] = MirrorPaths.folderPath(chain(id))
            for (id in binnedFolders.keys) desired[MirrorFolders.folderKey(id)] = MirrorPaths.binned(MirrorPaths.folderPath(chain(id)))

            val liveTitles = HashMap<String, String?>()
            fun liveTitle(type: String, id: String): String? = liveTitles.getOrPut("$type:$id") {
                val table = if (type == "task") "tasks" else if (type == "note") "notes" else return@getOrPut null
                sql.query("SELECT title FROM $table WHERE id = ?", arrayOf(id)) { it.stringOrNull(0) }.firstOrNull()
            }
            val binTitles = HashMap<String, String>()
            for (p in payloads) {
                p.tasks.forEach { binTitles.putIfAbsent("task:${it.id}", it.title) }
                p.notes.forEach { binTitles.putIfAbsent("note:${it.id}", it.title) }
            }
            fun ownerTitle(type: String, id: String): String? = liveTitle(type, id) ?: binTitles["$type:$id"]

            data class Raw(val stored: String, val name: String, val parentPath: String, val folderKey: String?, val ownerKey: String?)

            val liveFiles = LinkedHashMap<String, Raw>()
            val ownersLive = LinkedHashMap<String, String>()
            sql.query("SELECT id, name, target_type, target_id, folder_id, local_path FROM file_rows") { c ->
                val stored = c.stringOrNull(5)
                if (stored.isNullOrEmpty()) return@query
                val base = PathText.basename(stored)
                val type = c.getString(2)
                val targetId = c.getString(3)
                val name = MirrorPaths.fileName(c.getString(0), c.getString(1), base)
                liveFiles[base] = when (type) {
                    "workspace" -> {
                        val folder = c.stringOrNull(4)?.takeIf { it in live }
                        Raw(base, name, MirrorPaths.folderPath(chain(folder)), folder?.let { MirrorFolders.folderKey(it) }, null)
                    }
                    "task", "note" -> {
                        val key = MirrorFolders.ownerKey(type, targetId)
                        val path = MirrorPaths.ownerPath(type, ownerTitle(type, targetId), targetId)
                        ownersLive[key] = path
                        Raw(base, name, path, null, key)
                    }
                    else -> Raw(base, name, MirrorPaths.ownerPath(type, null, null), null, null)
                }
            }

            fun binnedRaw(f: BinFile, base: String): Raw {
                val name = MirrorPaths.fileName(f.id, f.name, base)
                return when (f.targetType) {
                    "workspace" -> {
                        val folder = f.folderId?.takeIf { it in live || it in binnedFolders }
                        val key = folder?.takeIf { it in binnedFolders }?.let { MirrorFolders.folderKey(it) }
                        Raw(base, name, MirrorPaths.binned(MirrorPaths.folderPath(chain(folder))), key, null)
                    }
                    "task", "note" -> {
                        val key = MirrorFolders.ownerKey(f.targetType, f.targetId)
                        val path = MirrorPaths.ownerPath(f.targetType, ownerTitle(f.targetType, f.targetId), f.targetId)
                        Raw(base, name, MirrorPaths.binned(path), null, key)
                    }
                    else -> Raw(base, name, MirrorPaths.binned(MirrorPaths.ownerPath(f.targetType, null, null)), null, null)
                }
            }

            val binnedFiles = LinkedHashMap<String, Raw>()
            val ownersBinned = LinkedHashMap<String, String>()
            for (p in payloads) {
                for (f in p.files) {
                    val stored = f.localPath
                    if (stored.isNullOrEmpty()) continue
                    val base = PathText.basename(stored)
                    if (base in liveFiles || base in binnedFiles) continue
                    val raw = binnedRaw(f, base)
                    binnedFiles[base] = raw
                    raw.ownerKey?.let { if (it !in ownersLive) ownersBinned.putIfAbsent(it, raw.parentPath) }
                }
            }
            for (stored in unreadable) {
                val base = PathText.basename(stored)
                if (base.isEmpty() || base in liveFiles || base in binnedFiles) continue
                binnedFiles[base] = Raw(base, MirrorPaths.leafName(MirrorPaths.binnedPath(base)), MirrorPaths.BIN_FOLDER, null, null)
            }

            val owners = LinkedHashMap<String, String>().apply {
                putAll(ownersLive)
                putAll(ownersBinned)
            }
            val shared = owners.values.groupingBy { it.lowercase() }.eachCount()
            for ((key, path) in owners) if (shared[path.lowercase()] == 1) desired[key] = path

            val files = LinkedHashMap<String, FileSpot>()
            for (raw in liveFiles.values + binnedFiles.values) {
                val key = (raw.folderKey ?: raw.ownerKey)?.takeIf { desired[it] == raw.parentPath }
                files[raw.stored] = FileSpot(raw.stored, key, raw.parentPath, raw.name)
            }
            val ordered = LinkedHashMap<String, String>()
            desired.entries.sortedBy { MirrorPaths.depth(it.value) }.forEach { ordered[it.key] = it.value }
            return MirrorLayout(ordered, files, complete)
        }
    }
}
