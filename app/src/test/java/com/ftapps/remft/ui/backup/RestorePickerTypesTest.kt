package com.ftapps.remft.ui.backup

import com.ftapps.remft.domain.backup.BackupFormat
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePickerTypesTest {
    @Test
    fun aZipThatTheProviderLabelsAsGenericBytesCanBePicked() {
        assertTrue("application/octet-stream" in restorePickerMimeTypes)
        assertTrue(BackupFormat.ZIP_MIME in restorePickerMimeTypes)
        assertTrue("application/x-zip-compressed" in restorePickerMimeTypes)
    }
}
