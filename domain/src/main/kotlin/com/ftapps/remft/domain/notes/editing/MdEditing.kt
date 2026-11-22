package com.ftapps.remft.domain.notes.editing

data class MdEdit(val text: String, val selection: Int)

data class MdListItem(
    val indent: String,
    val marker: String,
    val content: String,
    val ordered: Boolean,
    val checkbox: String? = null,
) {
    val isEmpty: Boolean get() = content.isBlank()

    val number: Int get() = if (ordered) marker.dropLast(1).toIntOrNull() ?: 1 else 0

    fun render(body: String): String {
        val box = if (checkbox == null) "" else "[$checkbox] "
        return "$indent$marker $box$body"
    }
}

object MdEditing {
    private val listLine = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(?:\\[([ xX])\\]\\s+)?(.*)$")
    private val orderedMarker = Regex("\\d+[.)]")

    fun parseListItem(line: String): MdListItem? {
        val match = listLine.find(line) ?: return null
        val marker = match.groupValues[2]
        return MdListItem(
            indent = match.groupValues[1],
            marker = marker,
            checkbox = match.groups[3]?.value?.lowercase(),
            content = match.groupValues[4],
            ordered = marker.first().isDigit(),
        )
    }

    private fun lineStart(text: String, caret: Int): Int =
        text.lastIndexOf('\n', if (caret == 0) 0 else caret - 1).let { if (caret == 0) 0 else it + 1 }

    private fun lineEnd(text: String, caret: Int): Int = text.indexOf('\n', caret).let { if (it < 0) text.length else it }

    fun continueList(text: String, caret: Int): MdEdit? {
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        val line = text.substring(start, end)
        val item = parseListItem(line) ?: return null
        if (item.isEmpty) {
            val before = text.substring(0, start)
            val after = text.substring(start + line.length)
            return MdEdit("$before\n$after", start + 1)
        }
        if (caret - start <= contentStart(line)) {
            return MdEdit(text.substring(0, start) + "\n" + text.substring(start), caret + 1)
        }
        val marker = if (item.ordered) "${item.number + 1}${item.marker.last()}" else item.marker
        val box = if (item.checkbox == null) "" else "[ ] "
        val inserted = "\n${item.indent}$marker $box"
        return MdEdit(text.substring(0, caret) + inserted + text.substring(caret), caret + inserted.length)
    }

    fun indentListItem(text: String, caret: Int, outdent: Boolean): MdEdit? {
        val start = lineStart(text, caret)
        val end = lineEnd(text, caret)
        val line = text.substring(start, end)
        if (parseListItem(line) == null) return null
        val next: String
        val shift: Int
        if (outdent) {
            if (!line.startsWith("  ")) return null
            next = line.substring(2)
            shift = -2
        } else {
            next = "  $line"
            shift = 2
        }
        val updated = text.replaceRange(start, end, next)
        return MdEdit(updated, (caret + shift).coerceIn(start, updated.length))
    }

    private fun contentStart(line: String): Int {
        val match = listLine.find(line) ?: return 0
        return match.groups[4]?.range?.first ?: line.length
    }

    private val fenceOpen = Regex("^(\\s*)(`{3,}|~{3,})")

    private fun indentWidth(line: String): Int {
        var width = 0
        for (c in line) {
            when (c) {
                ' ' -> width++
                '\t' -> width += 4 - width % 4
                else -> return width
            }
        }
        return width
    }

    private enum class LineKind { Item, Continuation, Blank, Fenced, Outside }

    private fun classify(lines: List<String>): List<LineKind> {
        val kinds = ArrayList<LineKind>(lines.size)
        var fence: String? = null
        var fenceIndented = false
        for (line in lines) {
            val open = fenceOpen.find(line)
            if (fence != null) {
                kinds += if (fenceIndented) LineKind.Fenced else LineKind.Outside
                if (open != null && open.groupValues[2].first() == fence.first() && open.groupValues[2].length >= fence.length &&
                    line.substring(open.range.last + 1).isBlank()
                ) {
                    fence = null
                }
                continue
            }
            if (open != null) {
                fence = open.groupValues[2]
                fenceIndented = open.groupValues[1].isNotEmpty()
                kinds += if (fenceIndented) LineKind.Fenced else LineKind.Outside
                continue
            }
            kinds += when {
                line.isBlank() -> LineKind.Blank
                parseListItem(line) != null -> LineKind.Item
                line.first().isWhitespace() -> LineKind.Continuation
                else -> LineKind.Outside
            }
        }
        return kinds
    }

    private class ListLevel(val indent: Int, val ordered: Boolean, val delimiter: Char, var next: Int)

    fun renumberOrderedListAt(text: String, caret: Int): MdEdit {
        val at = caret.coerceIn(0, text.length)
        val lines = text.split('\n').toMutableList()
        val offsets = IntArray(lines.size)
        for (i in 1 until lines.size) offsets[i] = offsets[i - 1] + lines[i - 1].length + 1
        val kinds = classify(lines)
        val target = text.substring(0, at).count { it == '\n' }
        if (kinds[target] == LineKind.Outside) return MdEdit(text, at)
        var from = target
        while (from > 0 && kinds[from - 1] != LineKind.Outside) from--
        var to = target
        while (to < lines.lastIndex && kinds[to + 1] != LineKind.Outside) to++
        val levels = ArrayList<ListLevel>()
        var shift = 0
        for (i in from..to) {
            if (kinds[i] != LineKind.Item) continue
            val line = lines[i]
            val item = parseListItem(line) ?: continue
            val indent = indentWidth(line)
            while (levels.isNotEmpty() && levels.last().indent > indent) levels.removeAt(levels.lastIndex)
            val delimiter = item.marker.last()
            val top = levels.lastOrNull()
            val number = if (top != null && top.indent == indent && top.ordered && item.ordered && top.delimiter == delimiter) {
                top.next
            } else {
                if (top != null && top.indent == indent) levels.removeAt(levels.lastIndex)
                levels += ListLevel(indent, item.ordered, delimiter, item.number)
                item.number
            }
            levels.last().next = number + 1
            if (!item.ordered || number == item.number) continue
            val digitsStart = item.indent.length
            val digitsEnd = digitsStart + item.marker.length - 1
            val updated = line.replaceRange(digitsStart, digitsEnd, number.toString())
            if (i < target || (i == target && at - offsets[i] >= digitsEnd)) shift += updated.length - line.length
            lines[i] = updated
        }
        val result = lines.joinToString("\n")
        return MdEdit(result, (at + shift).coerceIn(0, result.length))
    }

    fun enterPressed(text: String, caret: Int): MdEdit? {
        val edit = continueList(text, caret) ?: return null
        return renumberOrderedListAt(edit.text, edit.selection)
    }

    fun newlineTyped(before: String, after: String, caretAfter: Int): MdEdit? {
        if (after.length != before.length + 1 || caretAfter < 1 || caretAfter > after.length) return null
        if (after[caretAfter - 1] != '\n') return null
        val caretBefore = caretAfter - 1
        if (after.substring(0, caretBefore) != before.substring(0, caretBefore)) return null
        if (after.substring(caretAfter) != before.substring(caretBefore)) return null
        return enterPressed(before, caretBefore)
    }

    private val pastableUrl = Regex("^(https?://|mailto:)\\S+$")

    fun isPastableUrl(text: String): Boolean = pastableUrl.matches(text.trim())

    fun pasteOverSelection(text: String, start: Int, end: Int, pasted: String): MdEdit {
        val selected = text.substring(start, end)
        val insert = if (selected.isNotEmpty() && isPastableUrl(pasted)) "[$selected](${pasted.trim()})" else pasted
        return MdEdit(text.replaceRange(start, end, insert), start + insert.length)
    }

    fun htmlToMarkdown(html: String): String {
        val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        var out = html
        out = Regex("<!--.*?-->", options).replace(out, "")
        out = Regex("<(head|style|script|title)\\b[^>]*>.*?</\\1\\s*>", options).replace(out, "")
        out = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE).replace(out, "\n")
        out = Regex("<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>", options).replace(out) { "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2] + "\n" }
        out = Regex("<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>", options).replace(out) { "**${it.groupValues[2]}**" }
        out = Regex("<(em|i)\\b[^>]*>(.*?)</\\1\\s*>", options).replace(out) { "*${it.groupValues[2]}*" }
        out = Regex("<code\\b[^>]*>(.*?)</code\\s*>", options).replace(out) { "`${it.groupValues[1]}`" }
        out = Regex("<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a\\s*>", options).replace(out) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
        out = Regex("<li\\b[^>]*>(.*?)</li\\s*>", options).replace(out) { "- ${it.groupValues[1]}\n" }
        out = Regex("</?(ul|ol|p|div)\\b[^>]*>", RegexOption.IGNORE_CASE).replace(out, "\n")
        out = Regex("<[^>]*>").replace(out, "")
        out = out.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return Regex("\n{3,}").replace(out, "\n\n").trim()
    }
}
