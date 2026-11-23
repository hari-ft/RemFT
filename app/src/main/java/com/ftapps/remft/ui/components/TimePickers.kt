package com.ftapps.remft.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ftapps.remft.R
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

enum class TimePickerFit { Dial, DialSideBySide, Input }

object TimePickerSizing {
    const val MAX_FONT_SCALE = 1.2f
    val screenMargin: Dp = Space.x4
    val contentPadding: Dp = Space.x6
    val pickerWidth: Dp = 280.dp
    val sideBySideWidth: Dp = 540.dp
    val dialHeight: Dp = 410.dp
    val sideBySideHeight: Dp = 290.dp
    val chromeHeight: Dp = 150.dp
    val chromeTextHeight: Dp = 44.dp

    fun dialogWidth(fit: TimePickerFit): Dp =
        (if (fit == TimePickerFit.DialSideBySide) sideBySideWidth else pickerWidth) + contentPadding * 2

    fun fit(screenWidth: Dp, screenHeight: Dp, fontScale: Float): TimePickerFit {
        val chrome = chromeHeight + chromeTextHeight * (fontScale - 1f).coerceAtLeast(0f) + screenMargin * 2
        val room = screenWidth - screenMargin * 2
        return when {
            room >= dialogWidth(TimePickerFit.Dial) && screenHeight >= chrome + dialHeight -> TimePickerFit.Dial
            room >= dialogWidth(TimePickerFit.DialSideBySide) && screenHeight >= chrome + sideBySideHeight -> TimePickerFit.DialSideBySide
            else -> TimePickerFit.Input
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemftTimePickerDialog(
    state: TimePickerState,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val fit = TimePickerSizing.fit(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp, density.fontScale)
    var typing by rememberSaveable { mutableStateOf(false) }
    val showInput = typing || fit == TimePickerFit.Input
    val room = configuration.screenWidthDp.dp - TimePickerSizing.screenMargin * 2
    val width = minOf(TimePickerSizing.dialogWidth(if (showInput) TimePickerFit.Input else fit), room)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MarkedShapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier.width(width).testTag("time-picker-dialog"),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = TimePickerSizing.contentPadding, end = TimePickerSizing.contentPadding, top = Space.x5, bottom = Space.x3),
            ) {
                TimePickerHeader(canSwitch = fit != TimePickerFit.Input, typing = showInput, onSwitch = { typing = !showInput })
                Spacer(Modifier.height(Space.x3))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePickerBody(state, if (showInput) TimePickerFit.Input else fit)
                }
                Spacer(Modifier.height(Space.x2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun TimePickerHeader(canSwitch: Boolean, typing: Boolean, onSwitch: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.uiref_timePicker_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canSwitch) {
            RemftIconButton(
                icon = if (typing) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                label = stringResource(if (typing) R.string.uiref_timePicker_useDial else R.string.uiref_timePicker_useKeyboard),
                onClick = onSwitch,
                modifier = Modifier.testTag("time-picker-mode"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerBody(state: TimePickerState, fit: TimePickerFit) {
    val density = LocalDensity.current
    val type = MaterialTheme.typography
    val pickerType = type.copy(
        displayLarge = type.displayLarge.copy(fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.02).em),
        displayMedium = type.displayMedium.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.02).em),
    )
    val tonal = RemftTheme.tonal
    val colors = TimePickerDefaults.colors(
        clockDialColor = tonal.level2,
        timeSelectorUnselectedContainerColor = tonal.level2,
        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, TimePickerSizing.MAX_FONT_SCALE))) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = pickerType) {
            when (fit) {
                TimePickerFit.Input -> TimeInput(state = state, colors = colors, modifier = Modifier.testTag("time-picker-input"))
                TimePickerFit.Dial -> TimePicker(state = state, colors = colors, layoutType = TimePickerLayoutType.Vertical, modifier = Modifier.testTag("time-picker-dial"))
                TimePickerFit.DialSideBySide -> TimePicker(state = state, colors = colors, layoutType = TimePickerLayoutType.Horizontal, modifier = Modifier.testTag("time-picker-dial"))
            }
        }
    }
}
