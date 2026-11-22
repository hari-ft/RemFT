package com.ftapps.remft.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreSourceSheet(
    oneDriveConnected: Boolean,
    onFile: () -> Unit,
    onOneDrive: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = Breakpoints.sheetMaxWidth).testTag("restore-source-sheet"),
    ) {
        RestoreSourceOptions(oneDriveConnected, onFile, onOneDrive)
    }
}

@Composable
fun RestoreSourceOptions(oneDriveConnected: Boolean, onFile: () -> Unit, onOneDrive: () -> Unit) {
    Column(Modifier.navigationBarsPadding()) {
        Text(
            stringResource(R.string.backup_restoreFrom),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x1, bottom = Space.x2),
        )
        ListItem(
            modifier = Modifier.testTag("restore-source-file").clickable(onClick = onFile),
            leadingContent = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.backup_restoreFromFile)) },
            supportingContent = { Text(stringResource(R.string.backup_restoreFromFileSub)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        val oneDriveClick = if (oneDriveConnected) Modifier.clickable(onClick = onOneDrive) else Modifier
        ListItem(
            modifier = Modifier.testTag("restore-source-onedrive").then(oneDriveClick).alpha(if (oneDriveConnected) 1f else 0.5f),
            leadingContent = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.backup_restoreFromOneDrive)) },
            supportingContent = {
                Text(stringResource(if (oneDriveConnected) R.string.backup_restoreFromOneDriveSub else R.string.backup_restoreFromOneDriveOff))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Spacer(Modifier.height(Space.x4))
    }
}

@Composable
fun RestoreConfirmDialog(date: String, onCancel: () -> Unit, onGo: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("restore-confirm"),
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_restoreTitle)) },
        text = { Text(stringFilled(R.string.backup_restoreBody, "date" to date)) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            Button(
                onClick = onGo,
                modifier = Modifier.testTag("restore-confirm-go"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.backup_restoreGo)) }
        },
    )
}

@Composable
fun BlockingProgressDialog(text: String, tag: String) {
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(Space.x4))
                Text(text)
            }
        },
    )
}
