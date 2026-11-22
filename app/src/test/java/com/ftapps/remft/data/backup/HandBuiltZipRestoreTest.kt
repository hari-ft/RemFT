package com.ftapps.remft.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.HandBuiltZips
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HandBuiltZipRestoreTest {
    private val rig = BackupRig()

    @After
    fun tearDown() = rig.close()

    private fun handBuiltZip(out: File, db: File, files: Map<String, ByteArray>, settings: String?): File =
        HandBuiltZips.write(out, db, files, settings)

    private fun otherDeviceDb(): File {
        val file = File(rig.root, "other/remft.sqlite")
        TestDatabases.create(file) { db ->
            BackupRig.seedRows(db, OTHER_WORKSPACE)
        }
        return file
    }

    @Test
    fun aZipBuiltFromTheFormatDescriptionValidatesAndRestores() = runBlocking {
        rig.seed()
        rig.store.set(SettingKeys.NotesViewMode.name, "grid")
        val files = mapOf(
            "01JC0000000000000000000001.png" to byteArrayOf(9, 8, 7),
            "01JC0000000000000000000002.txt" to "from another phone".toByteArray(),
        )
        val zip = handBuiltZip(
            File(rig.root, "remft-backup-2026-11-02.zip"),
            otherDeviceDb(),
            files,
            "{\"remft_date_format\":\"dmy_slash\",\"remft_tasks_sort\":\"priority:desc\"}",
        )
        val service = rig.service()

        val backup = service.validate(zip.inputStream())
        assertEquals(java.time.Instant.parse("2026-11-02T10:15:30.123Z"), backup.manifest.createdAt)
        service.restore(backup)

        rig.holder.reopenAfterRestore()
        assertEquals(2, rig.dump().getValue("tasks").size)
        assertEquals(files.mapValues { it.value.toList() }, rig.filesSnapshot())
        assertEquals(OTHER_WORKSPACE, rig.store.read(SettingKeys.LocalWorkspaceId.name))
        assertEquals("dmy_slash", rig.store.read(SettingKeys.DateFormat.name))
        assertEquals("priority:desc", rig.store.read(SettingKeys.TasksSort.name))
        assertEquals(null, rig.store.read(SettingKeys.NotesViewMode.name))
    }

    @Test
    fun aDatabaseWithoutSettingsRestores() = runBlocking {
        rig.seed()
        rig.store.set(SettingKeys.NotesViewMode.name, "grid")
        val zip = handBuiltZip(File(rig.root, "nosettings.zip"), otherDeviceDb(), emptyMap(), settings = null)
        val service = rig.service()

        service.restore(service.validate(zip.inputStream()))

        rig.holder.reopenAfterRestore()
        assertEquals(2, rig.dump().getValue("tasks").size)
        assertEquals(emptyMap<String, List<Byte>>(), rig.filesSnapshot())
        assertEquals("grid", rig.store.read(SettingKeys.NotesViewMode.name))
    }

    companion object {
        private const val OTHER_WORKSPACE = "01JCOTHERPHONE000000000WS1"
    }
}
