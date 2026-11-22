package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.notes.markdown.AlertKind
import kotlin.math.roundToInt

object NotePrintPalette {
    const val INK: Int = 0xFF0F1B2E.toInt()
    const val MUTED: Int = 0xFF44536F.toInt()
    const val ACCENT: Int = 0xFF0A5CFF.toInt()
    const val RULE: Int = 0xFFD8DEEA.toInt()
    const val CODE_BG: Int = 0xFFF2F4F9.toInt()
    const val SUCCESS: Int = 0xFF0D7A33.toInt()
    const val WARNING: Int = 0xFFC77700.toInt()
    const val SECONDARY: Int = 0xFF5A50E0.toInt()
    const val ERROR: Int = 0xFFD70015.toInt()
    const val MARK_BG: Int = 0xFFFDF0D5.toInt()
    const val TABLE_RULE: Int = 0xFFBDBDBD.toInt()
    const val CALLOUT_TINT_ALPHA: Double = 0.08

    fun calloutEdge(kind: AlertKind?): Int = when (kind) {
        AlertKind.Tip -> SUCCESS
        AlertKind.Important -> SECONDARY
        AlertKind.Warning -> WARNING
        AlertKind.Caution -> ERROR
        AlertKind.Note, null -> ACCENT
    }

    fun calloutTint(kind: AlertKind?): Int = blendOnWhite(calloutEdge(kind), CALLOUT_TINT_ALPHA)

    fun blendOnWhite(argb: Int, alpha: Double): Int {
        fun channel(shift: Int): Int {
            val c = (argb shr shift) and 0xFF
            return (255 - (255 - c) * alpha).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)
}

object NotePrintMetrics {
    const val PAGE_WIDTH_CM: Double = 21.0
    const val PAGE_HEIGHT_CM: Double = 29.7
    const val MARGIN_SIDE_CM: Double = 2.2
    const val MARGIN_TOP_BOTTOM_CM: Double = 2.0
    const val POINTS_PER_CM: Double = 72.0 / 2.54

    val pageWidth: Double get() = PAGE_WIDTH_CM * POINTS_PER_CM
    val pageHeight: Double get() = PAGE_HEIGHT_CM * POINTS_PER_CM
    val marginSide: Double get() = MARGIN_SIDE_CM * POINTS_PER_CM
    val marginTopBottom: Double get() = MARGIN_TOP_BOTTOM_CM * POINTS_PER_CM
    val contentWidth: Double get() = (PAGE_WIDTH_CM - 2 * MARGIN_SIDE_CM) * POINTS_PER_CM

    const val TITLE: Double = 24.0
    const val TITLE_LETTER_SPACING: Double = -0.4
    const val HEADING1: Double = 19.0
    const val HEADING2: Double = 15.5
    const val HEADING3: Double = 13.0
    const val BODY: Double = 11.0
    const val CODE: Double = 9.5
    const val META: Double = 9.0
    const val TABLE: Double = 10.0

    const val BODY_LINE_GAP: Double = 2.4
    const val CODE_LINE_GAP: Double = 2.0

    const val BLOCK_AFTER: Double = 6.0
    const val LIST_AFTER: Double = 3.0
    const val HEADING1_BEFORE: Double = 8.0
    const val HEADING2_BEFORE: Double = 7.0
    const val HEADING3_BEFORE: Double = 6.0
    const val CALLOUT_BEFORE: Double = 4.0
    const val FRONT_MATTER_AFTER: Double = 10.0
    const val IMAGE_BEFORE: Double = 4.0
    const val FIGURE_BEFORE: Double = 6.0
    const val MEDIA_AFTER: Double = 10.0
    const val DIVIDER_BEFORE: Double = 4.0
    const val TABLE_BEFORE: Double = 4.0
    const val BLANK_LINE: Double = 6.0

    const val TITLE_TO_LABEL: Double = 4.0
    const val TITLE_TO_RULE: Double = 10.0
    const val RULE_TO_BODY: Double = 14.0
    const val TITLE_RULE_WIDTH: Double = 0.7

    const val HEADER_GAP: Double = 12.0

    const val INDENT_PER_LEVEL: Double = 16.0
    const val MAX_INDENT_LEVEL: Int = 6
    const val MARKER_WIDTH: Double = 18.0

    const val QUOTE_EDGE: Double = 2.5
    const val QUOTE_PADDING: Double = 10.0
    const val CALLOUT_EDGE: Double = 3.0
    const val CALLOUT_PADDING_H: Double = 10.0
    const val CALLOUT_PADDING_V: Double = 8.0
    const val PANEL_PADDING: Double = 8.0
    const val PLACEHOLDER_PADDING_H: Double = 10.0
    const val PLACEHOLDER_PADDING_V: Double = 8.0
    const val HAIRLINE: Double = 0.7
    const val DIVIDER_WIDTH: Double = 1.0
    const val TABLE_BORDER: Double = 0.5
    const val TABLE_CELL_PADDING: Double = 4.0

    const val IMAGE_MAX_HEIGHT: Double = 320.0
    const val FIGURE_MAX_HEIGHT: Double = 420.0

    fun headingSize(level: Int): Double = when (level) {
        1 -> HEADING1
        2 -> HEADING2
        else -> HEADING3
    }

    fun headingBefore(level: Int): Double = when (level) {
        1 -> HEADING1_BEFORE
        2 -> HEADING2_BEFORE
        else -> HEADING3_BEFORE
    }
}
