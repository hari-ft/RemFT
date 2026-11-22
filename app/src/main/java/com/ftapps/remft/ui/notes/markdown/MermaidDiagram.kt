package com.ftapps.remft.ui.notes.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.mermaid.DiagramPoint
import com.ftapps.remft.domain.notes.mermaid.DiagramRect
import com.ftapps.remft.domain.notes.mermaid.DiagramSize
import com.ftapps.remft.domain.notes.mermaid.FlowLayout
import com.ftapps.remft.domain.notes.mermaid.FlowNodeBox
import com.ftapps.remft.domain.notes.mermaid.MermaidEdgeStyle
import com.ftapps.remft.domain.notes.mermaid.MermaidFlow
import com.ftapps.remft.domain.notes.mermaid.MermaidMeasure
import com.ftapps.remft.domain.notes.mermaid.MermaidParse
import com.ftapps.remft.domain.notes.mermaid.MermaidParseError
import com.ftapps.remft.domain.notes.mermaid.MermaidSequence
import com.ftapps.remft.domain.notes.mermaid.MermaidShape
import com.ftapps.remft.domain.notes.mermaid.MermaidUnsupported
import com.ftapps.remft.domain.notes.mermaid.SequenceLayout
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.theme.CodeStyle
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

@Composable
fun MermaidDiagram(source: String, modifier: Modifier = Modifier) {
    val chart = remember(source) { MermaidParse.parse(source) }
    when (chart) {
        is MermaidUnsupported -> MermaidPlaceholder(
            reason = stringResource(R.string.markdown_mermaidUnsupported).fill("type" to chart.type),
            source = source,
            modifier = modifier,
        )
        is MermaidParseError -> MermaidPlaceholder(
            reason = stringResource(R.string.markdown_mermaidUnreadable),
            source = source,
            modifier = modifier,
        )
        is MermaidFlow -> MermaidCanvas(flow = chart, sequence = null, modifier = modifier)
        is MermaidSequence -> MermaidCanvas(flow = null, sequence = chart, modifier = modifier)
    }
}

@Composable
private fun MermaidPlaceholder(reason: String, source: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = MarkedShapes.medium
    Column(
        modifier = modifier
            .padding(vertical = Space.x2)
            .fillMaxWidth()
            .clip(shape)
            .background(RemftTheme.tonal.level0)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(Space.x4),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = source.trimEnd(),
                style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = scheme.onSurface,
                softWrap = false,
            )
        }
    }
}

private data class DiagramPalette(
    val nodeFill: Color,
    val nodeInk: Color,
    val nodeStroke: Color,
    val decisionFill: Color,
    val decisionInk: Color,
    val decisionStroke: Color,
    val line: Color,
    val labelInk: Color,
    val ink: Color,
    val chip: Color,
    val lifeline: Color,
)

@Composable
private fun MermaidCanvas(flow: MermaidFlow?, sequence: MermaidSequence?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp)

    val measure = remember(textMeasurer, labelStyle, density) {
        MermaidMeasure { label ->
            val result = textMeasurer.measure(text = label, style = labelStyle, maxLines = 1, softWrap = false)
            DiagramSize(
                result.size.width / density.density.toDouble(),
                result.size.height / density.density.toDouble(),
            )
        }
    }
    val flowLayout = remember(flow, measure) { flow?.let { FlowLayout.of(it, measure) } }
    val sequenceLayout = remember(sequence, measure) { sequence?.let { SequenceLayout.of(it, measure) } }

    val size = flowLayout?.size ?: sequenceLayout?.size ?: DiagramSize.Zero
    val description = remember(flowLayout, sequenceLayout) {
        flowLayout?.nodes?.joinToString(", ") { it.label }
            ?: sequenceLayout?.let { layout ->
                (layout.participants.map { it.label } + layout.messages.map { it.text }).joinToString(", ")
            }
            ?: ""
    }

    val note = RemftTheme.colors.note
    val palette = DiagramPalette(
        nodeFill = scheme.primaryContainer,
        nodeInk = scheme.onPrimaryContainer,
        nodeStroke = scheme.primary,
        decisionFill = note.container,
        decisionInk = note.onContainer,
        decisionStroke = note.color,
        line = scheme.outline,
        labelInk = scheme.onSurfaceVariant,
        ink = scheme.onSurface,
        chip = RemftTheme.tonal.level2,
        lifeline = scheme.outlineVariant,
    )
    val shape = MarkedShapes.medium

    Box(
        modifier = modifier
            .padding(vertical = Space.x2)
            .fillMaxWidth()
            .clip(shape)
            .background(RemftTheme.tonal.level0)
            .border(1.dp, RemftTheme.extras.hairline, shape)
            .horizontalScroll(rememberScrollState())
            .padding(Space.x3),
    ) {
        Canvas(
            modifier = Modifier
                .size(size.width.toFloat().dp, size.height.toFloat().dp)
                .semantics { contentDescription = description },
        ) {
            val painter = DiagramPainter(this, textMeasurer, labelStyle, palette)
            flowLayout?.let { painter.drawFlow(it) }
            sequenceLayout?.let { painter.drawSequence(it) }
        }
    }
}

private class DiagramPainter(
    private val scope: DrawScope,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val palette: DiagramPalette,
) {
    private val scale = scope.density

    private fun px(value: Double): Float = value.toFloat() * scale

    private fun offset(point: DiagramPoint): Offset = Offset(px(point.x), px(point.y))

    private fun topLeft(rect: DiagramRect): Offset = Offset(px(rect.left), px(rect.top))

    private fun size(rect: DiagramRect): Size = Size(px(rect.width), px(rect.height))

    private fun stroke(width: Double, dashed: Boolean = false): Stroke = Stroke(
        width = px(width),
        cap = StrokeCap.Round,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(px(5.0), px(4.0)), 0f) else null,
    )

    private fun polyline(points: List<DiagramPoint>): Path = Path().apply {
        val first = offset(points.first())
        moveTo(first.x, first.y)
        for (point in points.drop(1)) {
            val o = offset(point)
            lineTo(o.x, o.y)
        }
    }

    private fun measure(text: String, maxWidth: Double?): TextLayoutResult = measurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        constraints = if (maxWidth == null) Constraints() else Constraints(maxWidth = px(maxWidth).toInt().coerceAtLeast(1)),
    )

    private fun text(text: String, anchor: DiagramPoint, color: Color, centered: Boolean = true, maxWidth: Double? = null) {
        val layout = measure(text, maxWidth)
        val at = offset(anchor)
        val position = if (centered) {
            Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
        } else {
            at
        }
        scope.drawText(textLayoutResult = layout, color = color, topLeft = position)
    }

    private fun arrow(from: DiagramPoint, tip: DiagramPoint) {
        val direction = tip - from
        val length = direction.distance
        if (length == 0.0) return
        val unit = direction / length
        val normal = DiagramPoint(-unit.y, unit.x)
        val headSize = 7.0
        val left = tip - unit * headSize + normal * (headSize * 0.5)
        val right = tip - unit * headSize - normal * (headSize * 0.5)
        val path = Path().apply {
            val t = offset(tip)
            val l = offset(left)
            val r = offset(right)
            moveTo(t.x, t.y)
            lineTo(l.x, l.y)
            lineTo(r.x, r.y)
            close()
        }
        scope.drawPath(path, palette.line)
    }

    fun drawFlow(layout: FlowLayout) {
        for (edge in layout.edges) {
            if (edge.points.size < 2) continue
            val width = if (edge.style == MermaidEdgeStyle.Thick) 2.6 else 1.4
            scope.drawPath(
                path = polyline(edge.points),
                color = palette.line,
                style = stroke(width, dashed = edge.style == MermaidEdgeStyle.Dotted),
            )
            if (edge.arrow) arrow(edge.points[edge.points.size - 2], edge.points.last())

            val label = edge.label
            val anchor = edge.labelAnchor
            if (!label.isNullOrEmpty() && anchor != null) {
                val metrics = measure(label, null)
                val chipWidth = metrics.size.width + px(10.0)
                val chipHeight = metrics.size.height + px(4.0)
                val center = offset(anchor)
                scope.drawRoundRect(
                    color = palette.chip,
                    topLeft = Offset(center.x - chipWidth / 2f, center.y - chipHeight / 2f),
                    size = Size(chipWidth, chipHeight),
                    cornerRadius = CornerRadius(px(4.0)),
                )
                text(label, anchor, palette.labelInk)
            }
        }

        for (node in layout.nodes) {
            drawShape(node)
            val decision = node.shape == MermaidShape.Diamond
            text(node.label, node.rect.center, if (decision) palette.decisionInk else palette.nodeInk, maxWidth = (node.rect.width - 8).coerceAtLeast(1.0))
        }
    }

    private fun drawShape(node: FlowNodeBox) {
        val r = node.rect
        val outline = stroke(1.4)
        when (node.shape) {
            MermaidShape.Diamond -> {
                val c = r.center
                val path = Path().apply {
                    moveTo(px(c.x), px(r.top))
                    lineTo(px(r.right), px(c.y))
                    lineTo(px(c.x), px(r.bottom))
                    lineTo(px(r.left), px(c.y))
                    close()
                }
                scope.drawPath(path, palette.decisionFill)
                scope.drawPath(path, palette.decisionStroke, style = outline)
            }
            MermaidShape.Circle -> {
                scope.drawOval(palette.nodeFill, topLeft(r), size(r))
                scope.drawOval(palette.nodeStroke, topLeft(r), size(r), style = outline)
            }
            MermaidShape.Stadium -> roundRect(r, r.height / 2, outline)
            MermaidShape.Round -> roundRect(r, 12.0, outline)
            MermaidShape.Rect -> roundRect(r, 6.0, outline)
        }
    }

    private fun roundRect(r: DiagramRect, radius: Double, outline: Stroke) {
        val corner = CornerRadius(px(radius))
        scope.drawRoundRect(palette.nodeFill, topLeft(r), size(r), corner)
        scope.drawRoundRect(palette.nodeStroke, topLeft(r), size(r), corner, style = outline)
    }

    fun drawSequence(layout: SequenceLayout) {
        for (p in layout.participants) {
            scope.drawLine(
                color = palette.lifeline,
                start = Offset(px(p.lifelineX), px(p.header.bottom)),
                end = Offset(px(p.lifelineX), px(p.lifelineBottom)),
                strokeWidth = px(1.2),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(px(5.0), px(4.0)), 0f),
            )
            roundRect(p.header, 6.0, stroke(1.4))
            text(p.label, p.header.center, palette.nodeInk, maxWidth = (p.header.width - 8).coerceAtLeast(1.0))
        }

        for (m in layout.messages) {
            if (m.points.size < 2) continue
            scope.drawPath(path = polyline(m.points), color = palette.line, style = stroke(1.4, dashed = m.dotted))
            if (m.arrow) arrow(m.points[m.points.size - 2], m.points.last())
            text(
                text = m.text,
                anchor = m.textAnchor,
                color = palette.ink,
                centered = !m.isSelf,
                maxWidth = if (m.isSelf) 220.0 else null,
            )
        }
    }
}

private val PreviewFlow = """
flowchart TD
    A[Plan the trip] --> B{Tickets booked?}
    B -->|Yes| C[Pack the bags]
    B -->|No| D(Book tickets)
    D --> C
""".trimIndent()

@Preview(name = "Mermaid light")
@Composable
private fun MermaidLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { MermaidDiagram(PreviewFlow) } }
}

@Preview(name = "Mermaid dark")
@Composable
private fun MermaidDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { MermaidDiagram(PreviewFlow) } }
}
