package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.alarm.BackgroundTurn
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.tasks.LiveZoneClock
import com.ftapps.remft.data.tasks.TasksRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmBindings {
    @Multibinds
    abstract fun backgroundTurns(): Set<BackgroundTurn>
}

@Module
@InstallIn(SingletonComponent::class)
object AlarmModule {
    private const val STARTUP_WAIT_MS = 10_000L

    @Provides
    @Singleton
    fun alarmEnvironment(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        identity: LocalIdentityRepository,
        settings: SettingsStore,
        tasks: TasksRepository,
        startup: Provider<AppStartup>,
    ): AlarmEnvironment = AlarmEnvironment(
        context = context,
        database = { holder.db },
        identity = identity,
        settings = settings,
        tasks = tasks,
        clock = LiveZoneClock,
        appLog = AppLogs.of(context),
        ready = {
            val app = startup.get()
            app.start()
            val state = withTimeoutOrNull(STARTUP_WAIT_MS) { app.state.first { it !is StartupState.Starting } }
            check(state is StartupState.Ready) { "Startup is not ready" }
        },
    )

    @Provides
    @Singleton
    fun alarmStartup(environment: AlarmEnvironment): AlarmStartup = AlarmStartup(environment)
}
