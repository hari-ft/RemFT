package com.ftapps.remft.ui.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.onedrive.AutoBackupText
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.settings.SettingsRow
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Space

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    onOpenAutoBackup: () -> Unit = {},
    viewModel: BackupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
    BackupSettingsContent(
        state = state,
        busy = busy,
        onBack = onBack,
        onBackUpNow = viewModel::backUpNow,
        onRestore = viewModel::restore,
        autoBackupSummary = AutoBackupText.summary(LocalResources.current, autoBackup, state.dateFormat),
        onOpenAutoBackup = onOpenAutoBackup,
    )
}

@Composable
fun BackupSettingsContent(
    state: BackupSettingsState,
    busy: Boolean,
    onBack: () -> Unit,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    autoBackupSummary: String? = null,
    onOpenAutoBackup: (() -> Unit)? = null,
) {
    SectionScaffold(title = stringResource(R.string.settings_group_backup), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            item {
                SettingsCard {
                    SettingsRow(
                        Icons.Outlined.Backup,
                        stringResource(R.string.backup_backUpNow),
                        stringResource(R.string.backup_backUpSub),
                        "backup-now",
                        enabled = !busy,
                        trailing = null,
                        onClick = onBackUpNow,
                    )
                    SettingsRow(
                        Icons.Outlined.CloudSync,
                        stringResource(R.string.autoBackup_title),
                        autoBackupSummary ?: stringResource(R.string.autoBackup_off),
                        "settings-auto-backup",
                        enabled = onOpenAutoBackup != null,
                        onClick = onOpenAutoBackup,
                    )
                    SettingsRow(
                        Icons.Outlined.SettingsBackupRestore,
                        stringResource(R.string.backup_restore),
                        stringResource(R.string.backup_restoreSub),
                        "settings-restore",
                        enabled = !busy,
                        trailing = null,
                        onClick = onRestore,
                    )
                }
            }
            item {
                val last = state.lastBackup
                Text(
                    if (last == null) {
                        stringResource(R.string.backup_noBackupYet)
                    } else {
                        stringFilled(R.string.backup_lastBackupAt, "date" to formatBackupDate(last, state.dateFormat))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x3).testTag("backup-last"),
                )
            }
        }
    }
}
