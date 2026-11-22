package com.ftapps.remft.data.alarm

import android.annotation.SuppressLint
import android.content.Context
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.domain.alarm.AlarmPlanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock

class AlarmEnvironment(
    val context: Context,
    val database: () -> RemftDatabase,
    val identity: LocalIdentityRepository,
    val settings: SettingsStore,
    val tasks: TasksRepository,
    val clock: Clock,
    val gateway: AlarmGateway = AndroidAlarmGateway(context) { settings.snoozeOrderNow() },
    filesDir: File = context.filesDir,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val health: AlarmHealth = AndroidAlarmHealth(context),
    ready: suspend () -> Unit = {},
) {
    val planner = AlarmPlanner(AlarmTextsFactory.from(context))
    val appLog = AppLog(File(filesDir, "logs"), clock)
    val log = AlarmEventLog(database, clock)
    val store = AlarmStore(database, tasks, clock)
    val pending = PendingAlarms(File(File(filesDir, PendingAlarms.DIRECTORY), PendingAlarms.FILE_NAME))
    val sounds = AlarmSoundStore(context, database, clock, filesDir)
    val scheduler = AlarmScheduler(identity, settings, store, gateway, pending, log, appLog, sounds, planner, clock, ready)
    val silencer = AlarmSilencer(settings, store, gateway, planner, { scheduler }, log)
    val actions = AlarmActionHandler(settings, store, scheduler, silencer, gateway, planner, log, appLog, clock)
    val ring = AlarmRingController(this)

    suspend fun workspaceFeed() = identity.resolve().let { if (it.reliable) store.feed(it.workspaceId) else emptyList() }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmEntryPoint {
    fun alarmEnvironment(): AlarmEnvironment
}

object AlarmRuntime {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var installed: AlarmEnvironment? = null

    fun get(context: Context): AlarmEnvironment = installed
        ?: EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java).alarmEnvironment()

    fun install(environment: AlarmEnvironment?) {
        installed = environment
    }
}
