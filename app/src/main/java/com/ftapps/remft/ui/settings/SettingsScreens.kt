package com.ftapps.remft.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.R
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.AppBackground
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.SectionScaffold
import com.ftapps.remft.ui.notes.rememberHiltAvailable
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.MonoStyle
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.dynamicColorAvailable
import androidx.compose.ui.semantics.Role

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenAppLog: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenCompleted: () -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    onOpenBin: () -> Unit = {},
) {
    var licences by remember { mutableStateOf(false) }
    SectionScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionPreferences)) }
            item {
                SettingsCard {
                    SettingsRow(Icons.Outlined.Tune, stringResource(R.string.settings_group_general), stringResource(R.string.settings_group_generalSub), "settings-general", onClick = onOpenGeneral)
                    SettingsRow(Icons.Outlined.NotificationsActive, stringResource(R.string.settings_group_notifications), stringResource(R.string.settings_group_notificationsSub), "settings-notifications", onClick = onOpenNotifications)
                    SettingsRow(Icons.Outlined.Security, stringResource(R.string.settings_group_permissions), stringResource(R.string.settings_group_permissionsSub), "settings-permissions", onClick = onOpenPermissions)
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionData)) }
            item {
                SettingsCard {
                    SettingsRow(Icons.Outlined.Backup, stringResource(R.string.settings_group_backup), stringResource(R.string.settings_group_backupSub), "settings-backup", onClick = onOpenBackup)
                    SettingsRow(Icons.Outlined.TaskAlt, stringResource(R.string.completed_title), stringResource(R.string.completed_sub), "settings-completed", onClick = onOpenCompleted)
                    SettingsRow(Icons.Outlined.Schedule, stringResource(R.string.sched_title), stringResource(R.string.sched_sub), "settings-scheduled", onClick = onOpenScheduled)
                    SettingsRow(Icons.Outlined.DeleteOutline, stringResource(R.string.bin_title), null, "settings-bin", onClick = onOpenBin)
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionSupport)) }
            item {
                SettingsCard {
                    SettingsRow(Icons.AutoMirrored.Outlined.ListAlt, stringResource(R.string.appLog_title), stringResource(R.string.appLog_sub), "settings-app-log", onClick = onOpenAppLog)
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionAbout)) }
            item {
                SettingsCard {
                    SettingsItem(
                        title = "${stringResource(R.string.app_title)} ${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.testTag("settings-about"),
                        icon = Icons.Outlined.Info,
                        subtitle = stringResource(R.string.settings_aboutBody),
                    )
                    SettingsRow(
                        Icons.Outlined.Copyright,
                        stringResource(R.string.setx_licences),
                        stringResource(R.string.setx_licencesSub),
                        "settings-licences",
                        onClick = { licences = true },
                    )
                }
            }
        }
    }
    if (licences) FontLicencesSheet(onDismiss = { licences = false })
}

@Composable
fun SettingsGeneralScreen(onBack: () -> Unit, onReplayTour: (() -> Unit)? = null, viewModel: SettingsViewModel = settingsViewModel()) {
    val dateFormat by viewModel.dateFormat.collectAsStateWithLifecycle()
    val styling by viewModel.noteSourceStyling.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<GeneralSheet?>(null) }
    SectionScaffold(title = stringResource(R.string.settings_group_general), onBack = onBack) { padding ->
        CenteredList(padding, Breakpoints.settingsMaxWidth) {
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionLook)) }
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
                                SettingsSwitch(
                                    checked = dynamicColor,
                                    onCheckedChange = viewModel::setDynamicColor,
                                    modifier = Modifier.testTag("settings-dynamic-color-switch"),
                                )
                            },
                            onClick = { viewModel.setDynamicColor(!dynamicColor) },
                        )
                    }
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.setx_sectionHelp)) }
            item {
                SettingsCard {
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
    RemftBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x4)) {
            SheetTitle(stringResource(R.string.settings_dateFormat_title))
            SettingsCard {
                DateFormats.specs.forEach { spec ->
                    SettingsItem(
                        title = DateFormats.dateTime(DateFormats.sample, spec.id),
                        modifier = Modifier.testTag("date-format-${spec.id.id}").selectable(selected = spec.id == current, role = Role.RadioButton) { onPick(spec.id) },
                        subtitle = if (spec.id == DateFormatId.System) stringResource(R.string.settings_dateFormat_system) else null,
                        trailing = if (spec.id == current) {
                            { SelectedMark() }
                        } else {
                            null
                        },
                    )
                }
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
    RemftBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x4)) {
            SheetTitle(stringResource(R.string.settings_noteSourceStyling_title))
            SettingsCard {
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
                    StylingOption(
                        sample = sample,
                        label = stringResource(option.label()),
                        selected = option == current,
                        modifier = Modifier.testTag("note-source-styling-${option.id}").selectable(selected = option == current, role = Role.RadioButton) { onPick(option) },
                    )
                }
            }
            Spacer(Modifier.height(Space.x4))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x1, bottom = Space.x3),
    )
}

@Composable
private fun SelectedMark() {
    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StylingOption(sample: AnnotatedString, label: String, selected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Space.x4, end = Space.x3, top = Space.x3, bottom = Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sample, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Space.x1))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Spacer(Modifier.width(Space.x2))
            SelectedMark()
        }
    }
}

@Preview(name = "Settings light")
@Composable
private fun SettingsLightPreview() {
    RemftTheme(darkTheme = false) { AppBackground { SettingsScreen(onBack = {}, onOpenGeneral = {}) } }
}

@Preview(name = "Settings dark")
@Composable
private fun SettingsDarkPreview() {
    RemftTheme(darkTheme = true) { AppBackground { SettingsScreen(onBack = {}, onOpenGeneral = {}) } }
}

@Preview(name = "Styling option light")
@Composable
private fun StylingOptionLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { StylingOption(AnnotatedString("# Heading **bold**"), "Plain text", selected = true) } }
}

@Preview(name = "Styling option dark")
@Composable
private fun StylingOptionDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { StylingOption(AnnotatedString("# Heading **bold**"), "Plain text", selected = true) } }
}
