package com.ftapps.remft.domain.notes.export

import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.ftapps.remft.domain.notes.export.NotePrintMetrics as M
import com.ftapps.remft.domain.notes.export.NotePrintPalette as C

object NoteDocx {
    const val MIME_TYPE: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    val clickableSchemes: Set<String> = setOf("http", "https", "mailto")

    fun build(
        title: String,
        subtitle: String?,
        blocks: List<NoteBlock>,
        labels: NoteExportLabels,
        image: (fileId: String) -> ByteArray?,
    ): ByteArray = Writer(title, subtitle, labels, image).build(blocks)

    fun linkTarget(link: String?): String? {
        val trimmed = link?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in clickableSchemes) return null
        if (scheme != "mailto" && uri.host.isNullOrEmpty()) return null
        return uri.toString()
    }

    data class PictureSize(val width: Int, val height: Int, val extension: String)

    fun pictureSize(bytes: ByteArray): PictureSize? =
        pngSize(bytes) ?: jpegSize(bytes) ?: gifSize(bytes) ?: webpSize(bytes)

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun le24(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun sized(w: Long, h: Long, ext: String): PictureSize? =
        if (w in 1..65535 && h in 1..65535) PictureSize(w.toInt(), h.toInt(), ext) else null

    private fun pngSize(b: ByteArray): PictureSize? {
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (b.size < 24) return null
        for (i in sig.indices) if (u8(b, i) != sig[i]) return null
        return sized(be32(b, 16), be32(b, 20), "png")
    }

    private fun jpegSize(b: ByteArray): PictureSize? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        var orientation = 1
        while (i + 3 < b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) {
                i += 1
                continue
            }
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                i += 2
                continue
            }
            val length = be16(b, i + 2)
            if (marker == 0xE1 && orientation == 1) orientation = exifOrientation(b, i + 4, i + 2 + length)
            val frame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (frame) {
                if (i + 8 >= b.size) return null
                val width = be16(b, i + 7).toLong()
                val height = be16(b, i + 5).toLong()
                return if (orientation in 5..8) sized(height, width, "jpeg") else sized(width, height, "jpeg")
            }
            if (length < 2) return null
            i += 2 + length
        }
        return null
    }

    private fun exifOrientation(b: ByteArray, start: Int, end: Int): Int {
        val limit = min(end, b.size)
        if (start + 14 > limit) return 1
        if (String(b, start, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") return 1
        val tiff = start + 6
        val little = when (String(b, tiff, 2, Charsets.ISO_8859_1)) {
            "II" -> true
            "MM" -> false
            else -> return 1
        }
        fun r16(at: Int): Int = if (little) le16(b, at) else be16(b, at)
        fun r32(at: Int): Long = if (little) (le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)) else be32(b, at)
        if (r16(tiff + 2) != 42) return 1
        val ifd = tiff + r32(tiff + 4)
        if (ifd < tiff || ifd + 2 > limit) return 1
        val entries = r16(ifd.toInt())
        for (n in 0 until entries) {
            val entry = ifd.toInt() + 2 + n * 12
            if (entry + 12 > limit) return 1
            if (r16(entry) == 0x0112) {
                val value = r16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }

    private fun gifSize(b: ByteArray): PictureSize? {
        if (b.size < 10) return null
        val head = String(b, 0, 6, Charsets.ISO_8859_1)
        if (head != "GIF87a" && head != "GIF89a") return null
        return sized(le16(b, 6).toLong(), le16(b, 8).toLong(), "gif")
    }

    private fun webpSize(b: ByteArray): PictureSize? {
        if (b.size < 30) return null
        if (String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
            "VP8 " -> sized((le16(b, 26) and 0x3FFF).toLong(), (le16(b, 28) and 0x3FFF).toLong(), "webp")
            "VP8L" -> {
                if (u8(b, 20) != 0x2F) return null
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                sized(((bits and 0x3FFF) + 1).toLong(), (((bits shr 14) and 0x3FFF) + 1).toLong(), "webp")
            }
            "VP8X" -> sized((le24(b, 24) + 1).toLong(), (le24(b, 27) + 1).toLong(), "webp")
            else -> null
        }
    }

    private val illegalXml = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

    fun escape(text: String): String = stripUnpairedSurrogates(text.replace(illegalXml, ""))
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun stripUnpairedSurrogates(text: String): String {
        if (text.none { it.isSurrogate() }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (!c.isSurrogate()) out.append(c)
            i += 1
        }
        return out.toString()
    }

    private fun tw(pt: Double): Int = (pt * 20).roundToInt()

    private fun hp(pt: Double): Int = (pt * 2).roundToInt()

    private fun bdr(pt: Double): Int = (pt * 8).roundToInt().coerceIn(2, 96)

    private fun emu(pt: Double): Long = (pt * 12700).roundToInt().toLong()

    private fun line(size: Double, gap: Double): Int = tw(size * 1.17 + gap)

    private fun hex(argb: Int): String = C.hex(argb)

    private const val FONT = "Roboto"
    private const val SYMBOL_FONT = "Segoe UI Symbol"
    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val STORY_NS = "xmlns:w=\"$NS_W\" xmlns:r=\"$REL_BASE\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
    private const val BULLET_NUM_ID = 1
    private val zipTime: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    private class Edge(val width: Double, val color: Int, val space: Double = 0.0) {
        fun xml(side: String): String =
            "<w:$side w:val=\"single\" w:sz=\"${bdr(width)}\" w:space=\"${space.roundToInt().coerceIn(0, 31)}\" w:color=\"${hex(color)}\"/>"
    }

    private data class PPr(
        val style: String? = null,
        val keepNext: Boolean = false,
        val numId: Int? = null,
        val ilvl: Int? = null,
        val top: Edge? = null,
        val left: Edge? = null,
        val bottom: Edge? = null,
        val right: Edge? = null,
        val fill: Int? = null,
        val before: Double = 0.0,
        val after: Double = 0.0,
        val gap: Double = 0.0,
        val line: Int? = null,
        val exactLine: Boolean = false,
        val indLeft: Double? = null,
        val indRight: Double? = null,
        val hanging: Double? = null,
        val center: Boolean = false,
        val alignRight: Boolean = false,
        val markSize: Double? = null,
    ) {
        fun xml(): String = buildString {
            append("<w:pPr>")
            style?.let { append("<w:pStyle w:val=\"$it\"/>") }
            if (keepNext) append("<w:keepNext/>")
            numId?.let { append("<w:numPr><w:ilvl w:val=\"${ilvl ?: 0}\"/><w:numId w:val=\"$it\"/></w:numPr>") }
            if (top != null || left != null || bottom != null || right != null) {
                append("<w:pBdr>")
                top?.let { append(it.xml("top")) }
                left?.let { append(it.xml("left")) }
                bottom?.let { append(it.xml("bottom")) }
                right?.let { append(it.xml("right")) }
                append("</w:pBdr>")
            }
            fill?.let { append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
            val tail = if (after > gap) after - gap else 0.0
            append("<w:spacing w:before=\"${tw(before)}\" w:after=\"${tw(tail)}\"")
            line?.let { append(" w:line=\"$it\" w:lineRule=\"${if (exactLine) "exact" else "atLeast"}\"") }
            append("/>")
            if (indLeft != null || indRight != null || hanging != null) {
                append("<w:ind")
                indLeft?.let { append(" w:left=\"${tw(it)}\"") }
                indRight?.let { append(" w:right=\"${tw(it)}\"") }
                hanging?.let { append(" w:hanging=\"${tw(it)}\"") }
                append("/>")
            }
            if (center) append("<w:jc w:val=\"center\"/>")
            if (alignRight) append("<w:jc w:val=\"right\"/>")
            markSize?.let { append("<w:rPr><w:sz w:val=\"${hp(it)}\"/><w:szCs w:val=\"${hp(it)}\"/></w:rPr>") }
            append("</w:pPr>")
        }
    }

    private data class RPr(
        val size: Double,
        val font: String? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val color: Int = C.INK,
        val letterSpacing: Double? = null,
        val underline: Boolean = false,
        val fill: Int? = null,
        val superscript: Boolean = false,
        val noProof: Boolean = false,
    ) {
        fun xml(): String = buildString {
            append("<w:rPr>")
            font?.let { append("<w:rFonts w:ascii=\"$it\" w:hAnsi=\"$it\" w:eastAsia=\"$it\" w:cs=\"$it\"/>") }
            if (bold) append("<w:b/><w:bCs/>")
            if (italic) append("<w:i/><w:iCs/>")
            if (strike) append("<w:strike/>")
            if (noProof) append("<w:noProof/>")
            append("<w:color w:val=\"${hex(color)}\"/>")
            letterSpacing?.let { append("<w:spacing w:val=\"${tw(it)}\"/>") }
            append("<w:sz w:val=\"${hp(size)}\"/><w:szCs w:val=\"${hp(size)}\"/>")
            if (underline) append("<w:u w:val=\"single\"/>")
            fill?.let { append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
            if (superscript) append("<w:vertAlign w:val=\"superscript\"/>")
            append("</w:rPr>")
        }
    }

    private fun run(text: String, rPr: RPr): String {
        if (text.isEmpty()) return ""
        val b = StringBuilder("<w:r>").append(rPr.xml())
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        lines.forEachIndexed { i, ln ->
            if (i > 0) b.append("<w:br/>")
            ln.split('\t').forEachIndexed { j, cell ->
                if (j > 0) b.append("<w:tab/>")
                if (cell.isNotEmpty()) b.append("<w:t xml:space=\"preserve\">").append(escape(cell)).append("</w:t>")
            }
        }
        return b.append("</w:r>").toString()
    }

    private fun paragraph(pPr: PPr, runs: String = ""): String = "<w:p>${pPr.xml()}$runs</w:p>"

    private fun spacer(height: Double): String = paragraph(PPr(line = tw(height), exactLine = true, markSize = 1.0))

    private class Rel(val id: String, val type: String, val target: String, val external: Boolean) {
        fun xml(): String = "<Relationship Id=\"$id\" Type=\"$REL_BASE/$type\" Target=\"${escape(target)}\"" +
            (if (external) " TargetMode=\"External\"" else "") + "/>"
    }

    private class Embedded(val relId: String, val size: PictureSize)

    private class Writer(
        val title: String,
        val subtitle: String?,
        val labels: NoteExportLabels,
        val image: (String) -> ByteArray?,
    ) {
        private val rels = ArrayList<Rel>()
        private val media = LinkedHashMap<String, ByteArray>()
        private val mediaByKey = HashMap<String, Embedded?>()
        private val hyperlinkRels = HashMap<String, String>()
        private var drawingId = 0
        private val orderedStarts = ArrayList<Int>()
        private val orderedAtDepth = HashMap<Int, Pair<Int, Int>>()

        private fun addRel(type: String, target: String, external: Boolean = false): String {
            val id = "rId${rels.size + 1}"
            rels.add(Rel(id, type, target, external))
            return id
        }

        fun build(blocks: List<NoteBlock>): ByteArray {
            addRel("styles", "styles.xml")
            addRel("numbering", "numbering.xml")
            addRel("settings", "settings.xml")
            addRel("fontTable", "fontTable.xml")
            val headerId = addRel("header", "header1.xml")
            val firstHeaderId = addRel("header", "header2.xml")
            val footerId = addRel("footer", "footer1.xml")
            val firstFooterId = addRel("footer", "footer2.xml")

            val body = StringBuilder(titleBlock())
            for (block in blocks) {
                if (block !is NoteBlock.ListItem) orderedAtDepth.clear()
                body.append(block(block))
            }

            val margin = tw(M.marginTopBottom)
            val side = tw(M.marginSide)
            val document = XML_HEADER + "<w:document $STORY_NS><w:body>" + body +
                "<w:sectPr>" +
                "<w:headerReference w:type=\"default\" r:id=\"$headerId\"/>" +
                "<w:headerReference w:type=\"first\" r:id=\"$firstHeaderId\"/>" +
                "<w:footerReference w:type=\"default\" r:id=\"$footerId\"/>" +
                "<w:footerReference w:type=\"first\" r:id=\"$firstFooterId\"/>" +
                "<w:pgSz w:w=\"${tw(M.pageWidth)}\" w:h=\"${tw(M.pageHeight)}\"/>" +
                "<w:pgMar w:top=\"$margin\" w:right=\"$side\" w:bottom=\"$margin\" w:left=\"$side\" " +
                "w:header=\"$margin\" w:footer=\"$margin\" w:gutter=\"0\"/>" +
                "<w:titlePg/>" +
                "</w:sectPr></w:body></w:document>"

            val parts = LinkedHashMap<String, ByteArray>()
            parts["[Content_Types].xml"] = utf8(contentTypes())
            parts["_rels/.rels"] = utf8(packageRels())
            parts["docProps/core.xml"] = utf8(coreProps())
            parts["docProps/app.xml"] = utf8(appProps())
            parts["word/document.xml"] = utf8(document)
            parts["word/styles.xml"] = utf8(styles())
            parts["word/numbering.xml"] = utf8(numbering())
            parts["word/settings.xml"] = utf8(settings())
            parts["word/fontTable.xml"] = utf8(fontTable())
            parts["word/header1.xml"] = utf8(header())
            parts["word/header2.xml"] = utf8(emptyStory("hdr"))
            parts["word/footer1.xml"] = utf8(footer())
            parts["word/footer2.xml"] = utf8(footer())
            parts["word/_rels/document.xml.rels"] = utf8(documentRels())
            media.forEach { (name, bytes) -> parts["word/$name"] = bytes }

            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zip ->
                for ((name, bytes) in parts) {
                    val entry = ZipEntry(name)
                    entry.setTimeLocal(zipTime)
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return buffer.toByteArray()
        }

        private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

        private fun titleBlock(): String {
            val label = subtitle?.takeIf { it.isNotBlank() }
            val ruleEdge = Edge(M.TITLE_RULE_WIDTH, C.RULE, M.TITLE_TO_RULE)
            val titlePara = paragraph(
                PPr(
                    style = "Title",
                    line = line(M.TITLE, 0.0),
                    after = if (label == null) M.RULE_TO_BODY else M.TITLE_TO_LABEL,
                    bottom = if (label == null) ruleEdge else null,
                ),
                run(title, RPr(size = M.TITLE, bold = true, letterSpacing = M.TITLE_LETTER_SPACING)),
            )
            if (label == null) return titlePara
            return titlePara + paragraph(
                PPr(line = line(M.META, 0.0), after = M.RULE_TO_BODY, bottom = ruleEdge),
                run(label, RPr(size = M.META, color = C.MUTED)),
            )
        }

        private fun bodyPPr(after: Double = M.BLOCK_AFTER): PPr =
            PPr(after = after, line = line(M.BODY, M.BODY_LINE_GAP), gap = M.BODY_LINE_GAP)

        private fun block(block: NoteBlock): String = when (block) {
            is NoteBlock.Heading -> {
                val level = min(max(block.level, 1), 3)
                val size = M.headingSize(level)
                paragraph(
                    PPr(
                        style = "Heading$level",
                        keepNext = true,
                        before = M.headingBefore(level),
                        after = M.BLOCK_AFTER,
                        line = line(size, M.BODY_LINE_GAP),
                        gap = M.BODY_LINE_GAP,
                    ),
                    rich(block.spans, size = size, bold = true),
                )
            }
            is NoteBlock.ListItem -> listItem(block)
            is NoteBlock.Callout -> {
                val edge = C.calloutEdge(block.kind)
                val tint = C.calloutTint(block.kind)
                val label = labels.alert(block.kind)
                val spans = if (label.isBlank()) {
                    block.spans
                } else {
                    listOf(NoteSpan(label, bold = true)) + (if (block.spans.isEmpty()) emptyList() else listOf(NoteSpan("\n")) + block.spans)
                }
                paragraph(
                    PPr(
                        top = Edge(0.5, tint, M.CALLOUT_PADDING_V),
                        left = Edge(M.CALLOUT_EDGE, edge, M.CALLOUT_PADDING_H),
                        bottom = Edge(0.5, tint, M.CALLOUT_PADDING_V),
                        right = Edge(0.5, tint, M.CALLOUT_PADDING_H),
                        fill = tint,
                        before = M.CALLOUT_BEFORE + M.CALLOUT_PADDING_V,
                        after = M.BLOCK_AFTER + M.CALLOUT_PADDING_V,
                        line = line(M.BODY, M.BODY_LINE_GAP),
                        gap = M.BODY_LINE_GAP,
                        indLeft = M.CALLOUT_PADDING_H + M.CALLOUT_EDGE,
                        indRight = M.CALLOUT_PADDING_H + 0.5,
                    ),
                    rich(spans),
                )
            }
            is NoteBlock.FrontMatter -> panel(
                block.source,
                border = Edge(M.HAIRLINE, C.RULE, M.PANEL_PADDING),
                size = M.META,
                color = C.MUTED,
                after = M.FRONT_MATTER_AFTER,
                heading = labels.frontMatterTitle.takeIf { it.isNotBlank() },
            )
            is NoteBlock.Quote -> paragraph(
                PPr(
                    left = Edge(M.QUOTE_EDGE, C.RULE, M.QUOTE_PADDING),
                    after = M.BLOCK_AFTER,
                    line = line(M.BODY, M.BODY_LINE_GAP),
                    gap = M.BODY_LINE_GAP,
                    indLeft = M.QUOTE_PADDING + M.QUOTE_EDGE,
                ),
                rich(block.spans, italic = true, color = C.MUTED),
            )
            is NoteBlock.Code -> panel(block.source, fill = C.CODE_BG, size = M.CODE, color = C.INK, after = M.BLOCK_AFTER)
            is NoteBlock.Image -> picture(
                key = "file:${block.fileId}",
                alt = block.alt,
                maxHeight = M.IMAGE_MAX_HEIGHT,
                before = M.IMAGE_BEFORE,
                bytes = { image(block.fileId) },
            ) ?: placeholder()
            is NoteBlock.Figure -> panel(block.source, fill = C.CODE_BG, size = M.CODE, color = C.MUTED, after = M.BLOCK_AFTER)
            is NoteBlock.Divider -> paragraph(
                PPr(
                    bottom = Edge(M.DIVIDER_WIDTH, C.TABLE_RULE),
                    before = M.DIVIDER_BEFORE,
                    after = M.BLOCK_AFTER,
                    line = tw(M.DIVIDER_WIDTH),
                    exactLine = true,
                    markSize = 1.0,
                ),
            )
            is NoteBlock.Table -> table(block)
            is NoteBlock.Paragraph ->
                if (block.text.isBlank()) spacer(M.BLANK_LINE) else paragraph(bodyPPr(), rich(block.spans))
            is NoteBlock.Unsupported -> paragraph(
                bodyPPr(),
                run(labels.unsupportedBlock, RPr(size = M.BODY, color = C.MUTED, italic = true)),
            )
        }

        private fun listItem(block: NoteBlock.ListItem): String {
            val level = block.indent.coerceIn(0, 8)
            return when (block.kind) {
                NoteListKind.Bullet -> paragraph(
                    bodyPPr(M.LIST_AFTER).copy(numId = BULLET_NUM_ID, ilvl = level),
                    rich(block.spans),
                )
                NoteListKind.Ordered -> paragraph(
                    bodyPPr(M.LIST_AFTER).copy(numId = orderedNum(block, level), ilvl = level),
                    rich(block.spans),
                )
                NoteListKind.Checked, NoteListKind.Unchecked -> {
                    val done = block.kind == NoteListKind.Checked
                    val indent = min(level, M.MAX_INDENT_LEVEL) * M.INDENT_PER_LEVEL
                    paragraph(
                        bodyPPr(M.LIST_AFTER).copy(indLeft = indent + M.MARKER_WIDTH, hanging = M.MARKER_WIDTH),
                        run(
                            if (done) "☑" else "☐",
                            RPr(size = M.BODY, font = SYMBOL_FONT, color = if (done) C.SUCCESS else C.MUTED),
                        ) + "<w:r><w:tab/></w:r>" + rich(block.spans),
                    )
                }
            }
        }

        private fun orderedNum(block: NoteBlock.ListItem, level: Int): Int {
            val ordinal = block.ordinal ?: 1
            val current = orderedAtDepth[level]
            if (current != null && current.second == ordinal - 1) {
                orderedAtDepth[level] = current.first to ordinal
                return current.first
            }
            orderedStarts.add(ordinal)
            val numId = orderedStarts.size + 1
            orderedAtDepth[level] = numId to ordinal
            return numId
        }

        private fun panel(
            text: String,
            size: Double,
            color: Int,
            after: Double,
            fill: Int? = null,
            border: Edge? = null,
            heading: String? = null,
        ): String {
            val edge = border ?: Edge(0.5, fill ?: C.CODE_BG, M.PANEL_PADDING)
            val body = text.removeSuffix("\n")
            val inset = M.PANEL_PADDING + edge.width
            val runs = (heading?.let { run(it, RPr(size = size, color = color, bold = true)) + "<w:r><w:br/></w:r>" } ?: "") +
                run(body, RPr(size = size, color = color, noProof = true))
            return paragraph(
                PPr(
                    top = edge,
                    left = edge,
                    bottom = edge,
                    right = edge,
                    fill = fill,
                    before = M.PANEL_PADDING,
                    after = after + M.PANEL_PADDING,
                    line = line(size, M.CODE_LINE_GAP),
                    gap = M.CODE_LINE_GAP,
                    indLeft = inset,
                    indRight = inset,
                ),
                runs,
            )
        }

        private fun placeholder(): String {
            val e = Edge(M.HAIRLINE, C.RULE, M.PLACEHOLDER_PADDING_V)
            val h = Edge(M.HAIRLINE, C.RULE, M.PLACEHOLDER_PADDING_H)
            return paragraph(
                PPr(
                    top = e,
                    left = h,
                    bottom = e,
                    right = h,
                    before = M.PLACEHOLDER_PADDING_V,
                    after = M.BLOCK_AFTER + M.PLACEHOLDER_PADDING_V,
                    line = line(M.META, 0.0),
                    indLeft = M.PLACEHOLDER_PADDING_H + M.HAIRLINE,
                    indRight = M.PLACEHOLDER_PADDING_H + M.HAIRLINE,
                ),
                run(labels.mediaMissing, RPr(size = M.META, color = C.MUTED, italic = true)),
            )
        }

        private fun picture(
            key: String,
            alt: String,
            maxHeight: Double,
            before: Double,
            bytes: () -> ByteArray?,
        ): String? {
            val embedded = embed(key, bytes) ?: return null
            val size = embedded.size
            val scale = min(M.contentWidth / size.width, maxHeight / size.height)
            val cx = emu(size.width * scale)
            val cy = emu(size.height * scale)
            val id = ++drawingId
            val descr = escape(alt)
            val drawing = "<w:r><w:drawing>" +
                "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                "<wp:extent cx=\"$cx\" cy=\"$cy\"/>" +
                "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>" +
                "<wp:docPr id=\"$id\" name=\"Picture $id\" descr=\"$descr\"/>" +
                "<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
                "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                "<pic:pic>" +
                "<pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"Picture $id\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                "<pic:blipFill><a:blip r:embed=\"${embedded.relId}\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
                "</pic:pic></a:graphicData></a:graphic>" +
                "</wp:inline></w:drawing></w:r>"
            return paragraph(PPr(before = before, after = M.MEDIA_AFTER, center = true), drawing)
        }

        private fun embed(key: String, bytes: () -> ByteArray?): Embedded? {
            if (mediaByKey.containsKey(key)) return mediaByKey[key]
            val data = try {
                bytes()
            } catch (_: Exception) {
                null
            }
            val size = data?.let { pictureSize(it) }
            val embedded = if (data == null || size == null) {
                null
            } else {
                val name = "media/image${media.size + 1}.${size.extension}"
                media[name] = data
                Embedded(addRel("image", name), size)
            }
            mediaByKey[key] = embedded
            return embedded
        }

        private fun table(block: NoteBlock.Table): String {
            val rows = block.rows
            if (rows.isEmpty()) return ""
            val columns = block.columns
            if (columns == 0) return ""
            val weights = (0 until columns).map { c ->
                max(1, rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 }) * M.TABLE * 0.55 + 2 * M.TABLE_CELL_PADDING
            }
            val total = weights.sum()
            val widths = weights.map { (tw(M.contentWidth) * it / total).toInt() }
            fun edge(side: String): String =
                "<w:$side w:val=\"single\" w:sz=\"${bdr(M.TABLE_BORDER)}\" w:space=\"0\" w:color=\"${hex(C.TABLE_RULE)}\"/>"
            val pad = tw(M.TABLE_CELL_PADDING)
            val b = StringBuilder()
                .append(spacer(M.TABLE_BEFORE))
                .append("<w:tbl><w:tblPr>")
                .append("<w:tblW w:w=\"5000\" w:type=\"pct\"/>")
                .append("<w:tblBorders>")
                .append(edge("top")).append(edge("left")).append(edge("bottom"))
                .append(edge("right")).append(edge("insideH")).append(edge("insideV"))
                .append("</w:tblBorders>")
                .append("<w:tblLayout w:type=\"autofit\"/>")
                .append("<w:tblCellMar>")
                .append("<w:top w:w=\"$pad\" w:type=\"dxa\"/>")
                .append("<w:left w:w=\"$pad\" w:type=\"dxa\"/>")
                .append("<w:bottom w:w=\"$pad\" w:type=\"dxa\"/>")
                .append("<w:right w:w=\"$pad\" w:type=\"dxa\"/>")
                .append("</w:tblCellMar>")
                .append("<w:tblLook w:val=\"0020\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"1\" w:noVBand=\"1\"/>")
                .append("</w:tblPr><w:tblGrid>")
            widths.forEach { b.append("<w:gridCol w:w=\"$it\"/>") }
            b.append("</w:tblGrid>")
            rows.forEachIndexed { index, row ->
                val header = index == 0
                b.append("<w:tr>")
                if (header) b.append("<w:trPr><w:tblHeader/></w:trPr>")
                for (c in 0 until columns) {
                    val cell = row.getOrNull(c).orEmpty()
                    val align = block.alignment(c)
                    b.append("<w:tc><w:tcPr><w:tcW w:w=\"${widths[c]}\" w:type=\"dxa\"/></w:tcPr>")
                    b.append(
                        paragraph(
                            PPr(
                                line = line(M.TABLE, 0.0),
                                center = align == NoteTableAlignment.Center,
                                alignRight = align == NoteTableAlignment.Right,
                            ),
                            run(cell, RPr(size = M.TABLE, bold = header)),
                        ),
                    )
                    b.append("</w:tc>")
                }
                b.append("</w:tr>")
            }
            b.append("</w:tbl>").append(spacer(M.BLOCK_AFTER))
            return b.toString()
        }

        private fun rich(
            spans: List<NoteSpan>,
            size: Double = M.BODY,
            bold: Boolean = false,
            italic: Boolean = false,
            color: Int = C.INK,
        ): String {
            val b = StringBuilder()
            var openLink: String? = null
            fun closeLink() {
                if (openLink != null) b.append("</w:hyperlink>")
                openLink = null
            }
            for (span in spans) {
                val target = linkTarget(span.link)
                if (target != openLink) {
                    closeLink()
                    if (target != null) {
                        b.append("<w:hyperlink r:id=\"${hyperlinkRel(target)}\" w:history=\"1\">")
                        openLink = target
                    }
                }
                b.append(
                    run(
                        span.text,
                        RPr(
                            size = if (span.code) size - 0.5 else size,
                            bold = bold || span.bold,
                            italic = italic || span.italic,
                            strike = span.strike,
                            color = if (span.link != null) C.ACCENT else color,
                            underline = span.link != null,
                            fill = if (span.mark) C.MARK_BG else if (span.code) C.CODE_BG else null,
                            superscript = span.superscript,
                        ),
                    ),
                )
            }
            closeLink()
            return b.toString()
        }

        private fun hyperlinkRel(target: String): String =
            hyperlinkRels.getOrPut(target) { addRel("hyperlink", target, external = true) }

        private fun contentTypes(): String {
            val wml = "application/vnd.openxmlformats-officedocument.wordprocessingml"
            fun override(part: String, type: String) = "<Override PartName=\"$part\" ContentType=\"$type\"/>"
            return XML_HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Default Extension=\"gif\" ContentType=\"image/gif\"/>" +
                "<Default Extension=\"webp\" ContentType=\"image/webp\"/>" +
                override("/word/document.xml", "$wml.document.main+xml") +
                override("/word/styles.xml", "$wml.styles+xml") +
                override("/word/numbering.xml", "$wml.numbering+xml") +
                override("/word/settings.xml", "$wml.settings+xml") +
                override("/word/fontTable.xml", "$wml.fontTable+xml") +
                override("/word/header1.xml", "$wml.header+xml") +
                override("/word/header2.xml", "$wml.header+xml") +
                override("/word/footer1.xml", "$wml.footer+xml") +
                override("/word/footer2.xml", "$wml.footer+xml") +
                override("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml") +
                override("/docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml") +
                "</Types>"
        }

        private fun packageRels(): String = XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_BASE/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"$REL_BASE/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

        private fun documentRels(): String = XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            rels.joinToString("") { it.xml() } +
            "</Relationships>"

        private fun coreProps(): String = XML_HEADER +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<dc:creator>RemFT</dc:creator>" +
            "</cp:coreProperties>"

        private fun appProps(): String = XML_HEADER +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
            "<Application>RemFT</Application>" +
            "</Properties>"

        private fun header(): String = XML_HEADER + "<w:hdr $STORY_NS>" +
            paragraph(PPr(after = M.HEADER_GAP, line = line(M.META, 0.0)), run(title, RPr(size = M.META, color = C.MUTED))) +
            "</w:hdr>"

        private fun footer(): String {
            val rPr = RPr(size = M.META, color = C.MUTED)
            fun field(code: String) = "<w:fldSimple w:instr=\" $code \\* MERGEFORMAT \">${run("1", rPr)}</w:fldSimple>"
            return XML_HEADER + "<w:ftr $STORY_NS>" +
                paragraph(
                    PPr(before = M.HEADER_GAP, line = line(M.META, 0.0), alignRight = true),
                    field("PAGE") + run(" / ", rPr) + field("NUMPAGES"),
                ) +
                "</w:ftr>"
        }

        private fun emptyStory(tag: String): String = XML_HEADER + "<w:$tag $STORY_NS>" +
            paragraph(PPr(line = tw(1.0), exactLine = true, markSize = 1.0)) +
            "</w:$tag>"

        private fun styles(): String = XML_HEADER + "<w:styles xmlns:w=\"$NS_W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:eastAsia=\"$FONT\" w:cs=\"$FONT\"/>" +
            "<w:color w:val=\"${hex(C.INK)}\"/>" +
            "<w:sz w:val=\"${hp(M.BODY)}\"/><w:szCs w:val=\"${hp(M.BODY)}\"/>" +
            "</w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\">" +
            "<w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:rPr><w:b/><w:bCs/><w:spacing w:val=\"${tw(M.TITLE_LETTER_SPACING)}\"/>" +
            "<w:sz w:val=\"${hp(M.TITLE)}\"/><w:szCs w:val=\"${hp(M.TITLE)}\"/></w:rPr></w:style>" +
            headingStyle(1) + headingStyle(2) + headingStyle(3) +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>" +
            "<w:rPr><w:color w:val=\"${hex(C.ACCENT)}\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "</w:styles>"

        private fun headingStyle(level: Int): String {
            val size = M.headingSize(level)
            return "<w:style w:type=\"paragraph\" w:styleId=\"Heading$level\">" +
                "<w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"${tw(M.headingBefore(level))}\" w:after=\"${tw(M.BLOCK_AFTER)}\"/>" +
                "<w:outlineLvl w:val=\"${level - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/><w:sz w:val=\"${hp(size)}\"/><w:szCs w:val=\"${hp(size)}\"/></w:rPr></w:style>"
        }

        private fun numbering(): String {
            fun levels(ordered: Boolean, start: Int = 1): String = buildString {
                for (l in 0 until 9) {
                    val left = min(l, M.MAX_INDENT_LEVEL) * M.INDENT_PER_LEVEL + M.MARKER_WIDTH
                    append("<w:lvl w:ilvl=\"$l\">")
                    append("<w:start w:val=\"${if (ordered) start else 1}\"/>")
                    append("<w:numFmt w:val=\"${if (ordered) "decimal" else "bullet"}\"/>")
                    append("<w:suff w:val=\"tab\"/>")
                    append("<w:lvlText w:val=\"${if (ordered) "%${l + 1}." else "•"}\"/>")
                    append("<w:lvlJc w:val=\"left\"/>")
                    append("<w:pPr><w:tabs><w:tab w:val=\"num\" w:pos=\"${tw(left)}\"/></w:tabs>")
                    append("<w:ind w:left=\"${tw(left)}\" w:hanging=\"${tw(M.MARKER_WIDTH)}\"/></w:pPr>")
                    append("<w:rPr><w:rFonts w:ascii=\"$FONT\" w:hAnsi=\"$FONT\" w:cs=\"$FONT\"/>")
                    append("<w:b w:val=\"0\"/><w:i w:val=\"0\"/>")
                    append("<w:color w:val=\"${hex(C.MUTED)}\"/>")
                    append("<w:sz w:val=\"${hp(M.BODY)}\"/><w:szCs w:val=\"${hp(M.BODY)}\"/>")
                    append("</w:rPr></w:lvl>")
                }
            }
            fun abstract(id: Int, body: String) = "<w:abstractNum w:abstractNumId=\"$id\">" +
                "<w:nsid w:val=\"${Integer.toHexString(0x1A110000 + id).uppercase()}\"/>" +
                "<w:multiLevelType w:val=\"hybridMultilevel\"/>$body</w:abstractNum>"
            val b = StringBuilder(XML_HEADER).append("<w:numbering xmlns:w=\"$NS_W\">")
            b.append(abstract(0, levels(ordered = false)))
            orderedStarts.forEachIndexed { i, start -> b.append(abstract(i + 1, levels(ordered = true, start = start))) }
            b.append("<w:num w:numId=\"$BULLET_NUM_ID\"><w:abstractNumId w:val=\"0\"/></w:num>")
            for (i in orderedStarts.indices) {
                b.append("<w:num w:numId=\"${i + 2}\"><w:abstractNumId w:val=\"${i + 1}\"/></w:num>")
            }
            return b.append("</w:numbering>").toString()
        }

        private fun settings(): String = XML_HEADER + "<w:settings xmlns:w=\"$NS_W\">" +
            "<w:zoom w:percent=\"100\"/>" +
            "<w:defaultTabStop w:val=\"720\"/>" +
            "<w:characterSpacingControl w:val=\"doNotCompress\"/>" +
            "<w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat>" +
            "</w:settings>"

        private fun fontTable(): String = XML_HEADER + "<w:fonts xmlns:w=\"$NS_W\">" +
            "<w:font w:name=\"$FONT\"><w:altName w:val=\"Arial\"/><w:charset w:val=\"00\"/><w:family w:val=\"swiss\"/><w:pitch w:val=\"variable\"/></w:font>" +
            "<w:font w:name=\"$SYMBOL_FONT\"><w:charset w:val=\"00\"/><w:family w:val=\"swiss\"/><w:pitch w:val=\"variable\"/></w:font>" +
            "</w:fonts>"
    }
}
