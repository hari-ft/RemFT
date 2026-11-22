package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HomeGroupingTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant()

    private fun task(
        id: String,
        dueAt: Instant? = null,
        priority: String = "none",
        status: String = "open",
        title: String? = null,
        sortOrder: Int = 0,
    ) = Task(
        id = id,
        workspaceId = "W1",
        title = title ?: id,
        status = status,
        priority = priority,
        sortOrder = sortOrder,
        dueAt = dueAt,
    )

    private val now = LocalDateTime.of(2026, 7, 14, 9, 0)

    private fun todayIds(sort: SortState?, tasks: List<Task>): List<String> =
        HomeGrouping.group(tasks, now, zone, sort = sort).single { it.bucket == HomeBucket.Today }.tasks.map { it.id }

    @Test
    fun priorityPutsUrgentFirstInsideTheDay() {
        val ids = todayIds(
            SortState("priority"),
            listOf(
                task("low", dueAt = at(2026, 7, 14, 10)),
                task("urgent", priority = "urgent", dueAt = at(2026, 7, 14, 18)),
                task("medium", priority = "medium", dueAt = at(2026, 7, 14, 12)),
            ),
        )
        assertEquals(listOf("urgent", "medium", "low"), ids)
    }

    @Test
    fun aFinishedTaskStillSinksWhateverTheOrder() {
        val ids = todayIds(
            SortState("priority"),
            listOf(
                task("done-urgent", priority = "urgent", status = "completed", dueAt = at(2026, 7, 14, 10)),
                task("open-low", dueAt = at(2026, 7, 14, 11)),
            ),
        )
        assertEquals(listOf("open-low", "done-urgent"), ids)
    }

    @Test
    fun titleFoldsBeforeComparing() {
        val ids = todayIds(
            SortState("title", descending = false),
            listOf(
                task("a", title = "Ivy", dueAt = at(2026, 7, 14, 10)),
                task("b", title = "ıdea", dueAt = at(2026, 7, 14, 11)),
            ),
        )
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun reversingTheDateOrderReversesIt() {
        val tasks = listOf(task("early", dueAt = at(2026, 7, 14, 9)), task("late", dueAt = at(2026, 7, 14, 18)))
        assertEquals(listOf("early", "late"), todayIds(SortState("date", descending = false), tasks))
        assertEquals(listOf("late", "early"), todayIds(SortState("date"), tasks))
    }

    @Test
    fun noPreferenceMeansChronological() {
        val tasks = listOf(
            task("late", dueAt = at(2026, 7, 14, 18)),
            task("early", priority = "urgent", dueAt = at(2026, 7, 14, 9, 30)),
        )
        assertEquals(listOf("early", "late"), todayIds(null, tasks))
        assertEquals(listOf("early", "late"), todayIds(SortState.parse("galaxies:desc", TaskSort.choices), tasks))
        assertEquals(listOf("early", "late"), todayIds(SortState("unknown"), tasks))
    }

    @Test
    fun datelessTasksKeepTheirManualOrderAndSinkBelowDated() {
        val order = HomeGrouping.order(null)
        val sorted = listOf(
            task("b", sortOrder = 2),
            task("dated", dueAt = at(2026, 7, 14, 10)),
            task("a", sortOrder = 1),
        ).sortedWith(order)
        assertEquals(listOf("dated", "a", "b"), sorted.map { it.id })
    }

    @Test
    fun eachDayOfThisWeekGetsItsOwnHeadingInDayOrder() {
        val groups = HomeGrouping.group(
            listOf(task("sat", dueAt = at(2026, 7, 18, 9)), task("thu", dueAt = at(2026, 7, 16, 9))),
            now,
            zone,
        ).filter { it.bucket == HomeBucket.ThisWeek }
        assertEquals(2, groups.size)
        assertEquals(listOf(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 18)), groups.map { it.day })
        assertEquals(listOf("thu", "sat"), groups.map { it.tasks.single().id })
    }

    @Test
    fun aDayWithNothingOnItGetsNoHeading() {
        val groups = HomeGrouping.group(
            listOf(task("thu", dueAt = at(2026, 7, 16, 9)), task("thu2", dueAt = at(2026, 7, 16, 17))),
            now,
            zone,
        ).filter { it.bucket == HomeBucket.ThisWeek }
        assertEquals(1, groups.size)
        assertEquals(LocalDate.of(2026, 7, 16), groups.single().day)
        assertEquals(2, groups.single().tasks.size)
    }

    @Test
    fun theSplitLeavesTheBucketOrderAlone() {
        val groups = HomeGrouping.group(
            listOf(
                task("today", dueAt = at(2026, 7, 14, 18)),
                task("tomorrow", dueAt = at(2026, 7, 15, 9)),
                task("thu", dueAt = at(2026, 7, 16, 9)),
                task("sat", dueAt = at(2026, 7, 18, 9)),
                task("far", dueAt = at(2026, 8, 5, 9)),
            ),
            now,
            zone,
        )
        assertEquals(
            listOf(HomeBucket.Today, HomeBucket.Tomorrow, HomeBucket.ThisWeek, HomeBucket.ThisWeek, HomeBucket.Next30Days),
            groups.map { it.bucket },
        )
        assertNull(groups.last().day)
    }

    @Test
    fun everyDayOfTheWeekRecedesWhileADayIsSelected() {
        val groups = HomeGrouping.group(
            listOf(task("thu", dueAt = at(2026, 7, 16, 9)), task("sat", dueAt = at(2026, 7, 18, 9))),
            now,
            zone,
            selectedDay = LocalDate.of(2026, 7, 14),
        ).filter { it.bucket == HomeBucket.ThisWeek }
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all { it.dimmed })
    }

    @Test
    fun overdueNoDateAndTodayNeverDimAndTheSelectedDayComesFirst() {
        val groups = HomeGrouping.group(
            listOf(
                task("overdue", dueAt = at(2026, 7, 10, 9)),
                task("undated"),
                task("today", dueAt = at(2026, 7, 14, 18)),
                task("tomorrow", dueAt = at(2026, 7, 15, 9)),
                task("picked", dueAt = at(2026, 7, 20, 9)),
            ),
            now,
            zone,
            selectedDay = LocalDate.of(2026, 7, 20),
        )
        assertEquals(
            listOf(HomeBucket.SelectedDay, HomeBucket.Overdue, HomeBucket.NoDate, HomeBucket.Today, HomeBucket.Tomorrow),
            groups.map { it.bucket },
        )
        assertEquals(listOf(false, false, false, false, true), groups.map { it.dimmed })
        assertEquals("picked", groups.first().tasks.single().id)
    }

    @Test
    fun withoutASelectionNothingDims() {
        val groups = HomeGrouping.group(listOf(task("tomorrow", dueAt = at(2026, 7, 15, 9))), now, zone)
        assertFalse(groups.single().dimmed)
    }

    @Test
    fun theHorizonDropsFarDaysUnlessSelected() {
        val far = task("far", dueAt = at(2026, 8, 14, 9))
        val edge = task("edge", dueAt = at(2026, 8, 13, 9))
        val groups = HomeGrouping.group(listOf(far, edge), now, zone)
        assertEquals(listOf("edge"), groups.flatMap { it.tasks }.map { it.id })
        val selected = HomeGrouping.group(listOf(far), now, zone, selectedDay = LocalDate.of(2026, 8, 14))
        assertEquals(HomeBucket.SelectedDay, selected.single().bucket)
    }

    @Test
    fun aCompletedTaskSinksToTheEndOfItsOwnGroup() {
        val groups = HomeGrouping.group(
            listOf(
                task("Wrapped up", dueAt = at(2026, 7, 28, 9), status = "completed"),
                task("Still pending", dueAt = at(2026, 7, 28, 17)),
            ),
            LocalDateTime.of(2026, 7, 28, 12, 0),
            zone,
        )
        val today = groups.first { it.bucket == HomeBucket.Today }
        assertEquals(listOf("Still pending", "Wrapped up"), today.tasks.map { it.title })
    }

    @Test
    fun calendarDotsIgnoreCompletedWork() {
        val due = at(2026, 7, 28, 9)
        assertTrue(HomeGrouping.daysWithTasks(listOf(task("done", dueAt = due, status = "completed")), zone).isEmpty())
        assertEquals(setOf(LocalDate.of(2026, 7, 28)), HomeGrouping.daysWithTasks(listOf(task("open", dueAt = due), task("undated")), zone))
    }

    @Test
    fun planDayIsTheDueDayOrNull() {
        assertEquals(LocalDate.of(2030, 6, 11), HomeGrouping.planDay(task("T1", dueAt = at(2030, 6, 11, 9)), zone))
        assertNull(HomeGrouping.planDay(task("T1"), zone))
    }

    @Test
    fun overdueGoesByTheDeadlineAlone() {
        val today = LocalDate.of(2030, 6, 10)
        assertFalse(HomeGrouping.isOverdueOn(task("T1", dueAt = at(2030, 6, 12)), today, zone))
        assertTrue(HomeGrouping.isOverdueOn(task("T1", dueAt = at(2030, 6, 9, 23, 59)), today, zone))
        assertFalse(HomeGrouping.isOverdueOn(task("T1"), today, zone))
        assertFalse(HomeGrouping.isOverdueOn(task("T1", dueAt = at(2030, 6, 10, 8)), today, zone))
    }

    @Test
    fun localDayReadsTheZone() {
        val instant = Instant.parse("2030-06-10T20:00:00Z")
        assertEquals(LocalDate.of(2030, 6, 11), HomeGrouping.localDay(instant, zone))
        assertEquals(LocalDate.of(2030, 6, 10), HomeGrouping.localDay(instant, ZoneId.of("UTC")))
    }
}
