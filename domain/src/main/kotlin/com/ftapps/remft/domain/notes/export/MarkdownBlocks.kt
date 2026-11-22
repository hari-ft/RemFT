package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.notes.markdown.Alert
import com.ftapps.remft.domain.notes.markdown.Emoji
import com.ftapps.remft.domain.notes.markdown.FrontMatter
import com.ftapps.remft.domain.notes.markdown.Highlight
import com.ftapps.remft.domain.notes.markdown.MarkdownParsing
import com.ftapps.remft.domain.notes.markdown.MathBlock
import com.ftapps.remft.domain.notes.markdown.MathInline
import com.ftapps.remft.domain.notes.markdown.children
import com.ftapps.remft.domain.notes.markdown.textContent
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.InlineFootnote
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

object MarkdownBlocks {
    fun parse(markdown: String): List<NoteBlock> = Walker().run(MarkdownParsing.parse(markdown).document)

    private data class Style(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
        val mark: Boolean = false,
        val superscript: Boolean = false,
    )

    private class Walker {
        private val out = ArrayList<NoteBlock>()
        private val figures = HashMap<NoteFigureKind, Int>()
        private val definitions = LinkedHashMap<String, FootnoteDefinition>()
        private val footnoteOrder = ArrayList<String>()

        fun run(document: Document): List<NoteBlock> {
            document.accept(object : AbstractVisitor() {
                override fun visit(customBlock: org.commonmark.node.CustomBlock) {
                    if (customBlock is FootnoteDefinition) {
                        definitions.putIfAbsent(key(customBlock.label), customBlock)
                    }
                    visitChildren(customBlock)
                }
            })
            document.children().forEach { block(it, 0) }
            footnotes()
            while (out.isNotEmpty() && out.last().let { it is NoteBlock.Paragraph && it.text.isBlank() }) {
                out.removeAt(out.lastIndex)
            }
            return out
        }

        private fun key(label: String): String = label.trim().lowercase()

        private fun block(node: Node, depth: Int) {
            when (node) {
                is Heading -> out.add(NoteBlock.Heading(node.level.coerceIn(1, 6), inline(node.children(), Style())))
                is Paragraph -> paragraph(node)
                is Alert -> alert(node)
                is BlockQuote -> node.children().forEach { child ->
                    if (child is Paragraph) {
                        out.add(NoteBlock.Quote(inline(child.children(), Style())))
                    } else {
                        block(child, depth)
                    }
                }
                is FencedCodeBlock -> fenced(node)
                is IndentedCodeBlock -> out.add(NoteBlock.Code(node.literal.orEmpty().removeSuffix("\n")))
                is MathBlock -> figure(NoteFigureKind.Math, node.literal.trim('\n'))
                is ThematicBreak -> out.add(NoteBlock.Divider)
                is ListBlock -> list(node, depth)
                is TableBlock -> table(node)
                is HtmlBlock -> {
                    val literal = node.literal.orEmpty().trimEnd('\n')
                    if (literal.isNotBlank()) out.add(NoteBlock.Paragraph(listOf(NoteSpan(literal))))
                }
                is FrontMatter -> out.add(NoteBlock.FrontMatter(node.literal.trim()))
                is FootnoteDefinition -> Unit
                is LinkReferenceDefinition -> Unit
                is Document -> node.children().forEach { block(it, depth) }
                else -> out.add(NoteBlock.Unsupported)
            }
        }

        private fun paragraph(node: Paragraph) {
            val run = ArrayList<Node>()
            var split = false
            fun flush() {
                if (run.isEmpty()) return
                val spans = inline(run, Style())
                run.clear()
                if (spans.all { it.text.isBlank() }) return
                out.add(NoteBlock.Paragraph(trimEdges(spans)))
            }
            for (child in node.children()) {
                val fileId = (child as? Image)?.let { MarkdownParsing.remftFileId(it.destination) }
                if (child is Image && fileId != null) {
                    flush()
                    out.add(NoteBlock.Image(fileId, child.textContent().trim()))
                    split = true
                    continue
                }
                run.add(child)
            }
            if (!split) {
                out.add(NoteBlock.Paragraph(inline(run, Style())))
                return
            }
            flush()
        }

        private fun trimEdges(spans: List<NoteSpan>): List<NoteSpan> {
            if (spans.isEmpty()) return spans
            val result = spans.toMutableList()
            result[0] = result[0].copy(text = result[0].text.trimStart())
            result[result.lastIndex] = result[result.lastIndex].copy(text = result[result.lastIndex].text.trimEnd())
            return result.filter { it.text.isNotEmpty() }
        }

        private fun fenced(node: FencedCodeBlock) {
            MarkdownParsing.mermaidSource(node)?.let {
                figure(NoteFigureKind.Mermaid, it.removeSuffix("\n"))
                return
            }
            MarkdownParsing.mathFenceSource(node)?.let {
                figure(NoteFigureKind.Math, it.removeSuffix("\n"))
                return
            }
            val language = node.info?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }
            out.add(NoteBlock.Code(node.literal.orEmpty().removeSuffix("\n"), language))
        }

        private fun figure(kind: NoteFigureKind, source: String) {
            val index = figures[kind] ?: 0
            figures[kind] = index + 1
            out.add(NoteBlock.Figure(kind, source, index))
        }

        private fun alert(node: Alert) {
            val spans = ArrayList<NoteSpan>()
            for (child in node.children()) {
                val childSpans = when (child) {
                    is Paragraph -> inline(child.children(), Style())
                    is Heading -> inline(child.children(), Style(bold = true))
                    is FencedCodeBlock -> listOf(NoteSpan(child.literal.orEmpty().removeSuffix("\n"), code = true))
                    is IndentedCodeBlock -> listOf(NoteSpan(child.literal.orEmpty().removeSuffix("\n"), code = true))
                    is ListBlock -> listText(child)
                    else -> child.textContent().takeIf { it.isNotBlank() }?.let { listOf(NoteSpan(it)) }.orEmpty()
                }
                if (childSpans.isEmpty()) continue
                if (spans.isNotEmpty()) spans.add(NoteSpan("\n"))
                spans.addAll(childSpans)
            }
            out.add(NoteBlock.Callout(node.kind, spans))
        }

        private fun listText(list: ListBlock): List<NoteSpan> {
            val spans = ArrayList<NoteSpan>()
            var ordinal = (list as? OrderedList)?.markerStartNumber ?: 1
            for (item in list.children()) {
                if (item !is ListItem) continue
                if (spans.isNotEmpty()) spans.add(NoteSpan("\n"))
                val marker = when (val box = item.children().firstOrNull { it is TaskListItemMarker } as? TaskListItemMarker) {
                    null -> if (list is OrderedList) "${ordinal++}. " else "• "
                    else -> if (box.isChecked) "☑ " else "☐ "
                }
                spans.add(NoteSpan(marker))
                spans.addAll(itemSpans(item))
            }
            return spans
        }

        private fun list(list: ListBlock, depth: Int) {
            val ordered = list is OrderedList
            var ordinal = ((list as? OrderedList)?.markerStartNumber ?: 1) - 1
            for (item in list.children()) {
                if (item !is ListItem) continue
                val box = item.children().firstOrNull { it is TaskListItemMarker } as? TaskListItemMarker
                val kind = when {
                    box != null -> if (box.isChecked) NoteListKind.Checked else NoteListKind.Unchecked
                    ordered -> NoteListKind.Ordered
                    else -> NoteListKind.Bullet
                }
                if (kind == NoteListKind.Ordered) ordinal += 1
                out.add(
                    NoteBlock.ListItem(
                        kind = kind,
                        spans = itemSpans(item),
                        indent = depth,
                        ordinal = if (kind == NoteListKind.Ordered) ordinal else null,
                    ),
                )
                for (child in item.children()) {
                    when (child) {
                        is ListBlock -> list(child, depth + 1)
                        is Paragraph, is TaskListItemMarker -> Unit
                        else -> block(child, depth + 1)
                    }
                }
            }
        }

        private fun itemSpans(item: ListItem): List<NoteSpan> {
            val spans = ArrayList<NoteSpan>()
            for (child in item.children()) {
                if (child !is Paragraph) continue
                if (spans.isNotEmpty()) spans.add(NoteSpan("\n"))
                spans.addAll(inline(child.children(), Style()))
            }
            return spans
        }

        private fun table(node: TableBlock) {
            val rows = ArrayList<List<String>>()
            val alignments = ArrayList<NoteTableAlignment>()
            node.accept(object : AbstractVisitor() {
                override fun visit(customNode: org.commonmark.node.CustomNode) {
                    if (customNode is TableRow) {
                        val cells = customNode.children().filterIsInstance<TableCell>()
                        if (rows.isEmpty()) {
                            cells.forEach { alignments.add(alignmentOf(it.alignment)) }
                        }
                        rows.add(cells.map { cellText(it) })
                        return
                    }
                    visitChildren(customNode)
                }
            })
            if (rows.isNotEmpty()) out.add(NoteBlock.Table(rows, alignments))
        }

        private fun cellText(cell: TableCell): String = inline(cell.children(), Style()).plainText().trim()

        private fun alignmentOf(alignment: TableCell.Alignment?): NoteTableAlignment = when (alignment) {
            TableCell.Alignment.LEFT -> NoteTableAlignment.Left
            TableCell.Alignment.CENTER -> NoteTableAlignment.Center
            TableCell.Alignment.RIGHT -> NoteTableAlignment.Right
            null -> NoteTableAlignment.Default
        }

        private fun footnotes() {
            if (footnoteOrder.isEmpty()) return
            out.add(NoteBlock.Divider)
            var index = 0
            while (index < footnoteOrder.size) {
                val definition = definitions[footnoteOrder[index]]
                index += 1
                val spans = ArrayList<NoteSpan>()
                definition?.children()?.forEach { child ->
                    val childSpans = when (child) {
                        is Paragraph -> inline(child.children(), Style())
                        else -> child.textContent().takeIf { it.isNotBlank() }?.let { listOf(NoteSpan(it)) }.orEmpty()
                    }
                    if (childSpans.isEmpty()) return@forEach
                    if (spans.isNotEmpty()) spans.add(NoteSpan("\n"))
                    spans.addAll(childSpans)
                }
                out.add(NoteBlock.ListItem(NoteListKind.Ordered, spans, 0, index))
            }
        }

        private fun footnoteNumber(label: String): Int? {
            val key = key(label)
            if (!definitions.containsKey(key)) return null
            val existing = footnoteOrder.indexOf(key)
            if (existing >= 0) return existing + 1
            footnoteOrder.add(key)
            return footnoteOrder.size
        }

        private fun inline(nodes: List<Node>, style: Style): List<NoteSpan> {
            val spans = ArrayList<NoteSpan>()
            fun add(text: String, s: Style = style) {
                if (text.isEmpty()) return
                spans.add(NoteSpan(text, s.bold, s.italic, s.strike, s.code, s.link, s.mark, s.superscript))
            }
            for (node in nodes) {
                when (node) {
                    is Text -> add(node.literal.orEmpty())
                    is SoftLineBreak -> add(" ")
                    is HardLineBreak -> add("\n")
                    is StrongEmphasis -> spans.addAll(inline(node.children(), style.copy(bold = true)))
                    is Emphasis -> spans.addAll(inline(node.children(), style.copy(italic = true)))
                    is Strikethrough -> spans.addAll(inline(node.children(), style.copy(strike = true)))
                    is Highlight -> spans.addAll(inline(node.children(), style.copy(mark = true)))
                    is Code -> add(node.literal.orEmpty(), style.copy(code = true))
                    is MathInline -> add(node.literal, style.copy(code = true))
                    is Emoji -> add(node.glyph)
                    is HtmlInline -> add(node.literal.orEmpty())
                    is Link -> {
                        val href = node.destination?.trim()
                        val link = href?.takeIf { MarkdownParsing.isSafeLink(it) }
                        spans.addAll(inline(node.children(), style.copy(link = link ?: style.link)))
                    }
                    is Image -> add(node.textContent())
                    is FootnoteReference -> {
                        val number = footnoteNumber(node.label)
                        if (number == null) {
                            add("[^${node.label}]")
                        } else {
                            add(number.toString(), style.copy(superscript = true, link = "#fn-$number"))
                        }
                    }
                    is InlineFootnote -> spans.addAll(inline(node.children(), style.copy(superscript = true)))
                    is TaskListItemMarker -> Unit
                    else -> spans.addAll(inline(node.children(), style))
                }
            }
            return spans
        }
    }
}
