package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.stringFilled
import com.ftapps.remft.ui.theme.Breakpoints
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

@StringRes
fun snoozePresetLabel(id: String): Int = when (id) {
    SnoozePresets.THIRTY_MIN -> R.string.notif_action_snooze30m
    SnoozePresets.ONE_HOUR -> R.string.notif_action_snooze1h
    SnoozePresets.TOMORROW_MORNING -> R.string.notif_action_snoozeTomorrow
    else -> R.string.notif_action_snooze5m
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
        item {
            SettingsCard {
                Column(Modifier.padding(Space.x4)) {
                    Text(stringResource(R.string.reminderSettings_presets), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.x2))
                    val selected = profile.presetId ?: CUSTOM_PRESET
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                        (ReminderProfile.PRESETS.map { it.id } + CUSTOM_PRESET).forEach { id ->
                            FilterChip(
                                selected = selected == id,
                                onClick = {
                                    ReminderProfile.preset(id)?.let { preset -> save(profile.copy(slots = preset.slots)) }
                                },
                                label = { Text(stringResource(presetLabel(id))) },
                                modifier = Modifier.testTag("reminder-preset-$id"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.x3))
        }
        item {
            SettingsCard {
                Column(Modifier.padding(Space.x4)) {
                    Text(stringResource(R.string.reminderSettings_chain), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        stringFilled(R.string.reminderSettings_timeline, "time" to DateFormats.time(base, format)),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        slots.joinToString(" → ") { DateFormats.time(base.plusMinutes(it.toLong()), format) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("reminder-timeline"),
                    )
                    HorizontalDivider(Modifier.padding(vertical = Space.x3), color = scheme.outlineVariant)
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
                    TextButton(
                        onClick = { save(profile.copy(slots = slots + (slots.last() + ADD_STEP_GAP))) },
                        enabled = slots.size < ReminderProfile.MAX_SLOTS,
                        modifier = Modifier.testTag("reminder-add-step"),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.reminderSettings_addStep), modifier = Modifier.padding(start = Space.x2))
                    }
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
                        Text(
                            stringResource(R.string.reminderSettings_capacityWarning),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier.testTag("reminder-capacity-warning"),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = Space.x3), color = scheme.outlineVariant)
                    ListItem(
                        modifier = Modifier
                            .testTag("reminder-repeat-after-snooze")
                            .toggleable(value = profile.repeatAfterSnooze, role = Role.Switch) { save(profile.copy(repeatAfterSnooze = it)) },
                        headlineContent = { Text(stringResource(R.string.reminderSettings_repeatAfterSnooze)) },
                        supportingContent = { Text(stringResource(R.string.reminderSettings_repeatAfterSnoozeSub)) },
                        trailingContent = { Switch(checked = profile.repeatAfterSnooze, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    TextButton(onClick = { save(ReminderProfile.Factory) }, modifier = Modifier.testTag("reminder-reset")) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                        Text(stringResource(R.string.reminderSettings_reset), modifier = Modifier.padding(start = Space.x2))
                    }
                }
            }
            Spacer(Modifier.height(Space.x3))
        }
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
            Spacer(Modifier.height(Space.x3))
        }
        item {
            SettingsCard {
                Column(Modifier.padding(vertical = Space.x4)) {
                    Text(
                        stringResource(R.string.reminderSettings_snoozeOrder),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = Space.x4),
                    )
                    Spacer(Modifier.height(Space.x1))
                    Text(
                        stringResource(R.string.reminderSettings_snoozeOrderSub),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(horizontal = Space.x4),
                    )
                    Spacer(Modifier.height(Space.x2))
                    order.forEachIndexed { index, id ->
                        val label = stringResource(snoozePresetLabel(id))
                        ListItem(
                            modifier = Modifier.testTag("snooze-order-$id"),
                            headlineContent = { Text(label) },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = { saveOrder(order.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                                        enabled = index > 0,
                                        modifier = Modifier.testTag("snooze-up-$id"),
                                    ) {
                                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.tasks_checklistMoveUp))
                                    }
                                    IconButton(
                                        onClick = { saveOrder(order.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                                        enabled = index < order.lastIndex,
                                        modifier = Modifier.testTag("snooze-down-$id"),
                                    ) {
                                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.tasks_checklistMoveDown))
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
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
private fun ReminderStepRow(
    index: Int,
    minutes: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    canRemove: Boolean,
    onChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        modifier = Modifier.testTag("reminder-step-$index"),
        headlineContent = { Text(stringFilled(R.string.reminderSettings_step, "index" to index + 1)) },
        supportingContent = {
            Text(
                if (minutes == 0) stringResource(R.string.reminderSettings_immediately) else stringFilled(R.string.reminderSettings_atInstant, "minutes" to minutes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = { onChange(minutes - 1) }, enabled = canDecrease, modifier = Modifier.testTag("reminder-step-$index-minus")) {
                    Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.reminderSettings_stepEarlier))
                }
                IconButton(onClick = { onChange(minutes + 1) }, enabled = canIncrease, modifier = Modifier.testTag("reminder-step-$index-plus")) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = stringResource(R.string.reminderSettings_stepLater))
                }
                IconButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.testTag("reminder-step-$index-remove")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.reminderSettings_removeStep))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
