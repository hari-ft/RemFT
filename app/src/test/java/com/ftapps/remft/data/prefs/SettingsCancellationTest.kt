package com.ftapps.remft.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.filesDir, "settings-cancel/${System.nanoTime()}.preferences_pb")
    private val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })

    @After
    fun tearDown() {
        scope.cancel()
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun aCancelledCallerSeesCancellationNotUnreadableSettings() = runBlocking {
        val seen = CompletableDeferred<Throwable?>()
        val job = launch {
            coroutineContext.job.cancel()
            try {
                store.snapshot()
                seen.complete(null)
            } catch (e: Exception) {
                seen.complete(e)
                throw e
            }
        }
        job.join()
        val error = seen.await()
        assertTrue(error is CancellationException)
        assertFalse(error is SettingsUnavailable)
    }
}
