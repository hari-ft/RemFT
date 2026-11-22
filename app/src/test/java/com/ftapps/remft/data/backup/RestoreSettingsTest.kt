package com.ftapps.remft.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreSettingsTest {
    private val rig = BackupRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun applyingSetsListedKeysAndRemovesAbsentBackedUpKeysOnly() = runBlocking {
        rig.store.setAll(
            mapOf(
                SettingKeys.DateFormat.name to "iso",
                SettingKeys.NotesViewMode.name to "grid",
                SettingKeys.LastBackupAt.name to "2026-10-01T00:00:00.000Z",
                SettingKeys.LocalWorkspaceId.name to BackupRig.WORKSPACE,
            ),
        )
        RestoreSettings.hold(
            rig.store,
            mapOf(SettingKeys.DateFormat.name to "dmy_long", SettingKeys.BinDays.name to "90"),
        )

        assertTrue(RestoreSettings.applyPending(rig.store))

        assertEquals("dmy_long", rig.store.read(SettingKeys.DateFormat.name))
        assertEquals("90", rig.store.read(SettingKeys.BinDays.name))
        assertNull(rig.store.read(SettingKeys.NotesViewMode.name))
        assertEquals("2026-10-01T00:00:00.000Z", rig.store.read(SettingKeys.LastBackupAt.name))
        assertEquals(BackupRig.WORKSPACE, rig.store.read(SettingKeys.LocalWorkspaceId.name))
        assertNull(rig.store.read(BackupSettings.pendingKey))
        assertFalse(RestoreSettings.applyPending(rig.store))
    }

    @Test
    fun unreadablePendingSettingsAreDroppedWithoutTouchingSettings() = runBlocking {
        rig.store.setAll(mapOf(SettingKeys.DateFormat.name to "iso", BackupSettings.pendingKey to "{not json"))
        assertFalse(RestoreSettings.applyPending(rig.store))
        assertEquals("iso", rig.store.read(SettingKeys.DateFormat.name))
        assertNull(rig.store.read(BackupSettings.pendingKey))
    }

    @Test
    fun unknownKeysInABackupAreIgnored() = runBlocking {
        rig.store.set(BackupSettings.pendingKey, "{\"remft_date_format\":\"iso\",\"remft_local_workspace_id\":\"X\",\"remft_bin_days\":30}")
        assertTrue(RestoreSettings.applyPending(rig.store))
        assertEquals("iso", rig.store.read(SettingKeys.DateFormat.name))
        assertNull(rig.store.read(SettingKeys.LocalWorkspaceId.name))
        assertNull(rig.store.read(SettingKeys.BinDays.name))
    }
}
