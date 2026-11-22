package com.ftapps.remft.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteSortTest {
    private fun row(
        id: String,
        title: String,
        pinned: Boolean = false,
        archived: Boolean = false,
        created: String? = null,
        updated: String? = null,
        body: String = "",
    ) = NoteSearchRow(NoteSortKey(id, title, pinned, created, updated), archived, null, null, body)

    private fun ids(rows: List<NoteSearchRow>, filter: NotesFilter, search: String, sort: NoteSortState) =
        NoteListing.arrange(rows, filter, search, sort) { it }.map { it.key.id }

    @Test
    fun parsesAndEncodes() {
        assertEquals(NoteSortState(NoteSortField.Updated, true), NoteSortState.parse(null))
        assertEquals(NoteSortState(NoteSortField.Title, false), NoteSortState.parse("title"))
        assertEquals(NoteSortState(NoteSortField.Created, false), NoteSortState.parse("created:asc"))
        assertEquals(NoteSortState(NoteSortField.Updated, true), NoteSortState.parse("bogus:asc"))
        assertEquals("title:desc", NoteSortState(NoteSortField.Title, true).encode())
        assertEquals(NoteSortState(NoteSortField.Title, false), NoteSortState().select(NoteSortField.Title))
        assertEquals(NoteSortState(NoteSortField.Updated, false), NoteSortState().reversed())
    }

    @Test
    fun pinnedFirstThenChosenOrder() {
        val rows = listOf(
            row("01A", "b", updated = "2026-01-02T00:00:00.000Z"),
            row("01B", "a", pinned = true, updated = "2026-01-01T00:00:00.000Z"),
            row("01C", "c", updated = "2026-01-03T00:00:00.000Z"),
        )
        assertEquals(listOf("01B", "01C", "01A"), ids(rows, NotesFilter.All, "", NoteSortState()))
        assertEquals(listOf("01B", "01A", "01C"), ids(rows, NotesFilter.All, "", NoteSortState(NoteSortField.Title, false)))
    }

    @Test
    fun titleSortFoldsAccents() {
        val rows = listOf(row("1", "Zebra"), row("2", "Élan"), row("3", "apple"))
        assertEquals(listOf("3", "2", "1"), ids(rows, NotesFilter.All, "", NoteSortState(NoteSortField.Title, false)))
    }

    @Test
    fun filtersAndSearchTiers() {
        val rows = listOf(
            row("1", "Groceries", body = "milk eggs"),
            row("2", "Trip", body = "pack groceries"),
            row("3", "Old groceries", archived = true),
            row("4", "Pinned", pinned = true, archived = true),
        )
        assertEquals(listOf("1", "2"), ids(rows, NotesFilter.All, "grocéries", NoteSortState(NoteSortField.Title, false)))
        assertEquals(setOf("3", "4"), ids(rows, NotesFilter.Archived, "", NoteSortState()).toSet())
        assertEquals(emptyList<String>(), ids(rows, NotesFilter.Pinned, "", NoteSortState()))
        assertEquals(listOf("1"), ids(rows, NotesFilter.All, "milk groceries", NoteSortState()))
    }
}
