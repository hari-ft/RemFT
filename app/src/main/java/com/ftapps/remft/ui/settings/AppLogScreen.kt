package com.ftapps.remft.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AppLog
import com.ftapps.remft.data.alarm.AppLogEntry
import com.ftapps.remft.data.alarm.AppLogLevel
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.groupPosition
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

private fun shareAppLog(context: Context, text: String, title: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
    true
} catch (_: Exception) {
    false
}

@Composable
fun AppLogScreen(onBack: () -> Unit, onOpenAlarmLog: () -> Unit) {
    val env = LocalAlarmEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var entries by remember { mutableStateOf<List<AppLogEntry>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var min by remember { mutableStateOf(AppLogLevel.INFO) }
    val shareTitle = stringResource(R.string.appLog_share)
    val shareFailed = stringResource(R.string.appLog_shareFailed)
    val cleared = stringResource(R.string.appLog_cleared)
    LaunchedEffect(env, reload) {
        if (env != null) entries = withContext(Dispatchers.IO) { runCatching { env.appLog.read() }.getOrDefault(emptyList()) }
    }
    AlarmSettingsScaffold(
        title = stringResource(R.string.appLog_title),
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            IconButton(
                onClick = {
                    val log = env?.appLog ?: return@IconButton
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { runCatching { log.exportText() }.getOrNull() }
                        val ok = text != null && shareAppLog(context, text, shareTitle)
                        if (!ok) snackbar.showSnackbar(shareFailed)
                    }
                },
                modifier = Modifier.testTag("app-log-share"),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = shareTitle)
            }
            IconButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("app-log-clear")) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.appLog_clear))
            }
        },
    ) { padding ->
        if (env != null) AppLogContent(env, padding, entries, min, { min = it }, onOpenAlarmLog)
    }
    if (confirmClear) {
        RemftDialog(
            modifier = Modifier.testTag("app-log-clear-confirm"),
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.appLog_clearTitle),
            text = { Text(stringResource(R.string.appLog_clearBody), style = MaterialTheme.typography.bodyMedium) },
            dismissButton = { RemftTextButton(stringResource(R.string.common_cancel), onClick = { confirmClear = false }) },
            confirmButton = {
                RemftTonalButton(
                    stringResource(R.string.appLog_clear),
                    modifier = Modifier.testTag("app-log-clear-go"),
                    onClick = {
                        confirmClear = false
                        val log = env?.appLog
                        if (log != null) {
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { log.clear() } }
                                reload++
                                snackbar.showSnackbar(cleared)
                            }
                        }
                    },
                )
            },
        )
    }
}

@Composable
private fun AppLogContent(
    env: AlarmEnvironment,
    padding: PaddingValues,
    entries: List<AppLogEntry>?,
    min: AppLogLevel,
    onFilter: (AppLogLevel) -> Unit,
    onOpenAlarmLog: () -> Unit,
) {
    val format = rememberAlarmDateFormat(env)
    val rows = entries?.let { AppLog.filter(it, min) }
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item { AppLogHeader(min, onFilter, onOpenAlarmLog) }
        when {
            rows == null -> Unit
            rows.isEmpty() -> item {
                EmptyState(
                    Icons.AutoMirrored.Outlined.ReceiptLong,
                    stringResource(R.string.appLog_emptyTitle),
                    stringResource(R.string.appLog_emptyBody),
                    Modifier.testTag("app-log-empty"),
                )
            }
            else -> itemsIndexed(rows) { index, entry ->
                AppLogRow(entry, groupPosition(index, rows.size), DateFormats.short(LocalDateTime.ofInstant(entry.at, env.clock.zone), format))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLogHeader(min: AppLogLevel, onFilter: (AppLogLevel) -> Unit, onOpenAlarmLog: () -> Unit) {
    Column {
        SettingsInfo(stringResource(R.string.appLog_scope))
        Spacer(Modifier.height(Space.x3))
        SettingsCard {
            SettingsRow(Icons.Outlined.History, stringResource(R.string.appLog_alarmLog), null, "app-log-alarm-log", onClick = onOpenAlarmLog)
        }
        SettingsSectionHeader(stringResource(R.string.setx_logEntries))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            listOf(
                Triple(AppLogLevel.INFO, R.string.appLog_filter_all, "all"),
                Triple(AppLogLevel.WARN, R.string.appLog_filter_warn, "warn"),
                Triple(AppLogLevel.ERROR, R.string.appLog_filter_error, "error"),
            ).forEach { (level, label, tag) ->
                SettingsFilterChip(
                    selected = min == level,
                    onClick = { onFilter(level) },
                    label = stringResource(label),
                    modifier = Modifier.testTag("app-log-filter-$tag"),
                )
            }
        }
        Spacer(Modifier.height(Space.x2))
    }
}

@Composable
private fun AppLogRow(entry: AppLogEntry, position: GroupPosition, stamp: String) {
    val details = buildList {
        entry.fields.forEach { (key, value) -> add("$key=$value") }
        entry.errorType?.let { add("error=$it") }
    }.joinToString(" ")
    LogEntryRow(
        position = position,
        title = entry.event,
        stamp = stamp,
        details = details.ifEmpty { null },
        icon = when (entry.level) {
            AppLogLevel.INFO -> Icons.Outlined.Info
            AppLogLevel.WARN -> Icons.Outlined.WarningAmber
            AppLogLevel.ERROR -> Icons.Outlined.ErrorOutline
        },
        tone = when (entry.level) {
            AppLogLevel.INFO -> SettingsTone.Neutral
            AppLogLevel.WARN -> SettingsTone.Warning
            AppLogLevel.ERROR -> SettingsTone.Danger
        },
        modifier = Modifier.testTag("app-log-row-${entry.level.name.lowercase()}"),
    )
}

@Preview(name = "App log light")
@Composable
private fun AppLogLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { AppLogPreviewBody() } }
}

@Preview(name = "App log dark")
@Composable
private fun AppLogDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { AppLogPreviewBody() } }
}

@Composable
private fun AppLogPreviewBody() {
    Column {
        AppLogHeader(AppLogLevel.INFO, onFilter = {}, onOpenAlarmLog = {})
        LogEntryRow(GroupPosition.First, "backup.started", "22 Nov 02:00", icon = Icons.Outlined.Info)
        LogEntryRow(GroupPosition.Middle, "onedrive.slow", "22 Nov 02:01", details = "ms=8400", icon = Icons.Outlined.WarningAmber, tone = SettingsTone.Warning)
        LogEntryRow(GroupPosition.Last, "backup.failed", "22 Nov 02:02", details = "error=Offline", icon = Icons.Outlined.ErrorOutline, tone = SettingsTone.Danger)
    }
}
