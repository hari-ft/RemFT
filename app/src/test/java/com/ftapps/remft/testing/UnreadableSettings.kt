package com.ftapps.remft.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.ftapps.remft.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

object UnreadableSettings {
    fun store(): SettingsStore = SettingsStore(
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("unreadable settings") }

            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("unwritable settings")
        },
    )
}
