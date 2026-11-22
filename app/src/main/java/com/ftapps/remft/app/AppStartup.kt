package com.ftapps.remft.app

import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.files.FilesMaintenance
import com.ftapps.remft.data.identity.LocalIdentity
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.onedrive.OneDriveTriggers
import com.ftapps.remft.data.repeat.SeriesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

sealed interface StartupState {
    data object Starting : StartupState
    data class Ready(val identity: LocalIdentity) : StartupState
    data class Failed(val error: Throwable) : StartupState
}

@Singleton
class AppStartup @Inject constructor(
    private val databaseHolder: DatabaseHolder,
    private val identity: LocalIdentityRepository,
    private val restoreRecovery: StartupRestoreRecovery,
    private val series: SeriesRepository,
    private val oneDrive: Provider<OneDriveTriggers>,
    private val filesMaintenance: FilesMaintenance,
    private val alarms: Provider<AlarmStartup>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<StartupState>(StartupState.Starting)
    val state: StateFlow<StartupState> = mutableState.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var restored = false

    fun start() {
        if (started) return
        started = true
        scope.launch { run() }
    }

    fun retry() {
        mutableState.value = StartupState.Starting
        scope.launch { run() }
    }

    fun restartAfterRestore() {
        databaseHolder.close()
        databaseHolder.reopenAfterRestore()
        identity.forgetSession()
        restored = true
        retry()
    }

    private suspend fun run() {
        mutableState.value = try {
            restoreRecovery.run()
            databaseHolder.db.openHelper.writableDatabase
            StartupState.Ready(identity.resolve()).also { scope.launch { filesMaintenance.runAtStart() } }
        } catch (e: Exception) {
            StartupState.Failed(e)
        }
        if (mutableState.value is StartupState.Ready) runCatching { series.rollAll() }
        if (mutableState.value is StartupState.Ready) runCatching { oneDrive.get().onReady() }
        if (mutableState.value is StartupState.Ready && restored) {
            restored = false
            runCatching { alarms.get().afterRestore() }
        }
    }
}
