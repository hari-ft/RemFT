package com.ftapps.remft.ui.onedrive

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ftapps.remft.R
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.backup.ProgressLine
import com.ftapps.remft.ui.backup.RestoreUiState
import com.ftapps.remft.ui.backup.formatBackupDate
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.settings.SettingsChevron
import com.ftapps.remft.ui.settings.SettingsItem
import com.ftapps.remft.ui.settings.SettingsTone
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition

@Composable
fun CloudBackupListDialog(
    state: RestoreUiState.CloudList,
    dateFormat: DateFormatId,
    onPick: (StoredBackup) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    RemftDialog(
        modifier = Modifier.testTag("restore-onedrive-list"),
        onDismissRequest = onDismiss,
        title = stringResource(R.string.backup_oneDriveBackups),
        text = {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(Space.x5), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.failed -> Column {
                    SettingsItem(
                        title = stringResource(R.string.backup_oneDriveListFailed),
                        icon = Icons.Outlined.CloudOff,
                        tone = SettingsTone.Warning,
                    )
                    RemftTextButton(stringResource(R.string.common_retry), onClick = onRetry, modifier = Modifier.testTag("restore-onedrive-retry"))
                }
                state.backups.isEmpty() -> Text(stringResource(R.string.backup_oneDriveNone), style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(state.backups, key = { _, backup -> backup.id }) { index, backup ->
                        val at = BackupNames.parseAutomatic(backup.name)
                        CloudBackupRow(
                            position = groupPosition(index, state.backups.size),
                            title = if (at == null) backup.name else formatBackupDate(at, dateFormat),
                            size = backup.size?.let { Formatter.formatShortFileSize(context, it) },
                            modifier = Modifier.testTag("restore-onedrive-item-${backup.id}").clickable { onPick(backup) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss) },
    )
}

@Composable
private fun CloudBackupRow(position: GroupPosition, title: String, size: String?, modifier: Modifier = Modifier) {
    GroupedCardItem(position, color = RemftTheme.tonal.level1) {
        SettingsItem(
            title = title,
            modifier = modifier,
            icon = Icons.Outlined.Archive,
            subtitle = size,
            trailing = { SettingsChevron() },
        )
    }
}

@Composable
fun CloudDownloadingDialog(received: Long, total: Long?) {
    AlertDialog(
        modifier = Modifier.testTag("restore-onedrive-downloading"),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        shape = MarkedShapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = {
            val text = stringResource(R.string.backup_oneDriveDownloading)
            if (total == null || total <= 0) {
                ProgressLine(text)
            } else {
                ProgressLine(text) { (received.toFloat() / total).coerceIn(0f, 1f) }
            }
        },
    )
}

@Preview(name = "OneDrive backups light")
@Composable
private fun CloudBackupsLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { CloudBackupsPreview() } }
}

@Preview(name = "OneDrive backups dark")
@Composable
private fun CloudBackupsDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { CloudBackupsPreview() } }
}

@Composable
private fun CloudBackupsPreview() {
    Column {
        CloudBackupRow(GroupPosition.First, "21-11-2026 02:00", "1.2 MB")
        CloudBackupRow(GroupPosition.Last, "20-11-2026 02:00", "1.1 MB")
    }
}
