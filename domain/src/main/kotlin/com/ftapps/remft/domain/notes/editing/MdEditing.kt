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

    fun renumberOrderedLists(text: String): String {
        val lines = text.split('\n').toMutableList()
        val counters = HashMap<Int, Int>()
        for (i in lines.indices) {
            val item = parseListItem(lines[i])
            if (item == null || !item.ordered) {
                if (lines[i].isBlank()) continue
                counters.clear()
                continue
            }
            val level = item.indent.length / 2
            counters.keys.removeAll { it > level }
            val next = (counters[level] ?: 0) + 1
            counters[level] = next
            lines[i] = orderedMarker.replaceFirst(item.render(item.content), "$next${item.marker.last()}")
        }
        return lines.joinToString("\n")
    }

    fun enterPressed(text: String, caret: Int): MdEdit? {
        val edit = continueList(text, caret) ?: return null
        return MdEdit(renumberOrderedLists(edit.text), edit.selection)
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
        out = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(out, "\n")
        out = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", options).replace(out) { "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2] + "\n" }
        out = Regex("<(strong|b)[^>]*>(.*?)</\\1>", options).replace(out) { "**${it.groupValues[2]}**" }
        out = Regex("<(em|i)[^>]*>(.*?)</\\1>", options).replace(out) { "*${it.groupValues[2]}*" }
        out = Regex("<code[^>]*>(.*?)</code>", options).replace(out) { "`${it.groupValues[1]}`" }
        out = Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", options).replace(out) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
        out = Regex("<li[^>]*>(.*?)</li>", options).replace(out) { "- ${it.groupValues[1]}\n" }
        out = Regex("</?(ul|ol|p|div)[^>]*>", RegexOption.IGNORE_CASE).replace(out, "\n")
        out = out.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        return Regex("\n{3,}").replace(out, "\n\n").trim()
    }
}
