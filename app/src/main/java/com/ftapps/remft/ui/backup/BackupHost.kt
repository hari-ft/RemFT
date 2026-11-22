package com.ftapps.remft.ui.backup

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.backup.RestoreStage
import com.ftapps.remft.domain.backup.BackupFormat
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.onedrive.CloudBackupListDialog
import com.ftapps.remft.ui.onedrive.CloudDownloadingDialog
import com.ftapps.remft.ui.shell.BottomBarClearance
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal val restorePickerMimeTypes = arrayOf(BackupFormat.ZIP_MIME, "application/x-zip-compressed", "application/octet-stream")

@Composable
fun BackupHost(viewModel: BackupHostViewModel = hiltViewModel()) {
    val coordinator = viewModel.coordinator
    val state by coordinator.state.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        coordinator.onShareFinished()
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onPicked(uri)
    }
    LaunchedEffect(coordinator) {
        coordinator.events.collect { event ->
            when (event) {
                is BackupEvent.Message -> {
                    snackbars.currentSnackbarData?.dismiss()
                    scope.launch { snackbars.showSnackbar(event.text) }
                }
                is BackupEvent.Share -> {
                    BackupShareChoice.reset()
                    try {
                        shareLauncher.launch(backupShareChooser(context, event.uri))
                    } catch (_: ActivityNotFoundException) {
                        coordinator.onShareUnavailable()
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            snackbars,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = BottomBarClearance)
                .testTag("backup-snackbars"),
        )
    }
    RestoreFlow(
        restore = state.restore,
        dateFormat = dateFormat,
        onDismissSource = coordinator::dismissSource,
        onFile = {
            coordinator.dismissSource()
            pickLauncher.launch(restorePickerMimeTypes)
        },
        onConfirm = coordinator::confirmRestore,
        oneDriveConnected = state.oneDriveConnected,
        onOneDrive = coordinator::chooseOneDrive,
        onPickCloud = coordinator::pickCloud,
        onRetryCloud = coordinator::retryCloud,
        onDismissCloud = coordinator::dismissCloud,
    )
}

@Composable
fun RestoreFlow(
    restore: RestoreUiState,
    dateFormat: DateFormatId,
    onDismissSource: () -> Unit,
    onFile: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    oneDriveConnected: Boolean = false,
    onOneDrive: () -> Unit = {},
    onPickCloud: (StoredBackup) -> Unit = {},
    onRetryCloud: () -> Unit = {},
    onDismissCloud: () -> Unit = {},
) {
    when (restore) {
        RestoreUiState.Idle -> Unit
        RestoreUiState.ChoosingSource -> RestoreSourceSheet(
            oneDriveConnected = oneDriveConnected,
            onFile = onFile,
            onOneDrive = onOneDrive,
            onDismiss = onDismissSource,
        )
        is RestoreUiState.CloudList -> CloudBackupListDialog(restore, dateFormat, onPickCloud, onRetryCloud, onDismissCloud)
        is RestoreUiState.CloudDownloading -> CloudDownloadingDialog(restore.received, restore.total)
        RestoreUiState.Checking -> BlockingProgressDialog(stringResource(R.string.backup_checking), "restore-checking")
        is RestoreUiState.Confirm -> RestoreConfirmDialog(
            date = formatBackupDate(restore.createdAt, dateFormat),
            onCancel = { onConfirm(false) },
            onGo = { onConfirm(true) },
        )
        is RestoreUiState.Running -> BlockingProgressDialog(
            when (restore.stage) {
                RestoreStage.Tasks -> stringResource(R.string.backup_restoringTasks)
                RestoreStage.Files -> stringFilled(R.string.backup_restoringFiles, "done" to restore.done, "total" to restore.total)
                RestoreStage.Settings -> stringResource(R.string.backup_restoringSettings)
            },
            "restore-progress",
        )
    }
}

fun formatBackupDate(at: Instant, format: DateFormatId, zone: ZoneId = ZoneId.systemDefault()): String =
    DateFormats.dateTime(LocalDateTime.ofInstant(at, zone), format)

fun backupShareChooser(context: Context, uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND)
        .setType(BackupFormat.ZIP_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val chosen = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, BackupShareReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or mutable,
    )
    return Intent.createChooser(send, null, chosen.intentSender)
}
