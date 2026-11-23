package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.backup.FileOps
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OldAppFixture {
    const val WS_A = "01JOLDWSAAAAAAAAAAAAAAAAAA"
    const val WS_B = "01JOLDWSBBBBBBBBBBBBBBBBBB"
    const val CREATED_AT = "2026-11-03T07:30:00.000Z"
    const val APP_VERSION = "2.3.7+237"

    val attachments: Map<String, ByteArray> = linkedMapOf(
        "F1.pdf" to "%PDF-1.4 synthetic receipt".toByteArray(),
        "F2.jpg" to byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70),
        "F4.m4a" to byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 77, 52, 65),
        "F5.txt" to "binned note attachment".toByteArray(),
    )
    val mirroredOnly: Pair<String, ByteArray> = "F3.png" to byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    const val MIRROR_ITEM_ID = "ITEM-F3"
    const val MIRROR_PATH = "Files/Tasks/Buy milk/Photo (F3).png"

    val settings: Map<String, String> = linkedMapOf(
        "remft_alarm_sound" to "bundled:aw_alarm",
        "remft_reminder_sound" to "file:F4",
        "remft_reminder_profile" to "{\"slots\":[0,2,5,10,30],\"repeatAfterSnooze\":false}",
        "remft_snooze_presets" to "30_min,5_min,1_hour,tomorrow_morning",
        "remft_date_format" to "dmy_long",
        "remft_bin_days" to "45",
        "remft_tasks_sort" to "priority:desc",
        "notification_privacy" to "true",
    )

    fun schemaV3(): List<String> {
        val dir = System.getProperty("fixtures.dir") ?: error("fixtures.dir not set")
        val text = File(dir, "oldapp/schema_v3.sql").readText(Charsets.UTF_8)
        val statements = text.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return statements.filter { it.startsWith("CREATE TABLE") } + statements.filterNot { it.startsWith("CREATE TABLE") }
    }

    fun createOldDatabase(file: File, opener: SqlOpener, schemaVersion: Int = 3, rows: Boolean = true) {
        file.parentFile?.mkdirs()
        FileOps.deleteFileIfExists(file)
        opener.open(file).use { db ->
            for (sql in schemaV3()) db.exec(sql)
            if (rows) insertRows(db)
            db.exec("PRAGMA user_version = $schemaVersion")
        }
        for (side in listOf("-wal", "-shm", "-journal")) FileOps.deleteFileIfExists(File(file.path + side))
    }

    private fun insertRows(db: SqlPort) {
        fun insert(table: String, values: Map<String, Any?>) {
            val columns = values.keys.joinToString(", ") { "\"$it\"" }
            val marks = values.keys.joinToString(", ") { "?" }
            db.exec("INSERT INTO \"$table\" ($columns) VALUES ($marks)", values.values.toList())
        }
        insert(
            "tasks",
            linkedMapOf(
                "id" to "T1", "workspace_id" to WS_A, "title" to "Buy İstanbul MILK", "description" to "Call  Ömer\nnow",
                "status" to "open", "priority" to "high", "due_at" to "2026-11-05T10:45:00.000Z",
                "remind_at" to "2026-11-05T10:15:00.000123Z", "snoozed_until" to "2026-11-05T16:15:00.000 +05:30",
                "alarms_muted_at" to null, "timezone" to "Asia/Kolkata", "is_urgent" to 1L, "requires_acknowledgement" to 1L,
                "series_id" to null, "occurrence_date" to null, "spawned_from_task_id" to null, "sort_order" to 3L,
                "completed_at" to null, "title_fold" to "stale", "description_fold" to null,
                "created_at" to 1762300000L, "updated_at" to "2026-11-01 08:00:00",
            ),
        )
        insert(
            "tasks",
            linkedMapOf(
                "id" to "T2", "workspace_id" to WS_A, "title" to "Weekly review", "description" to null, "status" to "completed",
                "priority" to "none", "due_at" to "2026-11-09T03:30:00.000Z", "timezone" to "Asia/Kolkata", "is_urgent" to 0L,
                "requires_acknowledgement" to 0L, "series_id" to "S1", "occurrence_date" to "2026-11-09",
                "spawned_from_task_id" to null, "sort_order" to 0L, "completed_at" to "2026-11-09T04:00:00.000Z",
                "title_fold" to "weekly review", "created_at" to "2026-11-02T03:30:00.000Z", "updated_at" to "2026-11-09T04:00:00.000Z",
            ),
        )
        insert(
            "tasks",
            linkedMapOf(
                "id" to "T3", "workspace_id" to WS_B, "title" to "From the other workspace", "status" to "waiting",
                "priority" to "low", "alarms_muted_at" to "2026-10-01T00:00:00.000Z", "timezone" to "Europe/London",
                "is_urgent" to 0L, "requires_acknowledgement" to 0L, "sort_order" to -2L,
                "spawned_from_task_id" to "T2", "created_at" to "2026-10-01T00:00:00.000Z",
            ),
        )
        insert("checklist_items", linkedMapOf("id" to "C1", "task_id" to "T1", "title" to "Full fat", "is_done" to 1L, "sort_order" to 0L, "created_at" to "2026-11-01T08:00:00.000Z", "updated_at" to null))
        insert("checklist_items", linkedMapOf("id" to "C2", "task_id" to "T1", "title" to "Skimmed", "is_done" to 0L, "sort_order" to 1L))
        insert(
            "notes",
            linkedMapOf(
                "id" to "N1", "workspace_id" to WS_A, "title" to "Plan", "content_markdown" to "# Plan\n\n**Bold** step and [the task](remft://task/T1)",
                "plain_text" to "stale plain", "is_pinned" to 1L, "is_archived" to 0L, "title_fold" to "plan", "body_fold" to "stale",
                "created_at" to "2026-11-01T09:00:00.000Z", "updated_at" to "2026-11-02T09:00:00.000Z",
            ),
        )
        insert(
            "notes",
            linkedMapOf(
                "id" to "N2", "workspace_id" to WS_A, "title" to "Archived", "content_markdown" to null, "plain_text" to "Only plain TEXT",
                "is_pinned" to 0L, "is_archived" to 1L, "created_at" to "2026-10-01T09:00:00.000Z",
            ),
        )
        insert("note_link_rows", linkedMapOf("id" to "L1", "note_id" to "N1", "entity_type" to "task", "entity_id" to "T1"))
        insert(
            "reminders",
            linkedMapOf(
                "id" to "R1", "task_id" to "T1", "kind" to "remind", "snooze_count" to 2L, "remind_at" to "2026-11-05T10:15:00.000Z",
                "alarm_level" to "urgent", "requires_acknowledgement" to 1L, "status" to "snoozed",
                "snoozed_until" to "2026-11-05T10:45:00.000Z", "acknowledged_at" to null,
                "created_at" to "2026-11-01T08:00:00.000Z", "updated_at" to "2026-11-05T10:16:00.000Z",
            ),
        )
        insert(
            "reminders",
            linkedMapOf(
                "id" to "R2", "task_id" to "T1", "kind" to "due", "snooze_count" to 0L, "remind_at" to "2026-11-05T10:45:00.000Z",
                "alarm_level" to "normal", "requires_acknowledgement" to 0L, "status" to "acknowledged",
                "acknowledged_at" to "2026-11-05T10:46:00.500Z",
            ),
        )
        insert("folders", linkedMapOf("id" to "D1", "workspace_id" to WS_A, "parent_id" to null, "name" to "Docs", "created_at" to "2026-10-01T00:00:00.000Z"))
        insert("folders", linkedMapOf("id" to "D2", "workspace_id" to WS_B, "parent_id" to null, "name" to "docs", "created_at" to "2026-10-02T00:00:00.000Z"))
        insert("folders", linkedMapOf("id" to "D3", "workspace_id" to WS_A, "parent_id" to null, "name" to "Ringtones", "created_at" to "2026-10-03T00:00:00.000Z"))
        insert("folders", linkedMapOf("id" to "D4", "workspace_id" to WS_A, "parent_id" to "D1", "name" to "Bills", "created_at" to "2026-10-04T00:00:00.000Z"))
        file("F1", WS_A, "task", "T1", "Receipt.PDF", "application/pdf", "F1.pdf", null, insert = ::insert)
        file("F2", WS_B, "workspace", WS_B, "Photo  One.jpg", "image/jpeg", "F2.jpg", "D2", insert = ::insert)
        file("F3", WS_A, "task", "T1", "Photo.png", "image/png", "F3.png", null, insert = ::insert)
        file("F4", WS_A, "workspace", WS_A, "Bell.m4a", "audio/mp4", "F4.m4a", "D3", insert = ::insert)
        insert(
            "task_series",
            linkedMapOf(
                "id" to "S1", "workspace_id" to WS_A,
                "rule_json" to "{\"freq\":\"weekly\",\"interval\":1,\"byWeekday\":[{\"day\":\"MO\",\"ordinal\":null}],\"end\":{\"type\":\"never\"}}",
                "template_json" to "{\"title\":\"Weekly review\",\"description\":null,\"priority\":\"none\",\"isUrgent\":false,\"requiresAcknowledgement\":false,\"remindMinutesBefore\":15}",
                "timezone" to "Asia/Kolkata", "anchor_at" to "2026-11-02T09:00:00.000 +05:30",
                "created_at" to "2026-11-02T03:30:00.000Z", "updated_at" to null,
            ),
        )
        insert("series_skips", linkedMapOf("series_id" to "S1", "occurrence_date" to "2026-11-16", "created_at" to "2026-11-10T00:00:00.000Z"))
        insert(
            "trash_items",
            linkedMapOf(
                "id" to "B1", "workspace_id" to WS_A, "kind" to "task", "entity_id" to "T9", "title" to "Binned task",
                "payload" to binTaskPayload(), "local_paths" to "[\"F5.txt\"]", "size_bytes" to 22L, "deleted_at" to "2026-11-04T12:00:00.000Z",
            ),
        )
        insert(
            "trash_items",
            linkedMapOf(
                "id" to "B2", "workspace_id" to WS_A, "kind" to "note", "entity_id" to "N9", "title" to "Binned note",
                "payload" to binNotePayload(), "local_paths" to "[]", "size_bytes" to 0L, "deleted_at" to "2026-11-04T12:30:00.123456Z",
            ),
        )
        insert(
            "alarm_events",
            linkedMapOf(
                "id" to 7L, "at" to "2026-11-05T10:15:00.000Z", "event" to "ring_shown", "lane" to "notification", "kind" to "remind",
                "slot_index" to 0L, "urgent" to 1L, "sound" to "aw_alarm", "level" to "urgent", "fire_at" to "2026-11-05T10:15:00.000Z",
                "task_id" to "T1", "reminder_id" to "R1", "detail" to null,
            ),
        )
        insert(
            "file_mirrors",
            linkedMapOf(
                "local_path" to "F3.png", "sha256" to FileOps.sha256Hex(mirroredOnly.second), "size_bytes" to mirroredOnly.second.size.toLong(),
                "quick_xor_hash" to "AAAAAAAAAAAAAAAAAAAAAAAAAAA=", "state" to "mirrored", "remote_path" to MIRROR_PATH,
                "item_id" to MIRROR_ITEM_ID, "attempts" to 1L, "last_error" to null, "mirrored_at" to "2026-11-03T06:00:00.000Z",
                "listed_at" to "2026-11-03T07:30:00.000Z", "updated_at" to "2026-11-03T07:30:00.000Z",
            ),
        )
    }

    private fun file(
        id: String,
        ws: String,
        targetType: String,
        targetId: String,
        name: String,
        mime: String,
        stored: String,
        folder: String?,
        insert: (String, Map<String, Any?>) -> Unit,
    ) {
        val bytes = attachments[stored] ?: mirroredOnly.second
        insert(
            "file_rows",
            linkedMapOf(
                "id" to id, "workspace_id" to ws, "target_type" to targetType, "target_id" to targetId, "name" to name, "mime" to mime,
                "size_bytes" to bytes.size.toLong(), "folder_id" to folder, "name_fold" to null, "local_path" to stored,
                "created_at" to "2026-11-01T10:00:00.000Z", "updated_at" to "2026-11-01T10:00:00.000Z",
            ),
        )
    }

    fun binTaskPayload(): String = """{"tasks":[{"id":"T9","workspaceId":"$WS_B","title":"Binned task","description":null,"status":"open","priority":"medium","dueAt":1762336800000,"remindAt":"2026-11-05T09:45:00.000123Z","snoozedUntil":null,"alarmsMutedAt":null,"timezone":"Asia/Kolkata","isUrgent":false,"requiresAcknowledgement":false,"seriesId":null,"occurrenceDate":null,"spawnedFromTaskId":null,"sortOrder":0,"completedAt":null,"titleFold":"binned task","descriptionFold":null,"createdAt":1762250400000,"updatedAt":"2026-11-04T12:00:00.000Z"}],"checklist":[{"id":"C9","taskId":"T9","title":"Step","isDone":true,"sortOrder":0,"createdAt":null,"updatedAt":null}],"reminders":[{"id":"R9","taskId":"T9","kind":"remind","snoozeCount":0,"remindAt":"2026-11-05T09:45:00.000Z","alarmLevel":"normal","requiresAcknowledgement":false,"status":"scheduled","snoozedUntil":null,"acknowledgedAt":null,"createdAt":null,"updatedAt":null}],"notes":[],"noteLinks":[],"files":[{"id":"F5","workspaceId":"$WS_B","targetType":"task","targetId":"T9","name":"Notes.txt","mime":"text/plain","sizeBytes":22,"folderId":null,"nameFold":"notes.txt","localPath":"F5.txt","createdAt":"2026-11-04T11:00:00.000Z","updatedAt":"2026-11-04T11:00:00.000Z"}],"folders":[]}"""

    fun binNotePayload(): String = """{"tasks":[],"checklist":[],"reminders":[],"notes":[{"id":"N9","workspaceId":"$WS_A","title":"Gone","contentMarkdown":"Some *text*","plainText":"Some text","isPinned":false,"isArchived":false,"titleFold":"gone","bodyFold":"some text","createdAt":"2026-11-01T00:00:00.000Z","updatedAt":"2026-11-01T00:00:00.000Z"}],"noteLinks":[{"id":"L9","noteId":"N9","entityType":"task","entityId":"T1"}],"files":[],"folders":[]}"""

    data class Built(val zip: File, val manifest: JsonObject)

    fun buildZip(
        dir: File,
        opener: SqlOpener,
        format: Int = 3,
        schemaVersion: Int = 3,
        withMirrored: Boolean = true,
        settingsJson: Map<String, String>? = settings,
        dbName: String = "remft.sqlite",
        rows: Boolean = true,
        manifest: (MutableMap<String, JsonElement>) -> Unit = {},
        entry: (String, ByteArray) -> ByteArray = { _, bytes -> bytes },
    ): Built {
        dir.mkdirs()
        val db = File(dir, "old-${System.nanoTime()}.sqlite")
        createOldDatabase(db, opener, schemaVersion, rows)
        val dbBytes = db.readBytes()
        db.delete()
        val files = ArrayList<JsonElement>()
        for ((name, bytes) in attachments) {
            files.add(
                buildJsonObject {
                    put("path", name)
                    put("size", bytes.size)
                    put("sha256", FileOps.sha256Hex(bytes))
                    put("inZip", true)
                },
            )
        }
        if (withMirrored) {
            files.add(
                buildJsonObject {
                    put("path", mirroredOnly.first)
                    put("size", mirroredOnly.second.size)
                    put("sha256", FileOps.sha256Hex(mirroredOnly.second))
                    put("inZip", false)
                    put(
                        "mirror",
                        buildJsonObject {
                            put("itemId", MIRROR_ITEM_ID)
                            put("path", MIRROR_PATH)
                        },
                    )
                },
            )
        }
        val fields = LinkedHashMap<String, JsonElement>()
        fields["app"] = JsonPrimitive("remft")
        fields["format"] = JsonPrimitive(format)
        fields["appVersion"] = JsonPrimitive(APP_VERSION)
        fields["schemaVersion"] = JsonPrimitive(schemaVersion)
        fields["createdAt"] = JsonPrimitive(CREATED_AT)
        fields["dbSha256"] = JsonPrimitive(FileOps.sha256Hex(dbBytes))
        fields["dbBytes"] = JsonPrimitive(dbBytes.size)
        fields["fileCount"] = JsonPrimitive(attachments.size)
        if (format >= 3) {
            fields["kind"] = JsonPrimitive(if (withMirrored) "mirrored" else "complete")
            fields["files"] = JsonArray(files)
        }
        manifest(fields)
        val json = JsonObject(fields)
        val zip = File(dir, "old-backup-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun add(name: String, bytes: ByteArray) {
                out.putNextEntry(ZipEntry(name))
                out.write(entry(name, bytes))
                out.closeEntry()
            }
            for ((name, bytes) in attachments) add("files/$name", bytes)
            add("manifest.json", json.toString().toByteArray(Charsets.UTF_8))
            if (settingsJson != null) add("settings.json", JsonObject(settingsJson.mapValues { JsonPrimitive(it.value) }).toString().toByteArray())
            add(dbName, dbBytes)
        }
        return Built(zip, json)
    }
}
