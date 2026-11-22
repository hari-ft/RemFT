package com.ftapps.remft.domain.backup

import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import com.ftapps.remft.domain.time.UtcText

class BackupFormatTest {
    private val sha = "a".repeat(64)

    @Test
    fun manifestIsPrettyJsonThatParsesBack() {
        val manifest = BackupManifest(
            format = 1,
            appVersion = "9.9.9",
            schemaVersion = 1,
            createdAt = Instant.parse("2026-11-02T10:00:00Z"),
            dbSha256 = sha,
            dbBytes = 128,
            fileCount = 1,
            kind = BackupKind.COMPLETE_PARTIAL,
            files = listOf(
                BackupFileEntry("a/photo.jpg", 3, sha, inZip = true),
                BackupFileEntry("b.pdf", 5, sha, inZip = false, mirror = MirrorRef("item7", "Files/b (F2).pdf", 5, sha)),
            ),
            unavailable = listOf("gone.bin"),
        )
        val expected = """
            {
              "app": "remft",
              "format": 1,
              "appVersion": "9.9.9",
              "schemaVersion": 1,
              "createdAt": "2026-11-02T10:00:00.000Z",
              "dbSha256": "$sha",
              "dbBytes": 128,
              "fileCount": 1,
              "kind": "complete-partial",
              "files": [
                {
                  "path": "a/photo.jpg",
                  "size": 3,
                  "sha256": "$sha",
                  "inZip": true
                },
                {
                  "path": "b.pdf",
                  "size": 5,
                  "sha256": "$sha",
                  "inZip": false,
                  "mirror": {
                    "itemId": "item7",
                    "path": "Files/b (F2).pdf"
                  }
                }
              ],
              "unavailable": [
                "gone.bin"
              ]
            }
        """.trimIndent()
        val written = manifest.toPrettyJson()
        assertTrue(written.contains("\n"))
        assertEquals(JsonText.parse(expected), JsonText.parse(written))
        assertEquals(manifest, BackupManifest.parse(written))
        assertEquals(manifest, BackupManifest.parse(expected))
    }

    @Test
    fun emptyFileListAndNoUnavailableKey() {
        val manifest = BackupManifest(1, "1", 1, Instant.parse("2026-01-01T00:00:00Z"), sha, 1, 0, files = emptyList())
        val json = JsonText.parse(manifest.toPrettyJson()) as JsonObject
        assertEquals(JsonArray(emptyList()), json["files"])
        assertFalse(json.containsKey("unavailable"))
    }

    @Test
    fun createdAtIsIsoUtcWithMilliseconds() {
        assertEquals("2026-11-02T10:00:00.123Z", UtcText.format(Instant.parse("2026-11-02T10:00:00.123Z")))
        assertEquals("2026-11-02T10:00:00.123Z", UtcText.format(Instant.parse("2026-11-02T10:00:00.123456789Z")))
        assertEquals(Instant.parse("2026-11-02T10:00:00.123Z"), UtcText.parse("2026-11-02T10:00:00.123Z"))
    }

    @Test
    fun manifestParsingRules() {
        val base = """{"app":"remft","format":1,"appVersion":"1","schemaVersion":1,"createdAt":"2026-11-02T10:00:00.000Z","dbSha256":"$sha","dbBytes":1,"fileCount":0"""
        val noKind = BackupManifest.parse("$base,\"files\":[]}")
        assertEquals(BackupKind.COMPLETE, noKind.kind)
        assertEquals(emptyList<String>(), noKind.unavailable)
        assertTrue(runCatching { BackupManifest.parse("$base,\"files\":{}}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse("$base,\"files\":[],\"unavailable\":[1]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse(base.replace("\"format\":1", "\"format\":1.0") + ",\"files\":[]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse(base.replace("\"format\":1,", "") + ",\"files\":[]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse(base.replace("\"app\":\"remft\",", "") + ",\"files\":[]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse(base.replace("\"remft\"", "\"other\"") + ",\"files\":[]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse(base.replace("2026-11-02T10:00:00.000Z", "2026-11-02 10:00:00") + ",\"files\":[]}") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse("[]") }.isFailure)
        assertTrue(runCatching { BackupManifest.parse("$base}") }.isFailure)
    }

    @Test
    fun fileEntriesThisBuildCannotTrustAreRejected() {
        fun entry(json: String) = BackupFileEntry.fromJson(JsonText.parse(json))
        assertEquals(BackupFileEntry("a", 1, sha, true), entry("""{"path":"a","size":1,"sha256":"$sha","inZip":true}"""))
        assertNull(entry("""{"path":"a","size":-1,"sha256":"$sha","inZip":true}"""))
        assertNull(entry("""{"path":"a","size":1,"sha256":"${sha.uppercase()}","inZip":true}"""))
        assertNull(entry("""{"path":"a","size":1,"sha256":"$sha","inZip":false}"""))
        assertNull(entry("""{"path":"a","size":1,"sha256":"$sha","inZip":false,"mirror":{"itemId":"","path":"Other/x"}}"""))
        assertNull(entry("""{"path":"a","size":1,"sha256":"$sha","inZip":false,"mirror":{"itemId":1,"path":"Files/x"}}"""))
        assertEquals(
            "",
            entry("""{"path":"a","size":1,"sha256":"$sha","inZip":false,"mirror":{"itemId":"","path":"Files/x"}}""")!!.mirror!!.itemId,
        )
        assertEquals("i", entry("""{"path":"a","size":1,"sha256":"$sha","inZip":false,"mirror":{"itemId":"i","path":"x"}}""")!!.mirror!!.itemId)
        assertNull(entry("""{"path":"a","size":"1","sha256":"$sha","inZip":true}"""))
        assertNull(entry("[]"))
    }

    @Test
    fun manualNamesUseTheLocalDateAndAutomaticNamesUtc() {
        assertEquals("remft-backup-2026-03-04.zip", BackupNames.manual(LocalDate.of(2026, 3, 4)))
        val at = Instant.parse("2026-11-02T07:05:09Z")
        assertEquals("remft-auto-2026-11-02-070509Z.zip", BackupNames.automatic(at))
        assertEquals(at, BackupNames.parseAutomatic(BackupNames.automatic(at)))
        assertEquals("remft-auto-2026-11-02-120000Z.zip", BackupNames.automatic(Instant.parse("2026-11-02T12:00:00Z")))
    }

    @Test
    fun localNamesParseInTheGivenZone() {
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals(
            LocalDateTime.of(2026, 11, 2, 7, 5, 9).atZone(zone).toInstant(),
            BackupNames.parseAutomatic("remft-auto-2026-11-02-070509.zip", zone),
        )
        assertEquals(Instant.parse("2026-11-02T07:05:09Z"), BackupNames.parseAutomatic("remft-auto-2026-11-02-070509Z.zip", zone))
        assertNull(BackupNames.parseAutomatic("remft-backup-2026-11-02.zip"))
    }

    @Test
    fun nothingElseParsesAsAnAutomaticBackup() {
        for (name in listOf(
            "remft-backup-2026-11-02.zip",
            "remft-auto-2026-11-02.zip",
            "remft-auto-2026-11-02-070509.zip.part",
            "remft-auto-2026-11-02-070509Z.zip.part",
            "remft-auto-2026-11-02-070509z.zip",
            "remft-auto-2026-11-02-070509Z.zip\n",
            "holiday.jpg",
        )) {
            assertNull(name, BackupNames.parseAutomatic(name))
        }
    }

    @Test
    fun outOfRangeAutoNameFieldsRollOver() {
        assertEquals(Instant.parse("2027-02-09T00:00:00Z"), BackupNames.parseAutomatic("remft-auto-2026-13-40-000000Z.zip"))
    }

    @Test
    fun settingsCarryOnlyTheBackedUpKeys() {
        assertEquals(SettingKeys.backedUp, BackupSettings.keys.toSet())
        val store = mapOf(
            "remft_date_format" to "dmy",
            "remft_reminder_profile" to "[0,30,60]",
            "notification_privacy" to "true",
            "remft_auto_backup_onedrive_token" to "secret",
            "remft_auto_backup_onedrive_account" to "someone",
            "remft_local_workspace_id" to "W1",
            "remft_last_backup_at" to "2026-11-18",
        )
        val collected = BackupSettings.collect { store[it] }
        assertEquals(
            listOf("remft_reminder_profile", "notification_privacy", "remft_date_format"),
            collected.keys.toList(),
        )
        assertEquals(
            """{"remft_reminder_profile":"[0,30,60]","notification_privacy":"true","remft_date_format":"dmy"}""",
            BackupSettings.encode(collected),
        )
    }

    @Test
    fun onlyKnownSettingsAreReadBack() {
        assertEquals(
            mapOf("remft_date_format" to "dmy"),
            BackupSettings.parse("""{"remft_date_format":"dmy","remft_auto_backup_onedrive_token":"x","remft_bin_days":30}"""),
        )
        assertNull(BackupSettings.parse("not json"))
        assertNull(BackupSettings.parse("[1,2]"))
    }

    @Test
    fun applyingReplacesThePreferencesAndLeavesEverythingElse() {
        val kv = mutableMapOf(
            "remft_date_format" to "mdy",
            "remft_tasks_sort" to "title:asc",
            "remft_auto_backup_onedrive_on" to "true",
        )
        kv[BackupSettings.pendingKey] = BackupSettings.encode(mapOf("remft_date_format" to "dmy"))
        assertTrue(BackupSettings.applyPending({ kv[it] }, { k, v -> kv[k] = v }, { kv.remove(it) }))
        assertEquals("dmy", kv["remft_date_format"])
        assertFalse(kv.containsKey("remft_tasks_sort"))
        assertEquals("true", kv["remft_auto_backup_onedrive_on"])
        assertFalse(kv.containsKey(BackupSettings.pendingKey))
        assertFalse(BackupSettings.applyPending({ kv[it] }, { k, v -> kv[k] = v }, { kv.remove(it) }))
    }

    @Test
    fun jsonTextWritesStandardJson() {
        val json = JsonText.parse("""{"a":[],"b":{},"c":"q\"\\\n\u0001é/","d":[1,{"e":null}]}""") as JsonObject
        assertEquals("""{"a":[],"b":{},"c":"q\"\\\n\u0001é/","d":[1,{"e":null}]}""", JsonText.encode(json))
        val pretty = JsonText.encodePretty(json)
        assertTrue(pretty.contains("\n"))
        assertEquals(json, JsonText.parse(pretty))
    }

    @Test
    fun safeRelativePathsFollowPackagePath() {
        assertTrue(PathText.isSafeRelative("a/b.txt"))
        assertFalse(PathText.isSafeRelative(""))
        assertFalse(PathText.isSafeRelative("../x"))
        assertFalse(PathText.isSafeRelative("a/../../x"))
        assertFalse(PathText.isSafeRelative("a/../b"))
        assertFalse(PathText.isSafeRelative("/abs"))
        assertFalse(PathText.isSafeRelative("a\\b"))
        assertFalse(PathText.isSafeRelative("./a"))
        assertFalse(PathText.isSafeRelative("a//b"))
        assertFalse(PathText.isSafeRelative("a/"))
        assertFalse(PathText.isSafeRelative("..hidden"))
        assertEquals("", PathText.extension(".bashrc"))
        assertEquals(".gz", PathText.extension("a.tar.gz"))
    }

    @Test
    fun monthlyReminder() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 20, 0, 0, 0, 0, zone)
        assertTrue(BackupReminder.isDue(now, hasData = true))
        assertFalse(BackupReminder.isDue(now, hasData = false))
        assertFalse(BackupReminder.isDue(now, true, now.minusDays(29).toInstant()))
        assertTrue(BackupReminder.isDue(now, true, now.minusDays(31).toInstant()))
        assertFalse(BackupReminder.isDue(now, true, dismissedMonth = "2026-11"))
        assertTrue(BackupReminder.isDue(ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, zone), true, dismissedMonth = "2026-11"))
    }

    @Test
    fun sqliteUserVersionIsBigEndianAtOffsetSixty() {
        val head = BackupTestSupport.fakeDbBytes(schema = 0x01020304)
        assertTrue(SqliteHeader.hasMagic(head))
        assertEquals(0x01020304L, SqliteHeader.userVersion(head))
        head[60] = 0xff.toByte()
        assertEquals(0xff020304L, SqliteHeader.userVersion(head))
        assertFalse(SqliteHeader.hasMagic(ByteArray(64)))
        assertFalse(SqliteHeader.hasMagic(head.copyOf(63)))
    }

    @Test
    fun errorCodesHaveStringKeys() {
        assertEquals(4, BackupErrorCode.all.size)
        assertEquals("backup.error.not_a_backup", BackupErrorCode.stringKey(BackupErrorCode.NOT_A_BACKUP))
    }
}
