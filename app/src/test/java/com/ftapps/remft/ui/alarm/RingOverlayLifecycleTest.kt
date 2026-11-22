package com.ftapps.remft.ui.alarm

import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.PlannedNotification
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class RingOverlayLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeFeedback : AlarmFeedback {
        override val state = MutableStateFlow(RingFeedback())
        var starts = 0

        override fun start() {
            starts++
            state.value = RingFeedback(playing = true)
        }

        override fun stop() {
            state.value = state.value.copy(playing = false)
        }

        override fun pulse() = Unit
    }

    private val t = TestAlarms()
    private val made = mutableListOf<FakeFeedback>()

    @After
    fun tearDown() = t.close()

    private fun rig(): Pair<AlarmInput, PlannedNotification> = runBlocking {
        t.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = t.clock.instant().plus(Duration.ofMinutes(10)), isUrgent = true))
        t.env.scheduler.pass()
        val first = t.env.pending.read().values.minByOrNull { it.fireAtMillis }!!.toPlanned()
        t.clock.now = first.fireAt.plusSeconds(5)
        t.env.gateway.show(first)
        t.env.workspaceFeed().first { it.reminderId == first.reminderId } to first
    }

    private fun posted(id: Int) =
        t.context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification

    private fun waitUntil(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!what()) {
            compose.waitForIdle()
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun goingHomeDuringARingStopsTheInAppSoundAndBringsBackTheNotification() {
        val (alarm, first) = rig()
        InAppRing.install(t.env, InAppRing(t.env, sweepMs = 50) { FakeFeedback().also { made += it } })
        compose.setContent { RemftTheme { AlarmRingOverlay(t.env, alarm, missed = false, onOpenTask = {}) } }
        waitUntil { made.singleOrNull()?.state?.value?.playing == true && posted(first.id) == null }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitUntil { posted(first.id) != null }
        assertFalse(made.single().state.value.playing)
        assertEquals("Acknowledge", posted(first.id)!!.actions.first().title.toString())

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitUntil { made.single().starts == 2 && posted(first.id) == null }
        assertNull(posted(first.id))
        assertEquals(1, made.size)
    }
}
