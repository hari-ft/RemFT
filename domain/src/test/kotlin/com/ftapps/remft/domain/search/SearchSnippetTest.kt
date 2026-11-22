package com.ftapps.remft.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetTest {
    @Test
    fun windowsAroundTheFoldedMatch() {
        val body = "In the middle of a long description the café run comes up, and then " +
            "it goes on about other things for a while."
        val snippet = SearchSnippet.build(body, "cafe")
        assertTrue(snippet.contains("café run"))
        assertTrue(snippet.length < body.length)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun newlinesAndRunsOfSpacesDoNotShiftTheWindow() {
        val body = "Shopping list\n\n\n- bread\n- milk\n\n\n\n" +
            "Monday    remember   rental   payment today\n\nlast line"
        val snippet = SearchSnippet.build(body, "rent", radius = 12)
        assertEquals("…remember rental payment…", snippet)
        assertFalse(snippet.contains("\n"))
        assertFalse(snippet.contains("  "))
    }

    @Test
    fun theWindowNeverOpensOrClosesOnHalfAWord() {
        val body = "First line goes here\nsecond line goes there\n" +
            "third line has CAFÉ brewing in it\nfourth line over there"
        val snippet = SearchSnippet.build(body, "cafe", radius = 10)
        assertTrue(snippet.contains("CAFÉ"))
        val words = body.replace("\n", " ").split(" ").toSet()
        for (w in snippet.replace("…", "").split(" ")) assertTrue("fragment \"$w\" in \"$snippet\"", w in words)
    }

    @Test
    fun dottedAndDotlessIFoldOntoTheRightOffsets() {
        val body = "IĞLOO\ntrip planning for\n\nİNDIA office on a kıwı farm day"
        assertTrue(SearchSnippet.build(body, "india", radius = 6).startsWith("…"))
        assertTrue(SearchSnippet.build(body, "india", radius = 6).contains("İNDIA"))
        assertTrue(SearchSnippet.build(body, "kiwi", radius = 4).contains("kıwı"))
        assertTrue(SearchSnippet.build(body, "igloo", radius = 4).startsWith("IĞLOO"))
    }

    @Test
    fun anExpandingFoldKeepsTheHitWhole() {
        val body = "Die Straße\nist lang und die Sonne scheint heute sehr warm"
        val snippet = SearchSnippet.build(body, "sonne", radius = 8)
        assertTrue(snippet.contains("Sonne"))
        assertFalse(snippet.contains("\n"))
        assertTrue(SearchSnippet.build(body, "strasse", radius = 4).contains("Straße"))
    }

    @Test
    fun noHitReturnsTheHeadCutOnAWord() {
        assertEquals("short note", SearchSnippet.build("short\n\nnote", "none"))
        val long = List(30) { "word" }.joinToString("\n")
        val snippet = SearchSnippet.build(long, "none", radius = 10)
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.replace("…", "").split(" ").all { it == "word" })
    }

    @Test
    fun defaultRadiusIsThirtyTwo() {
        val body = "a".repeat(10) + " " + List(20) { "filler" }.joinToString(" ") + " target " +
            List(20) { "filler" }.joinToString(" ")
        val snippet = SearchSnippet.build(body, "target")
        assertTrue(snippet.contains("target"))
        assertTrue(snippet.replace("…", "").length <= "target".length + 2 * SearchSnippet.RADIUS)
    }

    @Test
    fun theContextWordIsTheFirstOneTheTitleDoesNotAlreadyShow() {
        assertEquals("march", SearchSnippet.contextWord(listOf("invoice", "march"), "Invoice for the shop"))
        assertEquals("invoice", SearchSnippet.contextWord(listOf("invoice", "march"), "Groceries"))
        assertEquals("invoice", SearchSnippet.contextWord(listOf("invoice"), "Invoice"))
        assertEquals("cafe", SearchSnippet.contextWord(listOf("rent", "cafe"), "Rent"))
        assertEquals("", SearchSnippet.contextWord(emptyList(), "Rent"))
    }
}
