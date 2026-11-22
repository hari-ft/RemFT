package com.ftapps.remft.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.MarkedShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemftBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = MarkedShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.widthIn(max = Breakpoints.sheetMaxWidth),
        content = content,
    )
}

@Composable
fun RemftDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: String? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = title?.let { { Text(it, style = MaterialTheme.typography.titleLarge) } },
        text = text,
        shape = MarkedShapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    )
}

@Composable
fun RemftSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        data,
        modifier = modifier,
        shape = MarkedShapes.medium,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
    )
}
