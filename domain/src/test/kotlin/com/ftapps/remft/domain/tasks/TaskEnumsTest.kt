package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TaskEnumsTest {
    @Test
    fun statusesKeepTheirStoredSpelling() {
        assertEquals(
            listOf("inbox", "open", "scheduled", "in_progress", "waiting", "completed", "cancelled", "archived"),
            TaskStatus.entries.map { it.db },
        )
        assertEquals(TaskStatus.InProgress, TaskStatus.parse("in_progress"))
        assertNull(TaskStatus.parse("done"))
        assertNull(TaskStatus.parse(null))
    }

    @Test
    fun planningAndTerminalSetsAreDisjoint() {
        assertEquals(listOf("open", "scheduled", "in_progress", "waiting"), TaskStatus.planningDb)
        assertEquals(listOf(TaskStatus.Completed, TaskStatus.Cancelled, TaskStatus.Archived), TaskStatus.terminal)
        assertFalse(TaskStatus.Inbox.isPlanning)
        assertFalse(TaskStatus.Inbox.isTerminal)
        assertTrue(TaskStatus.Waiting.isPlanning)
        assertTrue(TaskStatus.Archived.isTerminal)
        assertTrue(TaskStatus.planning.none { it.isTerminal })
    }

    @Test
    fun priorityRanksAscendAndUnknownIsLowest() {
        assertEquals(listOf("none", "low", "medium", "high", "urgent"), TaskPriority.entries.map { it.db })
        assertEquals(4, TaskPriority.Urgent.rank)
        assertEquals(TaskPriority.None, TaskPriority.parse("galaxy"))
        assertEquals(TaskPriority.High, TaskPriority.parse("high"))
        assertEquals(0, TaskPriority.rankOf("galaxy"))
        assertEquals(0, TaskPriority.rankOf(null))
        assertEquals(2, TaskPriority.rankOf("medium"))
    }

    @Test
    fun taskFlagsFollowStatusAndSeries() {
        val task = Task(id = "T1", workspaceId = "W1", title = "Task")
        assertFalse(task.isCompleted)
        assertFalse(task.isRecurring)
        assertTrue(task.copy(status = "completed").isCompleted)
        assertTrue(task.copy(seriesId = "S1").isRecurring)
        assertNull(task.copy(createdAt = Instant.EPOCH).dueAt)
    }
}
