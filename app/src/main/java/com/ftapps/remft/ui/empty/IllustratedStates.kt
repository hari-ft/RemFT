package com.ftapps.remft.ui.empty

import com.ftapps.remft.ui.theme.Sizes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftFilledButton
import com.ftapps.remft.ui.theme.Brand
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

enum class EmptyIllustration(@param:DrawableRes val fill: Int, @param:DrawableRes val line: Int) {
    Home(R.drawable.illus_home_fill, R.drawable.illus_home_line),
    Inbox(R.drawable.illus_inbox_fill, R.drawable.illus_inbox_line),
    Notes(R.drawable.illus_notes_fill, R.drawable.illus_notes_line),
    Files(R.drawable.illus_files_fill, R.drawable.illus_files_line),
    Bin(R.drawable.illus_bin_fill, R.drawable.illus_bin_line),
    Completed(R.drawable.illus_completed_fill, R.drawable.illus_completed_line),
    Search(R.drawable.illus_search_fill, R.drawable.illus_search_line),
    Error(R.drawable.illus_error_fill, R.drawable.illus_error_line),
}

@Composable
private fun EmptyIllustration.ink(): Color = when (this) {
    EmptyIllustration.Notes -> RemftTheme.colors.note.color
    EmptyIllustration.Files -> RemftTheme.colors.file.color
    EmptyIllustration.Completed -> RemftTheme.colors.done.color
    EmptyIllustration.Bin -> MaterialTheme.colorScheme.onSurfaceVariant
    EmptyIllustration.Error -> RemftTheme.colors.overdue.color
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun BrandIllustration(illustration: EmptyIllustration, modifier: Modifier = Modifier, size: Dp = Sizes.emptyIllustration) {
    val disc = RemftTheme.tonal.level2
    val ring = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Box(modifier.size(size).testTag("illustration-${illustration.name.lowercase()}")) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(color = disc, radius = this.size.minDimension * DISC_RATIO)
        }
        IllustrationLayer(R.drawable.illus_orbit_ring, ring)
        IllustrationLayer(R.drawable.illus_orbit_dot, Brand.Marigold)
        IllustrationLayer(illustration.fill, RemftTheme.tonal.level1)
        IllustrationLayer(illustration.line, illustration.ink())
    }
}

private const val DISC_RATIO = 44f / 120f

@Composable
private fun BoxScope.IllustrationLayer(@DrawableRes id: Int, tint: Color) {
    Image(
        painter = painterResource(id),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.matchParentSize(),
    )
}

@Composable
fun IllustratedEmptyState(
    illustration: EmptyIllustration,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.x6, vertical = Space.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandIllustration(illustration)
        Spacer(Modifier.height(Space.x4))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TextMaxWidth).semantics { heading() },
        )
        Spacer(Modifier.height(Space.x2))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = TextMaxWidth),
        )
        if (action != null) {
            Spacer(Modifier.height(Space.x4))
            action()
        }
    }
}

private val TextMaxWidth = 360.dp

@Composable
fun IllustratedErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier, message: String? = null) {
    IllustratedEmptyState(
        illustration = EmptyIllustration.Error,
        title = stringResource(R.string.state_somethingWrong),
        message = message ?: stringResource(R.string.emptyx_errorBody),
        modifier = modifier,
        action = { RemftFilledButton(stringResource(R.string.common_retry), onClick = onRetry) },
    )
}

@Composable
fun StartupErrorScreen(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(RemftTheme.tonal.level0)
            .safeDrawingPadding()
            .testTag("startup-error"),
        contentAlignment = Alignment.Center,
    ) {
        IllustratedErrorState(
            onRetry = onRetry,
            message = stringResource(R.string.emptyx_startupBody),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Preview(name = "Empty states light", widthDp = 380, heightDp = 1400)
@Composable
private fun EmptyStatesLightPreview() {
    RemftTheme(darkTheme = false) { EmptyStatesPreviewContent() }
}

@Preview(name = "Empty states dark", widthDp = 380, heightDp = 1400)
@Composable
private fun EmptyStatesDarkPreview() {
    RemftTheme(darkTheme = true) { EmptyStatesPreviewContent() }
}

@Composable
private fun EmptyStatesPreviewContent() {
    PreviewSurface {
        Column {
            IllustratedEmptyState(EmptyIllustration.Home, "Nothing planned today", "Add a task with the plus button.")
            IllustratedEmptyState(EmptyIllustration.Notes, "No notes here", "Capture your first note.")
            IllustratedEmptyState(EmptyIllustration.Search, "No matches", "Try another word.")
        }
    }
}

@Preview(name = "Illustrations light", widthDp = 380)
@Composable
private fun IllustrationsLightPreview() {
    RemftTheme(darkTheme = false) { IllustrationGrid() }
}

@Preview(name = "Illustrations dark", widthDp = 380)
@Composable
private fun IllustrationsDarkPreview() {
    RemftTheme(darkTheme = true) { IllustrationGrid() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IllustrationGrid() {
    PreviewSurface {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x2)) {
            EmptyIllustration.entries.forEach { BrandIllustration(it, size = 96.dp) }
        }
    }
}

@Preview(name = "Startup error light", widthDp = 411, heightDp = 891)
@Composable
private fun StartupErrorLightPreview() {
    RemftTheme(darkTheme = false) { StartupErrorScreen(onRetry = {}) }
}

@Preview(name = "Startup error dark", widthDp = 411, heightDp = 891)
@Composable
private fun StartupErrorDarkPreview() {
    RemftTheme(darkTheme = true) { StartupErrorScreen(onRetry = {}) }
}
