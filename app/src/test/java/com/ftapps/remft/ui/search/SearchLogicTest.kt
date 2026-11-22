package com.ftapps.remft.ui.search

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.search.SearchTier
import com.ftapps.remft.domain.tasks.Task
import com.ftapps.remft.ui.quickadd.QuickAddHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SearchLogicTest {
    private fun task(id: String, title: String, description: String? = null, status: String = "open", due: String? = null) =
        Task(id = id, workspaceId = "w", title = title, description = description, status = status, dueAt = due?.let(Instant::parse))

    @Test
    fun highlightMapsFoldedMatchesBackToTheOriginalText() {
        assertEquals(listOf(0..3), SearchHighlight.ranges("Café time", "cafe"))
        assertEquals(listOf(4..7, 13..16), SearchHighlight.ranges("Pay RENT and rent", "rent"))
        assertEquals(listOf(0..2, 4..7), SearchHighlight.ranges("Pay rent", "rent pay"))
        assertEquals(listOf(0..4), SearchHighlight.ranges("Straße", "strass"))
        assertTrue(SearchHighlight.ranges("Pay rent", "   ").isEmpty())
        assertTrue(SearchHighlight.ranges("", "rent").isEmpty())
    }

    @Test
    fun overlappingMatchesMerge() {
        assertEquals(listOf(0..5), SearchHighlight.ranges("banana", "ana ban"))
    }

    @Test
    fun rankingPutsTitleHitsFirstThenByDueDate() {
        val rows = TaskSearchRanking.rank(
            listOf(
                task("1", "Bank call", description = "rent receipt"),
                task("2", "Pay rent", due = "2026-11-25T04:30:00Z"),
                task("3", "Rent deposit", due = "2026-11-21T04:30:00Z"),
                task("4", "Rent idea", status = "inbox"),
                task("5", "Rent paid", status = "completed"),
                task("6", "Groceries"),
            ),
            "rent",
        )
        assertEquals(listOf("3", "2", "4", "1"), rows.map { it.task.id })
        assertEquals(SearchTier.Body, rows.last().tier)
        assertEquals("rent receipt", rows.last().context)
        assertNull(rows.first().context)
        assertTrue(TaskSearchRanking.rank(listOf(task("1", "Rent")), " ").isEmpty())
    }

    @Test
    fun quickAddHighlightStylesTheRecognisedRanges() {
        val style = SpanStyle(fontWeight = FontWeight.Bold)
        val out = QuickAddHighlight(listOf(0..7, 20..40), style).filter(AnnotatedString("tomorrow pay rent p1")).text
        assertEquals("tomorrow pay rent p1", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(0, out.spanStyles[0].start)
        assertEquals(8, out.spanStyles[0].end)
    }
}
