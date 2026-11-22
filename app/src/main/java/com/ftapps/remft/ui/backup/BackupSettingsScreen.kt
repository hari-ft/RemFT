package com.ftapps.remft.ui.backup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.components.AppBackground
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.onedrive.AutoBackupText
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.settings.SettingsItem
import com.ftapps.remft.ui.settings.SettingsRow
import com.ftapps.remft.ui.settings.SettingsSectionHeader
import com.ftapps.remft.ui.settings.SettingsTone
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftTheme
import java.time.Instant

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
        BackupSettingsBody(padding, state, busy, onBackUpNow, onRestore, autoBackupSummary, onOpenAutoBackup)
    }
}

@Composable
private fun BackupSettingsBody(
    padding: PaddingValues,
    state: BackupSettingsState,
    busy: Boolean,
    onBackUpNow: () -> Unit,
    onRestore: () -> Unit,
    autoBackupSummary: String?,
    onOpenAutoBackup: (() -> Unit)?,
) {
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            val last = state.lastBackup
            SettingsCard {
                SettingsItem(
                    title = if (last == null) {
                        stringResource(R.string.backup_noBackupYet)
                    } else {
                        stringFilled(R.string.backup_lastBackupAt, "date" to formatBackupDate(last, state.dateFormat))
                    },
                    modifier = Modifier.testTag("backup-last"),
                    icon = if (last == null) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                    tone = if (last == null) SettingsTone.Warning else SettingsTone.Success,
                )
            }
        }
        item { SettingsSectionHeader(stringResource(R.string.setx_sectionBackUpRestore)) }
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
        item { SettingsSectionHeader(stringResource(R.string.setx_sectionCloud)) }
        item {
            SettingsCard {
                SettingsRow(
                    Icons.Outlined.CloudSync,
                    stringResource(R.string.autoBackup_title),
                    autoBackupSummary ?: stringResource(R.string.autoBackup_off),
                    "settings-auto-backup",
                    enabled = onOpenAutoBackup != null,
                    onClick = onOpenAutoBackup,
                )
            }
        }
    }
}

@Preview(name = "Backup light")
@Composable
private fun BackupLightPreview() {
    RemftTheme(darkTheme = false) {
        AppBackground { BackupSettingsBody(PaddingValues(), BackupSettingsState(lastBackup = null), false, {}, {}, null, {}) }
    }
}

@Preview(name = "Backup dark")
@Composable
private fun BackupDarkPreview() {
    RemftTheme(darkTheme = true) {
        AppBackground {
            BackupSettingsBody(PaddingValues(), BackupSettingsState(lastBackup = Instant.parse("2026-11-21T02:00:00Z"), dateFormat = DateFormatId.Iso), false, {}, {}, null, {})
        }
    }
}
