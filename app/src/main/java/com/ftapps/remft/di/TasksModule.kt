package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.LiveZoneClock
import com.ftapps.remft.data.tasks.PendingDeletes
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.ui.tasks.TasksEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TasksModule {
    @Provides
    @Singleton
    fun tasksRepository(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        identity: LocalIdentityRepository,
        settings: SettingsStore,
        series: SeriesRepository,
    ): TasksRepository = TasksRepository(
        database = { holder.db },
        identity = identity,
        settings = settings,
        clock = LiveZoneClock,
        attachmentsDir = File(context.filesDir, "files"),
        seriesHooks = series,
    )

    @Provides
    @Singleton
    fun pendingDeletes(): PendingDeletes = PendingDeletes(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    @Provides
    @Singleton
    fun tasksEnvironment(
        repository: TasksRepository,
        settings: SettingsStore,
        pendingDeletes: PendingDeletes,
        series: SeriesRepository,
    ): TasksEnvironment = TasksEnvironment(repository, settings, pendingDeletes, series = series)
}
