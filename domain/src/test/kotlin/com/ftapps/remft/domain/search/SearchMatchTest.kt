package com.ftapps.remft.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    private data class Row(val id: String, val title: String, val body: String? = null)

    @Test
    fun queryWordsAreFoldedSplitAndDeduped() {
        assertEquals(listOf("cafe", "run"), SearchMatch.queryWords("  CAFÉ   run cafe "))
        assertTrue(SearchMatch.queryWords("   ").isEmpty())
    }

    @Test
    fun ranksTitleAboveBodyAndFoldsDiacriticsBothWays() {
        val rows = listOf(
            Row("T-title", "Café order"),
            Row("T-body", "Meeting", "do not forget the café run"),
            Row("T-none", "Unrelated task"),
        )
        val hits = SearchMatch.rank(rows, "cafe", { it.title }, { it.body })
        assertEquals(listOf("T-title", "T-body"), hits.map { it.item.id })
        assertEquals(listOf(SearchTier.Title, SearchTier.Body), hits.map { it.tier })
        assertEquals(listOf(0, 2), hits.map { it.tier.rank })
        val reverse = SearchMatch.rank(rows, "CAFÉ", { it.title }, { it.body })
        assertTrue(reverse.any { it.item.id == "T-title" })
    }

    @Test
    fun bodyHitsListedFirstStillRankBelowTitleHits() {
        val rows = listOf(Row("B", "Meeting", "report"), Row("A", "Report"))
        assertEquals(listOf("A", "B"), SearchMatch.rank(rows, "report", { it.title }, { it.body }).map { it.item.id })
    }

    @Test
    fun multiWordQueriesAndTheirWordsOrderFree() {
        val rows = listOf(Row("T1", "Prepare slides", "due monday"), Row("T2", "Watch slides"))
        val both = SearchMatch.rank(rows, "monday slides", { it.title }, { it.body })
        assertEquals(listOf("T1"), both.map { it.item.id })
        assertEquals(SearchTier.Body, both.single().tier)
    }

    @Test
    fun everyWordMustAppear() {
        assertNull(SearchMatch.tier(listOf("pay", "rent"), "Pay the bill"))
        assertEquals(SearchTier.Title, SearchMatch.tier(listOf("rent", "pay"), "Pay rent"))
    }

    @Test
    fun wildcardCharactersAreLiterals() {
        val rows = listOf(Row("T%", "save %20 today"), Row("TX", "no discount"))
        assertEquals(listOf("T%"), SearchMatch.rank(rows, "%20", { it.title }).map { it.item.id })
        assertTrue(SearchMatch.rank(rows, "_", { it.title }).isEmpty())
    }

    @Test
    fun anEmptyQueryRanksNothing() {
        assertTrue(SearchMatch.rank(listOf(Row("A", "x")), "  ", { it.title }).isEmpty())
    }

    @Test
    fun singleFieldEntitiesAreAlwaysTitleTier() {
        val files = listOf(Row("F1", "Invoice_October.PDF"), Row("F2", "notes.txt"))
        val hits = SearchMatch.rank(files, "invoice", { it.title })
        assertEquals(listOf("F1"), hits.map { it.item.id })
        assertTrue(hits.all { it.tier == SearchTier.Title })
    }

    @Test
    fun matchesSearchJoinsFieldsAndTreatsEmptyAsMatchAll() {
        assertTrue(SearchMatch.matchesSearch("", listOf("anything")))
        assertTrue(SearchMatch.matchesSearch("rent pay", listOf("Pay", null, "Rent due")))
        assertFalse(SearchMatch.matchesSearch("rent pay", listOf("Pay", "")))
    }
}
