package com.ftapps.remft.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        file = File(context.filesDir, "settings-test/${System.nanoTime()}.preferences_pb")
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun roundTripsValues() = runBlocking {
        store.set("remft_date_format", "iso")
        assertEquals("iso", store.read("remft_date_format"))
        store.remove("remft_date_format")
        assertNull(store.read("remft_date_format"))
    }

    @Test
    fun flowFallsBackToTheDefault() = runBlocking {
        assertEquals("system", store.flow(SettingKeys.DateFormat).first())
        store.set(SettingKeys.DateFormat.name, "mdy_12h")
        assertEquals("mdy_12h", store.flow(SettingKeys.DateFormat).first())
        assertEquals("30", store.value(SettingKeys.BinDays))
    }

    @Test
    fun backupSettingsHoldOnlyBackedUpKeys() = runBlocking {
        store.setAll(
            mapOf(
                "remft_date_format" to "iso",
                "notification_privacy" to "true",
                "remft_local_workspace_id" to "01JWSAMPLE0000000000000000",
                "remft_last_backup_at" to "2026-11-01T00:00:00.000Z",
            ),
        )
        assertEquals(mapOf("remft_date_format" to "iso", "notification_privacy" to "true"), store.backupSettings())
    }

    @Test
    fun setAllRemovesListedKeys() = runBlocking {
        store.setAll(mapOf("a" to "1", "b" to "2"))
        store.setAll(mapOf("c" to "3"), removing = listOf("a"))
        assertEquals(mapOf("b" to "2", "c" to "3"), store.snapshot())
    }
}
