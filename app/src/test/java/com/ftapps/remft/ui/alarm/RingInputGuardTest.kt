package com.ftapps.remft.ui.alarm

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmKind
import com.ftapps.remft.domain.alarm.SnoozePresets
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class RingInputGuardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-20T04:31:00Z")
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

    private val calls = mutableListOf<String>()

    private val actions = object : AlarmRingActions {
        override suspend fun acknowledge(): RingAnswer = RingAnswer.APPLIED.also { calls += "acknowledge" }

        override suspend fun snooze(until: Instant): RingAnswer = RingAnswer.APPLIED.also { calls += "snooze" }

        override suspend fun complete(): RingAnswer = RingAnswer.APPLIED.also { calls += "complete" }

        override suspend fun silence(): RingAnswer = RingAnswer.APPLIED.also { calls += "silence" }

        override fun open() {
            calls += "open"
        }

        override fun startSound() = Unit

        override fun dismissMissed() = Unit
    }

    private var ready by mutableStateOf(true)

    private fun show() {
        compose.setContent {
            RemftTheme {
                AlarmRingScreen(
                    alarm = alarm,
                    missed = false,
                    now = now,
                    zone = ZoneId.of("Asia/Kolkata"),
                    dateFormat = DateFormatId.System,
                    snoozeOrder = SnoozePresets.ids,
                    feedback = RingFeedback(playing = true),
                    volumeOff = false,
                    actions = actions,
                    inputReady = ready,
                )
            }
        }
    }

    @Test
    fun tapsWhileTheRingScreenIsStillAppearingDoNothing() {
        compose.mainClock.autoAdvance = false
        show()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("alarm-snooze-5_min").performClick()
        compose.onNodeWithTag("alarm-open").performClick()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(emptyList<String>(), calls)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-snooze-5_min").performClick()
        compose.waitForIdle()
        assertEquals(listOf("snooze"), calls)
    }

    @Test
    fun theButtonsStayAwayUntilTheScreensSettingsHaveLoaded() {
        ready = false
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-acknowledge").assertDoesNotExist()
        ready = true
        compose.waitForIdle()
        compose.onNodeWithTag("alarm-acknowledge").performClick()
        compose.waitForIdle()
        assertEquals(listOf("acknowledge"), calls)
    }
}
