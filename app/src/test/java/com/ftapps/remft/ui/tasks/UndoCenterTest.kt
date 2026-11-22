package com.ftapps.remft.ui.tasks

import com.ftapps.remft.data.tasks.PendingDeletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UndoCenterTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val pending = PendingDeletes(scope, window = 5_000, settle = 1_000)
    private val undo = UndoCenter(pending)

    @Test
    fun theRequestOutlivesItsHostAndOnlyTheWindowCommitsIt() {
        var commits = 0
        undo.post("a", "deleted") { commits++ }
        scope.advanceTimeBy(4_000)
        scope.runCurrent()
        assertEquals(0, commits)
        assertNotNull(undo.current.value)
        scope.advanceTimeBy(1_100)
        scope.runCurrent()
        assertEquals(1, commits)
        assertNull(undo.current.value)
    }

    @Test
    fun undoWithinTheWindowCancelsTheCommit() {
        var commits = 0
        undo.post("a", "deleted") { commits++ }
        assertTrue(undo.resolve(undo.current.value!!, undo = true))
        scope.advanceTimeBy(20_000)
        scope.runCurrent()
        assertEquals(0, commits)
        assertNull(undo.current.value)
    }

    @Test
    fun aNewDeleteCommitsThePreviousOne() {
        val done = mutableListOf<String>()
        undo.post("a", "a deleted") { done += "a" }
        undo.post("b", "b deleted") { done += "b" }
        scope.runCurrent()
        assertEquals(listOf("a"), done)
        assertEquals("b", undo.current.value?.id)
    }

    @Test
    fun theWindowCountsFromWhenTheSnackbarIsShown() {
        var commits = 0
        undo.post("a", "deleted") { commits++ }
        scope.advanceTimeBy(1_500)
        scope.runCurrent()
        assertTrue(undo.shown(undo.current.value!!))
        scope.advanceTimeBy(4_900)
        scope.runCurrent()
        assertEquals(0, commits)
        assertNotNull(undo.current.value)
        scope.advanceTimeBy(200)
        scope.runCurrent()
        assertEquals(1, commits)
        assertNull(undo.current.value)
    }

    @Test
    fun anOldRequestShownLateDoesNotExtendTheNewOne() {
        undo.post("a", "a deleted") {}
        val first = undo.current.value!!
        undo.post("b", "b deleted") {}
        assertEquals(false, undo.shown(first))
    }
}
