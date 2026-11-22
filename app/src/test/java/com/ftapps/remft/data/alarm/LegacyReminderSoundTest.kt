package com.ftapps.remft.data.alarm

import android.app.Notification
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.alarm.PlannedNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [25])
class LegacyReminderSoundTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun planned(urgent: Boolean, sound: String?) = PlannedNotification(
        id = 0x0020_0000,
        title = "Water plants",
        body = "Reminder",
        fireAt = Instant.parse("2026-11-20T04:30:00Z"),
        urgent = urgent,
        payload = "{\"taskId\":\"T\"}",
        soundName = sound,
    )

    @Suppress("DEPRECATION")
    @Test
    fun aReminderWithTheSystemSoundStillMakesSoundAndVibratesOnAndroid7() {
        val built = AlarmNotifications.build(context, planned(urgent = false, sound = null))
        assertTrue(built.defaults and Notification.DEFAULT_SOUND != 0)
        assertTrue(built.defaults and Notification.DEFAULT_VIBRATE != 0)
        assertTrue(built.priority >= Notification.PRIORITY_HIGH)
    }

    @Suppress("DEPRECATION")
    @Test
    fun anAlarmWithItsOwnSoundPlaysItOnTheAlarmStreamAndVibrates() {
        val built = AlarmNotifications.build(context, planned(urgent = true, sound = "remft_alarm"))
        assertNotNull(built.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, built.audioAttributes.usage)
        assertEquals(0, built.defaults and Notification.DEFAULT_SOUND)
        assertTrue(built.defaults and Notification.DEFAULT_VIBRATE != 0)
        assertTrue(built.flags and Notification.FLAG_INSISTENT != 0)
    }
}
