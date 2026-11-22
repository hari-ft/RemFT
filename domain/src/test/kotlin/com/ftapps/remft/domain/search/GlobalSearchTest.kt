package com.ftapps.remft.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    private val t1 = SearchRecent(GlobalSearchKind.Task, "T1")
    private val f1 = SearchRecent(GlobalSearchKind.File, "F1")
    private val n1 = SearchRecent(GlobalSearchKind.Note, "N1")

    @Test
    fun parseToleratesJunkAndDropsWhatDoesNotParse() {
        assertTrue(SearchRecents.parse(null).isEmpty())
        assertTrue(SearchRecents.parse("not json").isEmpty())
        assertTrue(SearchRecents.parse("{\"kind\":\"task\"}").isEmpty())
        assertEquals(
            listOf(t1, f1),
            SearchRecents.parse(
                "[{\"kind\":\"task\",\"id\":\"T1\"},{\"kind\":\"nope\",\"id\":\"X\"}," +
                    "{\"id\":\"Y\"},7,{\"kind\":\"project\",\"id\":\"P1\"},{\"kind\":\"task\",\"id\":\"\"}," +
                    "{\"kind\":\"file\",\"id\":\"F1\"},{\"kind\":\"task\",\"id\":\"T1\"}]",
            ),
        )
    }

    @Test
    fun parseCapsAtEight() {
        val stored = SearchRecents.encode(List(12) { SearchRecent(GlobalSearchKind.Task, "T$it") })
        assertEquals(SearchRecents.LIMIT, SearchRecents.parse(stored).size)
    }

    @Test
    fun pushDedupesMovesToTheFrontAndCapsAtEight() {
        assertEquals(listOf(f1, t1), SearchRecents.push(listOf(t1, f1), f1))
        val sameIdNote = SearchRecent(GlobalSearchKind.Note, "T1")
        assertEquals(listOf(sameIdNote, t1), SearchRecents.push(listOf(t1), sameIdNote))
        var list = emptyList<SearchRecent>()
        for (i in 0 until 12) list = SearchRecents.push(list, SearchRecent(GlobalSearchKind.Task, "T$i"))
        assertEquals(8, list.size)
        assertEquals("T11", list.first().id)
    }

    @Test
    fun recordOnTopOfAHydratedListKeepsIt() {
        val stored = SearchRecents.parse(SearchRecents.encode(listOf(f1, n1)))
        assertEquals(listOf(t1, f1, n1), SearchRecents.record(stored, t1))
        val same = listOf(t1, f1)
        assertTrue(SearchRecents.record(same, t1) === same)
    }

    @Test
    fun encodeAndParseRoundTrip() {
        val json = SearchRecents.encode(listOf(t1, f1))
        assertEquals("[{\"kind\":\"task\",\"id\":\"T1\"},{\"kind\":\"file\",\"id\":\"F1\"}]", json)
        assertEquals(listOf(t1, f1), SearchRecents.parse(json))
        assertEquals("search.recent", SearchRecents.KEY)
    }

    @Test
    fun aRouteVisitNamesItsEntityAndOnlyAnEntity() {
        assertEquals(SearchRecent(GlobalSearchKind.Task, "X"), SearchRecents.fromLocation("/tasks/X"))
        assertNull(SearchRecents.fromLocation("/tasks/X/history"))
        assertNull(SearchRecents.fromLocation("/projects/X"))
        assertNull(SearchRecents.fromLocation("/files/folder/X"))
        assertNull(SearchRecents.fromLocation("/notes/new"))
        assertNull(SearchRecents.fromLocation("/notes/import"))
        assertNull(SearchRecents.fromLocation("/notes/file"))
        assertEquals(SearchRecent(GlobalSearchKind.Note, "X"), SearchRecents.fromLocation("/notes/X"))
        assertEquals(SearchRecent(GlobalSearchKind.Note, "X"), SearchRecents.fromLocation("/edit-note/X"))
        assertNull(SearchRecents.fromLocation("/home"))
        assertNull(SearchRecents.fromLocation("/settings/general"))
        assertNull(SearchRecents.fromLocation(""))
    }

    private fun item(kind: GlobalSearchKind, id: String) = GlobalSearchItem(kind, id, "Report $id")

    @Test
    fun anEmptyQueryIsNoSearchAtAll() {
        val hits = mapOf(GlobalSearchKind.Task to listOf(SearchHit("T1", SearchTier.Title)))
        assertTrue(GlobalSearch.groups("   ", hits) { kind, shown -> shown.map { item(kind, it.id) } }.isEmpty())
    }

    @Test
    fun groupsComeInAFixedOrderFiveRowsEachWithTheTotal() {
        val hits = mapOf(
            GlobalSearchKind.File to listOf(SearchHit("F1", SearchTier.Title)),
            GlobalSearchKind.Note to listOf(SearchHit("N1", SearchTier.Body)),
            GlobalSearchKind.Task to List(8) { SearchHit("T$it", SearchTier.Title) },
        )
        val groups = GlobalSearch.groups("report", hits) { kind, shown -> shown.map { item(kind, it.id) } }
        assertEquals(GlobalSearchKind.entries.toList(), groups.map { it.kind })
        assertEquals(8, groups.first().total)
        assertEquals(GlobalSearch.GROUP_LIMIT, groups.first().items.size)
        assertEquals(listOf("T0", "T1", "T2", "T3", "T4"), groups.first().items.map { it.id })
    }

    @Test
    fun emptyAndUnresolvedGroupsAreDropped() {
        val hits = mapOf(
            GlobalSearchKind.Task to emptyList(),
            GlobalSearchKind.Note to listOf(SearchHit("gone", SearchTier.Title)),
            GlobalSearchKind.File to listOf(SearchHit("F1", SearchTier.Title)),
        )
        val groups = GlobalSearch.groups("x", hits) { kind, shown ->
            shown.filter { it.id != "gone" }.map { item(kind, it.id) }
        }
        assertEquals(listOf(GlobalSearchKind.File), groups.map { it.kind })
    }

    @Test
    fun contextIsASnippetOnlyForBodyHits() {
        val words = SearchMatch.queryWords("report")
        assertEquals("weekly report draft", GlobalSearch.context(SearchTier.Body, "weekly report draft", words))
        assertNull(GlobalSearch.context(SearchTier.Title, "weekly report draft", words))
        assertNull(GlobalSearch.context(SearchTier.Body, null, words))
    }

    @Test
    fun taskStatusesLeaveCancelledOut() {
        assertTrue("completed" in GlobalSearch.taskStatuses)
        assertTrue("inbox" in GlobalSearch.taskStatuses)
        assertTrue("cancelled" !in GlobalSearch.taskStatuses)
    }

    @Test
    fun recentsDropRowsThatAreGone() {
        val known = mapOf(t1 to item(GlobalSearchKind.Task, "T1"), n1 to item(GlobalSearchKind.Note, "N1"))
        val resolved = GlobalSearch.resolveRecents(listOf(n1, f1, t1)) { known[it] }
        assertEquals(listOf("N1", "T1"), resolved.map { it.id })
    }

    @Test
    fun paletteEntriesShowRecentsWhenEmptyAndACreateRowWhenTyped() {
        val recents = listOf(item(GlobalSearchKind.Task, "T1"))
        assertEquals(listOf(PaletteEntry.Item(recents.single())), GlobalSearch.entries("  ", recents, emptyList()))
        val group = GlobalSearchGroup(GlobalSearchKind.Note, listOf(item(GlobalSearchKind.Note, "N1")), 1)
        assertEquals(
            listOf(PaletteEntry.Item(group.items.single()), PaletteEntry.Create("Pay rent")),
            GlobalSearch.entries(" Pay rent ", recents, listOf(group)),
        )
        assertEquals(listOf(PaletteEntry.Create("Pay rent")), GlobalSearch.entries("Pay rent", recents, emptyList()))
    }
}
