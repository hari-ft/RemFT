package com.ftapps.remft.domain.notes.mermaid

import kotlin.math.max

data class SeqParticipantBox(
    val id: String,
    val label: String,
    val header: DiagramRect,
    val lifelineX: Double,
    val lifelineBottom: Double,
)

data class SeqMessageLine(
    val points: List<DiagramPoint>,
    val text: String,
    val dotted: Boolean,
    val arrow: Boolean,
    val textAnchor: DiagramPoint,
    val isSelf: Boolean,
)

data class SequenceLayout(
    val participants: List<SeqParticipantBox>,
    val messages: List<SeqMessageLine>,
    val size: DiagramSize,
) {
    fun byId(id: String): SeqParticipantBox? = participants.firstOrNull { it.id == id }

    companion object {
        const val HEADER_PAD_H = 14.0
        const val HEADER_PAD_V = 8.0
        const val COLUMN_GAP = 40.0
        const val MIN_COLUMN = 90.0
        const val ROW_GAP = 44.0
        const val SELF_LOOP_HEIGHT = 34.0
        const val MARGIN = 12.0
        const val LIFELINE_TAIL = 20.0

        fun of(seq: MermaidSequence, measure: MermaidMeasure): SequenceLayout {
            if (seq.participants.isEmpty()) return SequenceLayout(emptyList(), emptyList(), DiagramSize.Zero)

            val headerSizes = LinkedHashMap<String, DiagramSize>()
            val columnWidths = HashMap<String, Double>()
            for (p in seq.participants) {
                val text = measure.measure(p.label)
                val header = DiagramSize(text.width + HEADER_PAD_H * 2, text.height + HEADER_PAD_V * 2)
                headerSizes[p.id] = header
                columnWidths[p.id] = max(header.width, MIN_COLUMN)
            }
            for (m in seq.messages) {
                if (!m.isSelf) continue
                val caption = measure.measure(m.text).width + SELF_LOOP_HEIGHT + 24
                columnWidths[m.from] = max(columnWidths[m.from] ?: 0.0, caption)
            }

            val headerHeight = headerSizes.values.fold(0.0) { a, s -> max(a, s.height) }

            val centres = HashMap<String, Double>()
            var x = MARGIN
            for (p in seq.participants) {
                val width = columnWidths.getValue(p.id)
                centres[p.id] = x + width / 2
                x += width + COLUMN_GAP
            }
            val totalWidth = x - COLUMN_GAP + MARGIN

            val lines = ArrayList<SeqMessageLine>()
            var y = MARGIN + headerHeight + ROW_GAP
            for (m in seq.messages) {
                val fromX = centres[m.from] ?: continue
                val toX = centres[m.to] ?: continue

                if (m.isSelf) {
                    lines.add(
                        SeqMessageLine(
                            points = listOf(
                                DiagramPoint(fromX, y),
                                DiagramPoint(fromX + SELF_LOOP_HEIGHT, y),
                                DiagramPoint(fromX + SELF_LOOP_HEIGHT, y + SELF_LOOP_HEIGHT),
                                DiagramPoint(fromX, y + SELF_LOOP_HEIGHT),
                            ),
                            text = m.text,
                            dotted = m.dotted,
                            arrow = m.arrow,
                            textAnchor = DiagramPoint(fromX + SELF_LOOP_HEIGHT + 8, y + 4),
                            isSelf = true,
                        ),
                    )
                    y += SELF_LOOP_HEIGHT + ROW_GAP
                    continue
                }

                lines.add(
                    SeqMessageLine(
                        points = listOf(DiagramPoint(fromX, y), DiagramPoint(toX, y)),
                        text = m.text,
                        dotted = m.dotted,
                        arrow = m.arrow,
                        textAnchor = DiagramPoint((fromX + toX) / 2, y - 6),
                        isSelf = false,
                    ),
                )
                y += ROW_GAP
            }

            val lifelineBottom = y - ROW_GAP + LIFELINE_TAIL
            val boxes = seq.participants.map { p ->
                val cx = centres.getValue(p.id)
                SeqParticipantBox(
                    id = p.id,
                    label = p.label,
                    header = DiagramRect.fromCenter(
                        DiagramPoint(cx, MARGIN + headerHeight / 2),
                        headerSizes.getValue(p.id).width,
                        headerHeight,
                    ),
                    lifelineX = cx,
                    lifelineBottom = lifelineBottom,
                )
            }

            return SequenceLayout(boxes, lines, DiagramSize(totalWidth, lifelineBottom + MARGIN))
        }
    }
}
