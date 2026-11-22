package com.ftapps.remft.ui.alarm

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.ftapps.remft.data.alarm.SnoozeLabels

@Composable
fun snoozeLabel(id: String): String = SnoozeLabels.of(LocalResources.current, id)
