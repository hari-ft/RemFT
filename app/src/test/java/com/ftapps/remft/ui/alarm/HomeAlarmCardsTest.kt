package com.ftapps.remft.ui.alarm

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.TestAlarms
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HomeAlarmCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private var alarms: TestAlarms? = null

    @After
    fun tearDown() {
        alarms?.close()
    }

    private fun open(health: FakeHealth = FakeHealth()): TestAlarms = TestAlarms(health = health).also { alarms = it }

    private fun show(t: TestAlarms, dark: Boolean = false) {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAlarmEnvironment provides t.env) {
                    Column {
                        HomeAlarmWarnings()
                        HomeUrgentAlarm()
                    }
                }
            }
        }
    }

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher): Int =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    @Test
    fun nothingShowsWhenAlarmsCanRing() {
        val t = open()
        show(t)
        compose.waitForIdle()
        assertEquals(0, count(hasTestTag("alarm-banner")))
        assertEquals(0, count(hasTestTag("missed-alarm-card")))
    }

    @Test
    fun bannerShowsTheWorstProblemAndOpensItsFix() {
        val health = FakeHealth(FakeHealth.AllAllowed.copy(notifications = false, exactAlarms = false))
        val t = open(health)
        show(t)
        waitFor { count(hasTestTag("alarm-banner")) == 1 }
        compose.onNodeWithText("Notifications are off — nothing can reach you. Tap to fix.").assertIsDisplayed()
        compose.onNodeWithTag("alarm-banner").performClick()
        waitFor { count(hasTestTag("alarm-fix-open")) == 1 }
        compose.onNodeWithTag("alarm-fix-open").performClick()
        compose.waitForIdle()
        assertEquals(listOf(PermissionKind.NOTIFICATIONS), health.opened)
    }

    @Test
    fun volumeZeroWarnsOnlyWhenAnUrgentRingIsAhead() = runBlocking {
        val health = FakeHealth(FakeHealth.AllAllowed.copy(alarmVolumeOff = true))
        val t = open(health)
        t.env.ring.reload()
        show(t)
        compose.waitForIdle()
        assertEquals(0, count(hasTestTag("alarm-banner")))
        t.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = t.clock.instant().plus(Duration.ofHours(1)), isUrgent = true))
        t.env.ring.reload()
        waitFor { count(hasText("Alarm volume is off — urgent alarms will be silent. Tap to fix.")) == 1 }
    }

    @Test
    fun missedCardShowsTheLatestMissedAlarmAndTheRest() = runBlocking {
        val t = open()
        val now = t.clock.instant()
        t.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = now.minus(Duration.ofHours(2)), isUrgent = true))
        t.tasks.repository.create(TaskDraft(title = "Pay the rent", remindAt = now.minus(Duration.ofHours(3)), isUrgent = true))
        t.env.ring.reload()
        assertEquals(2, t.env.ring.state.value.missed.size)
        show(t)
        waitFor { count(hasTestTag("missed-alarm-card")) == 1 }
        compose.onNodeWithText("Missed alarm").assertIsDisplayed()
        compose.onNodeWithText("Call the plumber").assertIsDisplayed()
        compose.onNodeWithTag("missed-alarm-when", useUnmergedTree = true).assertTextContains("8:00 AM · 2 h ago", ignoreCase = true)
        compose.onNodeWithTag("missed-alarm-more", useUnmergedTree = true).assertTextContains("+1 more")
        compose.onNodeWithTag("missed-alarm-card").performClick()
        assertNotNull(t.env.ring.missedShown.value)
        assertEquals("Call the plumber", t.env.ring.missedShown.value!!.taskTitle)
    }

    @Test
    fun ringingNowIsNotMissed() = runBlocking {
        val t = open()
        t.tasks.repository.create(TaskDraft(title = "Call the plumber", remindAt = t.clock.instant().minus(Duration.ofMinutes(2)), isUrgent = true))
        t.env.ring.reload()
        assertEquals("Call the plumber", t.env.ring.state.value.ringing?.taskTitle)
        assertEquals(0, t.env.ring.state.value.missed.size)
    }
}
