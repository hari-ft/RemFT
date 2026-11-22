package com.ftapps.remft.ui.onedrive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.R
import com.ftapps.remft.ui.notes.rememberHiltAvailable
import com.ftapps.remft.data.mirror.MirrorDownloadPhase
import com.ftapps.remft.data.mirror.MirrorDownloadState
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.onedrive.AutoBackupHealth
import com.ftapps.remft.data.onedrive.AutoBackupTrouble
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.ui.theme.Space
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

data class OneDriveHealthUi(val paused: Boolean = false, val trouble: AutoBackupTrouble? = null)

@HiltViewModel
class OneDriveHomeViewModel @Inject constructor(
    store: SettingsStore,
    auth: OneDriveAuth,
    private val downloads: MirrorDownloads,
) : ViewModel() {
    val health: StateFlow<OneDriveHealthUi> = AutoBackupHealth.snapshots(store, auth.connected)
        .map { s ->
            val paused = s.on && s.pause != null
            OneDriveHealthUi(paused, if (paused) null else AutoBackupHealth.trouble(s, Instant.now()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OneDriveHealthUi())

    val downloadState: StateFlow<MirrorDownloadState> = downloads.state

    fun retryDownloads() {
        downloads.start()
    }
}

@Composable
fun BackupPausedCard(onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: OneDriveHomeViewModel = hiltViewModel()) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    if (health.paused) BackupPausedCardContent(onOpen, modifier)
}

@Composable
fun BackupPausedCardContent(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("backup-paused-card"),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Column(Modifier.padding(start = Space.x4, top = Space.x3, end = Space.x2, bottom = Space.x1)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.PauseCircleOutline, contentDescription = null)
                Spacer(Modifier.width(Space.x3))
                Text(stringResource(R.string.home_backupPausedBody), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpen, modifier = Modifier.testTag("backup-paused-open")) {
                    Text(stringResource(R.string.home_backupPausedOpen), color = scheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
fun BackupTroubleCard(onFix: () -> Unit, modifier: Modifier = Modifier, viewModel: OneDriveHomeViewModel = hiltViewModel()) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val trouble = health.trouble
    if (!health.paused && trouble != null) BackupTroubleCardContent(trouble, onFix, modifier)
}

@Composable
fun BackupTroubleCardContent(trouble: AutoBackupTrouble, onFix: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onFix).testTag("backup-trouble-banner"),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Row(Modifier.padding(horizontal = Space.x3, vertical = Space.x3), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.x2))
            Text(
                stringResource(
                    when (trouble) {
                        AutoBackupTrouble.Reconnect -> R.string.home_backupTroubleReconnect
                        AutoBackupTrouble.Failing -> R.string.home_backupTroubleFailing
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.x2))
            Text(
                stringResource(R.string.home_backupTroubleOpen),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun MirrorDownloadsCard(onConnect: () -> Unit, modifier: Modifier = Modifier, viewModel: OneDriveHomeViewModel = hiltViewModel()) {
    val state by viewModel.downloadState.collectAsStateWithLifecycle()
    MirrorDownloadsCardContent(state, onConnect, viewModel::retryDownloads, modifier)
}

@Composable
fun MirrorDownloadsCardContent(state: MirrorDownloadState, onConnect: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val line = AutoBackupText.downloadLine(LocalResources.current, state) ?: return
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth().testTag("mirror-download-card"),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(start = Space.x4, top = Space.x3, end = Space.x2, bottom = Space.x1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(Space.x3))
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (state.phase == MirrorDownloadPhase.Downloading) {
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth().padding(top = Space.x2, bottom = Space.x2, end = Space.x2),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (state.phase) {
                    MirrorDownloadPhase.NeedsOneDrive -> FilledTonalButton(
                        onClick = onConnect,
                        modifier = Modifier.testTag("mirror-download-connect"),
                    ) { Text(stringResource(R.string.mirror_connect)) }
                    MirrorDownloadPhase.Waiting -> TextButton(onClick = onRetry, modifier = Modifier.testTag("mirror-download-retry")) {
                        Text(stringResource(R.string.mirror_retry))
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun HomeOneDriveCards(onOpenAutoBackup: () -> Unit) {
    if (!rememberHiltAvailable()) return
    Column {
        BackupPausedCard(onOpen = onOpenAutoBackup, modifier = Modifier.padding(vertical = Space.x2))
        BackupTroubleCard(onFix = onOpenAutoBackup, modifier = Modifier.padding(vertical = Space.x2))
        MirrorDownloadsCard(onConnect = onOpenAutoBackup, modifier = Modifier.padding(vertical = Space.x2))
    }
}
