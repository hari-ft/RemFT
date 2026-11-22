package com.ftapps.remft.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UlidTest {
    @Test
    fun hasTwentySixCrockfordCharacters() {
        val id = Ulid.next()
        assertEquals(26, id.length)
        assertTrue(id.all { it in Ulid.ALPHABET })
        assertTrue(Ulid.isValid(id))
    }

    @Test
    fun encodesTheTimestampInTheFirstTenCharacters() {
        val millis = 1_760_608_800_123L
        val id = Ulid.next(millis)
        assertEquals(millis, Ulid.timestampMillis(id))
    }

    @Test
    fun sortsByTime() {
        val earlier = Ulid.next(1_000_000L)
        val later = Ulid.next(2_000_000L)
        assertTrue(earlier.substring(0, 10) < later.substring(0, 10))
    }

    @Test
    fun randomPartDiffers() {
        assertNotEquals(Ulid.next(5L).substring(10), Ulid.next(5L).substring(10))
    }

    @Test
    fun rejectsExcludedLetters() {
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAI"))
        assertFalse(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FA"))
        assertTrue(Ulid.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
    }

    @Test
    fun zeroTimestampIsAllZeros() {
        assertEquals("0000000000", Ulid.next(0L).substring(0, 10))
    }
}
