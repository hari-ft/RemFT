package com.ftapps.remft.ui.onedrive

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ftapps.remft.R
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.backup.RestoreUiState
import com.ftapps.remft.ui.backup.formatBackupDate
import com.ftapps.remft.ui.theme.Space

@Composable
fun CloudBackupListDialog(
    state: RestoreUiState.CloudList,
    dateFormat: DateFormatId,
    onPick: (StoredBackup) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.testTag("restore-onedrive-list"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_oneDriveBackups)) },
        text = {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(Space.x5), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.failed -> Column {
                    Text(stringResource(R.string.backup_oneDriveListFailed))
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("restore-onedrive-retry")) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
                state.backups.isEmpty() -> Text(stringResource(R.string.backup_oneDriveNone))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(state.backups, key = { it.id }) { backup ->
                        val at = BackupNames.parseAutomatic(backup.name)
                        ListItem(
                            modifier = Modifier.testTag("restore-onedrive-item-${backup.id}").clickable { onPick(backup) },
                            leadingContent = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                            headlineContent = { Text(if (at == null) backup.name else formatBackupDate(at, dateFormat)) },
                            supportingContent = backup.size?.let { size -> { Text(Formatter.formatShortFileSize(context, size)) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun CloudDownloadingDialog(received: Long, total: Long?) {
    AlertDialog(
        modifier = Modifier.testTag("restore-onedrive-downloading"),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (total == null || total <= 0) {
                    CircularProgressIndicator()
                } else {
                    CircularProgressIndicator(progress = { (received.toFloat() / total).coerceIn(0f, 1f) })
                }
                Spacer(Modifier.width(Space.x4))
                Text(stringResource(R.string.backup_oneDriveDownloading))
            }
        },
    )
}
