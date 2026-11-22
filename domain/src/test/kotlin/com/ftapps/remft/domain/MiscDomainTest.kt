package com.ftapps.remft.domain

import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.text.fill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscDomainTest {
    @Test
    fun versionRollsOverNines() {
        assertEquals("1.2.1", AppVersion.parse("1.1.9").next().toString())
        assertEquals("2.1.1", AppVersion.parse("1.9.9").next().toString())
        assertEquals("3.1.2", AppVersion.parse("3.1.1").next().toString())
        assertEquals(311, AppVersion.parse("3.1.1").code)
        assertTrue(AppVersion.parse("3.1.1") > AppVersion.parse("2.3.7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionRejectsZeros() {
        AppVersion(3, 0, 1)
    }

    @Test
    fun fillReplacesNamedPlaceholders() {
        assertEquals("“Rent” deleted", "“{title}” deleted".fill("title" to "Rent"))
        assertEquals("3 of {total}", "{done} of {total}".fill("done" to 3))
    }

    @Test
    fun backupKeysAreTheMarkedOnes() {
        assertTrue("remft_date_format" in SettingKeys.backedUp)
        assertTrue("notification_privacy" in SettingKeys.backedUp)
        assertFalse("remft_local_workspace_id" in SettingKeys.backedUp)
        assertFalse("remft_last_backup_at" in SettingKeys.backedUp)
        assertEquals(SettingKeys.all.size, SettingKeys.all.map { it.name }.toSet().size)
    }

    @Test
    fun enumeratedSettingsFallBackToDefaults() {
        assertEquals(DateFormatId.Iso, DateFormatId.parse("iso"))
        assertEquals(DateFormatId.System, DateFormatId.parse("junk"))
        assertEquals(NoteSourceStyling.MarkersOnly, NoteSourceStyling.parse(null))
    }
}
