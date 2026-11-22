package com.ftapps.remft.domain.notes.editing

import com.ftapps.remft.domain.Fold

enum class MdActionId(val slash: String) {
    Bold("/bold"),
    Italic("/italic"),
    Highlight("/highlight"),
    Strike("/strike"),
    Code("/code"),
    Link("/link"),
    H1("/h1"),
    H2("/h2"),
    H3("/h3"),
    Bullet("/list"),
    Numbered("/numbered"),
    Todo("/todo"),
    Quote("/quote"),
    CodeBlock("/codeblock"),
    Table("/table"),
    Divider("/divider"),
}

object MdActions {
    private val fenceLine = Regex("^\\s{0,3}(```|~~~)\\s*[A-Za-z0-9_+-]*\\s*$")

    fun apply(id: MdActionId, text: String, start: Int, end: Int, tableHeader: String = "Heading"): MdEdit {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return when (id) {
            MdActionId.Bold -> wrap(text, s, e, "**", "**")
            MdActionId.Italic -> wrap(text, s, e, "*", "*")
            MdActionId.Highlight -> wrap(text, s, e, "==", "==")
            MdActionId.Strike -> wrap(text, s, e, "~~", "~~")
            MdActionId.Code -> wrap(text, s, e, "`", "`")
            MdActionId.Link -> {
                val inserted = "[${text.substring(s, e)}]()"
                MdEdit(text.replaceRange(s, e, inserted), s + inserted.length - 1)
            }
            MdActionId.H1 -> prefixLines(text, s, e, "# ")
            MdActionId.H2 -> prefixLines(text, s, e, "## ")
            MdActionId.H3 -> prefixLines(text, s, e, "### ")
            MdActionId.Bullet -> prefixLines(text, s, e, "- ")
            MdActionId.Numbered -> {
                val prefixed = prefixLines(text, s, e, "1. ").text
                val renumbered = MdEditing.renumberOrderedListAt(prefixed, s).text
                MdEdit(renumbered, (e + 3).coerceAtMost(renumbered.length))
            }
            MdActionId.Todo -> prefixLines(text, s, e, "- [ ] ")
            MdActionId.Quote -> prefixLines(text, s, e, "> ")
            MdActionId.CodeBlock -> fence(text, s, e)
            MdActionId.Table -> insertBlock(text, e, "| $tableHeader | $tableHeader |\n| --- | --- |\n|  |  |\n")
            MdActionId.Divider -> insertBlock(text, e, "---\n")
        }
    }

    private fun wrap(text: String, start: Int, end: Int, left: String, right: String): MdEdit {
        val selected = text.substring(start, end)
        val inserted = "$left$selected$right"
        return MdEdit(text.replaceRange(start, end, inserted), if (selected.isEmpty()) start + left.length else start + inserted.length)
    }

    private fun prefixLines(text: String, start: Int, end: Int, prefix: String): MdEdit {
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val rawEnd = text.indexOf('\n', end)
        val lineEnd = if (rawEnd < 0) text.length else rawEnd
        val updated = text.substring(lineStart, lineEnd).split('\n').joinToString("\n") { if (it.startsWith(prefix)) it else "$prefix$it" }
        return MdEdit(text.replaceRange(lineStart, lineEnd, updated), lineStart + updated.length)
    }

    private fun insertBlock(text: String, end: Int, block: String): MdEdit {
        val rawEnd = text.indexOf('\n', end)
        val at = if (rawEnd < 0) text.length else rawEnd
        val atLineStart = at == 0 || text[at - 1] == '\n'
        val inserted = if (atLineStart) block else "\n$block"
        return MdEdit(text.replaceRange(at, at, inserted), at + inserted.length)
    }

    private fun fence(text: String, start: Int, end: Int): MdEdit {
        val selected = text.substring(start, end)
        if (selected.isEmpty()) return insertBlock(text, end, "```\n\n```\n")
        val unwrapped = unfence(selected)
        if (unwrapped != null) return MdEdit(text.replaceRange(start, end, unwrapped), start + unwrapped.length)
        val body = if (selected.endsWith("\n")) selected.dropLast(1) else selected
        val inserted = "```\n$body\n```"
        return MdEdit(text.replaceRange(start, end, inserted), start + inserted.length)
    }

    private fun unfence(selected: String): String? {
        val lines = selected.trimEnd().split('\n')
        if (lines.size < 2) return null
        if (!fenceLine.matches(lines.first()) || !fenceLine.matches(lines.last())) return null
        return lines.subList(1, lines.size - 1).joinToString("\n")
    }

    fun match(query: String, label: (MdActionId) -> String = { "" }): List<MdActionId> {
        val trimmed = query.trim()
        if (trimmed.startsWith("/")) {
            val needle = trimmed.lowercase()
            return MdActionId.entries.filter { it.slash.startsWith(needle) }
        }
        val needle = Fold.searchText(trimmed)
        if (needle.isEmpty()) return MdActionId.entries
        return MdActionId.entries.filter { Fold.searchText(label(it)).contains(needle) || Fold.searchText(it.slash).contains(needle) }
    }

    fun matchSlash(typed: String): List<MdActionId> = if (typed.startsWith("/")) match(typed) else emptyList()

    fun slashTokenAt(text: String, caret: Int): String? {
        if (caret <= 0 || caret > text.length) return null
        val upto = text.substring(0, caret)
        val slash = upto.lastIndexOf('/')
        if (slash < 0) return null
        if (slash > 0 && !upto[slash - 1].isWhitespace()) return null
        val token = upto.substring(slash)
        if (token.any { it.isWhitespace() }) return null
        return token
    }

    fun applySlash(id: MdActionId, text: String, caret: Int, tableHeader: String = "Heading"): MdEdit {
        val token = slashTokenAt(text, caret)
        var current = text
        var at = caret
        if (token != null) {
            current = text.replaceRange(caret - token.length, caret, "")
            at = caret - token.length
        }
        return apply(id, current, at, at, tableHeader)
    }
}
