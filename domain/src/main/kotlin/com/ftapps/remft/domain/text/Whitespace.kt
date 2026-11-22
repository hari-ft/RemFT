package com.ftapps.remft.domain.text

object Whitespace {
    const val CHARS: String = """\t\n\x0B\f\r    -     　﻿"""

    fun isTrimmable(code: Int): Boolean =
        code in 0x09..0x0D || code == 0x20 || code == 0x85 || code == 0xA0 || code == 0x1680 ||
            code in 0x2000..0x200A || code == 0x2028 || code == 0x2029 || code == 0x202F ||
            code == 0x205F || code == 0x3000 || code == 0xFEFF

    fun trim(input: String): String {
        var start = 0
        var end = input.length
        while (start < end && isTrimmable(input[start].code)) start++
        while (end > start && isTrimmable(input[end - 1].code)) end--
        return input.substring(start, end)
    }
}
