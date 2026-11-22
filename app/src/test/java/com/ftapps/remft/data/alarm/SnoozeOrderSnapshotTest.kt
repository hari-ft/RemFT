package com.ftapps.remft.data.alarm

import com.ftapps.remft.domain.alarm.SnoozePresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SnoozeOrderSnapshotTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun SnoozeOrderSnapshot.awaitOrder(expected: List<String>) {
        withTimeout(5_000) { while (current() != expected) delay(5) }
    }

    @Test
    fun beforeTheFirstValueItUsesTheDefaultOrder() {
        val snapshot = SnoozeOrderSnapshot(flowOf("m:90"))
        assertFalse(snapshot.ready)
        assertEquals(SnoozePresets.ids, snapshot.current())
    }

    @Test
    fun itReadsTheSavedOrderWithoutBlocking() = runBlocking {
        val snapshot = SnoozeOrderSnapshot(flowOf("m:90,tomorrow_morning,5_min"))
        snapshot.start(scope)
        assertEquals(listOf("m:90", "tomorrow_morning", "5_min"), snapshot.awaitReady(5_000))
        assertTrue(snapshot.ready)
    }

    @Test
    fun itFollowsChangesToTheSetting() = runBlocking {
        val source = MutableStateFlow<String?>("m:90,5_min")
        val snapshot = SnoozeOrderSnapshot(source)
        snapshot.start(scope)
        snapshot.awaitOrder(listOf("m:90", "5_min"))
        source.value = "tomorrow_morning"
        snapshot.awaitOrder(listOf("tomorrow_morning"))
        source.value = null
        snapshot.awaitOrder(SnoozePresets.ids)
    }

    @Test
    fun waitingGivesUpOnTheDefaultOrderWhenNothingArrives() = runBlocking {
        val snapshot = SnoozeOrderSnapshot(emptyFlow())
        snapshot.start(scope)
        assertEquals(SnoozePresets.ids, snapshot.awaitReady(50))
        assertFalse(snapshot.ready)
    }

    @Test
    fun aBrokenSettingsFileFallsBackToTheDefaultOrder() = runBlocking {
        val snapshot = SnoozeOrderSnapshot(flow { throw IOException("corrupt") })
        snapshot.start(scope)
        assertEquals(SnoozePresets.ids, snapshot.awaitReady(5_000))
        assertTrue(snapshot.ready)
    }
}
