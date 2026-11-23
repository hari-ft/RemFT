package com.ftapps.remft.domain.backup

import com.ftapps.remft.domain.backup.BackupTestSupport.SCHEMA
import com.ftapps.remft.domain.backup.BackupTestSupport.buildZip
import com.ftapps.remft.domain.backup.BackupTestSupport.dbContent
import com.ftapps.remft.domain.backup.BackupTestSupport.fakeDbBytes
import com.ftapps.remft.domain.backup.BackupTestSupport.readEntries
import com.ftapps.remft.domain.backup.BackupTestSupport.rewrite
import com.ftapps.remft.domain.backup.BackupTestSupport.writeFile
import com.ftapps.remft.domain.backup.BackupTestSupport.writeZip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class BackupValidatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val root: File get() = temp.root
    private val scratch: File get() = File(root, "scratch")

    private fun validate(zip: File, currentSchema: Int = SCHEMA): ValidatedBackup =
        BackupValidator.validate(zip, currentSchema, scratch)

    private fun refused(zip: File, currentSchema: Int = SCHEMA): String? = try {
        validate(zip, currentSchema).discard()
        null
    } catch (e: BackupException) {
        e.code
    }

    private fun stagingIsEmpty() = scratch.listFiles().orEmpty().isEmpty()

    private fun filesDirWith(vararg files: Pair<String, ByteArray>): File {
        val dir = File(root, "files-${System.nanoTime()}")
        dir.mkdirs()
        for ((rel, bytes) in files) writeFile(dir, rel, bytes)
        return dir
    }

    @Test
    fun aBuiltZipValidatesAndUnpacksIntoStaging() {
        val files = filesDirWith("a/photo.jpg" to byteArrayOf(1, 2, 3), "att/deep/n.txt" to "ok".toByteArray())
        val built = buildZip(root, "tasks:T1", files)
        assertEquals(2, built.manifest.fileCount)
        val backup = validate(built.zip)
        assertEquals("tasks:T1", dbContent(backup.dbFile))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(backup.filesDir, "a/photo.jpg").readBytes())
        assertEquals("ok", File(backup.filesDir, "att/deep/n.txt").readText())
        assertTrue(backup.staging.path.startsWith(scratch.path))
        assertTrue(backup.staging.name.startsWith("restore-"))
        assertNull(backup.settings)
        backup.discard()
        assertFalse(backup.staging.exists())
    }

    @Test
    fun entriesAreWrittenFilesFirstThenManifestSettingsAndDatabase() {
        val files = filesDirWith("b.bin" to byteArrayOf(2), "a.bin" to byteArrayOf(1))
        val built = buildZip(root, "x", files, settings = mapOf("remft_date_format" to "dmy"))
        assertEquals(
            listOf("files/a.bin", "files/b.bin", "manifest.json", "settings.json", "remft.sqlite"),
            readEntries(built.zip).map { it.first },
        )
    }

    @Test
    fun aLargeIncompressibleFileRoundTrips() {
        var seed = 0x2545F491L
        val payload = ByteArray(3 * 1024 * 1024) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            (seed shr 16).toByte()
        }
        val built = buildZip(root, "big", filesDirWith("att/video.bin" to payload))
        assertTrue(built.zip.length() > payload.size)
        val backup = validate(built.zip)
        assertArrayEquals(payload, File(backup.filesDir, "att/video.bin").readBytes())
        backup.discard()
    }

    @Test
    fun settingsTravelInsideTheZip() {
        val built = buildZip(root, "x", settings = mapOf("remft_date_format" to "dmy"))
        val backup = validate(built.zip)
        assertEquals(mapOf("remft_date_format" to "dmy"), backup.settings)
        backup.discard()
    }

    @Test
    fun unreadableOrOversizedSettingsAreIgnored() {
        val built = buildZip(root, "x")
        val junk = rewrite(built.zip, File(root, "junk.zip")) { it + ("settings.json" to "not json".toByteArray()) }
        assertNull(validate(junk).settings)
        val big = rewrite(built.zip, File(root, "big.zip")) {
            it + ("settings.json" to ("{\"remft_date_format\":\"" + "x".repeat(70 * 1024) + "\"}").toByteArray())
        }
        assertNull(validate(big).settings)
    }

    @Test
    fun aFileThatIsNotAZip() {
        val file = File(root, "hello.zip").apply { writeText("hello") }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(file))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aZipWithoutManifestOrDatabase() {
        val built = buildZip(root, "x")
        val noManifest = rewrite(built.zip, File(root, "nm.zip")) { list -> list.filter { it.first != "manifest.json" } }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(noManifest))
        val noDb = rewrite(built.zip, File(root, "nd.zip")) { list -> list.filter { it.first != "remft.sqlite" } }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(noDb))
    }

    @Test
    fun aManifestThatIsNotJson() {
        val built = buildZip(root, "x")
        val bad = rewrite(built.zip, File(root, "bad.zip")) { list ->
            list.map { if (it.first == "manifest.json") it.first to "{torn".toByteArray() else it }
        }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(bad))
        val notUtf8 = rewrite(built.zip, File(root, "utf.zip")) { list ->
            list.map { if (it.first == "manifest.json") it.first to byteArrayOf(0x7b, 0xff.toByte(), 0x7d) else it }
        }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(notUtf8))
    }

    @Test
    fun aManifestTooLargeToBeOne() {
        val built = buildZip(root, "x")
        val bad = rewrite(built.zip, File(root, "pad.zip"), { it["pad"] = JsonPrimitive("x".repeat(1024 * 1024)) })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(bad))
    }

    @Test
    fun aZipFromANewerApp() {
        val built = buildZip(root, "x")
        val schema = rewrite(built.zip, File(root, "s.zip"), { it["schemaVersion"] = JsonPrimitive(9999) })
        assertEquals(BackupErrorCode.NEWER_VERSION, refused(schema))
        val format = rewrite(built.zip, File(root, "f.zip"), { it["format"] = JsonPrimitive(BackupFormat.NEXT_FORMAT) })
        assertEquals(BackupErrorCode.NEWER_VERSION, refused(format))
    }

    @Test
    fun aZipWithAnOlderFormatOrSchemaIsNotABackup() {
        val built = buildZip(root, "x")
        val format = rewrite(built.zip, File(root, "old.zip"), { it["format"] = JsonPrimitive(0) })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(format))
        val schema = rewrite(built.zip, File(root, "olds.zip"), { it["schemaVersion"] = JsonPrimitive(0) })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(schema))
        val otherApp = rewrite(built.zip, File(root, "app.zip"), { it["app"] = JsonPrimitive("other") })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(otherApp))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aDatabaseWhoseSizeDiffersFromTheManifest() {
        val built = buildZip(root, "x")
        val bad = rewrite(built.zip, File(root, "size.zip"), { it["dbBytes"] = JsonPrimitive(5) })
        assertEquals(BackupErrorCode.DAMAGED, refused(bad))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aDatabaseWhoseSha256DoesNotMatch() {
        val built = buildZip(root, "x")
        val bad = rewrite(built.zip, File(root, "sha.zip")) { list ->
            list.map {
                if (it.first == "remft.sqlite") {
                    val tampered = it.second.copyOf()
                    tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xff).toByte()
                    it.first to tampered
                } else {
                    it
                }
            }
        }
        assertEquals(BackupErrorCode.DAMAGED, refused(bad))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aFileThatIsNotASqliteDatabase() {
        val notDb = ByteArray(128) { 7 }
        val manifest = BackupManifest(BackupFormat.CURRENT, "1", SCHEMA, Instant.EPOCH, FileOps.sha256Hex(notDb), notDb.size.toLong(), 0)
        val zip = writeZip(File(root, "nd.zip"), listOf("manifest.json" to manifest.toPrettyJson().toByteArray(), "remft.sqlite" to notDb))
        assertEquals(BackupErrorCode.NOT_A_DATABASE, refused(zip))
        val short = fakeDbBytes().copyOf(40)
        val m2 = manifest.copy(dbSha256 = FileOps.sha256Hex(short), dbBytes = short.size.toLong())
        val zip2 = writeZip(File(root, "short.zip"), listOf("manifest.json" to m2.toPrettyJson().toByteArray(), "remft.sqlite" to short))
        assertEquals(BackupErrorCode.NOT_A_DATABASE, refused(zip2))
    }

    @Test
    fun aDatabaseWhoseRealSchemaIsNotTheOneClaimed() {
        val db = fakeDbBytes(schema = SCHEMA + 1)
        val zip = writeZip(File(root, "v.zip"), listOf("manifest.json" to manifestFor(db).toPrettyJson().toByteArray(), "remft.sqlite" to db))
        assertEquals(BackupErrorCode.DAMAGED, refused(zip))
    }

    @Test
    fun anEntryThatWouldClimbOutOfTheFileStore() {
        val built = buildZip(root, "x")
        val bad = rewrite(built.zip, File(root, "evil.zip")) { it + ("files/../../evil.txt" to byteArrayOf(1)) }
        assertEquals(BackupErrorCode.DAMAGED, refused(bad))
        for (name in listOf("files/a\\b.txt", "files/./a.txt", "files//a.txt", "files/a/../b.txt")) {
            val manifest = manifestFor(fakeDbBytes(), name.removePrefix("files/") to byteArrayOf(1))
            val zip = writeZip(
                File(root, "p-${System.nanoTime()}.zip"),
                listOf("manifest.json" to manifest.toPrettyJson().toByteArray(), "remft.sqlite" to fakeDbBytes(), name to byteArrayOf(1)),
            )
            assertEquals(name, BackupErrorCode.DAMAGED, refused(zip))
        }
        assertFalse(File(root, "evil.txt").exists())
    }

    @Test
    fun aListedPathThatIsUnsafeOrDuplicated() {
        val built = buildZip(root, "x", filesDirWith("a.bin" to byteArrayOf(1)))
        val unsafe = rewrite(built.zip, File(root, "u.zip"), { m ->
            val files = (m["files"] as JsonArray).map { it as JsonObject }
            m["files"] = JsonArray(files.map { JsonObject(it + ("path" to JsonPrimitive("../a.bin"))) })
        })
        assertEquals(BackupErrorCode.DAMAGED, refused(unsafe))
        val duplicated = rewrite(built.zip, File(root, "d.zip"), { m ->
            val files = m["files"] as JsonArray
            m["files"] = JsonArray(files + files)
        })
        assertEquals(BackupErrorCode.DAMAGED, refused(duplicated))
    }

    @Test
    fun aDuplicatedZipEntryIsRefused() {
        val manifest = manifestFor(fakeDbBytes(), "a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2))
        val zip = writeZip(
            File(root, "dup.zip"),
            listOf(
                "manifest.json" to manifest.toPrettyJson().toByteArray(),
                "remft.sqlite" to fakeDbBytes(),
                "files/a.bin" to byteArrayOf(1),
                "files/b.bin" to byteArrayOf(2),
            ),
        )
        val patched = File(root, "dup2.zip").apply { writeBytes(BackupTestSupport.replaceAll(zip.readBytes(), "files/b.bin", "files/a.bin")) }
        assertEquals(BackupErrorCode.DAMAGED, refused(patched))
    }

    @Test
    fun aCrcMismatchIsDamaged() {
        val manifest = manifestFor(fakeDbBytes(), "a.bin" to byteArrayOf(1, 2, 3))
        val zip = writeZip(
            File(root, "crc.zip"),
            listOf("manifest.json" to manifest.toPrettyJson().toByteArray(), "remft.sqlite" to fakeDbBytes(), "files/a.bin" to byteArrayOf(1, 2, 3)),
        )
        assertNull(refused(zip))
        val patched = File(root, "crc2.zip").apply { writeBytes(BackupTestSupport.patchCentralCrc(zip.readBytes(), "files/a.bin", 0x12345678L)) }
        assertEquals(BackupErrorCode.DAMAGED, refused(patched))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun aTruncatedZipIsRefused() {
        val built = buildZip(root, "x", filesDirWith("a.bin" to ByteArray(4096) { it.toByte() }))
        val bytes = built.zip.readBytes()
        val cut = File(root, "cut.zip").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(cut))
    }

    @Test
    fun aDatabaseUnderAnyOtherNameIsRefused() {
        val built = buildZip(root, "renamed", filesDirWith("a.bin" to byteArrayOf(9)))
        val renamed = rewrite(built.zip, File(root, "renamed.zip")) { list ->
            list.map { if (it.first == "remft.sqlite") "other.sqlite" to it.second else it }
        }
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(renamed))
    }

    @Test
    fun listedFilesMustBeAccountedFor() {
        val sha = FileOps.sha256Hex("abc".toByteArray())
        val good = buildZip(
            root,
            "x",
            filesDirWith("F2.pdf" to byteArrayOf(5, 6)),
            listOnly = mapOf("F1.pdf" to MirrorRef("item1", "Files/F1 (F1).pdf", 3, sha)),
        )
        assertEquals(BackupKind.MIRRORED, good.manifest.kind)
        assertNull(refused(good.zip))

        val noMirror = rewrite(good.zip, File(root, "nomirror.zip"), { m ->
            m["files"] = JsonArray((m["files"] as JsonArray).map { JsonObject((it as JsonObject) - "mirror") })
        })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(noMirror))

        val stowaway = rewrite(good.zip, File(root, "stow.zip")) { it + ("files/stowaway.bin" to byteArrayOf(1, 2, 3)) }
        assertEquals(BackupErrorCode.DAMAGED, refused(stowaway))

        val listedBytes = rewrite(good.zip, File(root, "listedbytes.zip")) { it + ("files/F1.pdf" to "abc".toByteArray()) }
        assertEquals(BackupErrorCode.DAMAGED, refused(listedBytes))

        val missing = rewrite(good.zip, File(root, "missing.zip"), { it["fileCount"] = JsonPrimitive(1) }) { list ->
            list.filter { !it.first.startsWith("files/") }
        }
        assertEquals(BackupErrorCode.DAMAGED, refused(missing))

        val wrongSha = rewrite(good.zip, File(root, "wrongsha.zip"), { m ->
            m["files"] = JsonArray(
                (m["files"] as JsonArray).map {
                    val e = it as JsonObject
                    if (JsonText.boolOf(e["inZip"]) == true) JsonObject(e + ("sha256" to JsonPrimitive("0".repeat(64)))) else e
                },
            )
        })
        assertEquals(BackupErrorCode.DAMAGED, refused(wrongSha))

        val wrongCount = rewrite(good.zip, File(root, "count.zip"), { it["fileCount"] = JsonPrimitive(2) })
        assertEquals(BackupErrorCode.DAMAGED, refused(wrongCount))

        val noList = rewrite(good.zip, File(root, "nolist.zip"), { it.remove("files") })
        assertEquals(BackupErrorCode.NOT_A_BACKUP, refused(noList))
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun listedOnlyEntriesComeBackWithTheirMirrorReferences() {
        val sha = FileOps.sha256Hex("abc".toByteArray())
        val built = buildZip(root, "x", listOnly = mapOf("att/listed.bin" to MirrorRef("item7", "Files/Tasks/T/listed (F7).bin", 3, sha)))
        val backup = validate(built.zip)
        val listed = backup.manifest.listedOnly.single()
        assertEquals("att/listed.bin", listed.path)
        assertEquals(MirrorRef("item7", "Files/Tasks/T/listed (F7).bin", 3, sha), listed.mirror)
        backup.discard()
    }

    @Test
    fun theWriterSkipsPartialFilesAndListedOnlyFilesAndReportsUnavailable() {
        val files = filesDirWith(
            "keep.bin" to byteArrayOf(1),
            "copying.bin.part" to byteArrayOf(2),
            "listed.bin" to byteArrayOf(3),
        )
        val sha = FileOps.sha256Hex(byteArrayOf(3))
        val built = buildZip(
            root,
            "x",
            files,
            listOnly = mapOf("listed.bin" to MirrorRef("i", "Files/listed.bin", 1, sha)),
            referenced = setOf("keep.bin", "listed.bin", "zzz-gone.bin", "aaa-gone.bin"),
            kind = BackupKind.COMPLETE_PARTIAL,
        )
        val names = readEntries(built.zip).map { it.first }
        assertTrue("files/keep.bin" in names)
        assertFalse("files/copying.bin.part" in names)
        assertFalse("files/listed.bin" in names)
        assertEquals(1, built.manifest.fileCount)
        assertEquals(listOf("aaa-gone.bin", "zzz-gone.bin"), built.unavailable)
        assertEquals(BackupKind.COMPLETE_PARTIAL, built.manifest.kind)
        val backup = validate(built.zip)
        assertEquals(listOf("aaa-gone.bin", "zzz-gone.bin"), backup.manifest.unavailable)
        backup.discard()
    }

    @Test
    fun aFileDeletedWhileTheBackupRunsIsLeftOut() {
        val files = filesDirWith("a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2))
        val snapshot = BackupTestSupport.writeFakeDb(File(root, "snap.sqlite"), "x")
        File(snapshot.path + "-wal").writeText("stale")
        val built = BackupZipWriter.build(
            dbSnapshot = snapshot,
            out = File(root, "deleted.zip"),
            appVersion = "1",
            schemaVersion = SCHEMA,
            referenced = setOf("a.bin", "b.bin"),
            filesDir = files,
            now = BackupTestSupport.NOW,
            beforeEachFile = { rel -> if (rel == "b.bin") File(files, rel).delete() },
        )
        assertFalse(File(snapshot.path + "-wal").exists())
        assertEquals(1, built.manifest.fileCount)
        assertEquals(listOf("b.bin"), built.unavailable)
        assertEquals(BackupKind.COMPLETE, built.manifest.kind)
        assertNull(refused(built.zip))
    }

    @Test
    fun aFailedBuildLeavesNoZip() {
        val out = File(root, "fail.zip")
        try {
            BackupZipWriter.build(File(root, "missing.sqlite"), out, "1", SCHEMA, emptySet(), now = BackupTestSupport.NOW)
            fail("expected failure")
        } catch (_: Exception) {
        }
        assertFalse(out.exists())
    }

    @Test
    fun theManifestOfABuiltZipIsExact() {
        val built = buildZip(root, "x")
        val manifest = readEntries(built.zip).first { it.first == "manifest.json" }.second.toString(Charsets.UTF_8)
        val db = fakeDbBytes(content = "x")
        assertEquals(
            JsonText.parse(
                "{\"app\":\"remft\",\"format\":1,\"appVersion\":\"9.9.9\",\"schemaVersion\":1," +
                    "\"createdAt\":\"2026-11-02T10:00:00.000Z\",\"dbSha256\":\"${FileOps.sha256Hex(db)}\"," +
                    "\"dbBytes\":${db.size},\"fileCount\":0,\"kind\":\"complete\",\"files\":[]}",
            ),
            JsonText.parse(manifest),
        )
    }

    private fun manifestFor(db: ByteArray, vararg files: Pair<String, ByteArray>): BackupManifest = BackupManifest(
        format = BackupFormat.CURRENT,
        appVersion = "1",
        schemaVersion = SCHEMA,
        createdAt = Instant.EPOCH,
        dbSha256 = FileOps.sha256Hex(db),
        dbBytes = db.size.toLong(),
        fileCount = files.size,
        files = files.map { (path, bytes) -> BackupFileEntry(path, bytes.size.toLong(), FileOps.sha256Hex(bytes), inZip = true) },
    )
}
