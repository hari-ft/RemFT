package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.alarm.snoozeLabel
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Sizes
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val CUSTOM_PRESET = "custom"
private const val CAPACITY_WARNING_STEPS = 10
private const val ADD_STEP_GAP = ReminderProfile.MIN_GAP_MINUTES * 5
private const val SAMPLE_HOUR = 9

@StringRes
private fun presetLabel(id: String): Int = when (id) {
    ReminderProfile.PRESET_CALM -> R.string.reminderSettings_preset_calm
    ReminderProfile.PRESET_STANDARD -> R.string.reminderSettings_preset_standard
    ReminderProfile.PRESET_INSISTENT -> R.string.reminderSettings_preset_insistent
    else -> R.string.reminderSettings_preset_custom
}

@Composable
fun ReminderSystemScreen(onBack: () -> Unit) {
    val env = LocalAlarmEnvironment.current
    val snackbar = remember { SnackbarHostState() }
    AlarmSettingsScaffold(stringResource(R.string.reminderSettings_title), onBack, snackbar) { padding ->
        if (env != null) ReminderSystemContent(env, padding, snackbar)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderSystemContent(env: AlarmEnvironment, padding: PaddingValues, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val profile = ReminderProfile.parse(rememberAlarmSetting(env, SettingKeys.ReminderProfile))
    val format = rememberAlarmDateFormat(env)
    val order = SnoozePresets.parseOrder(rememberAlarmSetting(env, SettingKeys.SnoozePresets))
    val alarmSound = SoundChoice.parse(rememberAlarmSetting(env, SettingKeys.AlarmSound))
    val reminderSound = SoundChoice.parse(rememberAlarmSetting(env, SettingKeys.ReminderSound))
    var sheet by remember { mutableStateOf<SoundLane?>(null) }
    var addingSnooze by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val base = LocalDate.now(env.clock).atTime(SAMPLE_HOUR, 0)
    val slots = profile.slots

    fun save(next: ReminderProfile) {
        val normalized = next.copy(slots = ReminderProfile.normalizeSlots(next.slots))
        scope.launch { env.settings.set(SettingKeys.ReminderProfile.name, normalized.encode()) }
    }

    fun saveOrder(next: List<String>) {
        scope.launch { env.settings.set(SettingKeys.SnoozePresets.name, SnoozePresets.encodeOrder(next)) }
    }

    CenteredList(padding, Breakpoints.settingsMaxWidth) {
        item { SettingsSectionHeader(stringResource(R.string.reminderSettings_presets)) }
        item {
            SettingsCard {
                val selected = profile.presetId ?: CUSTOM_PRESET
                FlowRow(
                    modifier = Modifier.padding(horizontal = Space.x4, vertical = Space.x3),
                    horizontalArrangement = Arrangement.spacedBy(Space.x2),
                ) {
                    (ReminderProfile.PRESETS.map { it.id } + CUSTOM_PRESET).forEach { id ->
                        SettingsFilterChip(
                            selected = selected == id,
                            onClick = {
                                ReminderProfile.preset(id)?.let { preset -> save(profile.copy(slots = preset.slots)) }
                            },
                            label = stringResource(presetLabel(id)),
                            modifier = Modifier.testTag("reminder-preset-$id"),
                        )
                    }
                }
            }
        }
        item { SettingsSectionHeader(stringResource(R.string.reminderSettings_chain)) }
        item {
            SettingsCard {
                Column(Modifier.padding(start = Space.x4, end = Space.x4, top = Space.x4, bottom = Space.x3)) {
                    Text(
                        stringFilled(R.string.reminderSettings_timeline, "time" to DateFormats.time(base, format)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        slots.joinToString(" → ") { DateFormats.time(base.plusMinutes(it.toLong()), format) },
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.primary,
                        modifier = Modifier.testTag("reminder-timeline"),
                    )
                }
                Column {
                    slots.forEachIndexed { index, minutes ->
                        val minMinutes = ReminderProfile.minSlotAfter(slots.getOrNull(index - 1))
                        val maxMinutes = slots.getOrNull(index + 1)?.let { it - ReminderProfile.MIN_GAP_MINUTES }
                        ReminderStepRow(
                            index = index,
                            minutes = minutes,
                            canDecrease = minutes > minMinutes,
                            canIncrease = maxMinutes == null || minutes < maxMinutes,
                            canRemove = slots.size > 1,
                            onChange = { value -> save(profile.copy(slots = slots.toMutableList().also { it[index] = value })) },
                            onRemove = { save(profile.copy(slots = slots.toMutableList().also { it.removeAt(index) })) },
                        )
                    }
                }
                Column(Modifier.padding(start = Space.x2, end = Space.x4, top = Space.x1, bottom = Space.x4)) {
                    TextButton(
                        onClick = { save(profile.copy(slots = slots + (slots.last() + ADD_STEP_GAP))) },
                        enabled = slots.size < ReminderProfile.MAX_SLOTS,
                        shape = MarkedShapes.pill,
                        modifier = Modifier.testTag("reminder-add-step"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.reminderSettings_addStep), modifier = Modifier.padding(start = Space.x2))
                    }
                    Column(Modifier.padding(start = Space.x2)) {
                        Spacer(Modifier.height(Space.x2))
                        Text(
                            stringFilled(R.string.reminderSettings_minGap, "minutes" to ReminderProfile.MIN_GAP_MINUTES),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        Text(
                            stringFilled(R.string.reminderSettings_maxSteps, "count" to ReminderProfile.MAX_SLOTS),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                        Spacer(Modifier.height(Space.x2))
                        Text(
                            stringFilled(R.string.reminderSettings_capacity, "count" to profile.alarmsFullyCovered),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            modifier = Modifier.testTag("reminder-capacity"),
                        )
                        if (slots.size > CAPACITY_WARNING_STEPS) {
                            Spacer(Modifier.height(Space.x2))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = RemftTheme.colors.warning.color,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(Space.x2))
                                Text(
                                    stringResource(R.string.reminderSettings_capacityWarning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = RemftTheme.colors.warning.color,
                                    modifier = Modifier.testTag("reminder-capacity-warning"),
                                )
                            }
                        }
                    }
                }
                SettingsItem(
                    title = stringResource(R.string.reminderSettings_repeatAfterSnooze),
                    modifier = Modifier
                        .testTag("reminder-repeat-after-snooze")
                        .toggleable(value = profile.repeatAfterSnooze, role = Role.Switch) { save(profile.copy(repeatAfterSnooze = it)) },
                    icon = Icons.Outlined.Repeat,
                    subtitle = stringResource(R.string.reminderSettings_repeatAfterSnoozeSub),
                    trailing = { SettingsSwitch(checked = profile.repeatAfterSnooze, onCheckedChange = null) },
                )
                Box(Modifier.padding(horizontal = Space.x2, vertical = Space.x1)) {
                    TextButton(onClick = { save(ReminderProfile.Factory) }, shape = MarkedShapes.pill, modifier = Modifier.testTag("reminder-reset")) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                        Text(stringResource(R.string.reminderSettings_reset), modifier = Modifier.padding(start = Space.x2))
                    }
                }
            }
        }
        item { SettingsSectionHeader(stringResource(R.string.setx_sectionSounds)) }
        item {
            SettingsCard {
                SettingsRow(
                    Icons.Outlined.NotificationsActive,
                    stringResource(R.string.sound_alarmTitle),
                    stringResource(soundChoiceLabel(alarmSound)),
                    "sound-row-alarm",
                    onClick = { sheet = SoundLane.Alarm },
                )
                SettingsRow(
                    Icons.Outlined.Notifications,
                    stringResource(R.string.sound_reminderTitle),
                    stringResource(soundChoiceLabel(reminderSound)),
                    "sound-row-reminder",
                    onClick = { sheet = SoundLane.Reminder },
                )
            }
        }
        item { SettingsSectionHeader(stringResource(R.string.reminderSettings_snoozeOrder)) }
        item { SettingsNote(stringResource(R.string.reminderSettings_snoozeOrderSub), Modifier.padding(bottom = Space.x1)) }
        item {
            SettingsCard {
                order.forEachIndexed { index, id ->
                    SnoozeOrderRow(
                        index = index,
                        id = id,
                        label = snoozeLabel(id),
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        canRemove = SnoozePresets.canRemove(order),
                        onUp = { saveOrder(order.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        onDown = { saveOrder(order.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                        onRemove = { saveOrder(SnoozePresets.remove(order, id)) },
                    )
                }
                SnoozeOrderFooter(
                    canAdd = SnoozePresets.canAdd(order),
                    canReset = order != SnoozePresets.ids,
                    onAdd = { addingSnooze = true },
                    onReset = { saveOrder(SnoozePresets.ids) },
                )
            }
        }
    }

    if (addingSnooze) {
        SnoozeOptionDialog(
            order = order,
            onAdd = { next ->
                saveOrder(next)
                addingSnooze = false
            },
            onDismiss = { addingSnooze = false },
        )
    }

    sheet?.let { lane ->
        SoundPickerSheet(
            env = env,
            lane = lane,
            onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun StepBadge(text: String) {
    val colors = settingsToneColors(SettingsTone.Brand)
    Box(Modifier.size(32.dp).background(colors.container, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.content)
    }
}

@Composable
private fun ReminderStepRow(
    index: Int,
    minutes: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    canRemove: Boolean,
    onChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRow).testTag("reminder-step-$index").padding(start = Space.x4, end = Space.x1, top = Space.x1, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepBadge("${index + 1}")
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f)) {
            Text(stringFilled(R.string.reminderSettings_step, "index" to index + 1), style = MaterialTheme.typography.titleMedium)
            Text(
                if (minutes == 0) stringResource(R.string.reminderSettings_immediately) else stringFilled(R.string.reminderSettings_atInstant, "minutes" to minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RemftIconButton(label = stringResource(R.string.reminderSettings_stepEarlier), onClick = { onChange(minutes - 1) }, enabled = canDecrease, modifier = Modifier.testTag("reminder-step-$index-minus")) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.reminderSettings_stepEarlier))
        }
        RemftIconButton(label = stringResource(R.string.reminderSettings_stepLater), onClick = { onChange(minutes + 1) }, enabled = canIncrease, modifier = Modifier.testTag("reminder-step-$index-plus")) {
            Icon(Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.reminderSettings_stepLater))
        }
        RemftIconButton(label = stringResource(R.string.reminderSettings_removeStep), onClick = onRemove, enabled = canRemove, modifier = Modifier.testTag("reminder-step-$index-remove")) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.reminderSettings_removeStep))
        }
    }
}

@Composable
private fun SnoozeOrderRow(
    index: Int,
    id: String,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Sizes.listRow).testTag("snooze-order-$id").padding(start = Space.x4, end = Space.x1, top = Space.x1, bottom = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepBadge("${index + 1}")
        Spacer(Modifier.width(Space.x3))
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        RemftIconButton(label = stringResource(R.string.tasks_checklistMoveUp), onClick = onUp, enabled = canMoveUp, modifier = Modifier.testTag("snooze-up-$id")) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.tasks_checklistMoveUp))
        }
        RemftIconButton(label = stringResource(R.string.tasks_checklistMoveDown), onClick = onDown, enabled = canMoveDown, modifier = Modifier.testTag("snooze-down-$id")) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.tasks_checklistMoveDown))
        }
        RemftIconButton(label = stringFilled(R.string.snz_remove, "label" to label), onClick = onRemove, enabled = canRemove, modifier = Modifier.testTag("snooze-remove-$id")) {
            Icon(Icons.Outlined.Close, contentDescription = stringFilled(R.string.snz_remove, "label" to label))
        }
    }
}

@Composable
private fun SnoozeOrderFooter(canAdd: Boolean, canReset: Boolean, onAdd: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = Space.x4, end = Space.x4, top = Space.x3, bottom = Space.x3)) {
        if (canAdd) {
            RemftTonalButton(
                text = stringResource(R.string.snz_add),
                onClick = onAdd,
                icon = Icons.Outlined.Add,
                modifier = Modifier.testTag("snooze-add"),
            )
        } else {
            Text(
                stringFilled(R.string.snz_full, "count" to SnoozePresets.MAX_OPTIONS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("snooze-full"),
            )
        }
        Spacer(Modifier.height(Space.x1))
        RemftTextButton(
            text = stringResource(R.string.snz_reset),
            onClick = onReset,
            enabled = canReset,
            modifier = Modifier.testTag("snooze-reset"),
        )
    }
}

@Preview(name = "Reminder system light")
@Composable
private fun ReminderSystemLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { ReminderSystemPreviewBody() } }
}

@Preview(name = "Reminder system dark")
@Composable
private fun ReminderSystemDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { ReminderSystemPreviewBody() } }
}

@Composable
private fun ReminderSystemPreviewBody() {
    Column {
        SettingsSectionHeader("The alerts")
        SettingsCard {
            ReminderStepRow(0, 0, canDecrease = false, canIncrease = true, canRemove = true, onChange = {}, onRemove = {})
            ReminderStepRow(1, 5, canDecrease = true, canIncrease = true, canRemove = true, onChange = {}, onRemove = {})
        }
        SettingsSectionHeader("Snooze buttons")
        SettingsCard {
            SnoozeOrderRow(0, "5_min", "5 min", canMoveUp = false, canMoveDown = true, canRemove = true, onUp = {}, onDown = {}, onRemove = {})
            SnoozeOrderRow(1, "m:90", "1 h 30 min", canMoveUp = true, canMoveDown = false, canRemove = true, onUp = {}, onDown = {}, onRemove = {})
            SnoozeOrderFooter(canAdd = true, canReset = true, onAdd = {}, onReset = {})
        }
    }
}
