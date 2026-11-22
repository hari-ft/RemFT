package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.text.Whitespace

object MarkdownPlainText {
    const val MAX_LENGTH = 60000

    private const val S = "[" + Whitespace.CHARS + "]"
    private const val NOT_LINE_END = "[^\\n\\r\\u2028\\u2029]"

    private val ml = setOf(RegexOption.MULTILINE)

    private val fencedBackticks = Regex("""^```[\s\S]*?^```""", ml)
    private val fencedTildes = Regex("""^~~~[\s\S]*?^~~~""", ml)
    private val frontMatter = Regex("""\A---\n[\s\S]*?\n---\n""")
    private val image = Regex("""!\[[^\]]*\]\([^)]*\)""")
    private val link = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val tag = Regex("""<[^>${Whitespace.CHARS}]+>""")
    private val inlineCode = Regex("""`([^`]*)`""")
    private val heading = Regex("""^$S{0,3}#{1,6}$S+""", ml)
    private val quote = Regex("""^$S{0,3}>$S?""", ml)
    private val taskItem = Regex("""^$S*[-*+]$S+\[[ xX]\]$S+""", ml)
    private val bullet = Regex("""^$S*[-*+]$S+""", ml)
    private val numbered = Regex("""^$S*\d+[.)]$S+""", ml)
    private val rule = Regex("""^$S{0,3}([-*_])$S*(?:\1$S*){2,}$""", ml)
    private val emphasis = Regex("""(\*\*|__|~~|==)($NOT_LINE_END*?)\1""")
    private val singleEmphasis = Regex("""(\A|[^\w])[*_]([^*_\n]+)[*_]([^\w]|\z)""")
    private val whitespaceRun = Regex("""$S+""")

    fun from(markdown: String?): String {
        if (markdown.isNullOrEmpty()) return ""
        var text: String = markdown
        text = fencedBackticks.replace(text, " ")
        text = fencedTildes.replace(text, " ")
        text = frontMatter.replaceFirst(text, " ")
        text = image.replace(text, " ")
        text = link.replace(text) { it.groupValues[1] }
        text = tag.replace(text, " ")
        text = inlineCode.replace(text) { it.groupValues[1] }
        text = heading.replace(text, "")
        text = quote.replace(text, "")
        text = taskItem.replace(text, "")
        text = bullet.replace(text, "")
        text = numbered.replace(text, "")
        text = rule.replace(text, " ")
        text = text.replace("|", " ")
        text = emphasis.replace(text) { it.groupValues[2] }
        text = singleEmphasis.replace(text) { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
        text = whitespaceRun.replace(text, " ")
        text = Whitespace.trim(text)
        return if (text.length > MAX_LENGTH) text.substring(0, MAX_LENGTH) else text
    }
}
