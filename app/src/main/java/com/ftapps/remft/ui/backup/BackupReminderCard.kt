package com.ftapps.remft.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.ui.notes.rememberHiltAvailable
import com.ftapps.remft.ui.components.MarkedCard
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun HomeBackupReminder() {
    if (rememberHiltAvailable()) BackupReminderCard(Modifier.padding(vertical = Space.x2))
}

@Composable
fun BackupReminderCard(modifier: Modifier = Modifier, viewModel: BackupReminderViewModel = hiltViewModel()) {
    val due by viewModel.due.collectAsStateWithLifecycle()
    if (due) BackupReminderCardContent(onLater = viewModel::later, onBackUpNow = viewModel::backUpNow, modifier = modifier)
}

@Composable
fun BackupReminderCardContent(onLater: () -> Unit, onBackUpNow: () -> Unit, modifier: Modifier = Modifier) {
    MarkedCard(
        modifier = modifier.fillMaxWidth().testTag("backup-reminder"),
        color = RemftTheme.tonal.level2,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(start = Space.x4, top = Space.x4, end = Space.x2, bottom = Space.x1)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Space.x2)) {
                Icon(Icons.Outlined.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Space.x4))
                Text(stringResource(R.string.backup_reminderBody), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x1, Alignment.End)) {
                RemftTextButton(
                    stringResource(R.string.backup_later),
                    onClick = onLater,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("backup-reminder-later"),
                )
                RemftTextButton(
                    stringResource(R.string.backup_backUpNow),
                    onClick = onBackUpNow,
                    modifier = Modifier.testTag("backup-reminder-now"),
                )
            }
        }
    }
}

@Preview(name = "Backup reminder light")
@Composable
private fun BackupReminderLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { BackupReminderCardContent(onLater = {}, onBackUpNow = {}) } }
}

@Preview(name = "Backup reminder dark")
@Composable
private fun BackupReminderDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { BackupReminderCardContent(onLater = {}, onBackUpNow = {}) } }
}
