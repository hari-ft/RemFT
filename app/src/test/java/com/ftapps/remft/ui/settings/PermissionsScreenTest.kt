package com.ftapps.remft.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.data.alarm.PermissionStatus
import com.ftapps.remft.testing.FakeHealth
import com.ftapps.remft.testing.TestAlarms
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class PermissionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var alarms: TestAlarms? = null

    @After
    fun tearDown() {
        alarms?.close()
    }

    private fun show(status: PermissionStatus, dark: Boolean = false): TestAlarms {
        val created = TestAlarms(health = FakeHealth(status))
        alarms = created
        compose.showAlarmScreen(created, dark) { PermissionsScreen(onBack = {}) }
        compose.waitFor { compose.tagCount("permission-NOTIFICATIONS-status") == 1 }
        return created
    }

    private fun assertStatus(kind: PermissionKind, text: String) {
        compose.onNodeWithTag("permission-${kind.name}-status", useUnmergedTree = true).assertTextEquals(text)
    }

    @Test
    fun allowedRowsSayAllowed() {
        show(FakeHealth.AllAllowed)
        compose.onNodeWithText("What RemFT needs from Android", substring = true).assertIsDisplayed()
        listOf(PermissionKind.NOTIFICATIONS, PermissionKind.EXACT_ALARMS, PermissionKind.FULL_SCREEN, PermissionKind.BATTERY, PermissionKind.BACKGROUND_DATA)
            .forEach { assertStatus(it, "Allowed") }
        assertStatus(PermissionKind.ALARM_VOLUME, "Above 0")
        PermissionKind.entries.forEach { compose.onNodeWithTag("permission-${it.name}").assertIsDisplayed() }
    }

    @Test
    fun blockedRowsAskForAFix() {
        show(PermissionStatus(false, false, false, false, false, true), dark = true)
        listOf(PermissionKind.NOTIFICATIONS, PermissionKind.EXACT_ALARMS, PermissionKind.FULL_SCREEN, PermissionKind.BATTERY, PermissionKind.BACKGROUND_DATA)
            .forEach { assertStatus(it, "Not allowed — tap to fix") }
        assertStatus(PermissionKind.ALARM_VOLUME, "At 0 — urgent alarms are silent. Tap to fix")
    }

    @Test
    fun uncheckableRowsSaySo() {
        show(PermissionStatus.Unknown)
        assertStatus(PermissionKind.NOTIFICATIONS, "Allowed")
        listOf(PermissionKind.EXACT_ALARMS, PermissionKind.FULL_SCREEN, PermissionKind.BATTERY, PermissionKind.BACKGROUND_DATA, PermissionKind.ALARM_VOLUME)
            .forEach { assertStatus(it, "Can't be checked on this phone") }
    }

    @Test
    fun tappingARowOpensItsSetting() {
        val created = show(FakeHealth.AllAllowed)
        PermissionKind.entries.forEach { compose.onNodeWithTag("permission-${it.name}").performClick() }
        compose.waitForIdle()
        assertEquals(PermissionKind.entries.toList(), created.health.opened)
    }
}
