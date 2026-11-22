package com.ftapps.remft.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.backup.BackupDatabases
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.onedrive.AutoBackupHealth
import com.ftapps.remft.data.onedrive.AutoBackupSnapshot
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupReminder
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class BackupHostViewModel @Inject constructor(
    val coordinator: BackupCoordinator,
    store: SettingsStore,
) : ViewModel() {
    val dateFormat: StateFlow<DateFormatId> = store.flow(SettingKeys.DateFormat)
        .map { DateFormatId.parse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DateFormatId.System)

    fun onPicked(uri: Uri?) = coordinator.onPicked(uri)
}

data class BackupSettingsState(val lastBackup: Instant? = null, val dateFormat: DateFormatId = DateFormatId.System)

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val coordinator: BackupCoordinator,
    store: SettingsStore,
    auth: OneDriveAuth,
) : ViewModel() {
    val autoBackup: StateFlow<AutoBackupSnapshot?> = AutoBackupHealth.snapshots(store, auth.connected)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<BackupSettingsState> = combine(
        store.flow(SettingKeys.LastBackupAt.name),
        store.flow(SettingKeys.DateFormat),
    ) { last, format -> BackupSettingsState(UtcText.parse(last), DateFormatId.parse(format)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettingsState())

    val busy: StateFlow<Boolean> = coordinator.state
        .map { it.backingUp || it.restore != RestoreUiState.Idle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun backUpNow() = coordinator.backUpNow()

    fun restore() = coordinator.requestRestore()
}

@HiltViewModel
class BackupReminderViewModel @Inject constructor(
    private val coordinator: BackupCoordinator,
    private val holder: DatabaseHolder,
    private val store: SettingsStore,
) : ViewModel() {
    val due: StateFlow<Boolean> = combine(
        store.flow(SettingKeys.LastBackupAt.name),
        store.flow(SettingKeys.BackupReminderDismissed.name),
    ) { last, dismissed ->
        val hasData = withContext(Dispatchers.IO) {
            runCatching { BackupDatabases.hasData(holder.db.openHelper.readableDatabase) }.getOrDefault(false)
        }
        BackupReminder.isDue(ZonedDateTime.now(), hasData, UtcText.parse(last), dismissed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun later() {
        viewModelScope.launch {
            store.set(SettingKeys.BackupReminderDismissed.name, BackupReminder.month(ZonedDateTime.now()))
        }
    }

    fun backUpNow() = coordinator.backUpNow()
}
