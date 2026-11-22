package com.ftapps.remft.ui.onedrive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.mirror.MirrorCounts
import com.ftapps.remft.data.mirror.MirrorDownloadPhase
import com.ftapps.remft.data.mirror.MirrorDownloadState
import com.ftapps.remft.data.onedrive.AutoBackupSnapshot
import com.ftapps.remft.domain.backup.auto.BackupFrequency
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.auto.DestinationStatus
import com.ftapps.remft.domain.backup.auto.Retention
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.backup.formatBackupDate
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.settings.SettingsRow
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.Space
import java.time.Instant

data class AutoBackupUi(
    val snapshot: AutoBackupSnapshot?,
    val counts: MirrorCounts? = null,
    val downloads: MirrorDownloadState = MirrorDownloadState(),
    val busy: Boolean = false,
    val dateFormat: DateFormatId = DateFormatId.System,
)

class AutoBackupActions(
    val onConnect: () -> Unit = {},
    val onSetOn: (Boolean) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onSchedule: (BackupSchedule) -> Unit = {},
    val onKeepDays: (Int) -> Unit = {},
    val onBackUpNow: () -> Unit = {},
    val onBackUpAnyway: () -> Unit = {},
    val onRetryDownloads: () -> Unit = {},
)

@Composable
fun AutoBackupScreen(onBack: () -> Unit, viewModel: AutoBackupViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbars = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onSignInResult(result.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is AutoBackupMessage.Outcome -> resources.getString(AutoBackupText.outcome(message.outcome))
                is AutoBackupMessage.Error -> resources.getString(AutoBackupText.error(message.code))
                AutoBackupMessage.Working -> resources.getString(R.string.autoBackup_working)
                AutoBackupMessage.Disconnected -> resources.getString(R.string.autoBackup_disconnected)
            }
            snackbars.currentSnackbarData?.dismiss()
            snackbars.showSnackbar(text)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AutoBackupContent(
            ui = AutoBackupUi(snapshot, counts, downloads, busy, dateFormat),
            onBack = onBack,
            actions = AutoBackupActions(
                onConnect = { viewModel.signInIntent()?.let { runCatching { launcher.launch(it) } } },
                onSetOn = viewModel::setOn,
                onDisconnect = viewModel::disconnect,
                onSchedule = viewModel::setSchedule,
                onKeepDays = viewModel::setKeepDays,
                onBackUpNow = viewModel::backUpNow,
                onBackUpAnyway = viewModel::backUpAnyway,
                onRetryDownloads = viewModel::retryDownloads,
            ),
        )
        SnackbarHost(
            snackbars,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().testTag("auto-backup-snackbars"),
        )
    }
}

private enum class AutoBackupDialog { Disconnect, Paused, Keep, Frequency, Time, SecondTime, Weekday, EveryDays, MonthDay }

@Composable
fun AutoBackupContent(ui: AutoBackupUi, onBack: () -> Unit, actions: AutoBackupActions) {
    var dialog by rememberSaveable { mutableStateOf<AutoBackupDialog?>(null) }
    val s = ui.snapshot
    val resources = LocalResources.current
    SectionScaffold(title = stringResource(R.string.autoBackup_title), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            if (s == null) return@CenteredList
            val pause = s.pause
            if (pause != null && s.on) {
                item { PausedCard(pause.previous.items, pause.current.items, enabled = !ui.busy) { dialog = AutoBackupDialog.Paused } }
                item { Spacer(Modifier.height(Space.x3)) }
            }
            item {
                Text(
                    stringResource(R.string.autoBackup_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.x2, vertical = Space.x2).testTag("auto-backup-intro"),
                )
            }
            item {
                SettingsCard {
                    if (!s.connected) {
                        SettingsRow(
                            if (s.on) Icons.Outlined.ErrorOutline else Icons.Outlined.Cloud,
                            stringResource(if (s.on) R.string.autoBackup_oneDriveReconnect else R.string.autoBackup_oneDriveConnect),
                            stringResource(if (s.on) R.string.autoBackup_oneDriveReconnectSub else R.string.autoBackup_oneDriveSub),
                            if (s.on) "auto-backup-onedrive-reconnect" else "auto-backup-onedrive-connect",
                            enabled = !ui.busy,
                            onClick = actions.onConnect,
                        )
                    } else {
                        SettingsRow(
                            Icons.Outlined.Cloud,
                            stringResource(R.string.autoBackup_oneDrive),
                            s.account ?: stringResource(R.string.autoBackup_oneDriveConnected),
                            "auto-backup-onedrive",
                            enabled = !ui.busy,
                            trailing = {
                                Switch(
                                    checked = s.on,
                                    onCheckedChange = if (ui.busy) null else actions.onSetOn,
                                    modifier = Modifier.testTag("auto-backup-onedrive-switch"),
                                )
                            },
                            onClick = { actions.onSetOn(!s.on) },
                        )
                        if (s.reconnect) {
                            SettingsRow(
                                Icons.Outlined.ErrorOutline,
                                stringResource(R.string.autoBackup_oneDriveReconnect),
                                stringResource(R.string.autoBackup_oneDriveReconnectSub),
                                "auto-backup-onedrive-reconnect",
                                enabled = !ui.busy,
                                onClick = actions.onConnect,
                            )
                        }
                        SettingsRow(
                            Icons.Outlined.CloudOff,
                            stringResource(R.string.autoBackup_oneDriveDisconnect),
                            statusLine(s.status, ui.dateFormat) ?: stringResource(R.string.autoBackup_oneDriveDisconnectSub),
                            "auto-backup-onedrive-disconnect",
                            enabled = !ui.busy,
                            trailing = null,
                            onClick = { dialog = AutoBackupDialog.Disconnect },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(Space.x3)) }
            item { ScheduleCard(s, ui, onEdit = { dialog = it }) }
            if (s.connected) {
                item { Spacer(Modifier.height(Space.x3)) }
                item { MirrorStatusCard(ui.counts, ui.downloads, actions.onRetryDownloads) }
            }
            item { Spacer(Modifier.height(Space.x3)) }
            item {
                SettingsCard {
                    SettingsRow(
                        Icons.Outlined.History,
                        stringResource(R.string.autoBackup_keepTitle),
                        stringFilled(R.string.autoBackup_keepSub, "n" to s.keepDays),
                        "auto-backup-keep",
                        enabled = !ui.busy,
                        onClick = { dialog = AutoBackupDialog.Keep },
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = Space.x4), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = actions.onBackUpNow,
                        enabled = !ui.busy && s.on && s.connected,
                        modifier = Modifier.testTag("auto-backup-now"),
                    ) {
                        Icon(Icons.Outlined.Backup, contentDescription = null)
                        Spacer(Modifier.width(Space.x2))
                        Text(stringResource(R.string.autoBackup_now))
                    }
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        AutoBackupText.summary(resources, s, ui.dateFormat),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("auto-backup-summary"),
                    )
                }
            }
        }
    }
    if (s != null) {
        AutoBackupDialogs(dialog, s, ui.dateFormat, actions) { dialog = null }
    }
}

@Composable
private fun statusLine(status: DestinationStatus?, format: DateFormatId): String? {
    status ?: return null
    val date = formatBackupDate(status.at, format)
    return if (status.ok) {
        stringFilled(R.string.autoBackup_statusOk, "date" to date)
    } else {
        stringFilled(R.string.autoBackup_statusFailed, "date" to date, "error" to stringResource(AutoBackupText.error(status.error)))
    }
}

@Composable
private fun PausedCard(before: Long, now: Long, enabled: Boolean, onAnyway: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("auto-backup-paused"),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Column(Modifier.padding(Space.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PauseCircleOutline, contentDescription = null)
                Spacer(Modifier.width(Space.x3))
                Text(stringResource(R.string.autoBackup_pausedTitle), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(Space.x2))
            Text(stringFilled(R.string.autoBackup_pausedBody, "before" to before, "now" to now), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onAnyway, enabled = enabled, modifier = Modifier.testTag("auto-backup-paused-anyway")) {
                    Text(stringResource(R.string.autoBackup_pausedAnyway))
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(s: AutoBackupSnapshot, ui: AutoBackupUi, onEdit: (AutoBackupDialog) -> Unit) {
    val schedule = s.schedule
    val resources = LocalResources.current
    val enabled = !ui.busy
    SettingsCard {
        SettingsRow(
            Icons.Outlined.Repeat,
            stringResource(R.string.autoBackup_schedule_frequency),
            stringResource(AutoBackupText.frequency(schedule.frequency)),
            "auto-backup-frequency",
            enabled = enabled,
            onClick = { onEdit(AutoBackupDialog.Frequency) },
        )
        SettingsRow(
            Icons.Outlined.Schedule,
            stringResource(R.string.autoBackup_schedule_time),
            AutoBackupText.time(schedule.minutes, ui.dateFormat),
            "auto-backup-time",
            enabled = enabled,
            onClick = { onEdit(AutoBackupDialog.Time) },
        )
        when (schedule.frequency) {
            BackupFrequency.TwiceDaily -> SettingsRow(
                Icons.Outlined.Schedule,
                stringResource(R.string.autoBackup_schedule_secondTime),
                AutoBackupText.time(schedule.secondMinutes, ui.dateFormat),
                "auto-backup-second-time",
                enabled = enabled,
                onClick = { onEdit(AutoBackupDialog.SecondTime) },
            )
            BackupFrequency.Weekly -> SettingsRow(
                Icons.Outlined.CalendarMonth,
                stringResource(R.string.autoBackup_schedule_weekday),
                stringResource(AutoBackupText.weekday(schedule.weekday)),
                "auto-backup-weekday",
                enabled = enabled,
                onClick = { onEdit(AutoBackupDialog.Weekday) },
            )
            BackupFrequency.EveryNDays -> SettingsRow(
                Icons.Outlined.CalendarMonth,
                stringResource(R.string.autoBackup_schedule_every),
                stringFilled(R.string.autoBackup_schedule_everyDays, "n" to schedule.everyDays),
                "auto-backup-every",
                enabled = enabled,
                onClick = { onEdit(AutoBackupDialog.EveryDays) },
            )
            BackupFrequency.Monthly -> SettingsRow(
                Icons.Outlined.CalendarMonth,
                stringResource(R.string.autoBackup_schedule_monthDay),
                stringFilled(
                    if (schedule.monthDay > 28) R.string.autoBackup_schedule_monthDayShort else R.string.autoBackup_schedule_monthDayValue,
                    "n" to schedule.monthDay,
                ),
                "auto-backup-month-day",
                enabled = enabled,
                onClick = { onEdit(AutoBackupDialog.MonthDay) },
            )
            BackupFrequency.Daily -> Unit
        }
        Column(Modifier.padding(horizontal = Space.x4, vertical = Space.x3)) {
            AutoBackupText.next(resources, s, ui.dateFormat, Instant.now())?.let { next ->
                Text(next, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("auto-backup-next"))
                Spacer(Modifier.height(Space.x1))
            }
            Text(
                stringResource(R.string.autoBackup_schedule_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MirrorStatusCard(counts: MirrorCounts?, downloads: MirrorDownloadState, onRetry: () -> Unit) {
    val resources = LocalResources.current
    val lines = buildList {
        if (counts != null) {
            add(resources.getString(R.string.mirror_counts).fill("n" to counts.mirrored, "total" to counts.files))
            if (counts.notFound > 0) add(resources.getString(R.string.mirror_notFound).fill("n" to counts.notFound))
        }
        AutoBackupText.downloadLine(resources, downloads)?.let { add(it) }
    }
    SettingsCard {
        ListItem(
            modifier = Modifier.testTag("auto-backup-mirror"),
            leadingContent = { Icon(Icons.Outlined.FolderCopy, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.mirror_title)) },
            supportingContent = {
                Column {
                    if (lines.isNotEmpty()) Text(lines.joinToString("\n"))
                    Spacer(Modifier.height(Space.x1))
                    Text(stringResource(R.string.mirror_reviewNeeded), modifier = Modifier.testTag("auto-backup-mirror-review"))
                    if (downloads.phase == MirrorDownloadPhase.Waiting) {
                        TextButton(onClick = onRetry, modifier = Modifier.testTag("auto-backup-mirror-retry")) {
                            Text(stringResource(R.string.mirror_retry))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun AutoBackupDialogs(
    dialog: AutoBackupDialog?,
    s: AutoBackupSnapshot,
    format: DateFormatId,
    actions: AutoBackupActions,
    onClose: () -> Unit,
) {
    val schedule = s.schedule
    when (dialog) {
        null -> Unit
        AutoBackupDialog.Disconnect -> ConfirmDialog(
            title = stringResource(R.string.autoBackup_disconnectTitle),
            body = stringResource(R.string.autoBackup_disconnectBody),
            action = stringResource(R.string.autoBackup_disconnectAction),
            tag = "auto-backup-disconnect-confirm",
            onClose = onClose,
            onConfirm = actions.onDisconnect,
        )
        AutoBackupDialog.Paused -> ConfirmDialog(
            title = stringResource(R.string.autoBackup_pausedConfirmTitle),
            body = stringResource(R.string.autoBackup_pausedConfirmBody),
            action = stringResource(R.string.autoBackup_pausedAnyway),
            tag = "auto-backup-paused-confirm",
            onClose = onClose,
            onConfirm = actions.onBackUpAnyway,
        )
        AutoBackupDialog.Keep -> ChoiceDialog(
            title = stringResource(R.string.autoBackup_keepTitle),
            options = Retention.KEEP_DAY_CHOICES,
            selected = s.keepDays,
            tagPrefix = "auto-backup-keep",
            label = { stringFilled(R.string.autoBackup_keepDays, "n" to it) },
            onClose = onClose,
            onPick = actions.onKeepDays,
        )
        AutoBackupDialog.Frequency -> ChoiceDialog(
            title = stringResource(R.string.autoBackup_schedule_frequency),
            options = BackupFrequency.entries,
            selected = schedule.frequency,
            tagPrefix = "auto-backup-frequency",
            label = { stringResource(AutoBackupText.frequency(it)) },
            onClose = onClose,
            onPick = { actions.onSchedule(schedule.copy(frequency = it)) },
        )
        AutoBackupDialog.Weekday -> ChoiceDialog(
            title = stringResource(R.string.autoBackup_schedule_weekday),
            options = (1..7).toList(),
            selected = schedule.weekday,
            tagPrefix = "auto-backup-weekday",
            label = { stringResource(AutoBackupText.weekday(it)) },
            onClose = onClose,
            onPick = { actions.onSchedule(schedule.copy(weekday = it)) },
        )
        AutoBackupDialog.EveryDays -> ChoiceDialog(
            title = stringResource(R.string.autoBackup_schedule_every),
            options = (BackupSchedule.EVERY_DAYS_MIN..BackupSchedule.EVERY_DAYS_MAX).toList(),
            selected = schedule.everyDays,
            tagPrefix = "auto-backup-every",
            label = { stringFilled(R.string.autoBackup_schedule_everyDays, "n" to it) },
            onClose = onClose,
            onPick = { actions.onSchedule(schedule.copy(everyDays = it)) },
        )
        AutoBackupDialog.MonthDay -> ChoiceDialog(
            title = stringResource(R.string.autoBackup_schedule_monthDay),
            options = (1..31).toList(),
            selected = schedule.monthDay,
            tagPrefix = "auto-backup-month-day",
            label = { stringFilled(R.string.autoBackup_schedule_monthDayValue, "n" to it) },
            onClose = onClose,
            onPick = { actions.onSchedule(schedule.copy(monthDay = it)) },
        )
        AutoBackupDialog.Time -> TimeDialog(schedule.minutes, format, onClose) { actions.onSchedule(schedule.copy(minutes = it)) }
        AutoBackupDialog.SecondTime ->
            TimeDialog(schedule.secondMinutes, format, onClose) { actions.onSchedule(schedule.copy(secondMinutes = it)) }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, tag: String, onClose: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            Button(
                onClick = {
                    onClose()
                    onConfirm()
                },
                modifier = Modifier.testTag(tag),
            ) { Text(action) }
        },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    tagPrefix: String,
    label: @Composable (T) -> String,
    onClose: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == selected, role = Role.RadioButton) {
                                onClose()
                                onPick(option)
                            }
                            .testTag("$tagPrefix-$option"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(Space.x3))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, format: DateFormatId, onClose: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minutes / 60,
        initialMinute = minutes % 60,
        is24Hour = format != DateFormatId.Mdy12h,
    )
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("auto-backup-time-dialog"),
        text = { TimePicker(state = state) },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.common_cancel)) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onClose()
                    onPick(state.hour * 60 + state.minute)
                },
                modifier = Modifier.testTag("auto-backup-time-save"),
            ) { Text(stringResource(R.string.common_save)) }
        },
    )
}
