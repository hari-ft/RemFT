package com.ftapps.remft.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

fun Modifier.menuChoice(selected: Boolean): Modifier = semantics {
    role = Role.RadioButton
    this.selected = selected
}

fun Modifier.menuToggle(checked: Boolean): Modifier = semantics {
    role = Role.Checkbox
    toggleableState = ToggleableState(checked)
}
