package com.ftapps.remft.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ftapps.remft.ui.theme.Space

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
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("backup-reminder"),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(start = Space.x4, top = Space.x3, end = Space.x2, bottom = Space.x1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Backup, contentDescription = null)
                Spacer(Modifier.width(Space.x3))
                Text(stringResource(R.string.backup_reminderBody), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.x2, Alignment.End)) {
                TextButton(onClick = onLater, modifier = Modifier.testTag("backup-reminder-later")) {
                    Text(stringResource(R.string.backup_later))
                }
                FilledTonalButton(onClick = onBackUpNow, modifier = Modifier.testTag("backup-reminder-now")) {
                    Text(stringResource(R.string.backup_backUpNow))
                }
            }
        }
    }
}
