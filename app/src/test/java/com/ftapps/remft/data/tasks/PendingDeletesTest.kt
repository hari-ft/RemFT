package com.ftapps.remft.data.tasks

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeletesTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val pending = PendingDeletes(scope, window = 5_000, settle = 1_000)

    @Test
    fun scheduleHidesAtOnceAndUndoBringsItBackWithoutCommitting() {
        var commits = 0
        pending.schedule("a") { commits++ }
        assertTrue(pending.isPending("a"))
        assertTrue(pending.undo("a"))
        scope.advanceTimeBy(20_000)
        scope.runCurrent()
        assertEquals(0, commits)
        assertFalse(pending.isPending("a"))
    }

    @Test
    fun commitNowRunsOnceAndHidesTheRowOnlyUntilTheCommitHasSettled() {
        var commits = 0
        pending.schedule("a") { commits++ }
        pending.commitNow("a")
        pending.commitNow("a")
        scope.runCurrent()
        assertEquals(1, commits)
        assertTrue(pending.isPending("a"))
        assertFalse(pending.undo("a"))
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertFalse(pending.isPending("a"))
        assertEquals(emptySet<String>(), pending.ids.value)
    }

    @Test
    fun aCommittedIdIsNotHiddenForeverSoTheSameRowRestoredFromTheBinShows() {
        pending.schedule("a") {}
        scope.advanceTimeBy(5_001)
        scope.runCurrent()
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertFalse(pending.isPending("a"))
        assertEquals(listOf("a"), PendingDeletes.without(listOf("a"), pending.ids.value) { it })
    }

    @Test
    fun theUndoWindowCommitsWhenNothingElseDoes() {
        var commits = 0
        pending.schedule("a") { commits++ }
        scope.advanceTimeBy(4_900)
        scope.runCurrent()
        assertEquals(0, commits)
        scope.advanceTimeBy(200)
        scope.runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun aFailedCommitShowsTheRowAgainAtOnce() {
        pending.schedule("a") { error("disk full") }
        pending.commitNow("a")
        scope.runCurrent()
        assertFalse(pending.isPending("a"))
    }

    @Test
    fun reschedulingTheSameIdCommitsOnlyOnce() {
        var commits = 0
        pending.schedule("a") { commits++ }
        pending.schedule("a") { commits++ }
        scope.advanceTimeBy(11_000)
        scope.runCurrent()
        assertEquals(1, commits)
    }

    @Test
    fun extraIdsStayHiddenWithTheEntryAndComeBackOnUndo() {
        pending.schedule("a", hide = setOf("b", "c")) {}
        assertEquals(setOf("a", "b", "c"), pending.ids.value)
        pending.undo("a")
        assertEquals(emptySet<String>(), pending.ids.value)
    }

    @Test
    fun withoutDropsPendingRows() {
        assertEquals(listOf("b"), PendingDeletes.without(listOf("a", "b"), setOf("a")) { it })
    }
}
