package com.ftapps.remft.domain.notes.editing

import org.junit.Assert.assertEquals
import org.junit.Test

class FindReplaceTest {
    @Test
    fun findsCaseInsensitiveNonOverlapping() {
        assertEquals(listOf(0, 4, 8), FindReplace.matches("Cat cat CAT", "cat"))
        assertEquals(listOf(0, 2), FindReplace.matches("aaaa", "aa"))
        assertEquals(emptyList<Int>(), FindReplace.matches("abc", ""))
    }

    @Test
    fun replacesAllAndOne() {
        assertEquals("dog dog dog", FindReplace.replaceAll("Cat cat CAT", "cat", "dog"))
        assertEquals("abc", FindReplace.replaceAll("abc", "x", "y"))
        assertEquals(MdEdit("dog cat", 3), FindReplace.replaceAt("cat cat", 0, 3, "dog"))
    }
}
