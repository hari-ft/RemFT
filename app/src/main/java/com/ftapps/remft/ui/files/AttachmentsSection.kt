package com.ftapps.remft.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun rememberDateFormat(env: FilesEnvironment): DateFormatId {
    val format by remember(env) { env.settings.flow(SettingKeys.DateFormat).map { DateFormatId.parse(it) } }.collectAsState(DateFormatId.System)
    return format
}

@Composable
fun AttachmentsSection(
    targetType: String,
    targetId: String,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    launcher: AttachLauncher? = null,
) {
    val env = LocalFilesEnvironment.current ?: return
    val scope = rememberCoroutineScope()
    val ui = rememberFileUiState()
    val format = rememberDateFormat(env)
    val rows by remember(targetType, targetId) { env.files.watchTarget(targetType, targetId) }.collectAsState(emptyList<FileRowEntity>())
    val jobs by env.uploads.jobs.collectAsState()
    val mine = jobs.filter { it.target.targetType == targetType && it.target.targetId == targetId }
    val pickFailed = stringResource(R.string.file_pickFailed)
    Column(modifier.fillMaxWidth().testTag("attachments-$targetType")) {
        val total = mine.size + rows.size
        mine.forEachIndexed { index, job ->
            UploadRowCard(job, env, position = groupPosition(index, total)) { scope.launch { env.uploads.retry(job.localId) } }
        }
        rows.forEachIndexed { index, row ->
            FileRowCard(
                position = groupPosition(mine.size + index, total),
                row = row,
                env = env,
                format = format,
                onOpen = { ui.open(row, rows) },
                onMore = { ui.dialog = FileDialog.Actions(row) },
                onDelete = { ui.dialog = FileDialog.Delete(row) },
            )
        }
        Spacer(Modifier.height(Space.x2))
        AttachButton(
            onPicked = { picks ->
                scope.launch {
                    val target = UploadTarget(env.files.workspace(), targetType, targetId)
                    env.uploads.uploadAll(target, picks)
                }
            },
            onFailed = { onMessage(pickFailed) },
            tag = "attach-button",
            hoisted = launcher,
        )
    }
    FileDialogsHost(ui, env, folders = emptyList(), onMessage = onMessage)
}
