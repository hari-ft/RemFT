package com.ftapps.remft.ui.onedrive

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.MirrorCounts
import com.ftapps.remft.data.mirror.MirrorDownloadState
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.onedrive.AutoBackupHealth
import com.ftapps.remft.data.onedrive.AutoBackupOutcome
import com.ftapps.remft.data.onedrive.AutoBackupSnapshot
import com.ftapps.remft.data.onedrive.AutoBackupTurn
import com.ftapps.remft.data.onedrive.GraphClient
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.onedrive.OneDriveConfig
import com.ftapps.remft.data.onedrive.OneDriveErrors
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.data.onedrive.OneDriveSignIn
import com.ftapps.remft.data.onedrive.OneDriveTriggers
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.auto.DestinationStatus
import com.ftapps.remft.domain.backup.auto.PauseRule
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AutoBackupMessage {
    data class Outcome(val outcome: AutoBackupOutcome) : AutoBackupMessage
    data class Error(val code: String) : AutoBackupMessage
    data object Working : AutoBackupMessage
    data object Disconnected : AutoBackupMessage
}

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val store: SettingsStore,
    private val auth: OneDriveAuth,
    private val graph: GraphClient,
    private val signIn: OneDriveSignIn,
    private val turn: AutoBackupTurn,
    private val mirror: FileMirror,
    private val downloads: MirrorDownloads,
    private val triggers: OneDriveTriggers,
) : ViewModel() {
    val snapshot: StateFlow<AutoBackupSnapshot?> = AutoBackupHealth.snapshots(store, auth.connected)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dateFormat: StateFlow<DateFormatId> = store.flow(SettingKeys.DateFormat)
        .map { DateFormatId.parse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DateFormatId.System)

    val downloadState: StateFlow<MirrorDownloadState> = downloads.state

    private val mutableCounts = MutableStateFlow<MirrorCounts?>(null)
    val counts: StateFlow<MirrorCounts?> = mutableCounts.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val messageChannel = Channel<AutoBackupMessage>(Channel.BUFFERED)
    val messages: Flow<AutoBackupMessage> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch { runCatching { auth.isConnected() } }
        refreshCounts()
        viewModelScope.launch { turn.outcomes.collect { refreshCounts() } }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            mutableCounts.value = runCatching { mirror.counts() }.getOrNull()
        }
    }

    fun signInIntent(): Intent? = try {
        signIn.intent(snapshot.value?.account)
    } catch (e: OneDriveException) {
        messageChannel.trySend(AutoBackupMessage.Error(e.code))
        null
    } catch (e: Exception) {
        AndroidBackupLog.warn("autobackup.onedrive_sign_in_failed", e)
        messageChannel.trySend(AutoBackupMessage.Error(OneDriveErrors.SIGN_IN_FAILED))
        null
    }

    fun onSignInResult(data: Intent?) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val grant = signIn.complete(data) ?: return@launch
                auth.connect(grant)
                store.set(SettingKeys.OneDriveOn.name, OneDriveAuth.TRUE)
                store.remove(DestinationStatus.key(OneDriveConfig.DESTINATION_ID))
                try {
                    graph.accountName()?.let { store.set(SettingKeys.OneDriveAccount.name, it) }
                } catch (e: OneDriveException) {
                    AndroidBackupLog.warn("autobackup.onedrive_account_failed", fields = mapOf("code" to e.code))
                }
                downloads.startIfPending()
                triggers.afterSettingsChanged()
                triggers.mirrorSoon()
            } catch (e: OneDriveException) {
                if (e.code != OneDriveErrors.CANCELLED) messageChannel.trySend(AutoBackupMessage.Error(e.code))
            } catch (e: Exception) {
                AndroidBackupLog.error("autobackup.onedrive_connect_failed", e)
                messageChannel.trySend(AutoBackupMessage.Error(OneDriveErrors.UNKNOWN))
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun setOn(on: Boolean) {
        viewModelScope.launch {
            store.set(SettingKeys.OneDriveOn.name, on.toString())
            triggers.afterSettingsChanged()
        }
    }

    fun disconnect() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                try {
                    auth.disconnect()
                } catch (e: Exception) {
                    AndroidBackupLog.warn("autobackup.onedrive_disconnect_failed", e)
                }
                store.remove(SettingKeys.OneDriveOn.name)
                store.remove(SettingKeys.OneDriveAccount.name)
                store.remove(DestinationStatus.key(OneDriveConfig.DESTINATION_ID))
                triggers.afterSettingsChanged()
                messageChannel.trySend(AutoBackupMessage.Disconnected)
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun setSchedule(schedule: BackupSchedule) {
        viewModelScope.launch {
            store.set(SettingKeys.AutoBackupSchedule.name, schedule.encode())
            triggers.afterSettingsChanged()
        }
    }

    fun setKeepDays(days: Int) {
        viewModelScope.launch { store.set(SettingKeys.AutoBackupKeepDays.name, days.toString()) }
    }

    fun backUpAnyway() {
        viewModelScope.launch {
            store.set(SettingKeys.AutoBackupDropAccepted.name, PauseRule.ACCEPTED_VALUE)
            backUpNowInner()
        }
    }

    fun backUpNow() {
        viewModelScope.launch { backUpNowInner() }
    }

    private suspend fun backUpNowInner() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        messageChannel.trySend(AutoBackupMessage.Working)
        try {
            val outcome = turn.run(force = true)
            messageChannel.trySend(AutoBackupMessage.Outcome(outcome))
        } catch (e: Exception) {
            AndroidBackupLog.error("autobackup.run_failed", e)
            messageChannel.trySend(AutoBackupMessage.Outcome(AutoBackupOutcome.Failed))
        } finally {
            mutableBusy.value = false
            refreshCounts()
        }
    }

    fun retryDownloads() {
        downloads.start()
    }
}
