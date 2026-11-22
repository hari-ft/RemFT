package com.ftapps.remft.domain

import java.security.SecureRandom

object Ulid {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 26
    private val random = SecureRandom()
    private val pattern = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    fun next(nowMillis: Long = System.currentTimeMillis()): String {
        val chars = CharArray(LENGTH)
        var millis = nowMillis
        for (index in 9 downTo 0) {
            chars[index] = ALPHABET[(millis % 32).toInt()]
            millis /= 32
        }
        for (index in 10 until LENGTH) {
            chars[index] = ALPHABET[random.nextInt(32)]
        }
        return String(chars)
    }

    fun isValid(value: String): Boolean = pattern.matches(value)

    fun timestampMillis(value: String): Long {
        require(isValid(value)) { "not a ULID" }
        var millis = 0L
        for (index in 0 until 10) {
            millis = millis * 32 + ALPHABET.indexOf(value[index])
        }
        return millis
    }
}
