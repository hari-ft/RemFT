package com.ftapps.remft.ui.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.R
import com.ftapps.remft.domain.backup.BackupFileEntry
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.BackupManifest
import com.ftapps.remft.domain.backup.BuiltBackup
import com.ftapps.remft.domain.backup.MirrorRef
import com.ftapps.remft.domain.text.fill
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupDoneMessageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun built(files: List<BackupFileEntry>, unavailable: List<String> = emptyList()) = BuiltBackup(
        File("backup.zip"),
        BackupManifest(
            format = BackupFormat.CURRENT,
            appVersion = "1.0.0",
            schemaVersion = 3,
            createdAt = Instant.parse("2026-11-20T04:30:00Z"),
            dbSha256 = "0".repeat(64),
            dbBytes = 1,
            fileCount = files.count { it.inZip },
            files = files,
            unavailable = unavailable,
        ),
    )

    private fun inZip(path: String) = BackupFileEntry(path, 1, "a".repeat(64), inZip = true)

    private fun inOneDrive(path: String) =
        BackupFileEntry(path, 1, "b".repeat(64), inZip = false, mirror = MirrorRef("item-$path", "RemFT/files/$path", 1, "b".repeat(64)))

    @Test
    fun aBackupHoldingEveryFileIsSimplyDone() {
        assertEquals(context.getString(R.string.backup_done), doneMessage(context, built(listOf(inZip("a.pdf")))))
    }

    @Test
    fun filesStillInOneDriveAreCounted() {
        val zip = built(listOf(inZip("a.pdf"), inOneDrive("b.pdf"), inOneDrive("c.pdf")))
        assertEquals(context.getString(R.string.backup_doneNotAllHere).fill("n" to 2), doneMessage(context, zip))
    }

    @Test
    fun filesThatAreNowhereWinOverOneDriveCopies() {
        val zip = built(listOf(inOneDrive("b.pdf")), unavailable = listOf("gone.pdf"))
        assertEquals(context.getString(R.string.backup_doneSomeMissing).fill("n" to 1), doneMessage(context, zip))
    }
}
