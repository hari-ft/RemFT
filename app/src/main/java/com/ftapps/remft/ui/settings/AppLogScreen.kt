package com.ftapps.remft.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AppLog
import com.ftapps.remft.data.alarm.AppLogEntry
import com.ftapps.remft.data.alarm.AppLogLevel
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.EmptyState
import com.ftapps.remft.ui.theme.Breakpoints
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
        AlertDialog(
            modifier = Modifier.testTag("app-log-clear-confirm"),
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.appLog_clearTitle)) },
            text = { Text(stringResource(R.string.appLog_clearBody)) },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
            confirmButton = {
                FilledTonalButton(
                    modifier = Modifier.testTag("app-log-clear-go"),
                    onClick = {
                        confirmClear = false
                        val log = env?.appLog ?: return@FilledTonalButton
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { log.clear() } }
                            reload++
                            snackbar.showSnackbar(cleared)
                        }
                    },
                ) { Text(stringResource(R.string.appLog_clear)) }
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
    val scheme = MaterialTheme.colorScheme
    val rows = entries?.let { AppLog.filter(it, min) }
    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item {
            SettingsCard {
                Text(
                    stringResource(R.string.appLog_scope),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.x4),
                )
            }
            Spacer(Modifier.height(Space.x3))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                listOf(
                    Triple(AppLogLevel.INFO, R.string.appLog_filter_all, "all"),
                    Triple(AppLogLevel.WARN, R.string.appLog_filter_warn, "warn"),
                    Triple(AppLogLevel.ERROR, R.string.appLog_filter_error, "error"),
                ).forEach { (level, label, tag) ->
                    FilterChip(
                        selected = min == level,
                        onClick = { onFilter(level) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("app-log-filter-$tag"),
                    )
                }
            }
            Spacer(Modifier.height(Space.x2))
            SettingsCard {
                SettingsRow(Icons.Outlined.History, stringResource(R.string.appLog_alarmLog), null, "app-log-alarm-log", onClick = onOpenAlarmLog)
            }
            Spacer(Modifier.height(Space.x3))
        }
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
            else -> items(rows) { entry -> AppLogRow(entry, env, format) }
        }
    }
}

@Composable
private fun AppLogRow(entry: AppLogEntry, env: AlarmEnvironment, format: DateFormatId) {
    val scheme = MaterialTheme.colorScheme
    val details = buildList {
        entry.fields.forEach { (key, value) -> add("$key=$value") }
        entry.errorType?.let { add("error=$it") }
    }.joinToString(" ")
    LogEntryCard {
        ListItem(
            modifier = Modifier.fillMaxWidth().testTag("app-log-row-${entry.level.name.lowercase()}"),
            leadingContent = {
                Icon(
                    when (entry.level) {
                        AppLogLevel.INFO -> Icons.Outlined.Info
                        AppLogLevel.WARN -> Icons.Outlined.WarningAmber
                        AppLogLevel.ERROR -> Icons.Outlined.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (entry.level == AppLogLevel.ERROR) scheme.error else scheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(entry.event, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) },
            supportingContent = if (details.isEmpty()) {
                null
            } else {
                { Text(details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            },
            trailingContent = {
                Text(
                    DateFormats.short(LocalDateTime.ofInstant(entry.at, env.clock.zone), format),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
