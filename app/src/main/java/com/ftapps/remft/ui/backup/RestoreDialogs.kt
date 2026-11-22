package com.ftapps.remft.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.settings.SettingsChevron
import com.ftapps.remft.ui.settings.SettingsItem
import com.ftapps.remft.ui.settings.SettingsTone
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreSourceSheet(
    oneDriveConnected: Boolean,
    onFile: () -> Unit,
    onOneDrive: () -> Unit,
    onDismiss: () -> Unit,
) {
    RemftBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("restore-source-sheet"),
    ) {
        RestoreSourceOptions(oneDriveConnected, onFile, onOneDrive)
    }
}

@Composable
fun RestoreSourceOptions(oneDriveConnected: Boolean, onFile: () -> Unit, onOneDrive: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x4)) {
        Text(
            stringResource(R.string.backup_restoreFrom),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x1, bottom = Space.x3),
        )
        SettingsCard {
            SettingsItem(
                title = stringResource(R.string.backup_restoreFromFile),
                modifier = Modifier.testTag("restore-source-file").clickable(onClick = onFile),
                icon = Icons.Outlined.PhoneAndroid,
                subtitle = stringResource(R.string.backup_restoreFromFileSub),
                trailing = { SettingsChevron() },
            )
            val oneDriveClick = if (oneDriveConnected) Modifier.clickable(onClick = onOneDrive) else Modifier
            SettingsItem(
                title = stringResource(R.string.backup_restoreFromOneDrive),
                modifier = Modifier.testTag("restore-source-onedrive").then(oneDriveClick).alpha(if (oneDriveConnected) 1f else 0.5f),
                icon = Icons.Outlined.CloudDownload,
                tone = if (oneDriveConnected) SettingsTone.Brand else SettingsTone.Neutral,
                subtitle = stringResource(if (oneDriveConnected) R.string.backup_restoreFromOneDriveSub else R.string.backup_restoreFromOneDriveOff),
                trailing = if (oneDriveConnected) {
                    { SettingsChevron() }
                } else {
                    null
                },
            )
        }
        Spacer(Modifier.height(Space.x6))
    }
}

@Composable
fun RestoreConfirmDialog(date: String, onCancel: () -> Unit, onGo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        modifier = Modifier.testTag("restore-confirm"),
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_restoreTitle), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringFilled(R.string.backup_restoreBody, "date" to date), style = MaterialTheme.typography.bodyMedium) },
        dismissButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = onCancel) },
        confirmButton = {
            Button(
                onClick = onGo,
                shape = MarkedShapes.pill,
                modifier = Modifier.heightIn(min = Sizes.touchTarget).testTag("restore-confirm-go"),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
            ) { Text(stringResource(R.string.backup_restoreGo), style = MaterialTheme.typography.labelLarge) }
        },
        shape = MarkedShapes.large,
        containerColor = scheme.surfaceContainerLow,
        iconContentColor = scheme.error,
    )
}

@Composable
fun BlockingProgressDialog(text: String, tag: String) {
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        shape = MarkedShapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        text = { ProgressLine(text) },
    )
}

@Composable
fun ProgressLine(text: String, progress: (() -> Float)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress == null) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
            CircularProgressIndicator(progress = progress, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(Space.x4))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(name = "Restore source light")
@Composable
private fun RestoreSourceLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { RestoreSourceOptions(oneDriveConnected = true, onFile = {}, onOneDrive = {}) } }
}

@Preview(name = "Restore source dark")
@Composable
private fun RestoreSourceDarkPreview() {
    RemftTheme(darkTheme = true) {
        PreviewSurface {
            Column {
                RestoreSourceOptions(oneDriveConnected = false, onFile = {}, onOneDrive = {})
                ProgressLine("Restoring tasks and notes…")
            }
        }
    }
}
