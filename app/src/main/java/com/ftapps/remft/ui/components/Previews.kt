package com.ftapps.remft.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ftapps.remft.ui.theme.Space

@Composable
fun PreviewSurface(content: @Composable () -> Unit) {
    Box(Modifier.background(MaterialTheme.colorScheme.background).padding(Space.x4)) { content() }
}
