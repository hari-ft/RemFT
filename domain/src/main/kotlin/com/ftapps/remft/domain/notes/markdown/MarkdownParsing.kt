package com.ftapps.remft.domain.notes.markdown

import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

class Highlight : CustomNode(), Delimited {
    override fun getOpeningDelimiter(): String = "=="
    override fun getClosingDelimiter(): String = "=="
}

class MathInline(val literal: String) : CustomNode()

class MathBlock : CustomBlock() {
    var literal: String = ""
}

class FrontMatter(val literal: String) : CustomBlock()

enum class AlertKind(val marker: String) {
    Note("NOTE"), Tip("TIP"), Important("IMPORTANT"), Warning("WARNING"), Caution("CAUTION");

    companion object {
        fun of(marker: String): AlertKind? = entries.firstOrNull { it.marker.equals(marker, ignoreCase = true) }
    }
}

class Alert(val kind: AlertKind) : CustomBlock()

class Emoji(val shortcode: String, val glyph: String) : CustomNode()

data class ParsedMarkdown(val document: Document, val lines: List<String>, val frontMatterLines: Int)

object MarkdownParsing {
    val linkSchemes: Set<String> = setOf("http", "https", "mailto", "remft")

    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.builder().requireTwoTildes(true).build(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
        FootnotesExtension.create(),
    )

    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .customBlockParserFactory(MathBlockParser.Factory())
        .customDelimiterProcessor(HighlightDelimiterProcessor())
        .customInlineContentParserFactory(MathInlineParser.Factory())
        .postProcessor(AlertPostProcessor())
        .postProcessor(EmojiPostProcessor())
        .build()

    private val frontMatterFence = Regex("^---\\s*$")

    fun normalizeNewlines(source: String): String = source.replace("\r\n", "\n").replace('\r', '\n')

    fun parse(source: String): ParsedMarkdown {
        val text = normalizeNewlines(source)
        val lines = text.split('\n')
        var frontMatter: String? = null
        var frontMatterLines = 0
        var body = text
        if (lines.isNotEmpty() && frontMatterFence.matches(lines[0])) {
            val close = (1 until lines.size).firstOrNull { frontMatterFence.matches(lines[it]) }
            if (close != null) {
                frontMatter = lines.subList(1, close).joinToString("\n")
                frontMatterLines = close + 1
                body = (List(frontMatterLines) { "" } + lines.drop(frontMatterLines)).joinToString("\n")
            }
        }
        val document = parser.parse(body) as Document
        if (frontMatter != null) document.prependChild(FrontMatter(frontMatter))
        return ParsedMarkdown(document, lines, frontMatterLines)
    }

    fun isSafeLink(href: String?): Boolean {
        val trimmed = href?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(trimmed)?.groupValues?.get(1)
            ?: return !trimmed.contains(':') || trimmed.startsWith("#")
        return scheme.lowercase() in linkSchemes
    }

    fun anchorOf(href: String?): String? {
        val trimmed = href?.trim() ?: return null
        if (!trimmed.startsWith("#") || trimmed.length < 2) return null
        return trimmed.substring(1)
    }

    private val remftFile = Regex("^remft://file/([0-9A-HJKMNP-TV-Z]{26})$", RegexOption.IGNORE_CASE)

    fun remftFileId(src: String?): String? = src?.trim()?.let { remftFile.matchEntire(it) }?.groupValues?.get(1)?.uppercase()

    fun mermaidSource(block: FencedCodeBlock): String? {
        val info = block.info?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
        return if (info == "mermaid") block.literal else null
    }

    fun mathFenceSource(block: FencedCodeBlock): String? {
        val info = block.info?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
        return if (info == "math" || info == "latex" || info == "tex") block.literal else null
    }
}

fun Node.children(): List<Node> {
    val out = ArrayList<Node>()
    var child = firstChild
    while (child != null) {
        out.add(child)
        child = child.next
    }
    return out
}

fun Node.textContent(): String {
    val out = StringBuilder()
    accept(object : AbstractVisitor() {
        override fun visit(text: Text) {
            out.append(text.literal)
        }

        override fun visit(code: Code) {
            out.append(code.literal)
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            out.append(' ')
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            out.append(' ')
        }

        override fun visit(customNode: CustomNode) {
            when (customNode) {
                is MathInline -> out.append(customNode.literal)
                is Emoji -> out.append(customNode.glyph)
                else -> visitChildren(customNode)
            }
        }
    })
    return out.toString()
}

fun Node.startLine(): Int = sourceSpans.firstOrNull()?.lineIndex ?: -1

fun Node.endLine(): Int = sourceSpans.lastOrNull()?.lineIndex ?: startLine()

private class HighlightDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter(): Char = '='
    override fun getClosingCharacter(): Char = '='
    override fun getMinLength(): Int = 2

    override fun process(openingRun: DelimiterRun, closingRun: DelimiterRun): Int {
        if (openingRun.length() < 2 || closingRun.length() < 2) return 0
        val opener = openingRun.opener
        val highlight = Highlight()
        var node = opener.next
        while (node != null && node !== closingRun.closer) {
            val next = node.next
            highlight.appendChild(node)
            node = next
        }
        opener.insertAfter(highlight)
        return 2
    }
}

private class MathInlineParser : InlineContentParser {
    override fun tryParse(inlineParserState: InlineParserState): ParsedInline? {
        val scanner = inlineParserState.scanner()
        scanner.next()
        val display = scanner.next('$')
        val content = StringBuilder()
        while (scanner.hasNext()) {
            val c = scanner.peek()
            if (c == '\n') return ParsedInline.none()
            if (c == '\\') {
                scanner.next()
                if (scanner.peek() == '$') {
                    content.append('$')
                    scanner.next()
                    continue
                }
                content.append('\\')
                continue
            }
            if (c == '$') {
                if (display) {
                    scanner.next()
                    if (scanner.peek() != '$') return ParsedInline.none()
                    scanner.next()
                } else {
                    scanner.next()
                    val after = scanner.peek()
                    if (after in '0'..'9') return ParsedInline.none()
                }
                val literal = content.toString()
                if (literal.isEmpty() || literal.first().isWhitespace() || literal.last().isWhitespace()) {
                    return ParsedInline.none()
                }
                return ParsedInline.of(MathInline(literal), scanner.position())
            }
            content.append(c)
            scanner.next()
        }
        return ParsedInline.none()
    }

    class Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')
        override fun create(): InlineContentParser = MathInlineParser()
    }
}

private class MathBlockParser : AbstractBlockParser() {
    private val block = MathBlock()
    private val content = ArrayList<String>()
    private var closed = false

    override fun getBlock(): Block = block

    override fun tryContinue(parserState: ParserState): BlockContinue? {
        if (closed) return BlockContinue.none()
        val line = parserState.line.content.toString()
        if (fence.matches(line)) {
            closed = true
            return BlockContinue.finished()
        }
        return BlockContinue.atIndex(parserState.index)
    }

    private var opened = false

    override fun addLine(line: SourceLine) {
        if (!opened) {
            opened = true
            return
        }
        content.add(line.content.toString())
    }

    override fun closeBlock() {
        block.literal = content.joinToString("\n")
    }

    companion object {
        val fence = Regex("^ {0,3}\\$\\$\\s*$")
    }

    class Factory : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val line = state.line.content.substring(state.nextNonSpaceIndex)
            if (!fence.matches(line)) return BlockStart.none()
            return BlockStart.of(MathBlockParser()).atIndex(state.line.content.length)
        }
    }
}

private class AlertPostProcessor : PostProcessor {
    private val marker = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\\]\\s*$", RegexOption.IGNORE_CASE)

    override fun process(node: Node): Node {
        val quotes = ArrayList<BlockQuote>()
        node.accept(object : AbstractVisitor() {
            override fun visit(blockQuote: BlockQuote) {
                quotes.add(blockQuote)
                visitChildren(blockQuote)
            }
        })
        quotes.forEach(::convert)
        return node
    }

    private fun convert(quote: BlockQuote) {
        val paragraph = quote.firstChild as? Paragraph ?: return
        val first = paragraph.firstChild as? Text ?: return
        val match = marker.matchEntire(first.literal.trimEnd()) ?: return
        val after = first.next
        if (after != null && after !is SoftLineBreak && after !is HardLineBreak) return
        val kind = AlertKind.of(match.groupValues[1]) ?: return
        first.unlink()
        after?.unlink()
        if (paragraph.firstChild == null) paragraph.unlink()
        val alert = Alert(kind)
        alert.sourceSpans = quote.sourceSpans
        var child = quote.firstChild
        while (child != null) {
            val next = child.next
            alert.appendChild(child)
            child = next
        }
        quote.insertBefore(alert)
        quote.unlink()
    }
}

private class EmojiPostProcessor : PostProcessor {
    private val shortcode = Regex(":([a-z0-9_+\\-]+):")

    override fun process(node: Node): Node {
        val texts = ArrayList<Text>()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                if (text.literal.indexOf(':') >= 0 && !insideLinkOrCode(text)) texts.add(text)
            }
        })
        texts.forEach(::expand)
        return node
    }

    private fun insideLinkOrCode(node: Node): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent is Link) return true
            parent = parent.parent
        }
        return false
    }

    private fun expand(text: Text) {
        val literal = text.literal
        var last = 0
        var anchor: Node = text
        var changed = false
        for (match in shortcode.findAll(literal)) {
            val glyph = EmojiShortcodes.lookup(match.groupValues[1]) ?: continue
            changed = true
            if (match.range.first > last) {
                val before = Text(literal.substring(last, match.range.first))
                anchor.insertAfter(before)
                anchor = before
            }
            val emoji = Emoji(match.groupValues[1], glyph)
            anchor.insertAfter(emoji)
            anchor = emoji
            last = match.range.last + 1
        }
        if (!changed) return
        if (last < literal.length) anchor.insertAfter(Text(literal.substring(last)))
        text.unlink()
    }
}
