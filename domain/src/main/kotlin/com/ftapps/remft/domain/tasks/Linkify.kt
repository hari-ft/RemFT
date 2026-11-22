package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.text.Whitespace

data class LinkSegment(val text: String, val url: String?)

object Linkify {
    private val urlPattern = Regex(
        """(https?://[^${Whitespace.CHARS}<>]+|www\.[^${Whitespace.CHARS}<>]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val schemePrefix = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    private const val TRAILING = ".,;:!?'\"”’»"

    private fun trimUrl(url: String): String {
        var out = url
        while (out.isNotEmpty()) {
            val last = out[out.length - 1]
            if (TRAILING.indexOf(last) >= 0) {
                out = out.substring(0, out.length - 1)
                continue
            }
            if (last == ')') {
                val opens = out.count { it == '(' }
                val closes = out.count { it == ')' }
                if (closes > opens) {
                    out = out.substring(0, out.length - 1)
                    continue
                }
            }
            break
        }
        return out
    }

    fun linkUrlOf(url: String): String = if (schemePrefix.containsMatchIn(url)) url else "https://$url"

    fun segments(text: String): List<LinkSegment> {
        val segments = mutableListOf<LinkSegment>()
        var cursor = 0
        for (match in urlPattern.findAll(text)) {
            val url = trimUrl(match.value)
            if (url.isEmpty()) continue
            val start = match.range.first
            val end = start + url.length
            if (start > cursor) segments.add(LinkSegment(text.substring(cursor, start), null))
            segments.add(LinkSegment(url, linkUrlOf(url)))
            cursor = end
        }
        if (cursor < text.length) segments.add(LinkSegment(text.substring(cursor), null))
        return segments
    }
}
