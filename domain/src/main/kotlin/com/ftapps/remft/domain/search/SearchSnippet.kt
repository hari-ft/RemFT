package com.ftapps.remft.domain.search

import com.ftapps.remft.domain.Fold

object SearchSnippet {
    const val RADIUS = 32
    private const val ELLIPSIS = "…"
    private val whitespaceRun = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    fun build(original: String, word: String, radius: Int = RADIUS): String {
        val text = whitespaceRun.replace(original, " ").trim()
        val needle = Fold.searchText(word)
        val folded = StringBuilder()
        val sourceAt = mutableListOf<Int>()
        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            val char = String(Character.toChars(code))
            val piece = if (char == " ") " " else Fold.searchText(char)
            repeat(piece.length) { sourceAt.add(offset) }
            folded.append(piece)
            offset += char.length
        }
        val index = if (needle.isEmpty()) -1 else folded.indexOf(needle)
        if (index < 0) {
            return if (text.length <= radius * 2) text else "${snapEnd(text, radius * 2)}$ELLIPSIS"
        }
        val hitStart = sourceAt[index]
        val lastFolded = index + needle.length - 1
        val hitEnd = if (lastFolded + 1 < sourceAt.size) sourceAt[lastFolded + 1] else text.length
        var start = (hitStart - radius).coerceIn(0, text.length)
        var end = (hitEnd + radius).coerceIn(0, text.length)
        if (start > 0) {
            val space = text.indexOf(' ', start)
            start = if (space in 0 until hitStart) space + 1 else hitStart
        }
        if (end < text.length) {
            val space = text.lastIndexOf(' ', end)
            end = if (space >= hitEnd) space else hitEnd
        }
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < text.length) ELLIPSIS else ""
        return "$prefix${text.substring(start, end).trim()}$suffix"
    }

    private fun snapEnd(text: String, max: Int): String {
        val space = text.lastIndexOf(' ', max)
        return (if (space > 0) text.substring(0, space) else text.substring(0, max)).trimEnd()
    }
}
