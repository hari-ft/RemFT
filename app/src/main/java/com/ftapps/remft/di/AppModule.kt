package com.ftapps.remft.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(SettingsStore.FILE_NAME) }

    @Provides
    @Singleton
    fun settingsStore(dataStore: DataStore<Preferences>): SettingsStore = SettingsStore(dataStore)

    @Provides
    @Singleton
    fun databaseHolder(@ApplicationContext context: Context): DatabaseHolder = DatabaseHolder(context)

    @Provides
    @Singleton
    fun localIdentity(holder: DatabaseHolder, store: SettingsStore): LocalIdentityRepository =
        LocalIdentityRepository({ holder.db.identityDao() }, store)
}
