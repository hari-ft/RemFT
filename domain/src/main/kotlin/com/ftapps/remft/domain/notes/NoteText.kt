package com.ftapps.remft.domain.notes

data class TitledMarkdown(val title: String, val body: String)

data class TextCounts(val words: Int, val characters: Int)

object NoteText {
    const val MAX_PLAIN_TEXT = 60_000
    const val SNIPPET_LENGTH = 160
    const val MARKDOWN_MAX_BYTES = 2 * 1024 * 1024

    private val fencedBackticks = Regex("^```[\\s\\S]*?^```", RegexOption.MULTILINE)
    private val fencedTildes = Regex("^~~~[\\s\\S]*?^~~~", RegexOption.MULTILINE)
    private val frontMatter = Regex("^---\\n[\\s\\S]*?\\n---\\n")
    private val image = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val tag = Regex("<[^>\\s]+>")
    private val code = Regex("`([^`]*)`")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)
    private val quote = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
    private val task = Regex("^\\s*[-*+]\\s+\\[[ xX]\\]\\s+", RegexOption.MULTILINE)
    private val bullet = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE)
    private val ordered = Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE)
    private val tableDivider = Regex("^[ \\t]*\\|?(?:[ \\t]*:?-+:?[ \\t]*\\|)+(?:[ \\t]*:?-+:?[ \\t]*)?$", RegexOption.MULTILINE)
    private val rule = Regex("^\\s{0,3}([-*_])\\s*(?:\\1\\s*){2,}$", RegexOption.MULTILINE)
    private val strong = Regex("(\\*\\*|__|~~|==)(.*?)\\1")
    private val emphasis = Regex("(^|[^\\w])[*_]([^*_\\n]+)[*_]([^\\w]|$)")
    private val whitespace = Regex("\\s+")

    fun plainTextFromMarkdown(markdown: String?): String {
        if (markdown.isNullOrEmpty()) return ""
        var text = markdown
        text = fencedBackticks.replace(text, " ")
        text = fencedTildes.replace(text, " ")
        text = frontMatter.replaceFirst(text, " ")
        text = image.replace(text, " ")
        text = link.replace(text) { it.groupValues[1] }
        text = tag.replace(text, " ")
        text = code.replace(text) { it.groupValues[1] }
        text = heading.replace(text, "")
        text = quote.replace(text, "")
        text = task.replace(text, "")
        text = bullet.replace(text, "")
        text = ordered.replace(text, "")
        text = tableDivider.replace(text, " ")
        text = rule.replace(text, " ")
        text = text.replace("|", " ")
        text = strong.replace(text) { it.groupValues[2] }
        text = emphasis.replace(text) { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
        text = whitespace.replace(text, " ").trim()
        return if (text.length > MAX_PLAIN_TEXT) text.substring(0, MAX_PLAIN_TEXT) else text
    }

    fun snippet(plainText: String?): String {
        val text = plainText.orEmpty()
        return if (text.length > SNIPPET_LENGTH) text.substring(0, SNIPPET_LENGTH) else text
    }

    fun savedTitle(raw: String, untitled: String): String = raw.trim().ifEmpty { untitled }

    private val h1 = Regex("^#\\s+(.+)$")

    fun splitMarkdownTitle(markdown: String, fallback: String): TitledMarkdown {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var first = 0
        while (first < lines.size && lines[first].isBlank()) first++
        if (first < lines.size) {
            val match = h1.find(lines[first].trim())
            if (match != null) {
                val rest = lines.subList(first + 1, lines.size).toMutableList()
                while (rest.isNotEmpty() && rest.first().isBlank()) rest.removeAt(0)
                return TitledMarkdown(match.groupValues[1].trim(), rest.joinToString("\n"))
            }
        }
        return TitledMarkdown(fallback, markdown)
    }

    fun fileStem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun countText(text: String): TextCounts {
        val trimmed = text.trim()
        return TextCounts(if (trimmed.isEmpty()) 0 else trimmed.split(whitespace).size, text.length)
    }
}
