package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.identity.WorkspaceIdentity
import com.ftapps.remft.domain.notes.NoteText
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.tasks.TaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

data class OldConversion(val counts: OldAppCounts, val adjustments: Map<String, Int>)

class OldDatabaseConverter(private val opener: SqlOpener, private val log: BackupLog = BackupLog.None) {
    enum class Type { Text, TextOrNull, Int, IntOrNull, Bool, Date, DateOrNull }

    data class Column(val name: String, val type: Type, val default: Any? = null)

    data class Table(val name: String, val columns: List<Column>) {
        val names: List<String> get() = columns.map { it.name }
    }

    private val adjustments = LinkedHashMap<String, Int>()

    private fun note(code: String, table: String, column: String? = null) {
        val key = if (column == null) "$code.$table" else "$code.$table.$column"
        val first = !adjustments.containsKey(key)
        adjustments[key] = (adjustments[key] ?: 0) + 1
        if (first) log.warn("oldbackup.value_adjusted", fields = mapOf("change" to code, "table" to table, "column" to column))
    }

    fun convert(oldDb: File, newDb: File): OldConversion {
        FileOps.deleteFileIfExists(newDb)
        val old = opener.open(oldDb)
        try {
            checkTables(old)
            val workspace = dominantWorkspace(old)
            val target = opener.open(newDb)
            try {
                target.transaction {
                    RoomSchemaV1.create(target)
                    for (table in TABLES) copy(old, target, table, workspace)
                    if (workspace.merged) renameClashingFolders(target)
                }
                checkCounts(old, target)
                val counts = OldAppCounts(
                    tasks = count(target, "tasks"),
                    notes = count(target, "notes"),
                    files = count(target, "file_rows"),
                    folders = count(target, "folders"),
                    series = count(target, "task_series"),
                    reminders = count(target, "reminders"),
                    binEntries = count(target, "trash_items"),
                )
                return OldConversion(counts, LinkedHashMap(adjustments))
            } finally {
                target.close()
            }
        } catch (e: Throwable) {
            runCatching { FileOps.deleteFileIfExists(newDb) }
            throw e
        } finally {
            old.close()
        }
    }

    private fun count(db: SqlPort, table: String): Int = (db.queryLong("SELECT count(*) FROM `$table`") ?: 0L).toInt()

    private fun checkTables(old: SqlPort) {
        val tables = ArrayList<String>()
        old.query("SELECT name FROM sqlite_master WHERE type = 'table'") { row -> (row["name"] as? String)?.let(tables::add) }
        val known = TABLES.map { it.name }.toSet()
        for (name in tables) {
            if (name.startsWith("sqlite_") || name == "android_metadata" || name in known) continue
            val rows = old.queryLong("SELECT count(*) FROM \"${name.replace("\"", "\"\"")}\"") ?: 0L
            if (rows > 0) throw BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, "unknown table with rows")
            log.info("oldbackup.empty_table_ignored")
        }
        for (table in TABLES) {
            if (table.name !in tables) {
                note("table_missing", table.name)
                continue
            }
            val columns = ArrayList<String>()
            old.query("PRAGMA table_info(`${table.name}`)") { row -> (row["name"] as? String)?.let(columns::add) }
            val extra = columns - table.names.toSet()
            if (extra.isNotEmpty()) throw BackupException(BackupErrorCode.OLD_APP_UNSUPPORTED, "unknown column in ${table.name}")
            for (missing in table.names - columns.toSet()) note("column_missing", table.name, missing)
        }
    }

    private fun checkCounts(old: SqlPort, target: SqlPort) {
        for (table in TABLES) {
            val before = runCatching { old.queryLong("SELECT count(*) FROM `${table.name}`") }.getOrNull() ?: 0L
            val after = count(target, table.name).toLong()
            if (before != after) throw IllegalStateException("row count changed in ${table.name}: $before -> $after")
        }
    }

    class Workspace(val id: String?, val merged: Boolean) {
        fun map(value: String?): String? = if (id == null) value else id
    }

    private fun dominantWorkspace(old: SqlPort): Workspace {
        val counts = LinkedHashMap<String, Int>()
        for (table in WorkspaceIdentity.ownedTables) {
            runCatching {
                old.query("SELECT workspace_id AS ws, count(*) AS n FROM `$table` GROUP BY workspace_id") { row ->
                    val ws = row["ws"] as? String ?: return@query
                    counts[ws] = (counts[ws] ?: 0) + ((row["n"] as? Number)?.toInt() ?: 0)
                }
            }
        }
        val dominant = WorkspaceIdentity.dominant(counts) ?: return Workspace(null, false)
        val merged = counts.size > 1
        if (merged) {
            log.warn("oldbackup.workspaces_merged", fields = mapOf("workspaces" to counts.size, "rows" to counts.filterKeys { it != dominant }.values.sum()))
            adjustments["workspacesMerged"] = counts.size - 1
        }
        return Workspace(dominant, merged)
    }

    private fun copy(old: SqlPort, target: SqlPort, table: Table, workspace: Workspace) {
        if (adjustments.containsKey("table_missing.${table.name}")) return
        val columnList = table.names.joinToString(", ") { "`$it`" }
        val placeholders = table.names.joinToString(", ") { "?" }
        val insert = "INSERT INTO `${table.name}` ($columnList) VALUES ($placeholders)"
        val present = ArrayList<String>()
        old.query("PRAGMA table_info(`${table.name}`)") { row -> (row["name"] as? String)?.let(present::add) }
        val select = "SELECT " + present.joinToString(", ") { "`$it`" } + " FROM `${table.name}`"
        old.query(select) { row ->
            val values = LinkedHashMap<String, Any?>()
            for (column in table.columns) values[column.name] = value(table.name, column, row[column.name], row.containsKey(column.name))
            reshape(table.name, values, workspace)
            target.exec(insert, table.names.map { values[it] })
        }
    }

    private fun value(table: String, column: Column, raw: Any?, present: Boolean): Any? {
        if (!present || raw == null) {
            return when (column.type) {
                Type.TextOrNull, Type.IntOrNull, Type.DateOrNull -> null
                else -> {
                    if (present) note("null_in_required", table, column.name)
                    column.default
                }
            }
        }
        return when (column.type) {
            Type.Text, Type.TextOrNull -> text(table, column, raw)
            Type.Int, Type.IntOrNull -> integer(table, column, raw)
            Type.Bool -> bool(table, column, raw)
            Type.Date, Type.DateOrNull -> date(table, column, raw)
        }
    }

    private fun text(table: String, column: Column, raw: Any): String = when (raw) {
        is String -> raw
        is ByteArray -> {
            note("blob_as_text", table, column.name)
            String(raw, Charsets.UTF_8)
        }
        else -> {
            note("number_as_text", table, column.name)
            raw.toString()
        }
    }

    private fun integer(table: String, column: Column, raw: Any): Any = when (raw) {
        is Long -> raw
        is Int -> raw.toLong()
        is Double -> {
            note("real_as_integer", table, column.name)
            raw.toLong()
        }
        is String -> raw.trim().toLongOrNull() ?: raw.also { note("unreadable_integer_kept", table, column.name) }
        else -> raw.also { note("unreadable_integer_kept", table, column.name) }
    }

    private fun bool(table: String, column: Column, raw: Any): Any = when (raw) {
        0L, 1L -> raw
        is Number -> {
            note("boolean_normalised", table, column.name)
            if (raw.toDouble() != 0.0) 1L else 0L
        }
        is String -> when (raw.trim().lowercase()) {
            "1", "true" -> 1L.also { note("boolean_normalised", table, column.name) }
            "0", "false" -> 0L.also { note("boolean_normalised", table, column.name) }
            else -> raw.also { note("unreadable_boolean_kept", table, column.name) }
        }
        else -> raw.also { note("unreadable_boolean_kept", table, column.name) }
    }

    private fun date(table: String, column: Column, raw: Any): Any {
        val instant = OldDates.fromDbValue(raw)
        if (instant == null) {
            note("unreadable_date_kept", table, column.name)
            return if (raw is ByteArray) String(raw, Charsets.UTF_8) else raw.toString()
        }
        val formatted = OldDates.format(instant)
        if (raw is String) {
            if (raw != formatted) note("date_normalised", table, column.name)
        } else {
            note("date_from_number", table, column.name)
        }
        return formatted
    }

    private fun reshape(table: String, row: MutableMap<String, Any?>, workspace: Workspace) {
        if (table in WorkspaceIdentity.ownedTables) {
            val before = row["workspace_id"] as? String
            val after = workspace.map(before)
            if (after != before) {
                row["workspace_id"] = after
                if (table == "file_rows" && row["target_type"] == "workspace" && row["target_id"] == before) row["target_id"] = after
            }
        }
        when (table) {
            "tasks" -> {
                fold(table, row, "title_fold", row["title"] as? String ?: "")
                fold(table, row, "description_fold", row["description"] as? String)
                known(table, "status", row["status"], TaskStatus.entries.map { it.db })
                known(table, "priority", row["priority"], TaskPriority.entries.map { it.db })
            }
            "notes" -> {
                val markdown = row["content_markdown"] as? String
                if (markdown != null) {
                    val plain = NoteText.plainTextFromMarkdown(markdown)
                    if (plain != row["plain_text"]) {
                        row["plain_text"] = plain
                        note("plain_text_derived", table)
                    }
                }
                fold(table, row, "title_fold", row["title"] as? String ?: "")
                fold(table, row, "body_fold", (row["plain_text"] as? String) ?: "")
            }
            "file_rows" -> {
                fold(table, row, "name_fold", row["name"] as? String ?: "")
                row["local_path"] = storedName(table, row["local_path"] as? String)
                known(table, "target_type", row["target_type"], listOf("workspace", "task", "note"))
            }
            "file_mirrors" -> {
                row["local_path"] = storedName(table, row["local_path"] as? String)
                known(table, "state", row["state"], listOf("uploading", "mirrored", "failed", "missing"))
            }
            "reminders" -> known(table, "kind", row["kind"], listOf("remind", "due"))
            "task_series" -> {
                val rule = row["rule_json"] as? String
                if (rule == null || RepeatRule.fromJsonString(rule) == null) note("unreadable_rule_kept", table)
                val template = row["template_json"] as? String
                if (template == null || JsonText.parseOrNull(template) !is JsonObject) note("unreadable_template_kept", table)
            }
            "trash_items" -> {
                known(table, "kind", row["kind"], listOf("task", "note", "file", "folder"))
                val payload = row["payload"] as? String
                if (payload != null) row["payload"] = binPayload(payload, workspace)
                row["local_paths"] = localPaths(row["local_paths"] as? String, row["payload"] as? String)
            }
        }
    }

    private fun known(table: String, column: String, value: Any?, allowed: List<String>) {
        if (value !in allowed) note("unknown_value_kept", table, column)
    }

    private fun fold(table: String, row: MutableMap<String, Any?>, column: String, source: String?) {
        val folded = source?.let(Fold::searchText)
        if (folded != row[column]) {
            row[column] = folded
            note("fold_recomputed", table, column)
        }
    }

    private fun storedName(table: String, raw: String?): String? {
        if (raw == null) return null
        val base = PathText.basename(raw.replace('\\', '/'))
        if (base != raw) note("stored_name_trimmed", table, "local_path")
        return base
    }

    private fun localPaths(raw: String?, payload: String?): String {
        val parsed = raw?.let { JsonText.parseOrNull(it) as? JsonArray }
        val names = if (parsed != null && parsed.all { JsonText.stringOf(it) != null }) {
            parsed.map { JsonText.stringOf(it)!! }
        } else {
            note("local_paths_rebuilt", "trash_items")
            payloadLocalPaths(payload)
        }
        val cleaned = names.filter { it.isNotEmpty() }.map { PathText.basename(it.replace('\\', '/')) }
        if (cleaned != names) note("stored_name_trimmed", "trash_items", "local_paths")
        val encoded = JsonText.encode(JsonArray(cleaned.map { JsonPrimitive(it) }))
        return if (raw != null && parsed != null && cleaned == names && raw == encoded) raw else encoded
    }

    private fun payloadLocalPaths(payload: String?): List<String> {
        val json = payload?.let { JsonText.parseOrNull(it) as? JsonObject } ?: return emptyList()
        val files = json["files"] as? JsonArray ?: return emptyList()
        return files.mapNotNull { (it as? JsonObject)?.let { f -> JsonText.stringOf(f["localPath"]) } }
    }

    private fun binPayload(raw: String, workspace: Workspace): String {
        val json = JsonText.parseOrNull(raw) as? JsonObject
        if (json == null) {
            note("unreadable_bin_payload_kept", "trash_items")
            return raw
        }
        var changed = false
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in json) {
            if (value is JsonArray && key in PAYLOAD_LISTS) {
                out[key] = JsonArray(
                    value.map { item ->
                        if (item is JsonObject) {
                            val next = binRow(key, item, workspace)
                            if (next != item) changed = true
                            next
                        } else {
                            item
                        }
                    },
                )
            } else {
                out[key] = value
            }
        }
        val result = if (changed) JsonText.encode(JsonObject(out)) else raw
        if (runCatching { BinPayload.parse(result) }.isFailure) note("unreadable_bin_payload_kept", "trash_items")
        return result
    }

    private fun binRow(list: String, item: JsonObject, workspace: Workspace): JsonObject {
        val row = LinkedHashMap<String, JsonElement>(item)
        for (key in PAYLOAD_DATE_KEYS) {
            val value = row[key] ?: continue
            if (JsonText.isNull(value)) continue
            val millis = JsonText.intOf(value) ?: JsonText.numberOf(value)?.toLong()
            val text = JsonText.stringOf(value)
            val instant = when {
                millis != null -> OldDates.fromJsonNumber(millis).also { note("bin_date_from_number", "trash_items", key) }
                text != null -> OldDates.fromText(text, numbersAreMillis = true)
                else -> null
            }
            if (instant == null) {
                note("unreadable_bin_date_kept", "trash_items", key)
                continue
            }
            val formatted = OldDates.format(instant)
            if (text != formatted) row[key] = JsonPrimitive(formatted)
        }
        val before = JsonText.stringOf(row["workspaceId"])
        if (before != null) {
            val after = workspace.map(before)
            if (after != before && after != null) {
                row["workspaceId"] = JsonPrimitive(after)
                if (list == "files" && JsonText.stringOf(row["targetType"]) == "workspace" && JsonText.stringOf(row["targetId"]) == before) {
                    row["targetId"] = JsonPrimitive(after)
                }
            }
        }
        when (list) {
            "tasks" -> {
                foldJson(row, "titleFold", JsonText.stringOf(row["title"]))
                foldJson(row, "descriptionFold", JsonText.stringOf(row["description"]), nullable = true)
            }
            "notes" -> {
                val markdown = JsonText.stringOf(row["contentMarkdown"])
                if (markdown != null) {
                    val plain = NoteText.plainTextFromMarkdown(markdown)
                    if (JsonText.stringOf(row["plainText"]) != plain) row["plainText"] = JsonPrimitive(plain)
                }
                foldJson(row, "titleFold", JsonText.stringOf(row["title"]))
                foldJson(row, "bodyFold", JsonText.stringOf(row["plainText"]) ?: "")
            }
            "files" -> {
                foldJson(row, "nameFold", JsonText.stringOf(row["name"]))
                val stored = JsonText.stringOf(row["localPath"])
                if (stored != null) {
                    val base = PathText.basename(stored.replace('\\', '/'))
                    if (base != stored) row["localPath"] = JsonPrimitive(base)
                }
            }
        }
        return JsonObject(row)
    }

    private fun foldJson(row: MutableMap<String, JsonElement>, key: String, source: String?, nullable: Boolean = false) {
        if (source == null) {
            if (nullable && !JsonText.isNull(row[key])) row[key] = JsonNull
            return
        }
        val folded = Fold.searchText(source)
        if (JsonText.stringOf(row[key]) != folded) row[key] = JsonPrimitive(folded)
    }

    private fun renameClashingFolders(target: SqlPort) {
        data class Folder(val id: String, val parent: String?, val name: String)
        val folders = ArrayList<Folder>()
        target.query("SELECT id, parent_id, name FROM folders ORDER BY created_at, id") { row ->
            folders.add(Folder(row["id"] as String, row["parent_id"] as? String, row["name"] as String))
        }
        val taken = HashMap<String?, MutableList<String>>()
        for (folder in folders) {
            val siblings = taken.getOrPut(folder.parent) { ArrayList() }
            val free = BinPayload.freeFolderName(folder.name, siblings)
            if (free != folder.name) {
                target.exec("UPDATE folders SET name = ? WHERE id = ?", listOf(free, folder.id))
                note("folder_renamed_after_merge", "folders")
            }
            siblings.add(free)
        }
    }

    companion object {
        private val PAYLOAD_LISTS = setOf("tasks", "checklist", "reminders", "notes", "noteLinks", "files", "folders", "series", "seriesSkips")
        private val PAYLOAD_DATE_KEYS = listOf(
            "dueAt", "remindAt", "snoozedUntil", "alarmsMutedAt", "completedAt", "acknowledgedAt", "createdAt", "updatedAt",
        )

        private fun t(name: String, default: String? = "") = Column(name, Type.Text, default)
        private fun tn(name: String) = Column(name, Type.TextOrNull)
        private fun i(name: String, default: Long = 0) = Column(name, Type.Int, default)
        private fun b(name: String) = Column(name, Type.Bool, 0L)
        private fun d(name: String) = Column(name, Type.Date, "")
        private fun dn(name: String) = Column(name, Type.DateOrNull)

        val TABLES: List<Table> = listOf(
            Table(
                "tasks",
                listOf(
                    t("id"), t("workspace_id"), t("title"), tn("description"), t("status", "open"), t("priority", "none"),
                    dn("due_at"), dn("remind_at"), dn("snoozed_until"), dn("alarms_muted_at"), t("timezone", "Asia/Kolkata"),
                    b("is_urgent"), b("requires_acknowledgement"), tn("series_id"), tn("occurrence_date"), tn("spawned_from_task_id"),
                    i("sort_order"), dn("completed_at"), tn("title_fold"), tn("description_fold"), dn("created_at"), dn("updated_at"),
                ),
            ),
            Table(
                "checklist_items",
                listOf(t("id"), t("task_id"), t("title"), b("is_done"), i("sort_order"), dn("created_at"), dn("updated_at")),
            ),
            Table(
                "notes",
                listOf(
                    t("id"), t("workspace_id"), t("title"), tn("content_markdown"), tn("plain_text"), b("is_pinned"), b("is_archived"),
                    tn("title_fold"), tn("body_fold"), dn("created_at"), dn("updated_at"),
                ),
            ),
            Table("note_link_rows", listOf(t("id"), t("note_id"), t("entity_type"), t("entity_id"))),
            Table(
                "reminders",
                listOf(
                    t("id"), t("task_id"), t("kind", "remind"), i("snooze_count"), d("remind_at"), t("alarm_level", "normal"),
                    b("requires_acknowledgement"), t("status", "scheduled"), dn("snoozed_until"), dn("acknowledged_at"),
                    dn("created_at"), dn("updated_at"),
                ),
            ),
            Table(
                "file_rows",
                listOf(
                    t("id"), t("workspace_id"), t("target_type"), t("target_id"), t("name"), t("mime"), i("size_bytes"),
                    tn("folder_id"), tn("name_fold"), tn("local_path"), dn("created_at"), dn("updated_at"),
                ),
            ),
            Table("folders", listOf(t("id"), t("workspace_id"), tn("parent_id"), t("name"), dn("created_at"), dn("updated_at"))),
            Table(
                "task_series",
                listOf(
                    t("id"), t("workspace_id"), t("rule_json"), t("template_json", "{}"), t("timezone", "Asia/Kolkata"), d("anchor_at"),
                    dn("created_at"), dn("updated_at"),
                ),
            ),
            Table("series_skips", listOf(t("series_id"), t("occurrence_date"), dn("created_at"))),
            Table(
                "trash_items",
                listOf(
                    t("id"), t("workspace_id"), t("kind"), t("entity_id"), t("title"), t("payload", "{}"), t("local_paths", "[]"),
                    i("size_bytes"), d("deleted_at"),
                ),
            ),
            Table(
                "alarm_events",
                listOf(
                    Column("id", Type.IntOrNull), d("at"), t("event"), t("lane"), tn("kind"), Column("slot_index", Type.IntOrNull),
                    b("urgent"), tn("sound"), tn("level"), dn("fire_at"), tn("task_id"), tn("reminder_id"), tn("detail"),
                ),
            ),
            Table(
                "file_mirrors",
                listOf(
                    t("local_path"), t("sha256"), i("size_bytes"), tn("quick_xor_hash"), t("state", "uploading"), tn("remote_path"),
                    tn("item_id"), i("attempts"), tn("last_error"), dn("mirrored_at"), dn("listed_at"), dn("updated_at"),
                ),
            ),
        )
    }
}
