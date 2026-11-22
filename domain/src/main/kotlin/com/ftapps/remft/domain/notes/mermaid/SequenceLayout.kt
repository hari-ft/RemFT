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
        const val SELF_TEXT_GAP = 8.0
        const val SELF_TEXT_MAX = 220.0
        const val MESSAGE_TEXT_PAD = 8.0

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
            val ids = seq.participants.map { it.id }
            val index = ids.withIndex().associate { (i, id) -> id to i }
            val selfReach = DoubleArray(ids.size)
            for (m in seq.messages) {
                if (!m.isSelf) continue
                val i = index[m.from] ?: continue
                val caption = measure.measure(m.text).width
                columnWidths[m.from] = max(columnWidths[m.from] ?: 0.0, caption + SELF_LOOP_HEIGHT + 24)
                selfReach[i] = max(selfReach[i], SELF_LOOP_HEIGHT + SELF_TEXT_GAP + minOf(caption, SELF_TEXT_MAX) + SELF_TEXT_GAP)
            }
            val rightHalf = DoubleArray(ids.size) { max(columnWidths.getValue(ids[it]) / 2, selfReach[it]) }

            val headerHeight = headerSizes.values.fold(0.0) { a, s -> max(a, s.height) }

            val extra = DoubleArray(ids.size)
            fun positions(): DoubleArray {
                val out = DoubleArray(ids.size)
                for (i in ids.indices) {
                    val leftHalf = columnWidths.getValue(ids[i]) / 2
                    out[i] = if (i == 0) MARGIN + leftHalf else out[i - 1] + rightHalf[i - 1] + COLUMN_GAP + leftHalf + extra[i]
                }
                return out
            }
            var xs = positions()
            for (m in seq.messages) {
                if (m.isSelf) continue
                val a = index[m.from] ?: continue
                val b = index[m.to] ?: continue
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                val needed = measure.measure(m.text).width + MESSAGE_TEXT_PAD * 2
                val deficit = needed - (xs[hi] - xs[lo])
                if (deficit > 0) {
                    extra[hi] += deficit
                    xs = positions()
                }
            }
            val centres = HashMap<String, Double>()
            ids.forEachIndexed { i, id -> centres[id] = xs[i] }
            val totalWidth = xs.last() + rightHalf.last() + MARGIN

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
                            textAnchor = DiagramPoint(fromX + SELF_LOOP_HEIGHT + SELF_TEXT_GAP, y + 4),
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
