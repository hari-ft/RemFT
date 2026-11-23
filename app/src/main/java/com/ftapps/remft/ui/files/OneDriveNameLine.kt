package com.ftapps.remft.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ftapps.remft.R
import com.ftapps.remft.data.files.OneDriveName
import com.ftapps.remft.data.files.OneDriveNames
import com.ftapps.remft.domain.text.fill
import kotlinx.coroutines.flow.flowOf

@Composable
fun OneDriveNameLine(
    localPath: String?,
    env: FilesEnvironment,
    modifier: Modifier = Modifier,
    label: String? = null,
    tag: String = "file-onedrive-name",
    itemTag: String = "file-onedrive-item",
) {
    val names = env.oneDriveNames
    val state by remember(names, localPath) { names?.watch(localPath) ?: flowOf(null) }.collectAsState(null)
    val format = rememberDateFormat(env)
    val current = state ?: return
    val described = when (current) {
        OneDriveName.Off -> stringResource(R.string.onedrivename_off)
        OneDriveName.NotYet -> stringResource(R.string.onedrivename_notYet)
        is OneDriveName.Saved -> stringResource(R.string.onedrivename_saved).fill("path" to OneDriveNames.display(current.path))
        is OneDriveName.Updating -> stringResource(R.string.uigap_oneDriveUpdating).fill("path" to OneDriveNames.display(current.path))
        is OneDriveName.Missing -> {
            val missingText = if (current.expected) R.string.mirrorsync_missingExpected else R.string.onedrivename_missing
            val where = current.path?.let { stringResource(missingText).fill("path" to OneDriveNames.display(it)) }
                ?: stringResource(R.string.onedrivename_missingNoPath)
            fileDate(current.lastSeen, format, env.clock.zone)?.let { stringResource(R.string.onedrivename_lastSeen).fill("text" to where, "date" to it) } ?: where
        }
    }
    val text = label?.let { stringResource(R.string.uigap_binFileLine).fill("name" to it, "text" to described) } ?: described
    val itemId = (current as? OneDriveName.Missing)?.itemId
    SelectionContainer(Modifier.testTag("onedrive-selectable")) {
        Column(modifier) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (current is OneDriveName.Missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(tag),
            )
            if (itemId != null) {
                Text(
                    stringResource(R.string.onedrivename_itemId).fill("id" to itemId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(itemTag),
                )
            }
        }
    }
}
