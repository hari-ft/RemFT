package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.LiveZoneClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepeatModule {
    @Provides
    @Singleton
    fun seriesRepository(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        settings: SettingsStore,
    ): SeriesRepository = SeriesRepository(
        database = { holder.db },
        clock = LiveZoneClock,
        binEnabled = settings::binEnabled,
        attachmentsDir = File(context.filesDir, "files"),
    )
}
