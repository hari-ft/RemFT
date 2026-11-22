package com.ftapps.remft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmEventLog
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.db.entity.AlarmEventEntity
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.groupPosition
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
private fun alarmEventLabel(event: String): String = when (event) {
    AlarmLogEvent.SCHEDULED -> stringResource(R.string.alarmLog_event_scheduled)
    AlarmLogEvent.CANCELLED -> stringResource(R.string.alarmLog_event_cancelled)
    AlarmLogEvent.INTERACTED -> stringResource(R.string.alarmLog_event_interacted)
    AlarmLogEvent.ACTION -> stringResource(R.string.alarmLog_event_action)
    AlarmLogEvent.RING_SHOWN -> stringResource(R.string.alarmLog_event_ringShown)
    AlarmLogEvent.DEGRADED -> stringResource(R.string.alarmLog_event_degraded)
    else -> event
}

@Composable
fun AlarmLogScreen(onBack: () -> Unit) {
    val env = LocalAlarmEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val flow: Flow<List<AlarmEventEntity>?> = remember(env) { env?.log?.watch() ?: flowOf(emptyList()) }
    val rows by flow.collectAsStateWithLifecycle(null)
    val label = stringResource(R.string.alarmLog_title)
    val copied = stringResource(R.string.alarmLog_copied)
    AlarmSettingsScaffold(
        title = label,
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            IconButton(
                onClick = {
                    val text = rows.orEmpty().joinToString("\n") { AlarmEventLog.formatLine(it) }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
                    scope.launch { snackbar.showSnackbar(copied) }
                },
                enabled = !rows.isNullOrEmpty(),
                modifier = Modifier.testTag("alarm-log-copy"),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.alarmLog_copy))
            }
        },
    ) { padding ->
        if (env != null) AlarmLogContent(env, padding, rows)
    }
}

@Composable
private fun AlarmLogContent(env: AlarmEnvironment, padding: PaddingValues, rows: List<AlarmEventEntity>?) {
    val format = rememberAlarmDateFormat(env)
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            SettingsInfo(stringResource(R.string.alarmLog_scope))
            Spacer(Modifier.height(Space.x4))
        }
        when {
            rows == null -> Unit
            rows.isEmpty() -> item {
                EmptyState(
                    Icons.Outlined.HistoryToggleOff,
                    stringResource(R.string.alarmLog_emptyTitle),
                    stringResource(R.string.alarmLog_emptyBody),
                    Modifier.testTag("alarm-log-empty"),
                )
            }
            else -> itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                LogEntryRow(
                    position = groupPosition(index, rows.size),
                    title = "${alarmEventLabel(row.event)} · ${row.lane}",
                    stamp = alarmLogStamp(env, row.at, format),
                    details = AlarmEventLog.formatLine(row),
                    modifier = Modifier.testTag("alarm-log-row"),
                )
            }
        }
    }
}

@Preview(name = "Alarm log light")
@Composable
private fun AlarmLogLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { AlarmLogPreviewBody() } }
}

@Preview(name = "Alarm log dark")
@Composable
private fun AlarmLogDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { AlarmLogPreviewBody() } }
}

@Composable
private fun AlarmLogPreviewBody() {
    Column {
        SettingsInfo("Sample note about which alarms this log keeps.")
        Spacer(Modifier.height(Space.x4))
        LogEntryRow(GroupPosition.First, "Scheduled · urgent", "22 Nov 09:00", details = "task=sample-1 at=09:30")
        LogEntryRow(GroupPosition.Middle, "Ring shown · urgent", "22 Nov 09:30", details = "task=sample-1")
        LogEntryRow(GroupPosition.Last, "Cancelled · reminder", "22 Nov 09:31", details = "task=sample-2")
    }
}
