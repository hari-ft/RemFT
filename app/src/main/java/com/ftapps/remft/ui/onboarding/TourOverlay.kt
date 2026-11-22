package com.ftapps.remft.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.shell.Section
import com.ftapps.remft.ui.theme.Motion
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.rememberReducedMotion
import kotlin.math.roundToInt

private val RingInset = 10.dp
private val RingRadius = 14.dp
private val MaxCardWidth = 380.dp
private val SkipReserve = 56.dp
private val MinSideWidth = 240.dp
private val RailNudge = 12.dp

@Composable
fun TourHost(tour: TourController) {
    if (!tour.running) return
    TourOverlay(
        step = tour.current,
        index = tour.step,
        total = tour.steps.size,
        anchors = tour.anchors,
        onNext = tour::next,
        onSkip = tour::skip,
    )
}

@Composable
fun TourOverlay(
    step: TourStep,
    index: Int,
    total: Int,
    anchors: Map<Section, Rect>,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    BackHandler(onBack = onSkip)
    var origin by remember { mutableStateOf(Offset.Zero) }
    val reduced = rememberReducedMotion()
    val label = stringResource(R.string.tour_stepOf).fill("step" to index + 1, "total" to total)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .semantics { paneTitle = label }
            .testTag("tour"),
    ) {
        Crossfade(targetState = step, animationSpec = tween(if (reduced) 0 else Motion.SHORT), label = "tour-step") { shown ->
            val anchor = shown.section?.let { anchors[it] }?.translate(-origin)
            TourStepLayer(shown, steps = total, isLast = shown.ordinal == total - 1, anchor = anchor, onNext = onNext, onSkip = onSkip)
        }
    }
}

@Composable
private fun TourStepLayer(step: TourStep, steps: Int, isLast: Boolean, anchor: Rect?, onNext: () -> Unit, onSkip: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val ringPx = with(density) { RingInset.toPx() }
    val radiusPx = with(density) { RingRadius.toPx() }
    val scrim = scheme.scrim.copy(alpha = 0.8f)
    val hole = anchor?.inflate(ringPx)
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("tour-scrim")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
        ) {
            if (hole == null) {
                drawRect(scrim)
            } else {
                val path = Path().apply {
                    op(
                        Path().apply { addRect(Rect(Offset.Zero, size)) },
                        Path().apply { addRoundRect(RoundRect(hole, CornerRadius(radiusPx))) },
                        PathOperation.Difference,
                    )
                }
                drawPath(path, scrim)
            }
        }
        if (hole != null) {
            with(density) {
                Box(
                    Modifier
                        .offset { IntOffset(hole.left.roundToInt(), hole.top.roundToInt()) }
                        .size(hole.width.toDp(), hole.height.toDp())
                        .border(2.dp, scheme.primary, RoundedCornerShape(RingRadius))
                        .testTag("tour-spotlight"),
                )
            }
        }
        TextButton(
            onClick = onSkip,
            colors = ButtonDefaults.textButtonColors(containerColor = scheme.surface, contentColor = scheme.primary),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Space.x1)
                .heightIn(min = 48.dp)
                .testTag("tour-skip"),
        ) { Text(stringResource(R.string.tour_skip)) }
        TourBubble(anchor) { TourCard(step, steps, isLast, onNext) }
    }
}

@Composable
private fun TourCard(step: TourStep, steps: Int, isLast: Boolean, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Radius.card),
        color = scheme.surface,
        contentColor = scheme.onSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.testTag("tour-card"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.x5)) {
            Text(stringResource(step.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("tour-title"))
            Spacer(Modifier.height(Space.x2))
            Text(stringResource(step.body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tour-body"))
            Spacer(Modifier.height(Space.x4))
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.x2),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(end = Space.x4), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(steps) { i ->
                        val color = if (i == step.ordinal) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.35f)
                        Box(Modifier.size(7.dp).background(color, CircleShape))
                    }
                }
                Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp).testTag("tour-next")) {
                    Text(stringResource(if (isLast) R.string.tour_done else R.string.tour_next))
                }
            }
        }
    }
}

@Composable
private fun TourBubble(anchor: Rect?, card: @Composable () -> Unit) {
    val insets = WindowInsets.safeDrawing
    val direction = LocalLayoutDirection.current
    Layout(content = card, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val padding = Rect(
            insets.getLeft(this, direction).toFloat(),
            insets.getTop(this).toFloat(),
            insets.getRight(this, direction).toFloat(),
            insets.getBottom(this).toFloat(),
        )
        val metrics = TourBubbleMetrics(
            gap = Space.x4.toPx(),
            skipReserve = SkipReserve.toPx(),
            ring = RingInset.toPx(),
            minSideWidth = MinSideWidth.toPx(),
            maxCardWidth = MaxCardWidth.toPx(),
            railNudge = RailNudge.toPx(),
        )
        val slot = TourBubbleLayout.slot(size, anchor, padding, metrics)
        val width = slot.area.width.coerceIn(0f, metrics.maxCardWidth).toInt()
        val placeable = measurables.first().measure(
            Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = slot.area.height.coerceAtLeast(0f).toInt()),
        )
        val position = TourBubbleLayout.position(slot, Size(placeable.width.toFloat(), placeable.height.toFloat()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(position.x.roundToInt(), position.y.roundToInt())
        }
    }
}

data class TourBubbleMetrics(val gap: Float, val skipReserve: Float, val ring: Float, val minSideWidth: Float, val maxCardWidth: Float, val railNudge: Float)

data class TourBubbleSlot(val area: Rect, val alignTop: Float?, val bottomAligned: Boolean, val beside: Boolean)

object TourBubbleLayout {
    fun slot(size: Size, anchor: Rect?, padding: Rect, metrics: TourBubbleMetrics): TourBubbleSlot {
        val safe = Rect(
            padding.left + metrics.gap,
            padding.top + metrics.skipReserve,
            size.width - padding.right - metrics.gap,
            size.height - padding.bottom - metrics.gap,
        )
        if (anchor == null) return TourBubbleSlot(safe, null, bottomAligned = false, beside = false)
        val hole = anchor.inflate(metrics.ring)
        val inBottomBar = anchor.center.y > size.height * 0.6f
        val onRail = !inBottomBar && anchor.center.x < size.width * 0.35f
        if (onRail && safe.right - (hole.right + metrics.gap) >= metrics.minSideWidth) {
            return TourBubbleSlot(
                Rect(hole.right + metrics.gap, safe.top, safe.right, safe.bottom),
                alignTop = anchor.top - metrics.railNudge,
                bottomAligned = false,
                beside = true,
            )
        }
        val above = Rect(safe.left, safe.top, safe.right, (hole.top - metrics.gap).coerceIn(safe.top, safe.bottom))
        val below = Rect(safe.left, (hole.bottom + metrics.gap).coerceIn(safe.top, safe.bottom), safe.right, safe.bottom)
        return if (above.height >= below.height) {
            TourBubbleSlot(above, null, bottomAligned = true, beside = false)
        } else {
            TourBubbleSlot(below, below.top, bottomAligned = false, beside = false)
        }
    }

    fun position(slot: TourBubbleSlot, child: Size): Offset {
        val area = slot.area
        val maxTop = (area.bottom - child.height).coerceIn(area.top, area.bottom)
        val top = when {
            slot.alignTop != null -> slot.alignTop.coerceIn(area.top, maxTop)
            slot.bottomAligned -> maxTop
            else -> area.top + (area.height - child.height) / 2f
        }
        val left = if (slot.beside) area.left else area.left + (area.width - child.width) / 2f
        return Offset(left, top)
    }
}
