package com.ftapps.remft.domain.notes.editing

enum class MdTokenKind {
    Plain, Marker, Heading1, Heading2, Heading3, HeadingSmall, Bold, Italic, Code, CodeBlock, Strike, Mark, Link, Quote,
}

data class MdToken(val start: Int, val end: Int, val kind: MdTokenKind, val line: Int)

object SourceTokens {
    private val headingStyles = listOf(
        MdTokenKind.Heading1, MdTokenKind.Heading2, MdTokenKind.Heading3,
        MdTokenKind.HeadingSmall, MdTokenKind.HeadingSmall, MdTokenKind.HeadingSmall,
    )
    private val fence = Regex("^\\s{0,3}(```|~~~)")
    private val heading = Regex("^(\\s{0,3})(#{1,6})(\\s+)")
    private val quote = Regex("^(\\s{0,3}>\\s?)")
    private val list = Regex("^(\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]\\]\\s+)?)")
    private val rule = Regex("^\\s{0,3}([-*_])\\s*(?:\\1\\s*){2,}$")
    private val inline = Regex(
        "(?<img>!\\[[^\\]\\n]*\\]\\([^)\\n]*\\))" +
            "|(?<link>\\[[^\\]\\n]*\\]\\([^)\\n]*\\))" +
            "|(?<code>`[^`\\n]+`)" +
            "|(?<bold>\\*\\*[^\\n]+?\\*\\*|__[^\\n]+?__)" +
            "|(?<strike>~~[^\\n]+?~~)" +
            "|(?<mark>==[^\\n]+?==)" +
            "|(?<italic>\\*[^*\\n]+?\\*|_[^_\\n]+?_)",
    )

    fun scan(text: String): List<MdToken> {
        val tokens = ArrayList<MdToken>()
        if (text.isEmpty()) return tokens
        var offset = 0
        var line = 0
        var inFence = false

        fun emit(start: Int, end: Int, kind: MdTokenKind) {
            if (end > start) tokens.add(MdToken(start, end, kind, line))
        }

        fun inlines(start: Int, end: Int, base: MdTokenKind) {
            var cursor = start
            for (m in inline.findAll(text.substring(start, end))) {
                val mStart = start + m.range.first
                val mEnd = start + m.range.last + 1
                if (mStart < cursor) continue
                emit(cursor, mStart, base)
                fun wrapped(open: Int, close: Int, inner: MdTokenKind) {
                    emit(mStart, mStart + open, MdTokenKind.Marker)
                    emit(mStart + open, mEnd - close, inner)
                    emit(mEnd - close, mEnd, MdTokenKind.Marker)
                }
                val groups = m.groups
                when {
                    groups["img"] != null || groups["link"] != null -> {
                        val open = if (groups["img"] != null) 2 else 1
                        val tail = text.indexOf("](", mStart + open)
                        if (tail == -1 || tail >= mEnd) {
                            emit(mStart, mEnd, MdTokenKind.Marker)
                        } else {
                            emit(mStart, mStart + open, MdTokenKind.Marker)
                            emit(mStart + open, tail, MdTokenKind.Link)
                            emit(tail, mEnd, MdTokenKind.Marker)
                        }
                    }
                    groups["code"] != null -> wrapped(1, 1, MdTokenKind.Code)
                    groups["bold"] != null -> wrapped(2, 2, MdTokenKind.Bold)
                    groups["strike"] != null -> wrapped(2, 2, MdTokenKind.Strike)
                    groups["mark"] != null -> wrapped(2, 2, MdTokenKind.Mark)
                    else -> wrapped(1, 1, MdTokenKind.Italic)
                }
                cursor = mEnd
            }
            emit(cursor, end, base)
        }

        while (offset <= text.length) {
            val br = text.indexOf('\n', offset)
            val lineEnd = if (br == -1) text.length else br
            val content = text.substring(offset, lineEnd)
            if (fence.containsMatchIn(content)) {
                inFence = !inFence
                emit(offset, lineEnd, MdTokenKind.Marker)
            } else if (inFence) {
                emit(offset, lineEnd, MdTokenKind.CodeBlock)
            } else if (rule.matches(content)) {
                emit(offset, lineEnd, MdTokenKind.Marker)
            } else {
                val h = heading.find(content)
                val q = quote.find(content)
                val l = list.find(content)
                when {
                    h != null -> {
                        val markerEnd = offset + h.range.last + 1
                        emit(offset, markerEnd, MdTokenKind.Marker)
                        inlines(markerEnd, lineEnd, headingStyles[h.groupValues[2].length - 1])
                    }
                    q != null -> {
                        emit(offset, offset + q.range.last + 1, MdTokenKind.Marker)
                        inlines(offset + q.range.last + 1, lineEnd, MdTokenKind.Quote)
                    }
                    l != null -> {
                        emit(offset, offset + l.range.last + 1, MdTokenKind.Marker)
                        inlines(offset + l.range.last + 1, lineEnd, MdTokenKind.Plain)
                    }
                    else -> inlines(offset, lineEnd, MdTokenKind.Plain)
                }
            }
            if (br == -1) break
            emit(lineEnd, lineEnd + 1, MdTokenKind.Plain)
            offset = lineEnd + 1
            line += 1
        }
        return tokens
    }

    fun lineOf(text: String, offset: Int): Int {
        var line = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
}
