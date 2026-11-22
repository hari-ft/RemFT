package com.ftapps.remft.domain.notes.editing

object FindReplace {
    fun matches(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val hay = haystack.lowercase()
        val pin = needle.lowercase()
        if (hay.length != haystack.length || pin.length != needle.length) return exactMatches(haystack, needle)
        val hits = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = hay.indexOf(pin, from)
            if (at < 0) break
            hits.add(at)
            from = at + pin.length
        }
        return hits
    }

    private fun exactMatches(haystack: String, needle: String): List<Int> {
        val hits = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            hits.add(at)
            from = at + needle.length
        }
        return hits
    }

    fun replaceAll(haystack: String, needle: String, replacement: String): String {
        val hits = matches(haystack, needle)
        if (hits.isEmpty()) return haystack
        val out = StringBuilder()
        var cursor = 0
        for (at in hits) {
            out.append(haystack, cursor, at).append(replacement)
            cursor = at + needle.length
        }
        out.append(haystack.substring(cursor))
        return out.toString()
    }

    fun replaceAt(haystack: String, at: Int, length: Int, replacement: String): MdEdit {
        val start = at.coerceIn(0, haystack.length)
        val end = (at + length).coerceIn(start, haystack.length)
        return MdEdit(haystack.replaceRange(start, end, replacement), start + replacement.length)
    }
}
