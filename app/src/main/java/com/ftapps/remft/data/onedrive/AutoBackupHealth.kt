package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.domain.backup.auto.AutoBackupPause
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.auto.DestinationStatus
import com.ftapps.remft.domain.backup.auto.Retention
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId

enum class AutoBackupTrouble { Reconnect, Failing }

data class AutoBackupSnapshot(
    val on: Boolean,
    val connected: Boolean,
    val reconnect: Boolean,
    val account: String?,
    val schedule: BackupSchedule,
    val keepDays: Int,
    val lastOk: Instant?,
    val unavailable: Int,
    val status: DestinationStatus?,
    val pause: AutoBackupPause?,
)

object AutoBackupHealth {
    fun trouble(snapshot: AutoBackupSnapshot, now: Instant, zone: ZoneId = ZoneId.systemDefault()): AutoBackupTrouble? {
        if (!snapshot.on) return null
        val status = snapshot.status
        if (!snapshot.connected || snapshot.reconnect || status?.error in OneDriveErrors.reconnectCodes) {
            return AutoBackupTrouble.Reconnect
        }
        if (status == null || status.ok) return null
        val last = snapshot.lastOk ?: return AutoBackupTrouble.Failing
        return if (snapshot.schedule.isOverdue(now, last, zone)) AutoBackupTrouble.Failing else null
    }

    fun snapshots(store: SettingsStore, connected: Flow<Boolean?>): Flow<AutoBackupSnapshot> {
        val core = combine(
            store.flow(SettingKeys.OneDriveOn.name),
            store.flow(SettingKeys.OneDriveReconnect.name),
            store.flow(SettingKeys.OneDriveAccount.name),
            store.flow(SettingKeys.AutoBackupSchedule.name),
            store.flow(SettingKeys.AutoBackupKeepDays.name),
        ) { on, reconnect, account, schedule, keep -> listOf(on, reconnect, account, schedule, keep) }
        val outcome = combine(
            store.flow(SettingKeys.AutoBackupLastOk.name),
            store.flow(SettingKeys.AutoBackupUnavailable.name),
            store.flow(DestinationStatus.key(OneDriveConfig.DESTINATION_ID)),
            store.flow(SettingKeys.AutoBackupPaused.name),
        ) { last, unavailable, status, pause -> listOf(last, unavailable, status, pause) }
        return combine(core, outcome, connected) { c, o, isConnected ->
            AutoBackupSnapshot(
                on = c[0] == OneDriveAuth.TRUE,
                connected = isConnected != false,
                reconnect = c[1] == OneDriveAuth.TRUE,
                account = c[2]?.takeIf { it.isNotBlank() },
                schedule = BackupSchedule.parse(c[3]),
                keepDays = Retention.keepDays(c[4]),
                lastOk = UtcText.parse(o[0]),
                unavailable = o[1]?.toIntOrNull() ?: 0,
                status = DestinationStatus.parse(o[2]),
                pause = AutoBackupPause.parse(o[3]),
            )
        }
    }
}
