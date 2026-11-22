package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSortTest {
    private val choices = listOf(
        SortChoice("updated"),
        SortChoice("created"),
        SortChoice("title", descendingByDefault = false),
    )

    @Test
    fun roundTripsFieldAndDirection() {
        val state = SortState("created", descending = false)
        assertEquals("created:asc", state.encode())
        assertEquals(state, SortState.parse("created:asc", choices))
        assertEquals("updated:desc", SortState("updated").encode())
    }

    @Test
    fun anUnknownFieldFallsBackToTheFirstChoiceWithItsOwnDirection() {
        val parsed = SortState.parse("whatever:desc", choices)
        assertEquals("updated", parsed.id)
        assertTrue(parsed.descending)
        val ascFirst = listOf(SortChoice("date", descendingByDefault = false), SortChoice("title"))
        assertFalse(SortState.parse("gone:desc", ascFirst).descending)
    }

    @Test
    fun aBareFieldTakesItsNaturalDirection() {
        assertFalse(SortState.parse("title", choices).descending)
        assertTrue(SortState.parse("updated", choices).descending)
        assertEquals("updated", SortState.parse("", choices).id)
        assertEquals("updated", SortState.parse(null, choices).id)
        assertFalse(SortState.parse("title:sideways", choices).descending)
    }

    @Test
    fun aNewFieldArrivesInItsNaturalDirection() {
        val toTitle = SortState("updated").select(choices[2])
        assertEquals("title", toTitle.id)
        assertFalse(toTitle.descending)
    }

    @Test
    fun rePickingTheFieldInUseKeepsTheDirection() {
        val reversedDate = SortState("updated", descending = false)
        assertEquals(reversedDate, reversedDate.select(choices[0]))
    }

    @Test
    fun reverseFlipsOnlyTheDirection() {
        assertEquals(SortState("title"), SortState("title", descending = false).reversed())
    }

    @Test
    fun ascendingIsTheComparatorAsWritten() {
        val byLength = Comparator<String> { a, b -> a.length.compareTo(b.length) }
        assertEquals(listOf("a", "bb", "ccc"), listOf("ccc", "a", "bb").sortedWith(SortState("x", false).comparator(byLength)))
    }

    @Test
    fun descendingIsTheSameComparatorReadBackwards() {
        val byLength = Comparator<String> { a, b -> a.length.compareTo(b.length) }
        assertEquals(listOf("ccc", "bb", "a"), listOf("a", "ccc", "bb").sortedWith(SortState("x").comparator(byLength)))
    }

    @Test
    fun taskSortChoicesAndKeys() {
        assertEquals(listOf("date", "priority", "title"), TaskSort.choices.map { it.id })
        assertEquals(listOf(false, true, false), TaskSort.choices.map { it.descendingByDefault })
        assertEquals(SortState("date", false), SortState.parse(TaskSort.DEFAULT, TaskSort.choices))
        assertEquals(SortState("date", false), SortState.parse("galaxies:desc", TaskSort.choices))
        assertEquals("idea", TaskSort.titleKey("ıdea"))
        assertEquals(4, TaskSort.priorityRank("urgent"))
        assertEquals(0, TaskSort.priorityRank("unknown"))
    }
}
