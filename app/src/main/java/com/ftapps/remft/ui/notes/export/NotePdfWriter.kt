package com.ftapps.remft.ui.notes.export

import android.graphics.Bitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.ftapps.remft.domain.notes.export.NoteBlock
import com.ftapps.remft.domain.notes.export.NoteExportLabels
import com.ftapps.remft.domain.notes.export.NoteListKind
import com.ftapps.remft.domain.notes.export.NotePrintMetrics as M
import com.ftapps.remft.domain.notes.export.NotePrintPalette as C
import com.ftapps.remft.domain.notes.export.NoteSpan
import com.ftapps.remft.domain.notes.export.NoteTableAlignment
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object NotePdfWriter {
    const val MIME_TYPE: String = "application/pdf"

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    fun write(
        out: OutputStream,
        title: String,
        subtitle: String?,
        blocks: List<NoteBlock>,
        labels: NoteExportLabels,
        image: (String) -> Bitmap?,
    ) {
        val images = HashMap<String, Bitmap?>()
        val resolveImage: (String) -> Bitmap? = { id ->
            if (images.containsKey(id)) {
                images[id]
            } else {
                runCatching { image(id) }.getOrNull().also { images[id] = it }
            }
        }

        val total = measure(title, subtitle, blocks, labels, resolveImage).pages

        val document = PdfDocument()
        try {
            val pager = Pager(document, title, total)
            Renderer(pager, title, subtitle, labels, resolveImage).render(blocks)
            pager.finish()
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    internal data class Measured(val pages: Int, val overflow: Float, val repeatedHeaders: Int)

    internal fun measure(
        title: String,
        subtitle: String?,
        blocks: List<NoteBlock>,
        labels: NoteExportLabels,
        image: (String) -> Bitmap?,
    ): Measured {
        val dry = Pager(null, title, 0)
        val renderer = Renderer(dry, title, subtitle, labels, image)
        renderer.render(blocks)
        val pages = dry.finish()
        return Measured(pages, dry.overflow, renderer.repeatedHeaders)
    }

    private fun f(value: Double): Float = value.toFloat()

    private fun textPaint(size: Double, color: Int = C.INK, typeface: Typeface = Typeface.DEFAULT): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = f(size)
            this.color = color
            this.typeface = typeface
        }

    private fun lineHeight(paint: Paint): Float = paint.fontMetrics.let { it.descent - it.ascent }

    private class Pager(val document: PdfDocument?, val title: String, val total: Int) {
        private val left = f(M.marginSide)
        private val marginTop = f(M.marginTopBottom)
        private val marginBottom = f(M.marginTopBottom)
        private val metaPaint = textPaint(M.META, C.MUTED)
        private val metaHeight = lineHeight(metaPaint)
        private var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
            private set
        var number = 0
            private set
        var overflow = 0f
            private set
        var y = 0f
            set(value) {
                field = value
                overflow = max(overflow, value - bottom)
            }
        val x: Float get() = left
        val width: Float get() = f(M.contentWidth)
        val bottom: Float get() = PAGE_HEIGHT - marginBottom - metaHeight - f(M.HEADER_GAP)
        val top: Float get() = if (number <= 1) marginTop else marginTop + metaHeight + f(M.HEADER_GAP)
        val atTop: Boolean get() = y <= top + 0.5f
        val pageHeight: Float get() = bottom - (marginTop + metaHeight + f(M.HEADER_GAP))
        val remaining: Float get() = bottom - y

        fun start() {
            closePage()
            number += 1
            if (document != null) {
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create()
                val opened = document.startPage(info)
                page = opened
                canvas = opened.canvas
            }
            y = top
            decorate()
        }

        fun ensure(height: Float) {
            if (page == null && number == 0) start()
            if (y + height > bottom && !atTop) start()
        }

        fun space(height: Float) {
            if (number == 0) start()
            y = min(y + height, bottom)
        }

        private fun decorate() {
            val c = canvas ?: return
            if (number > 1) {
                val header = TextUtils.ellipsize(title, metaPaint, width, TextUtils.TruncateAt.END).toString()
                c.drawText(header, left, marginTop - metaPaint.fontMetrics.ascent, metaPaint)
            }
            val label = "$number / ${max(total, number)}"
            val footerTop = PAGE_HEIGHT - marginBottom - metaHeight
            c.drawText(label, left + width - metaPaint.measureText(label), footerTop - metaPaint.fontMetrics.ascent, metaPaint)
        }

        private fun closePage() {
            val opened = page ?: return
            document?.finishPage(opened)
            page = null
            canvas = null
        }

        fun finish(): Int {
            if (number == 0) start()
            closePage()
            return number
        }
    }

    private class Renderer(
        val pager: Pager,
        val title: String,
        val subtitle: String?,
        val labels: NoteExportLabels,
        val image: (String) -> Bitmap?,
    ) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val italic: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        var repeatedHeaders = 0
            private set

        fun render(blocks: List<NoteBlock>) {
            pager.start()
            titleBlock()
            blocks.forEach { block(it) }
        }

        private fun titleBlock() {
            val paint = textPaint(M.TITLE, C.INK, Typeface.DEFAULT_BOLD).apply {
                letterSpacing = f(M.TITLE_LETTER_SPACING / M.TITLE)
            }
            flow(layout(title, paint, pager.width), pager.x)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                pager.space(f(M.TITLE_TO_LABEL))
                flow(layout(it, textPaint(M.META, C.MUTED), pager.width), pager.x)
            }
            pager.space(f(M.TITLE_TO_RULE))
            hline(pager.y, f(M.TITLE_RULE_WIDTH), C.RULE)
            pager.space(f(M.TITLE_RULE_WIDTH) + f(M.RULE_TO_BODY))
        }

        private fun block(block: NoteBlock) {
            when (block) {
                is NoteBlock.Heading -> {
                    val level = block.level.coerceIn(1, 3)
                    val size = M.headingSize(level)
                    pager.space(f(M.headingBefore(level)))
                    val text = layout(rich(block.spans, size), textPaint(size, C.INK, Typeface.DEFAULT_BOLD), pager.width, M.BODY_LINE_GAP)
                    pager.ensure(text.height.toFloat() + f(M.BLOCK_AFTER) + lineHeight(textPaint(M.BODY)))
                    flow(text, pager.x)
                    pager.space(f(M.BLOCK_AFTER))
                }
                is NoteBlock.Paragraph -> {
                    if (block.text.isBlank()) {
                        pager.space(f(M.BLANK_LINE))
                    } else {
                        flow(bodyLayout(block.spans, pager.width), pager.x)
                        pager.space(f(M.BLOCK_AFTER))
                    }
                }
                is NoteBlock.ListItem -> listItem(block)
                is NoteBlock.Quote -> {
                    val inset = f(M.QUOTE_EDGE + M.QUOTE_PADDING)
                    val text = layout(
                        rich(block.spans, M.BODY),
                        textPaint(M.BODY, C.MUTED, italic),
                        pager.width - inset,
                        M.BODY_LINE_GAP,
                    )
                    flow(text, pager.x + inset) { top, bottom ->
                        rect(pager.x, top, pager.x + f(M.QUOTE_EDGE), bottom, C.RULE)
                    }
                    pager.space(f(M.BLOCK_AFTER))
                }
                is NoteBlock.Callout -> {
                    val label = labels.alert(block.kind)
                    val spans = if (label.isBlank()) {
                        block.spans
                    } else {
                        listOf(NoteSpan(label, bold = true)) + if (block.spans.isEmpty()) emptyList() else listOf(NoteSpan("\n")) + block.spans
                    }
                    val edge = f(M.CALLOUT_EDGE)
                    val padH = f(M.CALLOUT_PADDING_H)
                    val padV = f(M.CALLOUT_PADDING_V)
                    val text = layout(rich(spans, M.BODY), textPaint(M.BODY), pager.width - edge - 2 * padH, M.BODY_LINE_GAP)
                    pager.space(f(M.CALLOUT_BEFORE))
                    flow(text, pager.x + edge + padH, padV) { top, bottom ->
                        rect(pager.x, top, pager.x + pager.width, bottom, C.calloutTint(block.kind))
                        rect(pager.x, top, pager.x + edge, bottom, C.calloutEdge(block.kind))
                    }
                    pager.space(f(M.BLOCK_AFTER))
                }
                is NoteBlock.Code -> codePanel(block.source, C.INK)
                is NoteBlock.Figure -> codePanel(block.source, C.MUTED)
                is NoteBlock.Image -> {
                    val bitmap = image(block.fileId)
                    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                        picture(bitmap, M.IMAGE_MAX_HEIGHT, M.IMAGE_BEFORE)
                    } else {
                        placeholder()
                    }
                }
                is NoteBlock.FrontMatter -> {
                    val pad = f(M.PANEL_PADDING)
                    val heading = labels.frontMatterTitle.takeIf { it.isNotBlank() }
                    val content = SpannableStringBuilder()
                    if (heading != null) {
                        content.append(heading)
                        content.setSpan(StyleSpan(Typeface.BOLD), 0, heading.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        content.append('\n')
                    }
                    content.append(block.source)
                    val text = layout(content, textPaint(M.META, C.MUTED), pager.width - 2 * pad, M.CODE_LINE_GAP)
                    flow(text, pager.x + pad, pad) { top, bottom ->
                        outline(pager.x, top, pager.x + pager.width, bottom, C.RULE, f(M.HAIRLINE))
                    }
                    pager.space(f(M.FRONT_MATTER_AFTER))
                }
                is NoteBlock.Table -> table(block)
                is NoteBlock.Divider -> {
                    pager.space(f(M.DIVIDER_BEFORE))
                    pager.ensure(f(M.DIVIDER_WIDTH))
                    hline(pager.y, f(M.DIVIDER_WIDTH), C.TABLE_RULE)
                    pager.space(f(M.DIVIDER_WIDTH) + f(M.BLOCK_AFTER))
                }
                is NoteBlock.Unsupported -> {
                    flow(layout(labels.unsupportedBlock, textPaint(M.BODY, C.MUTED, italic), pager.width), pager.x)
                    pager.space(f(M.BLOCK_AFTER))
                }
            }
        }

        private fun listItem(block: NoteBlock.ListItem) {
            val indent = min(block.indent, M.MAX_INDENT_LEVEL) * f(M.INDENT_PER_LEVEL)
            val markerWidth = f(M.MARKER_WIDTH)
            val textX = pager.x + indent + markerWidth
            val text = bodyLayout(block.spans, pager.width - indent - markerWidth)
            val (marker, color) = when (block.kind) {
                NoteListKind.Bullet -> "•" to C.MUTED
                NoteListKind.Ordered -> "${block.ordinal ?: 1}." to C.MUTED
                NoteListKind.Checked -> "☑" to C.SUCCESS
                NoteListKind.Unchecked -> "☐" to C.MUTED
            }
            val markerPaint = textPaint(M.BODY, color)
            var first = true
            flow(text, textX) { top, _ ->
                if (first) {
                    first = false
                    pager.canvas?.drawText(marker, pager.x + indent, top - markerPaint.fontMetrics.ascent, markerPaint)
                }
            }
            pager.space(f(M.LIST_AFTER))
        }

        private fun codePanel(source: String, color: Int) {
            val pad = f(M.PANEL_PADDING)
            val text = layout(source.removeSuffix("\n"), textPaint(M.CODE, color, Typeface.MONOSPACE), pager.width - 2 * pad, M.CODE_LINE_GAP)
            flow(text, pager.x + pad, pad) { top, bottom ->
                rect(pager.x, top, pager.x + pager.width, bottom, C.CODE_BG)
            }
            pager.space(f(M.BLOCK_AFTER))
        }

        private fun placeholder() {
            val padH = f(M.PLACEHOLDER_PADDING_H)
            val padV = f(M.PLACEHOLDER_PADDING_V)
            val text = layout(labels.mediaMissing, textPaint(M.META, C.MUTED, italic), pager.width - 2 * padH, 0.0)
            flow(text, pager.x + padH, padV) { top, bottom ->
                outline(pager.x, top, pager.x + pager.width, bottom, C.RULE, f(M.HAIRLINE))
            }
            pager.space(f(M.BLOCK_AFTER))
        }

        private fun picture(bitmap: Bitmap, maxHeight: Double, before: Double) {
            val scale = min(pager.width / bitmap.width, f(maxHeight) / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            pager.space(f(before))
            pager.ensure(h)
            val left = pager.x + (pager.width - w) / 2
            pager.canvas?.drawBitmap(bitmap, null, RectF(left, pager.y, left + w, pager.y + h), bitmapPaint)
            pager.y += h
            pager.space(f(M.MEDIA_AFTER))
        }

        private fun table(block: NoteBlock.Table) {
            val rows = block.rows
            val columns = block.columns
            if (rows.isEmpty() || columns == 0) return
            pager.space(f(M.TABLE_BEFORE))
            val pad = f(M.TABLE_CELL_PADDING)
            val weights = (0 until columns).map { c ->
                max(1, rows.maxOf { it.getOrNull(c)?.length ?: 0 }) * M.TABLE * 0.55 + 2 * M.TABLE_CELL_PADDING
            }
            val total = weights.sum()
            val widths = weights.map { f(M.contentWidth * it / total) }
            val regular = textPaint(M.TABLE)
            val bold = textPaint(M.TABLE, C.INK, Typeface.DEFAULT_BOLD)
            val laid = rows.mapIndexed { index, row ->
                (0 until columns).map { c ->
                    val alignment = when (block.alignment(c)) {
                        NoteTableAlignment.Center -> Layout.Alignment.ALIGN_CENTER
                        NoteTableAlignment.Right -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                    layout(row.getOrNull(c).orEmpty(), if (index == 0) bold else regular, max(1f, widths[c] - 2 * pad), 0.0, alignment)
                }
            }
            val border = f(M.TABLE_BORDER)
            fun rowHeight(cells: List<StaticLayout>): Float = cells.maxOf { it.height }.toFloat() + 2 * pad
            val header = laid[0]
            val repeatHeader = rowHeight(header) <= pager.pageHeight / 3
            fun drawRow(cells: List<StaticLayout>, from: IntArray, to: IntArray) {
                val h = cells.indices.maxOf { c -> cells[c].getLineTop(to[c]) - cells[c].getLineTop(from[c]) }.toFloat() + 2 * pad
                val top = pager.y
                var cx = pager.x
                cells.forEachIndexed { c, cell ->
                    if (to[c] > from[c]) {
                        val startTop = cell.getLineTop(from[c]).toFloat()
                        val chunk = cell.getLineTop(to[c]) - startTop
                        pager.canvas?.withClip(cx + pad, top + pad, cx + pad + cell.width, top + pad + chunk) {
                            withTranslation(cx + pad, top + pad - startTop) { cell.draw(this) }
                        }
                    }
                    outline(cx, top, cx + widths[c], top + h, C.TABLE_RULE, border)
                    cx += widths[c]
                }
                pager.y += h
            }
            fun newPage(index: Int) {
                pager.start()
                if (index > 0 && repeatHeader) {
                    drawRow(header, IntArray(columns), IntArray(columns) { header[it].lineCount })
                    repeatedHeaders += 1
                }
            }
            laid.forEachIndexed { index, cells ->
                val h = rowHeight(cells)
                val headerOnNewPage = if (index > 0 && repeatHeader) rowHeight(header) else 0f
                if (pager.y + h > pager.bottom && !pager.atTop && h <= pager.pageHeight - headerOnNewPage) newPage(index)
                val from = IntArray(columns)
                while (cells.indices.any { from[it] < cells[it].lineCount }) {
                    val available = pager.bottom - pager.y - 2 * pad
                    val to = IntArray(columns) { c ->
                        val cell = cells[c]
                        val startTop = cell.getLineTop(from[c])
                        var end = from[c]
                        while (end < cell.lineCount && cell.getLineBottom(end) - startTop <= available) end += 1
                        end
                    }
                    val stuck = cells.indices.none { to[it] > from[it] }
                    if (stuck) {
                        if (!pager.atTop) {
                            newPage(index)
                            continue
                        }
                        cells.indices.forEach { if (from[it] < cells[it].lineCount) to[it] = from[it] + 1 }
                    }
                    drawRow(cells, from, to)
                    to.copyInto(from)
                    if (cells.indices.any { from[it] < cells[it].lineCount }) newPage(index)
                }
            }
            pager.space(f(M.BLOCK_AFTER))
        }

        private fun bodyLayout(spans: List<NoteSpan>, width: Float): StaticLayout =
            layout(rich(spans, M.BODY), textPaint(M.BODY), width, M.BODY_LINE_GAP)

        private fun rich(spans: List<NoteSpan>, size: Double): CharSequence {
            val builder = SpannableStringBuilder()
            for (span in spans) {
                if (span.text.isEmpty()) continue
                val start = builder.length
                builder.append(span.text)
                val end = builder.length
                fun mark(what: Any) = builder.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                when {
                    span.bold && span.italic -> mark(StyleSpan(Typeface.BOLD_ITALIC))
                    span.bold -> mark(StyleSpan(Typeface.BOLD))
                    span.italic -> mark(StyleSpan(Typeface.ITALIC))
                }
                if (span.strike) mark(StrikethroughSpan())
                if (span.code) {
                    mark(TypefaceSpan("monospace"))
                    mark(RelativeSizeSpan(f((size - 0.5) / size)))
                }
                if (span.mark) {
                    mark(BackgroundColorSpan(C.MARK_BG))
                } else if (span.code) {
                    mark(BackgroundColorSpan(C.CODE_BG))
                }
                if (span.link != null) {
                    mark(ForegroundColorSpan(C.ACCENT))
                    mark(UnderlineSpan())
                }
                if (span.superscript) {
                    mark(SuperscriptSpan())
                    mark(RelativeSizeSpan(0.75f))
                }
            }
            return builder
        }

        private fun layout(
            text: CharSequence,
            paint: TextPaint,
            width: Float,
            gap: Double = 0.0,
            alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        ): StaticLayout {
            val w = max(1, ceil(width.toDouble()).toInt())
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(alignment)
                .setLineSpacing(f(gap), 1f)
                .setIncludePad(false)
                .build()
        }

        private fun flow(
            layout: StaticLayout,
            x: Float,
            padV: Float = 0f,
            decorate: ((top: Float, bottom: Float) -> Unit)? = null,
        ) {
            var line = 0
            val count = layout.lineCount
            while (line < count) {
                val startTop = layout.getLineTop(line)
                var end = line
                val available = pager.bottom - pager.y - 2 * padV
                while (end < count && layout.getLineBottom(end) - startTop <= available) end += 1
                if (end == line) {
                    if (!pager.atTop) {
                        pager.start()
                        continue
                    }
                    end = line + 1
                }
                val chunk = (layout.getLineTop(end) - startTop).toFloat()
                val top = pager.y
                val bottom = top + chunk + 2 * padV
                decorate?.invoke(top, bottom)
                pager.canvas?.withClip(x, top + padV, x + layout.width, top + padV + chunk) {
                    withTranslation(x, top + padV - startTop) { layout.draw(this) }
                }
                pager.y = bottom
                line = end
                if (line < count) pager.start()
            }
        }

        private fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            fill.color = color
            pager.canvas?.drawRect(left, top, right, bottom, fill)
        }

        private fun outline(left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float) {
            stroke.color = color
            stroke.strokeWidth = width
            val half = width / 2
            pager.canvas?.drawRect(left + half, top + half, right - half, bottom - half, stroke)
        }

        private fun hline(y: Float, thickness: Float, color: Int) {
            rect(pager.x, y, pager.x + pager.width, y + thickness, color)
        }
    }
}
