package com.ftapps.remft.domain

import com.ftapps.remft.domain.text.Whitespace

object Fold {
    private val table: Map<Int, String> = buildMap {
        put(0x00E7, "c"); put(0x011F, "g"); put(0x015F, "s"); put(0x00F6, "o"); put(0x00FC, "u")
        put(0x00E2, "a"); put(0x00EE, "i"); put(0x00FB, "u")
        listOf(0x00E0, 0x00E1, 0x00E3, 0x00E4, 0x00E5).forEach { put(it, "a") }
        listOf(0x00E8, 0x00E9, 0x00EA, 0x00EB).forEach { put(it, "e") }
        listOf(0x00EC, 0x00ED, 0x00EF).forEach { put(it, "i") }
        put(0x00F1, "n")
        listOf(0x00F2, 0x00F3, 0x00F4, 0x00F5).forEach { put(it, "o") }
        listOf(0x00F9, 0x00FA).forEach { put(it, "u") }
        listOf(0x00FD, 0x00FF).forEach { put(it, "y") }
        put(0x00DF, "ss"); put(0x00E6, "ae"); put(0x00F8, "o"); put(0x0111, "d"); put(0x0142, "l"); put(0x0153, "oe")
        listOf(0x0101, 0x0103, 0x0105).forEach { put(it, "a") }
        listOf(0x0107, 0x0109, 0x010B, 0x010D).forEach { put(it, "c") }
        put(0x010F, "d")
        listOf(0x0113, 0x0115, 0x0117, 0x0119, 0x011B).forEach { put(it, "e") }
        listOf(0x011D, 0x0121, 0x0123).forEach { put(it, "g") }
        listOf(0x0125, 0x0127).forEach { put(it, "h") }
        listOf(0x0129, 0x012B, 0x012D, 0x012F).forEach { put(it, "i") }
        put(0x0135, "j"); put(0x0137, "k")
        listOf(0x013A, 0x013C, 0x013E, 0x0140).forEach { put(it, "l") }
        listOf(0x0144, 0x0146, 0x0148).forEach { put(it, "n") }
        listOf(0x014D, 0x014F, 0x0151).forEach { put(it, "o") }
        listOf(0x0155, 0x0157, 0x0159).forEach { put(it, "r") }
        listOf(0x015B, 0x015D, 0x0161).forEach { put(it, "s") }
        listOf(0x0163, 0x0165, 0x0167).forEach { put(it, "t") }
        listOf(0x0169, 0x016B, 0x016D, 0x016F, 0x0171, 0x0173).forEach { put(it, "u") }
        put(0x0175, "w"); put(0x0177, "y")
        listOf(0x017A, 0x017C, 0x017E).forEach { put(it, "z") }
    }

    private val whitespaceRun = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    fun searchText(input: String): String {
        val lowered = input
            .replace("İ", "i")
            .replace("I", "i")
            .replace("ı", "i")
            .lowercase()
            .replace("̇", "")
        val out = StringBuilder(lowered.length)
        var index = 0
        while (index < lowered.length) {
            val code = lowered.codePointAt(index)
            val mapped = table[code]
            if (mapped != null) out.append(mapped) else out.appendCodePoint(code)
            index += Character.charCount(code)
        }
        val collapsed = whitespaceRun.replace(out, " ")
        var start = 0
        var end = collapsed.length
        while (start < end && Whitespace.isTrimmable(collapsed[start].code)) start++
        while (end > start && Whitespace.isTrimmable(collapsed[end - 1].code)) end--
        return collapsed.substring(start, end)
    }
}
