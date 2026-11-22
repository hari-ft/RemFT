package com.ftapps.remft.ui.alarm

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class AlarmRingScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-20T04:31:00Z")
    private val alarm = AlarmInput(
        reminderId = "task:due:01JTESTTASK00000000000000",
        taskId = "01JTESTTASK00000000000000",
        taskTitle = "Call the plumber",
        remindAt = Instant.parse("2026-11-20T04:30:00Z"),
        status = "scheduled",
        urgent = true,
        requiresAcknowledgement = true,
        kind = AlarmKind.DUE,
    )

    private class Recorder(var answer: RingAnswer = RingAnswer.APPLIED) : AlarmRingActions {
        val calls = mutableListOf<String>()

        override suspend fun acknowledge(): RingAnswer = answer.also { calls += "acknowledge" }

        override suspend fun snooze(until: Instant): RingAnswer = answer.also { calls += "snooze:$until" }

        override suspend fun complete(): RingAnswer = answer.also { calls += "complete" }

        override suspend fun silence(): RingAnswer = answer.also { calls += "silence" }

        override fun open() {
            calls += "open"
        }

        override fun startSound() {
            calls += "start"
        }

        override fun dismissMissed() {
            calls += "dismiss"
        }
    }

    private fun show(
        actions: Recorder,
        missed: Boolean = false,
        feedback: RingFeedback = RingFeedback(playing = true),
        volumeOff: Boolean = false,
        order: List<String> = SnoozePresets.ids,
        dark: Boolean = false,
    ) {
        compose.setContent {
            RemftTheme(darkTheme = dark) {
                AlarmRingScreen(
                    alarm = alarm,
                    missed = missed,
                    now = now,
                    zone = zone,
                    dateFormat = DateFormatId.System,
                    snoozeOrder = order,
                    feedback = feedback,
                    volumeOff = volumeOff,
                    actions = actions,
                )
            }
        }
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun showsTheAlarmAndEveryButton() {
        show(Recorder())
        compose.onNodeWithText("URGENT REMINDER").assertIsDisplayed()
        compose.onNodeWithTag("alarm-title").assertTextContains("Call the plumber")
        compose.onNodeWithText("10:00 am", ignoreCase = true).assertIsDisplayed()
        for (tag in listOf("alarm-acknowledge", "alarm-snooze-custom", "alarm-silence", "alarm-complete", "alarm-open", "alarm-pulse")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
        SnoozePresets.ids.forEach { compose.onNodeWithTag("alarm-snooze-$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("alarm-snooze-5_min").assertTextContains("rings at 10:06 am", substring = true, ignoreCase = true)
    }

    @Test
    fun snoozeButtonsFollowTheUsersOrder() {
        show(Recorder(), order = listOf(SnoozePresets.ONE_HOUR, SnoozePresets.FIVE_MIN, SnoozePresets.THIRTY_MIN, SnoozePresets.TOMORROW_MORNING))
        val hour = compose.onNodeWithTag("alarm-snooze-1_hour").fetchSemanticsNode().boundsInRoot
        val five = compose.onNodeWithTag("alarm-snooze-5_min").fetchSemanticsNode().boundsInRoot
        assert(hour.left < five.left || hour.top < five.top)
    }

    @Test
    fun aCustomSnoozeOptionShowsItsLengthAndSnoozesForThatLong() {
        val actions = Recorder()
        show(actions, order = listOf("m:90", SnoozePresets.TOMORROW_MORNING))
        compose.onNodeWithTag("alarm-snooze-m:90").performScrollTo().assertIsDisplayed()
            .assertTextContains("1 h 30 min", substring = true)
            .assertTextContains("rings at 11:31 am", substring = true, ignoreCase = true)
        assertEquals(0, compose.onAllNodesWithTag("alarm-snooze-5_min").fetchSemanticsNodes().size)
        compose.onNodeWithTag("alarm-snooze-m:90").performClick()
        compose.waitForIdle()
        assertEquals("snooze:2026-11-20T06:01:00Z", actions.calls.single())
    }

    @Test
    fun backNeverDismissesARingingAlarm() {
        val actions = Recorder()
        show(actions)
        pressBack()
        pressBack()
        compose.onNodeWithTag("alarm-ring").assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun backClosesTheMissedView() {
        val actions = Recorder()
        show(actions, missed = true)
        compose.onNodeWithText("MISSED ALARM").assertIsDisplayed()
        pressBack()
        assertEquals(listOf("dismiss"), actions.calls)
    }

    @Test
    fun buttonsCallTheirActions() {
        val actions = Recorder()
        show(actions)
        compose.onNodeWithTag("alarm-acknowledge").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-snooze-5_min").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-complete").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-silence").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-open").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("acknowledge", actions.calls[0])
        assertEquals("snooze:2026-11-20T04:36:00Z", actions.calls[1])
        assertEquals(listOf("complete", "silence", "open"), actions.calls.drop(2))
    }

    @Test
    fun failedAnswerKeepsRingingAndSaysSo() {
        val actions = Recorder(RingAnswer.FAILED)
        show(actions)
        compose.onNodeWithTag("alarm-acknowledge").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("That didn’t save, so the alarm is still ringing. Try again.") > 0 }
        compose.onNodeWithTag("alarm-ring").assertIsDisplayed()
    }

    @Test
    fun soundTroubleAndVolumeWarningsShow() {
        val actions = Recorder()
        show(actions, feedback = RingFeedback(playing = false, fallback = true, blocked = true), volumeOff = true, dark = true)
        compose.onNodeWithTag("alarm-sound-fallback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-volume-off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm-start-sound").performScrollTo().performClick()
        assertEquals(listOf("start"), actions.calls)
    }

    @Test
    fun missedViewDoesNotPulseOrOfferStartSound() {
        show(Recorder(), missed = true, feedback = RingFeedback(blocked = true))
        assertEquals(0, compose.onAllNodesWithTagCount("alarm-start-sound"))
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
