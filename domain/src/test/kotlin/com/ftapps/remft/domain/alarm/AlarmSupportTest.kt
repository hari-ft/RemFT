package com.ftapps.remft.domain.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmSupportTest {

    @Test
    fun notificationsOffOutranksEverything() {
        val support = AlarmSupport(notificationsEnabled = false, exactAlarmsEnabled = false, fullScreenAllowed = false)
        assertEquals(AlarmProblem.NOTIFICATIONS_OFF, support.worstProblem())
        assertEquals(AlarmProblem.NOTIFICATIONS_OFF, support.worstProblem(alarmVolumeOff = true))
    }

    @Test
    fun alarmVolumeOffComesAfterNotificationsAndBeforeExactAlarms() {
        val support = AlarmSupport(notificationsEnabled = true, exactAlarmsEnabled = false, fullScreenAllowed = false)
        assertEquals(AlarmProblem.ALARM_VOLUME_OFF, support.worstProblem(alarmVolumeOff = true))
        assertEquals(AlarmProblem.EXACT_ALARMS_OFF, support.worstProblem())
    }

    @Test
    fun fullScreenOffIsAProblemAfterLateAlarms() {
        assertEquals(
            AlarmProblem.FULL_SCREEN_OFF,
            AlarmSupport(notificationsEnabled = true, exactAlarmsEnabled = true, fullScreenAllowed = false).worstProblem(),
        )
        assertEquals(
            AlarmProblem.EXACT_ALARMS_OFF,
            AlarmSupport(notificationsEnabled = true, exactAlarmsEnabled = false, fullScreenAllowed = false).worstProblem(),
        )
        assertNull(AlarmSupport(notificationsEnabled = true, exactAlarmsEnabled = true).worstProblem())
    }

    @Test
    fun unknownPermissionsAreNotProblems() {
        assertNull(AlarmSupport(notificationsEnabled = true).worstProblem())
        assertEquals(AlarmProblem.ALARM_VOLUME_OFF, AlarmSupport(notificationsEnabled = true).worstProblem(alarmVolumeOff = true))
    }
}
