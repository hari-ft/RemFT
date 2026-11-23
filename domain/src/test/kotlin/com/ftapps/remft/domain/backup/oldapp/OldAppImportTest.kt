package com.ftapps.remft.domain.backup.oldapp

import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.backup.BackupErrorCode
import com.ftapps.remft.domain.backup.BackupException
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupKind
import com.ftapps.remft.domain.backup.BackupValidator
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.SqliteHeader
import com.ftapps.remft.domain.backup.ValidatedBackup
import com.ftapps.remft.domain.backup.bin.BinPayload
import com.ftapps.remft.domain.backup.oldapp.OldAppFixture.WS_A
import com.ftapps.remft.domain.backup.restore.BackupRestore
import com.ftapps.remft.domain.notes.NoteText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class OldAppImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root: File get() = temp.root
    private val scratch: File get() = File(root, "scratch")
    private val opener = JdbcSqlPort.opener

    private fun validate(zip: File, oldApp: SqlOpener? = opener): ValidatedBackup =
        BackupValidator.validate(zip, RoomSchemaV1.VERSION, scratch, oldApp = oldApp)

    private fun refused(zip: File, oldApp: SqlOpener? = opener): String? = try {
        validate(zip, oldApp).discard()
        null
    } catch (e: BackupException) {
        e.code
    }

    private fun rows(db: File, sql: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        JdbcSqlPort(db).use { it.query(sql) { row -> out.add(row) } }
        return out
    }

    private fun row(db: File, table: String, key: String, id: String): Map<String, Any?> =
        rows(db, "SELECT * FROM $table WHERE $key = '$id'").single()

    private fun assertRow(expected: Map<String, Any?>, actual: Map<String, Any?>) {
        for ((column, value) in expected) assertEquals("column $column", value, actual[column])
    }

    @Test
    fun theNextBackupFormatSkipsTheOldAppNumbers() {
        assertEquals(1, BackupFormat.CURRENT)
        assertEquals(4, BackupFormat.NEXT_FORMAT)
        assertEquals(setOf(2, 3), BackupFormat.OLD_APP_FORMATS)
        assertFalse(BackupFormat.CURRENT in BackupFormat.OLD_APP_FORMATS)
        assertFalse(BackupFormat.NEXT_FORMAT in BackupFormat.OLD_APP_FORMATS)
        assertTrue(BackupFormat.CURRENT == 1 || BackupFormat.CURRENT >= BackupFormat.NEXT_FORMAT)
        assertTrue(BackupFormat.OLD_APP_FORMATS.all { it > BackupFormat.CURRENT && it < BackupFormat.NEXT_FORMAT })
    }

    @Test
    fun roomSchemaMatchesTheExportedSchema() {
        val schemaFile = File(System.getProperty("fixtures.dir")).parentFile
            .resolve("app/schemas/com.ftapps.remft.data.db.RemftDatabase/1.json")
        val database = (JsonText.parse(schemaFile.readText()) as JsonObject)["database"] as JsonObject
        assertEquals(RoomSchemaV1.VERSION.toLong(), JsonText.intOf(database["version"]))
        assertEquals(RoomSchemaV1.IDENTITY_HASH, JsonText.stringOf(database["identityHash"]))
        val entities = database["entities"] as JsonArray
        val tables = entities.map { e ->
            val o = e as JsonObject
            JsonText.stringOf(o["createSql"])!!.replace("\${TABLE_NAME}", JsonText.stringOf(o["tableName"])!!)
        }
        val indices = entities.flatMap { e ->
            val o = e as JsonObject
            (o["indices"] as? JsonArray).orEmpty().map {
                JsonText.stringOf((it as JsonObject)["createSql"])!!.replace("\${TABLE_NAME}", JsonText.stringOf(o["tableName"])!!)
            }
        }
        assertEquals(tables, RoomSchemaV1.tables)
        assertEquals(indices, RoomSchemaV1.indices)
        assertEquals((database["setupQueries"] as JsonArray).map { JsonText.stringOf(it) }, RoomSchemaV1.setup)
        assertEquals(entities.map { JsonText.stringOf((it as JsonObject)["tableName"]) }, OldDatabaseConverter.TABLES.map { it.name })
    }

    @Test
    fun aFormat3BackupIsValidatedConvertedAndRestoredValueForValue() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener)
        val live = File(root, "live")
        val dbFile = File(live, "remft.sqlite")
        val filesDir = File(live, "files")
        val backup = validate(built.zip)
        assertTrue(backup.staging.path.startsWith(scratch.path))
        assertFalse(File(backup.staging, OldAppValidator.OLD_DB_FOLDER).exists())
        assertFalse(live.exists())

        assertNotNull(backup.oldApp)
        val import = backup.oldApp!!
        assertEquals(OldAppFixture.APP_VERSION, import.appVersion)
        assertEquals(3, import.format)
        assertEquals(OldAppCounts(tasks = 3, notes = 2, files = 4, folders = 4, series = 1, reminders = 2, binEntries = 2), import.counts)
        assertEquals(1, import.listedOnly)
        assertEquals(1, import.adjustments["workspacesMerged"])

        val manifest = backup.manifest
        assertEquals(BackupFormat.CURRENT, manifest.format)
        assertEquals(RoomSchemaV1.VERSION, manifest.schemaVersion)
        assertEquals(Instant.parse(OldAppFixture.CREATED_AT), manifest.createdAt)
        assertEquals(OldAppFixture.APP_VERSION, manifest.appVersion)
        assertEquals(BackupKind.MIRRORED, manifest.kind)
        assertEquals(4, manifest.fileCount)
        assertEquals(FileOps.sha256Hex(backup.dbFile), manifest.dbSha256)
        assertEquals(backup.dbFile.length(), manifest.dbBytes)
        val mirrored = manifest.listedOnly.single()
        assertEquals("F3.png", mirrored.path)
        assertEquals(OldAppFixture.MIRROR_ITEM_ID, mirrored.mirror!!.itemId)
        assertEquals(OldAppFixture.MIRROR_PATH, mirrored.mirror!!.remotePath)

        assertEquals(
            mapOf(
                "remft_alarm_sound" to "bundled:alarm",
                "remft_reminder_sound" to "file:F4",
                "remft_reminder_profile" to "{\"slots\":[0,30,60],\"repeatAfterSnooze\":false}",
                "remft_snooze_presets" to "30_min,5_min,1_hour,tomorrow_morning",
                "notification_privacy" to "true",
                "remft_date_format" to "dmy_long",
                "remft_tasks_sort" to "priority:desc",
            ),
            backup.settings,
        )

        live.mkdirs()
        BackupRestore.restore(backup, dbFile, filesDir)
        assertFalse(backup.staging.exists())
        for ((name, bytes) in OldAppFixture.attachments) assertArrayEquals(name, bytes, File(filesDir, name).readBytes())
        assertFalse(File(filesDir, "F3.png").exists())

        val head = SqliteHeader.read(dbFile)
        assertEquals(1L, SqliteHeader.userVersion(head))
        assertEquals(RoomSchemaV1.IDENTITY_HASH, row(dbFile, "room_master_table", "id", "42")["identity_hash"])
        val schema = rows(dbFile, "SELECT name, sql FROM sqlite_master WHERE type IN ('table', 'index') AND name NOT LIKE 'sqlite_%' ORDER BY name")
        val expectedSql = (RoomSchemaV1.tables + RoomSchemaV1.indices + RoomSchemaV1.setup.first())
            .map { it.replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE").replace("CREATE INDEX IF NOT EXISTS", "CREATE INDEX") }
            .sorted()
        assertEquals(expectedSql, schema.map { it["sql"] as String }.sorted())

        assertRow(
            mapOf(
                "workspace_id" to WS_A, "title" to "Buy İstanbul MILK", "description" to "Call  Ömer\nnow", "status" to "open",
                "priority" to "high", "due_at" to "2026-11-05T10:45:00.000Z", "remind_at" to "2026-11-05T10:15:00.000Z",
                "snoozed_until" to "2026-11-05T10:45:00.000Z", "alarms_muted_at" to null, "timezone" to "Asia/Kolkata",
                "is_urgent" to 1L, "requires_acknowledgement" to 1L, "series_id" to null, "occurrence_date" to null,
                "spawned_from_task_id" to null, "sort_order" to 3L, "completed_at" to null, "title_fold" to "buy istanbul milk",
                "description_fold" to Fold.searchText("Call  Ömer\nnow"), "created_at" to "2025-11-04T23:46:40.000Z",
                "updated_at" to "2026-11-01T08:00:00.000Z",
            ),
            row(dbFile, "tasks", "id", "T1"),
        )
        assertEquals("call omer now", Fold.searchText("Call  Ömer\nnow"))
        assertRow(
            mapOf(
                "workspace_id" to WS_A, "status" to "completed", "series_id" to "S1", "occurrence_date" to "2026-11-09",
                "completed_at" to "2026-11-09T04:00:00.000Z", "title_fold" to "weekly review", "description_fold" to null,
                "created_at" to "2026-11-02T03:30:00.000Z",
            ),
            row(dbFile, "tasks", "id", "T2"),
        )
        assertRow(
            mapOf(
                "workspace_id" to WS_A, "status" to "waiting", "priority" to "low", "timezone" to "Europe/London",
                "alarms_muted_at" to "2026-10-01T00:00:00.000Z", "sort_order" to -2L, "spawned_from_task_id" to "T2",
                "updated_at" to null, "is_urgent" to 0L,
            ),
            row(dbFile, "tasks", "id", "T3"),
        )
        assertRow(
            mapOf("task_id" to "T1", "title" to "Full fat", "is_done" to 1L, "sort_order" to 0L, "created_at" to "2026-11-01T08:00:00.000Z", "updated_at" to null),
            row(dbFile, "checklist_items", "id", "C1"),
        )
        assertRow(mapOf("is_done" to 0L, "sort_order" to 1L), row(dbFile, "checklist_items", "id", "C2"))
        val markdown = "# Plan\n\n**Bold** step and [the task](remft://task/T1)"
        val plain = NoteText.plainTextFromMarkdown(markdown)
        assertRow(
            mapOf(
                "workspace_id" to WS_A, "title" to "Plan", "content_markdown" to markdown, "plain_text" to plain, "is_pinned" to 1L,
                "is_archived" to 0L, "title_fold" to "plan", "body_fold" to Fold.searchText(plain),
                "created_at" to "2026-11-01T09:00:00.000Z", "updated_at" to "2026-11-02T09:00:00.000Z",
            ),
            row(dbFile, "notes", "id", "N1"),
        )
        assertRow(
            mapOf("content_markdown" to null, "plain_text" to "Only plain TEXT", "is_archived" to 1L, "body_fold" to "only plain text", "title_fold" to "archived"),
            row(dbFile, "notes", "id", "N2"),
        )
        assertRow(mapOf("note_id" to "N1", "entity_type" to "task", "entity_id" to "T1"), row(dbFile, "note_link_rows", "id", "L1"))
        assertRow(
            mapOf(
                "task_id" to "T1", "kind" to "remind", "snooze_count" to 2L, "remind_at" to "2026-11-05T10:15:00.000Z", "alarm_level" to "urgent",
                "requires_acknowledgement" to 1L, "status" to "snoozed", "snoozed_until" to "2026-11-05T10:45:00.000Z", "acknowledged_at" to null,
                "created_at" to "2026-11-01T08:00:00.000Z", "updated_at" to "2026-11-05T10:16:00.000Z",
            ),
            row(dbFile, "reminders", "id", "R1"),
        )
        assertRow(mapOf("kind" to "due", "status" to "acknowledged", "acknowledged_at" to "2026-11-05T10:46:00.500Z"), row(dbFile, "reminders", "id", "R2"))
        assertRow(
            mapOf(
                "workspace_id" to WS_A, "target_type" to "task", "target_id" to "T1", "name" to "Receipt.PDF", "mime" to "application/pdf",
                "size_bytes" to OldAppFixture.attachments.getValue("F1.pdf").size.toLong(), "folder_id" to null, "name_fold" to "receipt.pdf",
                "local_path" to "F1.pdf", "created_at" to "2026-11-01T10:00:00.000Z",
            ),
            row(dbFile, "file_rows", "id", "F1"),
        )
        assertRow(
            mapOf("workspace_id" to WS_A, "target_type" to "workspace", "target_id" to WS_A, "folder_id" to "D2", "name_fold" to "photo one.jpg", "local_path" to "F2.jpg"),
            row(dbFile, "file_rows", "id", "F2"),
        )
        assertRow(mapOf("local_path" to "F3.png"), row(dbFile, "file_rows", "id", "F3"))
        assertRow(mapOf("folder_id" to "D3", "local_path" to "F4.m4a", "target_id" to WS_A), row(dbFile, "file_rows", "id", "F4"))
        assertRow(mapOf("workspace_id" to WS_A, "name" to "Docs", "parent_id" to null), row(dbFile, "folders", "id", "D1"))
        assertRow(mapOf("workspace_id" to WS_A, "name" to "docs (2)", "created_at" to "2026-10-02T00:00:00.000Z"), row(dbFile, "folders", "id", "D2"))
        assertRow(mapOf("name" to "Ringtones"), row(dbFile, "folders", "id", "D3"))
        assertRow(mapOf("name" to "Bills", "parent_id" to "D1"), row(dbFile, "folders", "id", "D4"))
        val series = row(dbFile, "task_series", "id", "S1")
        assertRow(
            mapOf(
                "workspace_id" to WS_A, "timezone" to "Asia/Kolkata", "anchor_at" to "2026-11-02T03:30:00.000Z",
                "created_at" to "2026-11-02T03:30:00.000Z", "updated_at" to null,
                "rule_json" to "{\"freq\":\"weekly\",\"interval\":1,\"byWeekday\":[{\"day\":\"MO\",\"ordinal\":null}],\"end\":{\"type\":\"never\"}}",
                "template_json" to "{\"title\":\"Weekly review\",\"description\":null,\"priority\":\"none\",\"isUrgent\":false,\"requiresAcknowledgement\":false,\"remindMinutesBefore\":15}",
            ),
            series,
        )
        assertEquals(
            listOf(mapOf<String, Any?>("series_id" to "S1", "occurrence_date" to "2026-11-16", "created_at" to "2026-11-10T00:00:00.000Z")),
            rows(dbFile, "SELECT * FROM series_skips"),
        )

        val b1 = row(dbFile, "trash_items", "id", "B1")
        assertRow(
            mapOf("workspace_id" to WS_A, "kind" to "task", "entity_id" to "T9", "title" to "Binned task", "local_paths" to "[\"F5.txt\"]", "size_bytes" to 22L, "deleted_at" to "2026-11-04T12:00:00.000Z"),
            b1,
        )
        val p1 = BinPayload.parse(b1["payload"] as String)
        val t9 = p1.tasks.single()
        assertEquals(WS_A, t9.workspaceId)
        assertEquals(Instant.parse("2025-11-05T10:00:00Z"), t9.dueAt)
        assertEquals(Instant.parse("2026-11-05T09:45:00Z"), t9.remindAt)
        assertEquals(Instant.parse("2025-11-04T10:00:00Z"), t9.createdAt)
        assertEquals("medium", t9.priority)
        assertEquals("binned task", t9.titleFold)
        assertTrue(p1.checklist.single().isDone)
        assertEquals(Instant.parse("2026-11-05T09:45:00Z"), p1.reminders.single().remindAt)
        val f5 = p1.files.single()
        assertEquals(WS_A, f5.workspaceId)
        assertEquals("T9", f5.targetId)
        assertEquals("F5.txt", f5.localPath)
        val rawTask = ((JsonText.parse(b1["payload"] as String) as JsonObject)["tasks"] as JsonArray)[0] as JsonObject
        assertEquals(JsonPrimitive("2025-11-05T10:00:00.000Z"), rawTask["dueAt"])

        val b2 = row(dbFile, "trash_items", "id", "B2")
        assertEquals("2026-11-04T12:30:00.123Z", b2["deleted_at"])
        assertEquals("[]", b2["local_paths"])
        val p2 = BinPayload.parse(b2["payload"] as String)
        assertEquals("Gone", p2.notes.single().title)
        assertEquals("L9", p2.noteLinks.single().id)

        assertRow(
            mapOf(
                "id" to 7L, "at" to "2026-11-05T10:15:00.000Z", "event" to "ring_shown", "lane" to "notification", "kind" to "remind",
                "slot_index" to 0L, "urgent" to 1L, "sound" to "aw_alarm", "level" to "urgent", "fire_at" to "2026-11-05T10:15:00.000Z",
                "task_id" to "T1", "reminder_id" to "R1", "detail" to null,
            ),
            rows(dbFile, "SELECT * FROM alarm_events").single(),
        )
        assertRow(
            mapOf(
                "local_path" to "F3.png", "state" to "mirrored", "item_id" to OldAppFixture.MIRROR_ITEM_ID, "remote_path" to OldAppFixture.MIRROR_PATH,
                "attempts" to 1L, "mirrored_at" to "2026-11-03T06:00:00.000Z", "size_bytes" to OldAppFixture.mirroredOnly.second.size.toLong(),
            ),
            rows(dbFile, "SELECT * FROM file_mirrors").single(),
        )
        for ((table, n) in mapOf("tasks" to 3, "checklist_items" to 2, "notes" to 2, "note_link_rows" to 1, "reminders" to 2, "file_rows" to 4, "folders" to 4, "task_series" to 1, "series_skips" to 1, "trash_items" to 2, "alarm_events" to 1, "file_mirrors" to 1)) {
            assertEquals(table, n.toLong(), rows(dbFile, "SELECT count(*) AS n FROM $table").single()["n"])
        }
    }

    @Test
    fun aFormat2BackupWithoutAFilesListIsAccepted() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener, format = 2, withMirrored = false)
        assertNull(built.manifest["files"])
        val backup = validate(built.zip)
        assertEquals(2, backup.oldApp!!.format)
        assertEquals(BackupKind.COMPLETE, backup.manifest.kind)
        assertEquals(4, backup.manifest.fileCount)
        assertEquals(OldAppFixture.attachments.keys.sorted(), backup.manifest.files.map { it.path })
        for (entry in backup.manifest.files) {
            assertTrue(entry.inZip)
            assertEquals(FileOps.sha256Hex(OldAppFixture.attachments.getValue(entry.path)), entry.sha256)
        }
        backup.discard()
    }

    @Test
    fun aFormat3BackupMustListItsFiles() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener, manifest = { it.remove("files") })
        assertEquals(BackupErrorCode.DAMAGED, refused(built.zip))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anOldAppBackupWithAnotherSchemaGetsItsOwnMessage() {
        val older = OldAppFixture.buildZip(File(root, "a"), opener, format = 2, schemaVersion = 2, withMirrored = false)
        assertEquals(BackupErrorCode.OLD_APP_SCHEMA, refused(older.zip))
        val newer = OldAppFixture.buildZip(File(root, "b"), opener, schemaVersion = 4)
        assertEquals(BackupErrorCode.OLD_APP_SCHEMA, refused(newer.zip))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aDamagedAttachmentOrDatabaseIsRefused() {
        val file = OldAppFixture.buildZip(File(root, "a"), opener, entry = { name, bytes -> if (name == "files/F1.pdf") bytes + 1 else bytes })
        assertEquals(BackupErrorCode.DAMAGED, refused(file.zip))
        val sha = OldAppFixture.buildZip(File(root, "b"), opener, manifest = { it["dbSha256"] = JsonPrimitive("0".repeat(64)) })
        assertEquals(BackupErrorCode.DAMAGED, refused(sha.zip))
        val format2 = OldAppFixture.buildZip(File(root, "c"), opener, format = 2, withMirrored = false, manifest = { it["dbSha256"] = JsonPrimitive("1".repeat(64)) })
        assertEquals(BackupErrorCode.DAMAGED, refused(format2.zip))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aMirroredEntryIsKeptForTheDownloadAfterRestore() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener)
        val backup = validate(built.zip)
        val listed = backup.manifest.listedOnly
        assertEquals(listOf("F3.png"), listed.map { it.path })
        assertFalse(listed.single().inZip)
        assertEquals(FileOps.sha256Hex(OldAppFixture.mirroredOnly.second), listed.single().sha256)
        assertFalse(File(backup.filesDir, "F3.png").exists())
        backup.discard()

        val broken = OldAppFixture.buildZip(File(root, "bad"), opener, manifest = { fields ->
            val files = (fields["files"] as JsonArray).map { e ->
                val o = e as JsonObject
                if (JsonText.stringOf(o["path"]) == "F3.png") JsonObject(o - "mirror") else o
            }
            fields["files"] = JsonArray(files)
        })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(broken.zip))
    }

    @Test
    fun anArchiveWithoutTheExpectedDatabaseNameCannotBeRestored() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener, dbName = "legacy.sqlite")
        assertEquals(BackupErrorCode.OLD_APP_UNSUPPORTED, refused(built.zip))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun withoutAConverterAnOldAppBackupIsUnsupportedNotNewer() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener)
        assertEquals(BackupErrorCode.OLD_APP_UNSUPPORTED, refused(built.zip, oldApp = null))
    }

    @Test
    fun formatFourAndLaterIsANewerVersion() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener, format = BackupFormat.NEXT_FORMAT)
        assertEquals(BackupErrorCode.NEWER_VERSION, refused(built.zip))
    }

    @Test
    fun anUnknownTableWithRowsIsNotDroppedSilently() {
        val dir = File(root, "db")
        dir.mkdirs()
        val oldDb = File(dir, "old.sqlite")
        OldAppFixture.createOldDatabase(oldDb, opener)
        JdbcSqlPort(oldDb).use {
            it.exec("CREATE TABLE extra (x TEXT)")
            it.exec("INSERT INTO extra VALUES ('keep me')")
        }
        val failure = runCatching { OldDatabaseConverter(opener).convert(oldDb, File(dir, "new.sqlite")) }.exceptionOrNull()
        assertEquals(BackupErrorCode.OLD_APP_UNSUPPORTED, (failure as BackupException).code)
        assertFalse(File(dir, "new.sqlite").exists())
    }

    @Test
    fun anEmptyOldDatabaseConvertsToAnEmptyRoomDatabase() {
        val built = OldAppFixture.buildZip(File(root, "in"), opener, rows = false, withMirrored = false)
        val backup = validate(built.zip)
        assertEquals(OldAppCounts(0, 0, 0, 0, 0, 0, 0), backup.oldApp!!.counts)
        assertEquals(1L, SqliteHeader.userVersionOf(backup.dbFile))
        backup.discard()
    }

    @Test
    fun oldDatesInEveryFormTheOldAppWrote() {
        val expected = Instant.parse("2026-11-05T10:45:00Z")
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T10:45:00.000Z"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T10:45:00.000000Z"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T16:15:00.000 +05:30"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T05:45:00.000 -05:00"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T16:15:00+0530"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05 10:45:00"))
        assertEquals(expected, OldDates.fromDbValue("2026-11-05T10:45"))
        assertEquals(expected, OldDates.fromDbValue(expected.epochSecond))
        assertEquals(expected, OldDates.fromDbValue(expected.toEpochMilli()))
        assertEquals(expected, OldDates.fromDbValue(expected.epochSecond.toString()))
        assertEquals(expected, OldDates.fromText(expected.toEpochMilli().toString(), numbersAreMillis = true))
        assertNull(OldDates.fromDbValue("next tuesday"))
        assertNull(OldDates.fromDbValue(""))
        assertEquals("2026-11-05T10:45:00.123Z", OldDates.format(OldDates.fromDbValue("2026-11-05T10:45:00.123999Z")!!))
    }

    @Test
    fun oldSettingsAreConvertedOrSkipped() {
        fun one(key: String, value: String) = OldAppSettings.convertValue(key, value)
        assertEquals("bundled:alarm", one("remft_alarm_sound", "bundled:aw_alarm"))
        assertEquals("bundled:chime", one("remft_reminder_sound", "bundled:chime"))
        assertEquals("os", one("remft_alarm_sound", "bundled:nope"))
        assertEquals("file:F4", one("remft_alarm_sound", "file:F4"))
        assertEquals("{\"slots\":[0,30,60],\"repeatAfterSnooze\":true}", one("remft_reminder_profile", "{\"slots\":[30,10,5,2,0],\"repeatAfterSnooze\":true}"))
        val custom = "{\"slots\":[0,1,3],\"repeatAfterSnooze\":false}"
        assertEquals(custom, one("remft_reminder_profile", custom))
        assertNull(one("remft_reminder_profile", "garbage"))
        assertEquals("1_hour,5_min,30_min,tomorrow_morning", one("remft_snooze_presets", "1_hour,bogus,5_min"))
        assertNull(one("remft_bin_days", "45"))
        assertEquals("60", one("remft_bin_days", "60"))
        assertNull(one("remft_tasks_sort", "colour:asc"))
        assertEquals("size:desc", one("remft_files_sort", "size:desc"))
        assertNull(one("remft_notes_view_mode", "tiles"))
        assertEquals("{\"frequency\":\"daily\",\"minutes\":120}", one("remft_auto_backup_schedule", "{\"frequency\":\"daily\",\"minutes\":120}"))
        assertNull(one("remft_auto_backup_keep_days", "5"))
        assertEquals("true", one("remft_onboarding_seen_v1", "true"))
    }
}
