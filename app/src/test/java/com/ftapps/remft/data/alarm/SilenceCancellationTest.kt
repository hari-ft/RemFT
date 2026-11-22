package com.ftapps.remft.data.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SilenceCancellationTest {
    private val t = TestAlarms()

    @After
    fun tearDown() = t.close()

    @Test
    fun aCancelledSilenceIsNotLoggedAsDegraded() = runBlocking {
        val job = launch {
            coroutineContext.job.cancel()
            t.env.silencer.silence("01JTESTTASK00000000000000", null, emptyList(), "acknowledged", AlarmLogLane.INAPP)
        }
        job.join()
        assertEquals(emptyList<String>(), t.env.log.recent().filter { it.event == AlarmLogEvent.DEGRADED }.map { it.detail })
    }
}
