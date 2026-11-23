package com.ftapps.remft.domain.backup.mirror

import com.ftapps.remft.domain.backup.MirrorRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.random.Random

class MirrorTest {
    private fun referenceQuickXor(data: ByteArray): String {
        var reg = BigInteger.ZERO
        val mask = BigInteger.ONE.shiftLeft(160).subtract(BigInteger.ONE)
        for (i in data.indices) {
            val shift = (i * 11) % 160
            val v = BigInteger.valueOf((data[i].toInt() and 0xff).toLong()).shiftLeft(shift)
            reg = reg.xor(v.and(mask).or(v.shiftRight(160)))
        }
        val out = ByteArray(20)
        for (i in 0 until 20) out[i] = reg.shiftRight(8 * i).and(BigInteger.valueOf(0xff)).toInt().toByte()
        var len = data.size.toLong()
        for (i in 0 until 8) {
            out[12 + i] = (out[12 + i].toInt() xor (len and 0xff).toInt()).toByte()
            len = len shr 8
        }
        return Base64.getEncoder().encodeToString(out)
    }

    @Test
    fun quickXorMatchesTheDefinitionOnEveryLengthAndAnyChunking() {
        val rnd = Random(7)
        for (n in listOf(0, 1, 2, 19, 20, 159, 160, 161, 320, 1000, 4099)) {
            val data = ByteArray(n) { rnd.nextInt(256).toByte() }
            assertEquals("$n", referenceQuickXor(data), QuickXorHash.of(data))
            val chunked = QuickXorHash()
            var at = 0
            while (at < n) {
                val step = minOf(1 + rnd.nextInt(300), n - at)
                chunked.add(data.copyOfRange(at, at + step))
                at += step
            }
            assertEquals("$n", referenceQuickXor(data), chunked.base64Digest())
            assertEquals("$n", referenceQuickXor(data), QuickXorHash.of(ByteArrayInputStream(data)))
        }
    }

    @Test
    fun quickXorKnownValues() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", QuickXorHash.of(ByteArray(0)))
        assertEquals("SgAAAAAAAAAAAAAAAQAAAAAAAAA=", QuickXorHash.of(byteArrayOf(0x4a)))
        val offset = QuickXorHash().apply { add(byteArrayOf(9, 0x4a, 9), 1, 1) }
        assertEquals("SgAAAAAAAAAAAAAAAQAAAAAAAAA=", offset.base64Digest())
    }

    @Test
    fun filesKeepTheirFoldersAndTasksAndNotesGetTheirOwn() {
        assertEquals(
            "Files/Ringtones/Bell (F1).mp3",
            MirrorPaths.pathFor(MirrorPathInput("F1", "Bell.mp3", "F1.mp3", "workspace", listOf("Ringtones"))),
        )
        assertEquals("Files/scan (F2).pdf", MirrorPaths.pathFor(MirrorPathInput("F2", "scan.PDF", "F2.pdf", "workspace")))
        assertEquals(
            "Files/Tasks/Pay_ rent_April_/a (F3).png",
            MirrorPaths.pathFor(MirrorPathInput("F3", "a.png", "F3.png", "task", ownerTitle = "Pay: rent/April?", ownerId = "T1")),
        )
        assertEquals("Files/Notes/N1/x (F4)", MirrorPaths.pathFor(MirrorPathInput("F4", "x", "F4", "note", ownerId = "N1")))
        assertEquals("Files/Notes/_/x (F4)", MirrorPaths.pathFor(MirrorPathInput("F4", "x", "F4", "note")))
        assertEquals("Files/Other/x (F5).txt", MirrorPaths.pathFor(MirrorPathInput("F5", "x.txt", "F5.txt", "weird")))
        assertEquals("Files/Recycle bin/F5.jpg", MirrorPaths.binnedPath("F5.jpg"))
        assertEquals("Files/Recycle bin/" + "y".repeat(80), MirrorPaths.binnedPath("y".repeat(100)))
    }

    @Test
    fun segmentsAreSafeForOneDrive() {
        assertEquals("a b", MirrorPaths.safeSegment("  a  b. "))
        assertEquals("_", MirrorPaths.safeSegment("..."))
        assertEquals("_", MirrorPaths.safeSegment(""))
        assertEquals("_CON", MirrorPaths.safeSegment("CON"))
        assertEquals("_com1.txt", MirrorPaths.safeSegment("com1.txt"))
        assertEquals("_desktop.ini", MirrorPaths.safeSegment("desktop.ini"))
        assertEquals("_~\$lock", MirrorPaths.safeSegment("~\$lock"))
        assertEquals("__vti_x", MirrorPaths.safeSegment("_vti_x"))
        assertEquals("_.lock", MirrorPaths.safeSegment(".lock"))
        assertEquals("a_b_c_d", MirrorPaths.safeSegment("a#b%c|d"))
        assertEquals("a_b", MirrorPaths.safeSegment("a\u0001b"))
        assertEquals("a b", MirrorPaths.safeSegment("a  b"))
        assertEquals(60, MirrorPaths.safeSegment("x".repeat(100)).length)
        assertEquals(60, MirrorPaths.safeSegment("😀".repeat(70)).codePointCount(0, 120))
        assertEquals("ab" + "x".repeat(57), MirrorPaths.safeSegment("ab" + "x".repeat(57) + " cd"))
        assertEquals("a b", MirrorPaths.safeSegment("a" + " ".repeat(58) + "b"))
    }

    @Test
    fun theReviewFolderItsPathsItsNamesAndNoFilesFolderOfThatNameLandsInIt() {
        assertTrue(MirrorPaths.isReviewPath("Files/reviewneeded/a.pdf"))
        assertTrue(MirrorPaths.isReviewPath("Files/ReviewNeeded/a.pdf"))
        assertFalse(MirrorPaths.isReviewPath("Files/reviewneededx/a.pdf"))
        assertFalse(MirrorPaths.isReviewPath("Files/Tasks/a.pdf"))
        assertEquals("a (F1).pdf", MirrorPaths.reviewName("a (F1).pdf", 1))
        assertEquals("a (F1) (2).pdf", MirrorPaths.reviewName("a (F1).pdf", 2))
        assertEquals("x (F4) (3)", MirrorPaths.reviewName("x (F4)", 3))
        assertTrue(MirrorPaths.isReviewNameOf("a (F1) (2).pdf", "a (F1).pdf"))
        assertTrue(MirrorPaths.isReviewNameOf("A (F1).PDF", "a (F1).pdf"))
        assertFalse(MirrorPaths.isReviewNameOf("a (F1) (x).pdf", "a (F1).pdf"))
        assertFalse(MirrorPaths.isReviewNameOf("b (F1).pdf", "a (F1).pdf"))
        assertTrue(MirrorPaths.isReviewNameOf("a.b+c (12).txt", "a.b+c.txt"))
        assertEquals(
            "Files/_ReviewNeeded/x/a (F1).pdf",
            MirrorPaths.pathFor(MirrorPathInput("F1", "a.pdf", "F1.pdf", "workspace", listOf("ReviewNeeded", "x"))),
        )
    }

    @Test
    fun theMirrorNeverWritesOrDeletesOutsideFiles() {
        assertTrue(MirrorPaths.isMirrorPath("Files/a.pdf"))
        assertFalse(MirrorPaths.isMirrorPath("Files/../remft-auto-x.zip"))
        assertFalse(MirrorPaths.isMirrorPath("remft-auto-2026-11-01-090000.zip"))
        assertFalse(MirrorPaths.isMirrorPath("Files"))
        assertFalse(MirrorPaths.isMirrorPath("Other/a"))
        assertFalse(MirrorPaths.isMirrorPath("Files//a"))
    }

    @Test
    fun officeDocumentsMayBeRewritten() {
        assertTrue(MirrorPlan.oneDriveMayRewrite("Files/a/Report (F1).DOCX"))
        assertTrue(MirrorPlan.oneDriveMayRewrite("x.xlsm"))
        assertTrue(MirrorPlan.oneDriveMayRewrite("x.odp"))
        assertFalse(MirrorPlan.oneDriveMayRewrite("x.pdf"))
        assertFalse(MirrorPlan.oneDriveMayRewrite("x.docx.zip"))
        assertEquals(28, MirrorPlan.rewritableExtensions.size)
    }

    @Test
    fun confirmsAndHeldRewritten() {
        assertTrue(MirrorPlan.confirms(RemoteItem("i", size = 3, quickXorHash = "q"), 3, "q"))
        assertTrue(MirrorPlan.confirms(RemoteItem("i", size = 3), 3, "q"))
        assertTrue(MirrorPlan.confirms(RemoteItem("i", size = 3, quickXorHash = "q"), 3, null))
        assertFalse(MirrorPlan.confirms(RemoteItem("i", size = 3, quickXorHash = "q"), 3, "r"))
        assertFalse(MirrorPlan.confirms(RemoteItem("i", size = 4), 3, null))
        val path = "Files/Report (F1).docx"
        assertTrue(MirrorPlan.heldRewritten(RemoteItem("i", "report (f1).DOCX", 9), "i", path))
        assertTrue(MirrorPlan.heldRewritten(RemoteItem("i", null, 9), "i", path))
        assertFalse(MirrorPlan.heldRewritten(RemoteItem("j", null, 9), "i", path))
        assertFalse(MirrorPlan.heldRewritten(RemoteItem("i", "other.docx", 9), "i", path))
        assertFalse(MirrorPlan.heldRewritten(RemoteItem("i", null, 9), "i", "Files/a.pdf"))
        assertFalse(MirrorPlan.heldRewritten(RemoteItem("i", null, 9), "", path))
    }

    private val backupAt = Instant.parse("2026-11-20T02:00:30.500Z")

    private fun row(name: String, state: String = MirrorState.MIRRORED, item: String? = "id-$name", remote: String? = "Files/$name", mirroredAt: Instant? = null) =
        MirrorRecord(name, "sha-$name", 3, "q-$name", state, remote, item, mirroredAt = mirroredAt)

    @Test
    fun candidatesAreMirroredFilesOrFetchableOnesThePhoneLacks() {
        val rows = listOf(
            row("a.pdf"),
            row("b.pdf", MirrorState.MISSING),
            row("c.pdf", MirrorState.REMOTE_MISSING),
            row("d.pdf", MirrorState.FAILED),
            row("e.pdf", item = null),
            row("f.pdf", MirrorState.UPLOADING),
        ).associateBy { it.localPath }
        val local = setOf("a.pdf", "b.pdf", "f.pdf")
        val (candidates, hasLocal) = MirrorPlan.candidates(rows.keys + "g.pdf", rows, { it in local })
        assertEquals(listOf("a.pdf", "c.pdf"), candidates.map { it.localPath })
        assertEquals(false, hasLocal["c.pdf"])
        assertFalse(hasLocal.containsKey("e.pdf"))
    }

    @Test
    fun classifyListsConfirmedCopiesAndSortsTheRest() {
        val candidates = listOf(
            row("ok.pdf"),
            row("back.pdf", MirrorState.REMOTE_MISSING),
            row("gone-local.pdf"),
            row("gone-remote.pdf", MirrorState.REMOTE_MISSING),
            row("fresh.docx", remote = "Files/fresh.docx", mirroredAt = backupAt.minus(Duration.ofMinutes(10))),
            row("old.docx", remote = "Files/old.docx", mirroredAt = backupAt.minus(Duration.ofHours(2))),
            row("rewritten.docx", remote = "Files/rewritten.docx"),
        )
        val hasLocal = mapOf(
            "ok.pdf" to true, "back.pdf" to false, "gone-local.pdf" to true, "gone-remote.pdf" to false,
            "fresh.docx" to true, "old.docx" to true, "rewritten.docx" to true,
        )
        val items = mapOf(
            "id-ok.pdf" to RemoteItem("id-ok.pdf", size = 3, quickXorHash = "q-ok.pdf"),
            "id-back.pdf" to RemoteItem("id-back.pdf", size = 3),
            "id-fresh.docx" to RemoteItem("id-fresh.docx", size = 3, quickXorHash = "q-fresh.docx"),
            "id-old.docx" to RemoteItem("id-old.docx", size = 3, quickXorHash = "q-old.docx"),
            "id-rewritten.docx" to RemoteItem("id-rewritten.docx", "rewritten.docx", 5, "other"),
        )
        val plan = MirrorPlan.classify(candidates, hasLocal, items, backupAt) { true }
        assertEquals(listOf("ok.pdf", "back.pdf", "old.docx"), plan.plan.keys.toList())
        assertEquals(MirrorRef("id-ok.pdf", "Files/ok.pdf", 3, "sha-ok.pdf"), plan.plan["ok.pdf"])
        assertEquals(listOf("back.pdf"), plan.refound)
        assertEquals(listOf("gone-local.pdf"), plan.reupload)
        assertEquals(listOf("gone-remote.pdf"), plan.notThere)
        assertEquals(1, plan.rewrittenHeld)
        assertFalse(plan.otherAccount)

        val changed = MirrorPlan.classify(candidates, hasLocal, items, backupAt) { false }
        assertTrue("rewritten.docx" in changed.reupload)
    }

    @Test
    fun noneOfSeveralFoundIsAnotherAccount() {
        val candidates = listOf(row("a.pdf"), row("b.pdf", MirrorState.REMOTE_MISSING))
        val plan = MirrorPlan.classify(candidates, mapOf("a.pdf" to true, "b.pdf" to false), emptyMap(), backupAt) { true }
        assertTrue(plan.otherAccount)
        val single = MirrorPlan.classify(candidates.take(1), mapOf("a.pdf" to false), emptyMap(), backupAt) { true }
        assertFalse(single.otherAccount)
        val allLocal = MirrorPlan.classify(candidates.take(1) + row("c.pdf"), mapOf("a.pdf" to true, "c.pdf" to true), emptyMap(), backupAt) { true }
        assertFalse(allLocal.otherAccount)
    }

    @Test
    fun listedStampsNeverMoveBack() {
        assertEquals(Instant.parse("2026-11-20T02:00:30Z"), MirrorPlan.listedStamp(backupAt))
        assertTrue(MirrorPlan.shouldStampListed(null, backupAt))
        assertTrue(MirrorPlan.shouldStampListed(backupAt.minusSeconds(1), backupAt))
        assertFalse(MirrorPlan.shouldStampListed(backupAt.plusSeconds(1), backupAt))
        assertEquals(backupAt.plus(Duration.ofDays(365)), MirrorPlan.manualListStamp(backupAt))
    }

    @Test
    fun theManualBackupListsOnlyMirroredFilesThePhoneLacks() {
        val rows = listOf(
            row("here.pdf"),
            row("away.pdf"),
            row("pathonly.pdf", item = ""),
            row("nowhere.pdf", item = "", remote = "Other/x"),
            row("failed.pdf", MirrorState.FAILED),
        ).associateBy { it.localPath }
        val refs = MirrorPlan.refsForMissingLocal(rows.keys, rows) { it == "here.pdf" }
        assertEquals(listOf("away.pdf", "pathonly.pdf"), refs.keys.toList())
        assertEquals(MirrorRef("", "Files/pathonly.pdf", 3, "sha-pathonly.pdf"), refs["pathonly.pdf"])
    }

    @Test
    fun binAndReviewPathsKeepTheFolderStructure() {
        assertEquals("Files/Recycle bin/Bills/2025/a (F1).pdf", MirrorPaths.binned("Files/Bills/2025/a (F1).pdf"))
        assertEquals("Files/Recycle bin/Bills", MirrorPaths.binned("Files/Recycle bin/Bills"))
        assertEquals("Files/Recycle bin", MirrorPaths.binned("Files"))
        assertEquals("Files/reviewneeded/Bills/2025/a (F1).pdf", MirrorPaths.reviewPath("Files/Recycle bin/Bills/2025/a (F1).pdf"))
        assertEquals("Files/reviewneeded/Tasks/X/a.png", MirrorPaths.reviewPath("Files/Tasks/X/a.png"))
        assertEquals("Files/reviewneeded/a.png", MirrorPaths.reviewPath("Files/reviewneeded/a.png"))
        assertEquals("Files/reviewneeded/01F1.png", MirrorPaths.reviewPath("01F1.png"))
        assertEquals("Files/reviewneeded/Recycle binx/a", MirrorPaths.reviewPath("Files/Recycle binx/a"))
        assertEquals("Files/_Recycle bin/a (F1).pdf", MirrorPaths.pathFor(MirrorPathInput("F1", "a.pdf", "F1.pdf", "workspace", listOf("Recycle bin"))))
        assertEquals("Files/Docs/2025", MirrorPaths.folderPath(listOf("Docs", "2025")))
        assertEquals("Files/Tasks/Pay rent", MirrorPaths.ownerPath("task", "Pay rent", "T1"))
        assertEquals("2025 (2)", MirrorPaths.folderName("2025", 2))
        assertEquals("v1.2 (3)", MirrorPaths.folderName("v1.2", 3))
    }

    @Test
    fun aMissingCopyIsLookedForInAFewFoldersOnly() {
        assertEquals(
            listOf("Files/reviewneeded/Bills", "Files/Recycle bin/Bills", "Files/reviewneeded"),
            MirrorPaths.fallbackFolders("Files/Bills/a (F1).pdf"),
        )
        assertEquals(
            listOf("Files/reviewneeded/Bills", "Files/Bills", "Files/reviewneeded"),
            MirrorPaths.fallbackFolders("Files/Recycle bin/Bills/a (F1).pdf"),
        )
        assertEquals(listOf("Files/reviewneeded", "Files/Recycle bin"), MirrorPaths.fallbackFolders("Files/a (F1).pdf"))
    }

    @Test
    fun rebasingFollowsWholeSegmentsOnly() {
        assertEquals("Files/B/x.pdf", MirrorPaths.rebase("Files/A/x.pdf", "Files/A", "Files/B"))
        assertEquals("Files/B", MirrorPaths.rebase("files/a", "Files/A", "Files/B"))
        assertEquals(null, MirrorPaths.rebase("Files/AB/x.pdf", "Files/A", "Files/B"))
        assertTrue(MirrorPaths.isUnder("Files/Recycle bin/a", MirrorPaths.BIN_FOLDER))
        assertFalse(MirrorPaths.isUnder("Files/Recycle bins", MirrorPaths.BIN_FOLDER))
        assertEquals("Files/A", MirrorPaths.parentPath("Files/A/b.pdf"))
        assertEquals(2, MirrorPaths.depth("Files/A/b"))
    }

    @Test
    fun folderStateSurvivesARoundTripAndSkipsBrokenEntries() {
        val state = linkedMapOf(
            MirrorFolders.folderKey("D1") to MirrorFolder("I1", "Files/Docs", "Files/Docs"),
            MirrorFolders.ownerKey("task", "T1") to MirrorFolder("I2", "Files/Tasks/X (2)", "Files/Tasks/X"),
        )
        assertEquals(state, MirrorFolders.decode(MirrorFolders.encode(state)))
        assertTrue(MirrorFolders.decode(null).isEmpty())
        assertTrue(MirrorFolders.decode("not json").isEmpty())
        assertEquals(listOf("Files/Recycle bin/A"), MirrorFolders.decodePaths(MirrorFolders.encodePaths(listOf("Files/Recycle bin/A", "../x"))))
        assertEquals(setOf("a"), MirrorFolders.decode("""{"a":{"i":"I","p":"Files/A"},"b":{"i":"","p":"Files/B"},"c":{"i":"I","p":"../x"}}""").keys)
    }
}
