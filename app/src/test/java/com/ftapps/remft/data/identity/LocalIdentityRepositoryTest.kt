package com.ftapps.remft.data.identity

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.TestDatabases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalIdentityRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private lateinit var store: SettingsStore
    private var holder: DatabaseHolder? = null

    @Before
    fun setUp() {
        dir = File(context.filesDir, "identity-test-${System.nanoTime()}")
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
    }

    @After
    fun tearDown() {
        holder?.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun repository(seed: (android.database.sqlite.SQLiteDatabase) -> Unit = {}): LocalIdentityRepository {
        val file = File(dir, "remft.sqlite")
        TestDatabases.create(file, seed)
        val opened = DatabaseHolder(context, file)
        holder = opened
        return LocalIdentityRepository({ opened.db.identityDao() }, store)
    }

    @Test
    fun mintsAndStoresAnIdOnFirstStart() = runBlocking {
        val identity = repository().resolve()
        assertTrue(Ulid.isValid(identity.workspaceId))
        assertTrue(identity.reliable)
        assertEquals(identity.workspaceId, store.read(SettingKeys.LocalWorkspaceId.name))
    }

    @Test
    fun adoptsTheWorkspaceThatOwnsTheData() = runBlocking {
        store.set(SettingKeys.LocalWorkspaceId.name, "01JSTALE000000000000000000")
        val repo = repository { db ->
            db.execSQL("INSERT INTO tasks (id, workspace_id, title) VALUES ('T1', 'WSA', 'a'), ('T2', 'WSB', 'b'), ('T3', 'WSB', 'c')")
            db.execSQL("INSERT INTO notes (id, workspace_id, title) VALUES ('N1', 'WSA', 'n')")
            db.execSQL("INSERT INTO folders (id, workspace_id, name) VALUES ('F1', 'WSB', 'f')")
        }
        val identity = repo.resolve()
        assertEquals("WSB", identity.workspaceId)
        assertEquals("WSB", store.read(SettingKeys.LocalWorkspaceId.name))
    }

    @Test
    fun keepsAStoredIdThatOwnsRows() = runBlocking {
        store.set(SettingKeys.LocalWorkspaceId.name, "WSA")
        val repo = repository { db ->
            db.execSQL("INSERT INTO tasks (id, workspace_id, title) VALUES ('T1', 'WSA', 'a'), ('T2', 'WSB', 'b'), ('T3', 'WSB', 'c')")
        }
        assertEquals("WSA", repo.resolve().workspaceId)
    }
}
