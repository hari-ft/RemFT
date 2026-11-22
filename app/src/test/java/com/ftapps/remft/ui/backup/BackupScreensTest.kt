package com.ftapps.remft.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.backup.RestoreStage
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class BackupScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun restoreSourceSheetOffersTheFileAndShowsOneDriveDisabled() {
        var picked = 0
        var oneDrive = 0
        compose.setContent {
            RemftTheme {
                RestoreSourceSheet(oneDriveConnected = false, onFile = { picked++ }, onOneDrive = { oneDrive++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Restore from").assertIsDisplayed()
        compose.onNodeWithText("A file on this phone").assertIsDisplayed()
        compose.onNodeWithText("A backup zip you saved or downloaded").assertIsDisplayed()
        compose.onNodeWithText("Connect OneDrive under Automatic backup first").assertIsDisplayed()
        compose.onNodeWithTag("restore-source-onedrive").assertHasNoClickAction()
        compose.onNodeWithTag("restore-source-onedrive").performClick()
        compose.onNodeWithTag("restore-source-file").assertHasClickAction().performClick()
        assertEquals(1, picked)
        assertEquals(0, oneDrive)
    }

    @Test
    fun restoreSourceOptionsEnableOneDriveWhenConnected() {
        var oneDrive = 0
        compose.setContent {
            RemftTheme { RestoreSourceOptions(oneDriveConnected = true, onFile = {}, onOneDrive = { oneDrive++ }) }
        }
        compose.onNodeWithText("A backup in Apps › RemFT").assertIsDisplayed()
        compose.onNodeWithTag("restore-source-onedrive").performClick()
        assertEquals(1, oneDrive)
    }

    @Test
    fun restoreFlowAsksBeforeReplacingAndThenBlocksInOrder() {
        val createdAt = Instant.parse("2026-11-02T10:15:00Z")
        val answers = ArrayList<Boolean>()
        var restore by mutableStateOf<RestoreUiState>(RestoreUiState.Confirm(createdAt))
        compose.setContent {
            RemftTheme {
                RestoreFlow(restore, DateFormatId.Iso, onDismissSource = {}, onFile = {}, onConfirm = { answers.add(it) })
            }
        }
        val date = formatBackupDate(createdAt, DateFormatId.Iso)
        compose.onNodeWithText("Replace everything on this phone?").assertIsDisplayed()
        compose.onNodeWithText(date, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("restore-confirm-go").performClick()
        assertEquals(listOf(false, true), answers)

        restore = RestoreUiState.Running(RestoreStage.Tasks)
        compose.onNodeWithText("Restoring tasks and notes…").assertIsDisplayed()
        restore = RestoreUiState.Running(RestoreStage.Settings)
        compose.onNodeWithText("Restoring settings…").assertIsDisplayed()
        restore = RestoreUiState.Checking
        compose.onNodeWithTag("restore-checking").assertIsDisplayed()
        restore = RestoreUiState.Idle
        compose.onNodeWithTag("restore-progress").assertDoesNotExist()
    }

    @Test
    fun backupDatesFollowTheChosenFormat() {
        assertEquals("2026-11-02 10:15", formatBackupDate(Instant.parse("2026-11-02T10:15:00Z"), DateFormatId.Iso, ZoneOffset.UTC))
    }

    @Test
    fun backupSettingsScreenListsTheActionsAndTheLastBackup() {
        var backups = 0
        var restores = 0
        var state by mutableStateOf(BackupSettingsState())
        compose.setContent {
            RemftTheme {
                BackupSettingsContent(state, busy = false, onBack = {}, onBackUpNow = { backups++ }, onRestore = { restores++ })
            }
        }
        compose.onNodeWithText("Backup").assertIsDisplayed()
        compose.onNodeWithText("No backup yet").assertIsDisplayed()
        compose.onNodeWithText("Automatic backup").assertIsDisplayed()
        compose.onNodeWithTag("settings-auto-backup").assertHasNoClickAction()
        compose.onNodeWithTag("backup-now").performClick()
        compose.onNodeWithTag("settings-restore").performClick()
        assertEquals(1, backups)
        assertEquals(1, restores)

        val at = Instant.parse("2026-11-01T08:30:00Z")
        state = BackupSettingsState(lastBackup = at, dateFormat = DateFormatId.Iso)
        compose.onNodeWithText("Last backup ${formatBackupDate(at, DateFormatId.Iso)}").assertIsDisplayed()
    }

    @Test
    fun backupSettingsScreenDisablesActionsWhileBusy() {
        compose.setContent {
            RemftTheme { BackupSettingsContent(BackupSettingsState(), busy = true, onBack = {}, onBackUpNow = {}, onRestore = {}) }
        }
        compose.onNodeWithTag("backup-now").assertHasNoClickAction()
        compose.onNodeWithTag("settings-restore").assertHasNoClickAction()
    }

    @Test
    fun reminderCardOffersLaterAndBackUpNow() {
        var later = 0
        var now = 0
        compose.setContent {
            RemftTheme(darkTheme = true) {
                var shown by remember { mutableStateOf(true) }
                if (shown) BackupReminderCardContent(onLater = { later++; shown = false }, onBackUpNow = { now++ })
            }
        }
        compose.onNodeWithText("Your tasks live only on this phone. Back them up — it takes a few seconds.").assertIsDisplayed()
        compose.onNodeWithTag("backup-reminder-now").performClick()
        compose.onNodeWithTag("backup-reminder-later").performClick()
        compose.onNodeWithTag("backup-reminder").assertDoesNotExist()
        assertEquals(1, later)
        assertEquals(1, now)
    }
}
