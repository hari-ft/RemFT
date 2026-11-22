package com.ftapps.remft.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.SnoozeLabels
import com.ftapps.remft.domain.alarm.SnoozeAddCheck
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.alarm.SnoozeUnit
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

private const val MAX_AMOUNT_LENGTH = 6

@StringRes
private fun unitLabel(unit: SnoozeUnit): Int = when (unit) {
    SnoozeUnit.MINUTES -> R.string.snz_unitMinutes
    SnoozeUnit.HOURS -> R.string.snz_unitHours
    SnoozeUnit.DAYS -> R.string.snz_unitDays
}

@Composable
fun SnoozeOptionDialog(order: List<String>, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(SnoozeUnit.MINUTES) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val resources = LocalResources.current
    val messages = SnoozeAddMessages(
        number = stringResource(R.string.snz_errorNumber),
        tooShort = stringResource(R.string.snz_errorTooShort),
        tooLong = stringResource(R.string.snz_errorTooLong),
        duplicate = stringResource(R.string.snz_errorDuplicate),
        full = stringResource(R.string.snz_full).fill("count" to SnoozePresets.MAX_OPTIONS),
    )

    fun submit() {
        when (val check = SnoozePresets.checkAdd(order, amount, unit)) {
            is SnoozeAddCheck.Ok -> onAdd(check.order)
            else -> error = messages.of(check) { SnoozeLabels.of(resources, it) }
        }
    }

    RemftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.snz_addTitle),
        text = {
            SnoozeOptionFields(
                amount = amount,
                unit = unit,
                error = error,
                onAmount = {
                    amount = it
                    error = null
                },
                onUnit = {
                    unit = it
                    error = null
                },
                onDone = ::submit,
            )
        },
        confirmButton = {
            RemftTextButton(stringResource(R.string.snz_addConfirm), onClick = ::submit, modifier = Modifier.testTag("snooze-add-confirm"))
        },
        dismissButton = {
            RemftTextButton(stringResource(R.string.common_cancel), onClick = onDismiss, modifier = Modifier.testTag("snooze-add-cancel"))
        },
        modifier = Modifier.testTag("snooze-add-dialog"),
    )
}

private class SnoozeAddMessages(
    val number: String,
    val tooShort: String,
    val tooLong: String,
    val duplicate: String,
    val full: String,
) {
    fun of(check: SnoozeAddCheck, label: (String) -> String): String? = when (check) {
        is SnoozeAddCheck.Ok -> null
        SnoozeAddCheck.Empty -> number
        SnoozeAddCheck.TooShort -> tooShort
        SnoozeAddCheck.TooLong -> tooLong
        is SnoozeAddCheck.Duplicate -> duplicate.fill("label" to label(check.id))
        SnoozeAddCheck.Full -> full
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozeOptionFields(
    amount: String,
    unit: SnoozeUnit,
    error: String?,
    onAmount: (String) -> Unit,
    onUnit: (SnoozeUnit) -> Unit,
    onDone: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
        OutlinedTextField(
            value = amount,
            onValueChange = { next -> next.filter { it.isDigit() }.takeIf { it.length <= MAX_AMOUNT_LENGTH }?.let(onAmount) },
            label = { Text(stringResource(R.string.snz_amount)) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            shape = MarkedShapes.small,
            modifier = Modifier.fillMaxWidth().testTag("snooze-add-amount"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            SnoozeUnit.entries.forEach { option ->
                SettingsFilterChip(
                    selected = unit == option,
                    onClick = { onUnit(option) },
                    label = stringResource(unitLabel(option)),
                    modifier = Modifier.testTag("snooze-add-unit-${option.name.lowercase()}"),
                )
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("snooze-add-error").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Preview(name = "Snooze option light")
@Composable
private fun SnoozeOptionLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { SnoozeOptionFields("90", SnoozeUnit.MINUTES, null, {}, {}, {}) } }
}

@Preview(name = "Snooze option dark")
@Composable
private fun SnoozeOptionDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { SnoozeOptionFields("0", SnoozeUnit.DAYS, "Snooze for at least 1 minute", {}, {}, {}) } }
}
