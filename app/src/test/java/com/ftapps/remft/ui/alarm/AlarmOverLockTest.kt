package com.ftapps.remft.ui.alarm

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.RingState
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AlarmOverLockTest {
    private val alarm = AlarmInput(
        reminderId = "task:due:01JTESTTASK00000000000000",
        taskId = "01JTESTTASK00000000000000",
        taskTitle = "Sample task",
        remindAt = Instant.parse("2026-11-20T04:30:00Z"),
        status = "scheduled",
        urgent = true,
        requiresAcknowledgement = true,
        kind = AlarmKind.DUE,
    )

    private fun activity(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun gateFor(activity: ComponentActivity) = AlarmOverLock { AlarmActivitySupport.setOverLock(activity, it) }

    @Test
    fun aStaleNotRingingStateDoesNotPushTheAlarmLaunchBehindTheLockScreen() {
        val activity = activity()
        val gate = gateFor(activity)
        gate.alarmLaunched(loaded = 3)
        assertTrue(shadowOf(activity).showWhenLocked)

        gate.onState(RingState(ringing = null, decided = true, loaded = 3))
        assertTrue(shadowOf(activity).showWhenLocked)
        assertTrue(shadowOf(activity).turnScreenOn)

        gate.onState(RingState(ringing = alarm, decided = true, loaded = 4))
        assertTrue(shadowOf(activity).showWhenLocked)

        gate.onState(RingState(ringing = null, decided = true, loaded = 5))
        assertFalse(shadowOf(activity).showWhenLocked)
        assertFalse(shadowOf(activity).turnScreenOn)
    }

    @Test
    fun ringingTurnsOverLockBackOnAndTheEndOfRingingClearsIt() {
        val activity = activity()
        val gate = gateFor(activity)
        gate.onState(RingState(ringing = null, decided = true, loaded = 1))
        assertFalse(shadowOf(activity).showWhenLocked)

        gate.onState(RingState(ringing = alarm, decided = true, loaded = 1))
        assertTrue(shadowOf(activity).showWhenLocked)

        gate.onState(RingState(ringing = null, decided = true, loaded = 2))
        assertFalse(shadowOf(activity).showWhenLocked)
    }

    @Test
    fun aFreshDecisionThatNothingRingsAfterAnAlarmLaunchClearsOverLock() {
        val activity = activity()
        val gate = gateFor(activity)
        gate.alarmLaunched(loaded = 0)
        gate.onState(RingState(decided = false))
        assertTrue(shadowOf(activity).showWhenLocked)

        gate.onState(RingState(ringing = null, decided = true, loaded = 1))
        assertFalse(shadowOf(activity).showWhenLocked)
    }
}
