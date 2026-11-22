package com.ftapps.remft.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.ui.notes.rememberHiltAvailable
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.MonoStyle
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.dynamicColorAvailable

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column { content() }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tag: String,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ListItem(
        modifier = Modifier.testTag(tag).then(clickable).alpha(if (enabled) 1f else 0.5f),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = if (enabled) trailing else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenAppLog: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenCompleted: () -> Unit = {},
    onOpenBin: () -> Unit = {},
) {
    SectionScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            item {
                SettingsCard {
                    SettingsRow(Icons.Outlined.Tune, stringResource(R.string.settings_group_general), stringResource(R.string.settings_group_generalSub), "settings-general", onClick = onOpenGeneral)
                    SettingsRow(Icons.Outlined.NotificationsActive, stringResource(R.string.settings_group_notifications), stringResource(R.string.settings_group_notificationsSub), "settings-notifications", onClick = onOpenNotifications)
                    SettingsRow(Icons.Outlined.Security, stringResource(R.string.settings_group_permissions), stringResource(R.string.settings_group_permissionsSub), "settings-permissions", onClick = onOpenPermissions)
                    SettingsRow(Icons.Outlined.Backup, stringResource(R.string.settings_group_backup), stringResource(R.string.settings_group_backupSub), "settings-backup", onClick = onOpenBackup)
                    SettingsRow(Icons.Outlined.TaskAlt, stringResource(R.string.completed_title), stringResource(R.string.completed_sub), "settings-completed", onClick = onOpenCompleted)
                    SettingsRow(Icons.Outlined.DeleteOutline, stringResource(R.string.bin_title), null, "settings-bin", onClick = onOpenBin)
                    SettingsRow(Icons.AutoMirrored.Outlined.ListAlt, stringResource(R.string.appLog_title), stringResource(R.string.appLog_sub), "settings-app-log", onClick = onOpenAppLog)
                    HorizontalDivider(color = RemftThemeExtras.colors.hairline)
                    ListItem(
                        modifier = Modifier.testTag("settings-about"),
                        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        headlineContent = { Text("${stringResource(R.string.app_title)} ${BuildConfig.VERSION_NAME}") },
                        supportingContent = { Text(stringResource(R.string.settings_aboutBody)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsGeneralScreen(onBack: () -> Unit, onReplayTour: (() -> Unit)? = null, viewModel: SettingsViewModel = settingsViewModel()) {
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val styling by viewModel.noteSourceStyling.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<GeneralSheet?>(null) }
    SectionScaffold(title = stringResource(R.string.settings_group_general), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            item {
                SettingsCard {
                    SettingsRow(
                        Icons.AutoMirrored.Outlined.EventNote,
                        stringResource(R.string.settings_dateFormat_title),
                        DateFormats.dateTime(DateFormats.sample, dateFormat),
                        "settings-date-format",
                        onClick = { sheet = GeneralSheet.DateFormat },
                    )
                    SettingsRow(
                        Icons.Outlined.EditNote,
                        stringResource(R.string.settings_noteSourceStyling_title),
                        stringResource(styling.label()),
                        "settings-note-source-styling",
                        onClick = { sheet = GeneralSheet.Styling },
                    )
                    if (dynamicColorAvailable()) {
                        SettingsRow(
                            Icons.Outlined.Palette,
                            stringResource(R.string.settings_dynamicColor_title),
                            stringResource(R.string.settings_dynamicColor_sub),
                            "settings-dynamic-color",
                            trailing = {
                                Switch(
                                    checked = dynamicColor,
                                    onCheckedChange = viewModel::setDynamicColor,
                                    modifier = Modifier.testTag("settings-dynamic-color-switch"),
                                )
                            },
                            onClick = { viewModel.setDynamicColor(!dynamicColor) },
                        )
                    }
                    SettingsRow(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        stringResource(R.string.settings_appTour),
                        stringResource(R.string.settings_appTourSub),
                        "replay-tour",
                        enabled = onReplayTour != null,
                        trailing = null,
                        onClick = onReplayTour,
                    )
                }
            }
        }
    }
    when (sheet) {
        GeneralSheet.DateFormat -> DateFormatSheet(dateFormat, onPick = { viewModel.setDateFormat(it); sheet = null }, onDismiss = { sheet = null })
        GeneralSheet.Styling -> StylingSheet(styling, onPick = { viewModel.setNoteSourceStyling(it); sheet = null }, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun settingsViewModel(): SettingsViewModel {
    if (rememberHiltAvailable()) return hiltViewModel()
    val settings = LocalTasksEnvironment.current.settings
    return viewModel { SettingsViewModel(settings) }
}

private enum class GeneralSheet { DateFormat, Styling }

fun NoteSourceStyling.label(): Int = when (this) {
    NoteSourceStyling.Plain -> R.string.settings_noteSourceStyling_plain
    NoteSourceStyling.MarkersOnly -> R.string.settings_noteSourceStyling_markersOnly
    NoteSourceStyling.Live -> R.string.settings_noteSourceStyling_live
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFormatSheet(current: DateFormatId, onPick: (DateFormatId) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = Breakpoints.sheetMaxWidth),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.settings_dateFormat_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x1, bottom = Space.x2),
            )
            DateFormats.specs.forEach { spec ->
                ListItem(
                    modifier = Modifier.testTag("date-format-${spec.id.id}").clickable { onPick(spec.id) },
                    headlineContent = { Text(DateFormats.dateTime(DateFormats.sample, spec.id)) },
                    supportingContent = if (spec.id == DateFormatId.System) {
                        { Text(stringResource(R.string.settings_dateFormat_system)) }
                    } else {
                        null
                    },
                    trailingContent = if (spec.id == current) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(Space.x4))
        }
    }
}

private const val SOURCE_STYLING_SAMPLE = "# Heading **bold** ==highlight=="

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StylingSheet(current: NoteSourceStyling, onPick: (NoteSourceStyling) -> Unit, onDismiss: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = RemftThemeExtras.colors.warning.copy(alpha = 0.3f)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = Breakpoints.sheetMaxWidth),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.settings_noteSourceStyling_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x1, bottom = Space.x2),
            )
            NoteSourceStyling.entries.forEach { option ->
                val sample = when (option) {
                    NoteSourceStyling.Plain -> buildAnnotatedString { append(SOURCE_STYLING_SAMPLE) }
                    NoteSourceStyling.MarkersOnly -> buildAnnotatedString {
                        withStyle(SpanStyle(color = muted)) { append("# ") }
                        append("Heading ")
                        withStyle(SpanStyle(color = muted)) { append("**") }
                        append("bold")
                        withStyle(SpanStyle(color = muted)) { append("** ==") }
                        append("highlight")
                        withStyle(SpanStyle(color = muted)) { append("==") }
                    }
                    NoteSourceStyling.Live -> buildAnnotatedString {
                        withStyle(SpanStyle(color = muted)) { append("# ") }
                        withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append("Heading ") }
                        withStyle(SpanStyle(color = muted)) { append("**") }
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
                        withStyle(SpanStyle(color = muted)) { append("** ==") }
                        withStyle(SpanStyle(background = highlight)) { append("highlight") }
                        withStyle(SpanStyle(color = muted)) { append("==") }
                    }
                }
                ListItem(
                    modifier = Modifier.testTag("note-source-styling-${option.id}").clickable { onPick(option) },
                    headlineContent = { Text(sample, style = MonoStyle) },
                    supportingContent = { Text(stringResource(option.label())) },
                    trailingContent = if (option == current) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Spacer(Modifier.height(Space.x4))
        }
    }
}
