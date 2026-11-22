package com.ftapps.remft.ui.settings

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.alarm.AlarmLogLane
import com.ftapps.remft.testing.TestAlarms
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class LogScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val alarms = TestAlarms()

    @After
    fun tearDown() {
        alarms.close()
    }

    @Test
    fun alarmLogShowsEmptyState() {
        compose.showAlarmScreen(alarms) { AlarmLogScreen(onBack = {}) }
        compose.waitFor { compose.tagCount("alarm-log-empty") == 1 }
        compose.onNodeWithTag("alarm-log-copy").assertIsNotEnabled()
        assertEquals(1, compose.textCount("This is what the app DID", substring = true))
    }

    @Test
    fun alarmLogListsEntriesNewestFirstAndCopies() {
        runBlocking {
            alarms.env.log.record(AlarmLogEvent.SCHEDULED, AlarmLogLane.NOTIFICATION, kind = "due", slotIndex = 0, urgent = true)
            alarms.clock.advanceMinutes(1)
            alarms.env.log.record(AlarmLogEvent.RING_SHOWN, AlarmLogLane.INAPP)
            alarms.clock.advanceMinutes(1)
            alarms.env.log.record(AlarmLogEvent.TEST, AlarmLogLane.NOTIFICATION)
        }
        compose.showAlarmScreen(alarms, dark = true) { AlarmLogScreen(onBack = {}) }
        compose.waitFor { compose.tagCount("alarm-log-row") == 3 }
        assertEquals(1, compose.textCount("Scheduled · notification"))
        assertEquals(1, compose.textCount("Rang in the app · inapp"))
        assertEquals(1, compose.textCount("test · notification"))
        compose.onNodeWithTag("alarm-log-copy").performClick()
        compose.waitFor { compose.textCount("Log copied") == 1 }
        val clip = alarms.context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        val lines = clip.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains(" · test · "))
        assertTrue(lines[2].contains(" · scheduled · notification · due · slot 0 · urgent"))
    }

    private fun showAppLog(opened: MutableList<String> = mutableListOf()) {
        compose.showAlarmScreen(alarms) { AppLogScreen(onBack = {}, onOpenAlarmLog = { opened += "alarm-log" }) }
    }

    @Test
    fun appLogShowsEmptyState() {
        val opened = mutableListOf<String>()
        showAppLog(opened)
        compose.waitFor { compose.tagCount("app-log-empty") == 1 }
        compose.onNodeWithTag("app-log-alarm-log").performClick()
        assertEquals(listOf("alarm-log"), opened)
    }

    @Test
    fun appLogFiltersByLevel() {
        alarms.env.appLog.info("app.launch", mapOf("cold" to true))
        alarms.env.appLog.warn("alarms.pass_slow", mapOf("ms" to 1200))
        alarms.env.appLog.error("backup.failed", IllegalStateException())
        alarms.env.appLog.flush()
        showAppLog()
        compose.waitFor { compose.tagCount("app-log-row-info") == 1 && compose.tagCount("app-log-row-warn") == 1 && compose.tagCount("app-log-row-error") == 1 }
        assertEquals(1, compose.textCount("cold=true"))
        assertEquals(1, compose.textCount("error=IllegalStateException"))
        compose.onNodeWithTag("app-log-filter-warn").performClick()
        compose.waitFor { compose.tagCount("app-log-row-info") == 0 && compose.tagCount("app-log-row-warn") == 1 && compose.tagCount("app-log-row-error") == 1 }
        compose.onNodeWithTag("app-log-filter-error").performClick()
        compose.waitFor { compose.tagCount("app-log-row-warn") == 0 && compose.tagCount("app-log-row-error") == 1 }
        compose.onNodeWithTag("app-log-filter-all").performClick()
        compose.waitFor { compose.tagCount("app-log-row-info") == 1 }
    }

    @Test
    fun appLogClearAsksFirst() {
        alarms.env.appLog.info("app.launch")
        alarms.env.appLog.flush()
        showAppLog()
        compose.waitFor { compose.tagCount("app-log-row-info") == 1 }
        compose.onNodeWithTag("app-log-clear").performClick()
        compose.waitFor { compose.textCount("Clear the app log?") == 1 }
        compose.onNodeWithTag("app-log-clear-go").performClick()
        compose.waitFor { compose.tagCount("app-log-empty") == 1 }
        compose.waitFor { compose.textCount("Log cleared") == 1 }
        assertTrue(alarms.env.appLog.read().isEmpty())
    }

    @Test
    fun aFilterWithNoMatchesSaysSoInsteadOfNothingLogged() {
        alarms.env.appLog.info("app.launch")
        alarms.env.appLog.flush()
        showAppLog()
        compose.waitFor { compose.tagCount("app-log-row-info") == 1 }
        compose.onNodeWithTag("app-log-filter-warn").performClick()
        compose.waitFor { compose.tagCount("app-log-filtered-empty") == 1 }
        assertEquals(1, compose.textCount("No warnings or errors"))
        assertEquals(0, compose.tagCount("app-log-empty"))
        compose.onNodeWithTag("app-log-filter-error").performClick()
        compose.waitFor { compose.textCount("No errors") == 1 }
    }
}
