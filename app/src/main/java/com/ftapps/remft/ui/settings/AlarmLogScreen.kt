package com.ftapps.remft.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmEventLog
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.db.entity.AlarmEventEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.theme.Breakpoints
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
    val scheme = MaterialTheme.colorScheme
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            SettingsCard {
                Row(Modifier.padding(Space.x4)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.x3))
                    Text(stringResource(R.string.alarmLog_scope), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Space.x3))
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
            else -> items(rows, key = { it.id }) { row ->
                AlarmLogRow(row, env, format)
            }
        }
    }
}

@Composable
private fun AlarmLogRow(row: AlarmEventEntity, env: AlarmEnvironment, format: DateFormatId) {
    val scheme = MaterialTheme.colorScheme
    LogEntryCard {
        ListItem(
            modifier = Modifier.fillMaxWidth().testTag("alarm-log-row"),
            headlineContent = {
                Text("${alarmEventLabel(row.event)} · ${row.lane}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            },
            supportingContent = {
                Text(AlarmEventLog.formatLine(row), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            },
            trailingContent = {
                Text(alarmLogStamp(env, row.at, format), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun LogEntryCard(content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = Space.x2)) {
        SettingsCard(content)
    }
}
