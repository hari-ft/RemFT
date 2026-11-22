package com.ftapps.remft.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.search.GlobalSearch
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.SearchRecent
import com.ftapps.remft.domain.search.SearchRecents
import com.ftapps.remft.domain.search.SearchTier
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestSearch
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SearchDaoTest {
    private val tasks = TestTasks()
    private val search = TestSearch(tasks)

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun create(draft: TaskDraft): String = runBlocking { tasks.repository.create(draft) }

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    @Test
    fun taskSearchRanksTitleHitsAboveBodyHitsAndFiltersStatus() {
        val body = create(TaskDraft(title = "Call bank", description = "Ask about the rent receipt"))
        val late = create(TaskDraft(title = "Pay rent", dueAt = Instant.parse("2026-11-25T04:30:00Z")))
        val early = create(TaskDraft(title = "Rent agreement", dueAt = Instant.parse("2026-11-21T04:30:00Z")))
        create(TaskDraft(title = "Rent captured", status = "inbox"))
        val done = create(TaskDraft(title = "Old rent"))
        runBlocking { tasks.repository.setCompleted(done, true) }

        val hits = io { search.dao.tasks(search.workspace, "RENT", GlobalSearch.homeTaskStatuses) }
        assertEquals(listOf(early, late), hits.take(2).map { it.id })
        assertTrue(hits.dropLast(1).all { it.tier == SearchTier.Title })
        assertEquals(body, hits.last().id)
        assertEquals(SearchTier.Body, hits.last().tier)
        assertEquals(4, hits.size)
        assertFalse(hits.any { it.id == done })

        val all = io { search.dao.tasks(search.workspace, "rent", GlobalSearch.taskStatuses) }
        assertTrue(all.any { it.id == done })
    }

    @Test
    fun everyWordMustAppearAndFoldingApplies() {
        val id = create(TaskDraft(title = "Café visit", description = "bring the Ünïform"))
        create(TaskDraft(title = "Cafe only"))
        val hits = io { search.dao.tasks(search.workspace, "cafe uniform", GlobalSearch.taskStatuses) }
        assertEquals(listOf(id), hits.map { it.id })
        assertEquals(SearchTier.Body, hits.single().tier)
        assertTrue(io { search.dao.tasks(search.workspace, "   ", GlobalSearch.taskStatuses) }.isEmpty())
    }

    @Test
    fun likeWildcardsAreLiteral() {
        create(TaskDraft(title = "Discount 50% off"))
        create(TaskDraft(title = "Discount 50 off"))
        val hits = io { search.dao.tasks(search.workspace, "50%", GlobalSearch.taskStatuses) }
        assertEquals(1, hits.size)
        assertEquals("%a\\_b\\%c\\\\%", SearchDao.pattern("a_b%c\\"))
    }

    @Test
    fun notesSkipArchivedAndFilesSkipUnknownTargets() {
        search.note("n1", "Trip plan", "Pack the passport and tickets")
        search.note("n2", "Passport renewal")
        search.note("n3", "Old passport", archived = true)
        search.file("f1", "passport-scan.pdf")
        search.file("f2", "passport-old.pdf", targetType = "trash")
        val notes = io { search.dao.notes(search.workspace, "passport") }
        assertEquals(listOf("n2", "n1"), notes.map { it.id })
        assertEquals(listOf(SearchTier.Title, SearchTier.Body), notes.map { it.tier })
        val files = io { search.dao.files(search.workspace, "passport") }
        assertEquals(listOf("f1"), files.map { it.id })
    }

    @Test
    fun itemsCarrySnippetsDoneFlagAndMime() {
        val id = create(TaskDraft(title = "Groceries", description = "Remember the oat milk and a loaf of sourdough bread from the corner bakery"))
        runBlocking { tasks.repository.setCompleted(id, true) }
        search.note("n1", "Weekend", "A long walk then sourdough baking class at noon")
        search.file("f1", "sourdough.pdf")
        val groups = runBlocking { search.repository.global("sourdough") }
        assertEquals(listOf(GlobalSearchKind.Task, GlobalSearchKind.Note, GlobalSearchKind.File), groups.map { it.kind })
        val task = groups[0].items.single()
        assertTrue(task.done)
        assertTrue(task.context!!.contains("sourdough"))
        assertTrue(task.context!!.startsWith("…"))
        assertEquals("application/pdf", groups[2].items.single().mime)
        assertNull(groups[2].items.single().context)
    }

    @Test
    fun globalGroupsShowFiveButCountAll() {
        repeat(7) { create(TaskDraft(title = "Invoice $it")) }
        val group = runBlocking { search.repository.global("invoice") }.single()
        assertEquals(5, group.items.size)
        assertEquals(7, group.total)
    }

    @Test
    fun recentsAreRecordedNewestFirstAndResolvedToItems() {
        val id = create(TaskDraft(title = "Renew passport"))
        search.note("n1", "Packing list")
        runBlocking {
            search.recents.record(SearchRecent(GlobalSearchKind.Task, id))
            search.recents.record(SearchRecent(GlobalSearchKind.Note, "n1"))
            search.recents.record(SearchRecent(GlobalSearchKind.Note, "n1"))
            search.recents.record(SearchRecent(GlobalSearchKind.Task, "missing"))
        }
        val stored = runBlocking { search.recents.recents().first() }
        assertEquals(listOf("missing", "n1", id), stored.map { it.id })
        assertTrue(runBlocking { tasks.settings.get(SearchRecents.KEY) }!!.contains("\"kind\":\"note\""))
        val items = runBlocking { search.repository.recentItems(stored) }
        assertEquals(listOf("Packing list", "Renew passport"), items.map { it.title })
    }

    @Test
    fun recentsKeepAtMostEight() {
        runBlocking { repeat(10) { search.recents.record(SearchRecent(GlobalSearchKind.Note, "n$it")) } }
        val stored = runBlocking { search.recents.recents().first() }
        assertEquals(8, stored.size)
        assertEquals("n9", stored.first().id)
    }
}
