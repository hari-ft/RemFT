package com.ftapps.remft.domain.backup.auto

import com.ftapps.remft.domain.backup.BackupNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RetentionTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-20T12:00:00Z")

    private fun auto(at: Instant) = StoredBackup(BackupNames.automatic(at), BackupNames.automatic(at))

    private fun daysAgo(d: Long) = now.minus(Duration.ofDays(d))

    @Test
    fun keepsTheDailyWindowAndTheNewestOfEachWeekAndMonth() {
        val all = listOf(0L, 1, 3, 6, 12, 13, 30, 31, 400).map { auto(daysAgo(it)) }
        val expired = Retention.expired(all, keepDays = 7, now = now, zone = zone)
        assertEquals(listOf(all[5].name, all[7].name, all[8].name), expired.map { it.name })
    }

    @Test
    fun neverDeletesTheNewestThreeHoweverOld() {
        val old = listOf(400L, 401, 402, 403).map { auto(daysAgo(it)) }
        assertEquals(listOf(old[3].name), Retention.expired(old, 7, now, zone).map { it.name })
        assertTrue(Retention.expired(old.take(3), 7, now, zone).isEmpty())
    }

    @Test
    fun aShortWindowStillKeepsThree() {
        val all = listOf(0L, 1, 2).map { auto(now.minus(Duration.ofHours(it))) }
        assertTrue(Retention.expired(all, 1, now, zone).isEmpty())
    }

    @Test
    fun ignoresFilesThatAreNotAutomaticBackups() {
        val all = listOf(
            auto(now),
            StoredBackup("m", "remft-backup-2020-01-01.zip"),
            StoredBackup("x", "notes.txt"),
            StoredBackup("y", "remft-auto-2020-01-01-000000Z.zip.part"),
        )
        assertTrue(Retention.expired(all, 7, now, zone).isEmpty())
    }

    @Test
    fun oldNonAutomaticNamesAreNeverPruned() {
        val all = listOf(0L, 1, 2, 3).map { auto(daysAgo(it)) } +
            StoredBackup("notes", "notes-2020.zip") +
            StoredBackup("old", "remft-auto-2024-01-03-020000Z.zip")
        assertEquals(listOf("old"), Retention.expired(all, 7, now, zone).map { it.id })
    }

    @Test
    fun aSimulatedYearOfDailyBackupsKeeps7Days4WeeksAnd12Months() {
        var held = mutableListOf<StoredBackup>()
        var day = Instant.parse("2026-01-01T03:00:00Z")
        val end = Instant.parse("2027-01-20T03:00:00Z")
        while (!day.isAfter(end)) {
            held.add(auto(day))
            val gone = Retention.expired(held, 7, day, zone).map { it.id }.toSet()
            held = held.filter { it.id !in gone }.toMutableList()
            day = day.plus(Duration.ofDays(1))
        }
        val kept = held.map { BackupNames.parseAutomatic(it.name, zone)!! }.toSet()
        val expected = buildSet {
            for (d in 0L..7L) add(end.minus(Duration.ofDays(d)))
            add(Instant.parse("2027-01-17T03:00:00Z"))
            add(Instant.parse("2027-01-10T03:00:00Z"))
            add(Instant.parse("2027-01-03T03:00:00Z"))
            for (m in 2..12) {
                add(LocalDate.of(2026, m, 1).plusMonths(1).minusDays(1).atTime(3, 0).toInstant(ZoneOffset.UTC))
            }
        }
        assertEquals(expected, kept)
        assertTrue(held.size <= 8 + 4 + 12)
    }

    @Test
    fun keepDaysOnlyFromTheChoices() {
        assertEquals(7, Retention.keepDays(null))
        assertEquals(30, Retention.keepDays("30"))
        assertEquals(7, Retention.keepDays("5"))
        assertEquals(7, Retention.keepDays("x"))
    }

    @Test
    fun theDropRuleMoreThanHalfOfTheItemsOrOfTheFileBytes() {
        val before = BackupDataSize(tasks = 10, notes = 6, files = 4, fileBytes = 8L * 1024 * 1024)
        fun size(items: Long, bytes: Long) = BackupDataSize(items, 0, 0, bytes)
        assertFalse(size(10, 8L shl 20).isBigDropFrom(before))
        assertTrue(size(9, 8L shl 20).isBigDropFrom(before))
        assertFalse(size(20, 4L shl 20).isBigDropFrom(before))
        assertTrue(size(20, (4L shl 20) - 1).isBigDropFrom(before))
        val tiny = BackupDataSize(9, 0, 0, 1000)
        assertFalse(size(0, 0).isBigDropFrom(tiny))
    }

    @Test
    fun pauseOnlyWithoutAcceptanceAndAgainstAKnownPreviousSize() {
        val previous = BackupDataSize(20, 0, 0, 0)
        val shrunk = BackupDataSize(5, 0, 0, 0)
        assertTrue(PauseRule.shouldPause(shrunk, previous, dropAccepted = false))
        assertFalse(PauseRule.shouldPause(shrunk, previous, dropAccepted = true))
        assertFalse(PauseRule.shouldPause(shrunk, null, dropAccepted = false))
        assertFalse(PauseRule.shouldPause(BackupDataSize(10, 0, 0, 0), previous, false))
        assertTrue(PauseRule.dropAccepted("true"))
        assertFalse(PauseRule.dropAccepted("yes"))
    }

    @Test
    fun sizesAndPausesAndStatusesRoundTripAsCompactJson() {
        val size = BackupDataSize(1, 2, 3, 4)
        assertEquals("""{"tasks":1,"notes":2,"files":3,"fileBytes":4}""", size.encode())
        assertEquals(size, BackupDataSize.parse(size.encode()))
        assertEquals(BackupDataSize(1, 2, 3, 4), BackupDataSize.parse("""{"tasks":1.9,"notes":2,"files":3,"fileBytes":4}"""))
        assertNull(BackupDataSize.parse("""{"tasks":1}"""))
        assertNull(BackupDataSize.parse("junk"))

        val pause = AutoBackupPause(Instant.parse("2026-11-05T10:00:00Z"), BackupDataSize(20, 0, 0, 0), BackupDataSize(5, 0, 0, 0))
        assertEquals(
            """{"at":"2026-11-05T10:00:00.000Z","previous":{"tasks":20,"notes":0,"files":0,"fileBytes":0},"current":{"tasks":5,"notes":0,"files":0,"fileBytes":0}}""",
            pause.encode(),
        )
        assertEquals(pause, AutoBackupPause.parse(pause.encode()))
        assertNull(AutoBackupPause.parse("""{"at":"x","previous":{},"current":{}}"""))

        val failed = DestinationStatus(Instant.parse("2026-11-05T10:00:00Z"), false, "UPLOAD")
        assertEquals("""{"at":"2026-11-05T10:00:00.000Z","ok":false,"error":"UPLOAD"}""", failed.encode())
        assertEquals(failed, DestinationStatus.parse(failed.encode()))
        val ok = DestinationStatus(Instant.parse("2026-11-05T10:00:00Z"), true)
        assertEquals("""{"at":"2026-11-05T10:00:00.000Z","ok":true,"error":null}""", ok.encode())
        assertEquals(ok, DestinationStatus.parse(ok.encode()))
        assertEquals("remft_auto_backup_status_onedrive", DestinationStatus.key("onedrive"))
    }
}
