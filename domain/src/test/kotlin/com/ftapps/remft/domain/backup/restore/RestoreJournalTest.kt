package com.ftapps.remft.domain.backup.restore

import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupTestSupport
import com.ftapps.remft.domain.backup.BackupTestSupport.dbContent
import com.ftapps.remft.domain.backup.BackupTestSupport.writeFakeDb
import com.ftapps.remft.domain.backup.BackupTestSupport.writeFile
import com.ftapps.remft.domain.backup.BackupValidator
import com.ftapps.remft.domain.backup.FileOps
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.backup.ValidatedBackup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class RestoreJournalTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var tmp: File
    private lateinit var dbFile: File
    private lateinit var filesDir: File
    private lateinit var newZip: File
    private lateinit var listedZip: File
    private var n = 0
    private val listedPath = "att/listed.bin"
    private val listedBytes = "abc".toByteArray()

    @Before
    fun setUp() {
        tmp = temp.root
        val src = File(tmp, "src").apply { mkdirs() }
        val sourceFiles = File(src, "files")
        writeFile(sourceFiles, "att/new.bin", byteArrayOf(7, 8))
        newZip = BackupTestSupport.buildZip(src, "New task|New note", sourceFiles, out = File(src, "new.zip")).zip
        listedZip = BackupTestSupport.buildZip(
            src,
            "New task|New note",
            sourceFiles,
            out = File(src, "listed.zip"),
            listOnly = mapOf(
                listedPath to MirrorRef("item7", "Files/Tasks/T/listed (F7).bin", 3, FileOps.sha256Hex(listedBytes)),
            ),
        ).zip

        dbFile = writeFakeDb(File(tmp, "remft.sqlite"), "Old task|Old note")
        File(dbFile.path + "-wal").writeText("old wal")
        filesDir = File(tmp, "files")
        writeFile(filesDir, "att/old.bin", byteArrayOf(1, 2, 3))
    }

    private fun validated(zip: File = newZip): ValidatedBackup =
        BackupValidator.validate(zip, BackupTestSupport.SCHEMA, File(tmp, "scratch-${n++}"))

    private fun leftovers(): List<String> =
        tmp.listFiles().orEmpty().map { it.name }.filter { Regex("\\.(bak|restore)$|restore\\.journal").containsMatchIn(it) }

    private fun expectOldData() {
        assertEquals("Old task|Old note", dbContent(dbFile))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(filesDir, "att/old.bin").readBytes())
        assertFalse(File(filesDir, "att/new.bin").exists())
    }

    private fun expectNewData() {
        assertEquals("New task|New note", dbContent(dbFile))
        assertArrayEquals(byteArrayOf(7, 8), File(filesDir, "att/new.bin").readBytes())
        assertFalse(File(filesDir, "att/old.bin").exists())
    }

    private fun killAt(step: String, zip: File = newZip, settle: RestoreSettle? = null) {
        val backup = validated(zip)
        try {
            BackupRestore.restore(backup, dbFile, filesDir, settle = settle, onStep = { if (it == step) throw SimulatedProcessKill(it) })
            fail("expected a kill at $step")
        } catch (_: SimulatedProcessKill) {
        }
    }

    private fun startUp(settle: RestoreSettle? = null): RestoreRecovery =
        BackupRestore.recoverInterruptedRestore(dbFile, filesDir, settle)

    private fun restoreNormally() {
        BackupRestore.restore(validated(), dbFile, filesDir)
    }

    @Test
    fun aNormalRestoreReplacesEverythingAndLeavesNothingBehind() {
        val backup = validated()
        BackupRestore.restore(backup, dbFile, filesDir)
        expectNewData()
        assertFalse(File(dbFile.path + "-wal").exists())
        assertTrue(leftovers().isEmpty())
        assertFalse(backup.staging.exists())
    }

    @Test
    fun aRestoreOntoAnEmptyPhone() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        filesDir.deleteRecursively()
        restoreNormally()
        expectNewData()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aKillBeforeTheSwapFinishedRollsBackAtTheNextStart() {
        val cases = listOf(
            "recover" to RestoreRecovery.Nothing,
            "recovered" to RestoreRecovery.Nothing,
            "staged" to RestoreRecovery.DiscardedStaging,
            "swapping-db" to RestoreRecovery.RolledBack,
            "db-parked" to RestoreRecovery.RolledBack,
            "db-in" to RestoreRecovery.RolledBack,
            "verified" to RestoreRecovery.RolledBack,
            "files-parked" to RestoreRecovery.RolledBack,
            "files-in" to RestoreRecovery.RolledBack,
        )
        for ((step, expected) in cases) {
            setUpFresh()
            killAt(step)
            if (expected != RestoreRecovery.Nothing) {
                assertTrue(step, BackupRestore.recoveryPending(dbFile, filesDir))
            }
            assertEquals(step, expected, startUp())
            expectOldData()
            assertTrue(step, File(dbFile.path + "-wal").exists())
            assertEquals(step, "old wal", File(dbFile.path + "-wal").readText())
            assertTrue(step, leftovers().isEmpty())
            assertFalse(step, BackupRestore.recoveryPending(dbFile, filesDir))
            assertEquals(step, RestoreRecovery.Nothing, startUp())
            restoreNormally()
            expectNewData()
            assertTrue(step, leftovers().isEmpty())
        }
    }

    @Test
    fun aKillAfterTheRestoreSucceededFinishesIt() {
        for (step in listOf("done", "cleaning")) {
            setUpFresh()
            killAt(step)
            assertTrue(BackupRestore.recoveryPending(dbFile, filesDir))
            assertEquals(step, RestoreRecovery.Finished, startUp())
            expectNewData()
            assertTrue(step, leftovers().isEmpty())
            assertFalse(File(dbFile.path + "-wal").exists())
            assertFalse(File(dbFile.path + "-wal.bak").exists())
        }
    }

    @Test
    fun aRestoreWithNoLiveDataBeforeItRollsBackToNone() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        filesDir.deleteRecursively()
        killAt("files-in")
        assertEquals(RestoreRecovery.RolledBack, startUp())
        assertFalse(dbFile.exists())
        assertFalse(filesDir.exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun anUnreadableJournalIsTreatedAsMidSwapAndTheBakWins() {
        killAt("files-in")
        File(tmp, RESTORE_JOURNAL_NAME).writeText("{torn")
        assertEquals(RestoreRecovery.RolledBack, startUp())
        expectOldData()
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aTornJournalWriteLeavesThePreviousJournalInCharge() {
        killAt("db-in")
        File(tmp, "$RESTORE_JOURNAL_NAME.tmp").writeText("{\"phase\":\"done\"")
        assertEquals(RestoreRecovery.RolledBack, startUp())
        expectOldData()
        assertFalse(File(tmp, "$RESTORE_JOURNAL_NAME.tmp").exists())
    }

    @Test
    fun theJournalIsWrittenAsCompactJson() {
        killAt("swapping-db", zip = listedZip)
        val journal = File(tmp, RESTORE_JOURNAL_NAME).readText()
        assertEquals(
            "{\"phase\":\"swapping-db\",\"hadDb\":true,\"hadFiles\":true,\"keep\":[{\"path\":\"att/listed.bin\",\"size\":3," +
                "\"sha256\":\"${FileOps.sha256Hex(listedBytes)}\",\"mirror\":{\"itemId\":\"item7\",\"path\":\"Files/Tasks/T/listed (F7).bin\"}}]}",
            journal,
        )
        val parsed = RestoreJournal.parse(journal)
        assertEquals(RestorePhase.SwappingDb, parsed.phase)
        assertEquals(listedPath, parsed.keep.single().path)
        assertEquals(RestoreJournal.careful, RestoreJournal.parse("{\"phase\":\"flying\",\"hadDb\":true,\"hadFiles\":false}"))
        assertEquals(RestoreJournal.careful, RestoreJournal.parse("{\"phase\":\"done\",\"hadDb\":1,\"hadFiles\":false}"))
        assertTrue(RestoreJournal.parse("{\"phase\":\"done\",\"hadDb\":true,\"hadFiles\":false,\"keep\":[{\"path\":\"../x\",\"size\":1,\"sha256\":\"a\"}]}").keep.isEmpty())
        assertEquals("{\"phase\":\"done\",\"hadDb\":false,\"hadFiles\":true,\"settled\":true}", RestoreJournal(RestorePhase.Done, false, true, settled = true).encode())
    }

    @Test
    fun aBakWithoutAJournalIsPutBackWhenTheLiveCopyIsMissing() {
        FileOps.rename(dbFile, File(dbFile.path + ".bak"))
        FileOps.rename(File(dbFile.path + "-wal"), File(dbFile.path + "-wal.bak"))
        FileOps.rename(filesDir, File(filesDir.path + ".bak"))
        assertTrue(BackupRestore.recoveryPending(dbFile, filesDir))
        assertEquals(RestoreRecovery.RestoredWithoutJournal, startUp())
        assertTrue(RestoreRecovery.RestoredWithoutJournal.tellUser)
        expectOldData()
        assertTrue(File(dbFile.path + "-wal").exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aLeftoverBakNextToALiveCopyIsKeptNeverDeleted() {
        val leftover = File(dbFile.path + ".bak")
        dbFile.copyTo(leftover)
        val leftoverFiles = File(filesDir.path + ".bak")
        writeFile(leftoverFiles, "only-here.bin", byteArrayOf(5))
        assertEquals(RestoreRecovery.Nothing, startUp())
        assertTrue(leftover.exists())
        assertTrue(leftoverFiles.exists())
        assertFalse(BackupRestore.recoveryPending(dbFile, filesDir))

        restoreNormally()
        expectNewData()
        val kept = tmp.listFiles().orEmpty().map { it.name }.filter { it.contains(".bak.kept-") }
        assertEquals(2, kept.size)
        val keptFiles = File(tmp, kept.first { it.startsWith("files") })
        assertArrayEquals(byteArrayOf(5), File(keptFiles, "only-here.bin").readBytes())
    }

    @Test
    fun oldKeptLeftoversAreNeverDeletedByARestore() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        fun stamp(at: Instant) = ChronoUnit.MICROS.between(Instant.EPOCH, at)
        val ancient = stamp(now.minus(Duration.ofDays(90)))
        val recent = stamp(now.minus(Duration.ofDays(3)))
        val ancientDb = File(dbFile.path + ".bak.kept-$ancient").apply { writeText("ancient") }
        val ancientFiles = File(filesDir.path + ".bak.kept-$ancient").apply { mkdirs() }
        writeFile(ancientFiles, "old.bin", byteArrayOf(1))
        val recentDb = File(dbFile.path + ".bak.kept-$recent").apply { writeText("recent") }
        val unrelated = File(tmp, "notes.kept-$ancient").apply { writeText("not ours") }
        dbFile.copyTo(File(dbFile.path + ".bak"))

        BackupRestore.restore(validated(), dbFile, filesDir, clock = { now })
        expectNewData()

        assertTrue(ancientDb.exists())
        assertTrue(ancientFiles.exists())
        assertTrue(recentDb.exists())
        assertTrue(File(dbFile.path + ".bak.kept-${stamp(now)}").exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun anOldKeptLeftoverStaysWhenItIsTheNewestOne() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        val ancient = ChronoUnit.MICROS.between(Instant.EPOCH, now.minus(Duration.ofDays(90)))
        val only = File(dbFile.path + ".bak.kept-$ancient").apply { writeText("only copy") }
        BackupRestore.restore(validated(), dbFile, filesDir, clock = { now })
        expectNewData()
        assertTrue(only.exists())
    }

    @Test
    fun aRestoreThatFailsVerificationPutsTheOriginalBack() {
        val backup = validated()
        try {
            BackupRestore.restore(backup, dbFile, filesDir, verify = { throw IllegalStateException("cannot open") })
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        expectOldData()
        assertTrue(File(dbFile.path + "-wal").exists())
        assertTrue(leftovers().isEmpty())
        assertFalse(backup.staging.exists())
    }

    @Test
    fun aCorruptStagedDatabaseFailsTheDefaultVerificationAndRollsBack() {
        val backup = validated()
        backup.dbFile.writeText("not a database")
        try {
            BackupRestore.restore(backup, dbFile, filesDir)
            fail("expected failure")
        } catch (_: IOException) {
        }
        expectOldData()
        assertTrue(leftovers().isEmpty())
        assertFalse(backup.staging.exists())
    }

    @Test
    fun aRestoreKilledMidSwapGetsTheLiveFilesBackFirst() {
        val parked = File(filesDir.path + ".bak")
        FileOps.rename(filesDir, parked)
        val backup = validated()
        try {
            BackupRestore.restore(backup, dbFile, filesDir, verify = { throw IllegalStateException("cannot open") })
            fail("expected failure")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), File(filesDir, "att/old.bin").readBytes())
        assertFalse(parked.exists())
    }

    @Test
    fun theDatabaseMayNotOpenWhileAnUnfinishedSwapIsPending() {
        assertFalse(BackupRestore.openBlocked(dbFile))
        for (step in listOf("staged", "swapping-db", "db-parked", "db-in", "files-in")) {
            setUpFresh()
            killAt(step)
            assertTrue(step, BackupRestore.openBlocked(dbFile))
            startUp()
            assertFalse(step, BackupRestore.openBlocked(dbFile))
        }
        setUpFresh()
        killAt("done")
        assertFalse(BackupRestore.openBlocked(dbFile))
        setUpFresh()
        File(tmp, RESTORE_JOURNAL_NAME).writeText("{not json")
        assertTrue(BackupRestore.openBlocked(dbFile))
        File(tmp, RESTORE_JOURNAL_NAME).delete()
        dbFile.renameTo(File(dbFile.path + ".bak"))
        assertTrue(BackupRestore.openBlocked(dbFile))
    }

    @Test
    fun aRecoveryThatFailsKeepsTheJournalAndTheParkedCopy() {
        killAt("db-in")
        dbFile.delete()
        dbFile.mkdirs()
        File(dbFile, "blocker").writeText("x")
        try {
            startUp()
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertTrue(File(tmp, RESTORE_JOURNAL_NAME).exists())
        assertTrue(File(dbFile.path + ".bak").exists())
    }

    @Test
    fun aFailureWhileClearingLeftoversIsStillASuccessAndTheNextStartFinishes() {
        val settled = mutableListOf<List<BackupFileEntry>>()
        BackupRestore.restore(
            validated(listedZip),
            dbFile,
            filesDir,
            settle = { _, listed -> settled.add(listed) },
            onStep = { if (it == "cleaning") throw IOException("the disk said no") },
        )
        expectNewData()
        assertEquals(listOf(listedPath), settled.single().map { it.path })
        assertEquals("item7", settled.single().single().mirror!!.itemId)
        assertTrue(File(tmp, RESTORE_JOURNAL_NAME).exists())
        assertEquals(RestoreRecovery.Finished, startUp { _, listed -> settled.add(listed) })
        assertEquals(1, settled.size)
        assertTrue(leftovers().isEmpty())
        expectNewData()
    }

    @Test
    fun aKillRightAfterDoneRunsTheFollowUpAtTheNextStart() {
        killAt("done", zip = listedZip, settle = { _, _ -> fail("killed before this") })
        val settled = mutableListOf<List<BackupFileEntry>>()
        assertEquals(RestoreRecovery.Finished, startUp { _, listed -> settled.add(listed) })
        assertEquals(listOf(listedPath), settled.single().map { it.path })
        assertTrue(leftovers().isEmpty())
        expectNewData()
    }

    @Test
    fun aKillAfterTheFollowUpRanDoesNotRunItAgain() {
        for (step in listOf("settled", "cleaning")) {
            setUpFresh()
            var settled = 0
            killAt(step, zip = listedZip, settle = { _, _ -> settled++ })
            assertEquals(1, settled)
            assertEquals(RestoreRecovery.Finished, startUp { _, _ -> settled++ })
            assertEquals(step, if (step == "settled") 2 else 1, settled)
            assertTrue(leftovers().isEmpty())
        }
    }

    @Test
    fun aFollowUpThatFailsIsRetriedAtTheNextStart() {
        BackupRestore.restore(validated(listedZip), dbFile, filesDir, settle = { _, _ -> throw IllegalStateException("db busy") })
        expectNewData()
        assertTrue(File(tmp, RESTORE_JOURNAL_NAME).exists())
        assertEquals(RestoreRecovery.Finished, startUp { _, _ -> throw IllegalStateException("still busy") })
        assertTrue(File(tmp, RESTORE_JOURNAL_NAME).exists())
        var ran = 0
        assertEquals(RestoreRecovery.Finished, startUp { _, _ -> ran++ })
        assertEquals(1, ran)
        assertFalse(File(tmp, RESTORE_JOURNAL_NAME).exists())
        assertEquals(RestoreRecovery.Nothing, startUp { _, _ -> ran++ })
        assertEquals(1, ran)
    }

    @Test
    fun aListedFileThePhoneStillHadIsCarriedOverFromTheOldStore() {
        writeFile(filesDir, listedPath, listedBytes)
        BackupRestore.restore(validated(listedZip), dbFile, filesDir)
        expectNewData()
        assertArrayEquals(listedBytes, File(filesDir, listedPath).readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aListedFileWithDifferentBytesIsNotCarriedOver() {
        writeFile(filesDir, listedPath, "xyz".toByteArray())
        BackupRestore.restore(validated(listedZip), dbFile, filesDir)
        assertFalse(File(filesDir, listedPath).exists())
    }

    @Test
    fun aKillWhileCarryingListedFilesFinishesAtTheNextStart() {
        writeFile(filesDir, listedPath, listedBytes)
        writeFile(filesDir, "att/listed2.bin", listedBytes)
        killAt("kept", zip = listedZip)
        assertEquals(RestoreRecovery.Finished, startUp())
        expectNewData()
        assertArrayEquals(listedBytes, File(filesDir, listedPath).readBytes())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun aRestoreThatCannotRecoverAnEarlierOneDiscardsTheUnpackedBackup() {
        killAt("db-in")
        dbFile.delete()
        dbFile.mkdirs()
        File(dbFile, "blocker").writeText("x")
        val backup = validated()
        try {
            BackupRestore.restore(backup, dbFile, filesDir)
            fail("expected failure")
        } catch (_: IOException) {
        }
        assertFalse(backup.staging.exists())
    }

    private fun setUpFresh() {
        tmp.listFiles().orEmpty().filter { it.name != "src" }.forEach { it.deleteRecursively() }
        dbFile = writeFakeDb(File(tmp, "remft.sqlite"), "Old task|Old note")
        File(dbFile.path + "-wal").writeText("old wal")
        filesDir = File(tmp, "files")
        writeFile(filesDir, "att/old.bin", byteArrayOf(1, 2, 3))
    }
}
