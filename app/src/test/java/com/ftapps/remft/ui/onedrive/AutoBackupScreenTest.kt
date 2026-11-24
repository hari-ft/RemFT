package com.ftapps.remft.ui.onedrive

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.backup.RestoreStage
import com.ftapps.remft.data.mirror.MirrorCounts
import com.ftapps.remft.data.mirror.MirrorDownloadPhase
import com.ftapps.remft.data.mirror.MirrorDownloadState
import com.ftapps.remft.data.mirror.MirrorPhase
import com.ftapps.remft.data.mirror.MirrorProgress
import com.ftapps.remft.data.onedrive.AutoBackupSnapshot
import com.ftapps.remft.data.onedrive.AutoBackupTrouble
import com.ftapps.remft.domain.backup.auto.AutoBackupPause
import com.ftapps.remft.domain.backup.auto.BackupDataSize
import com.ftapps.remft.domain.backup.auto.BackupFrequency
import com.ftapps.remft.domain.backup.auto.BackupSchedule
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.ui.backup.RestoreFlow
import com.ftapps.remft.ui.backup.RestoreUiState
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AutoBackupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun reveal(tag: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag(tag))
    }

    private fun revealInDialog(tag: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onLast().performScrollToNode(hasTestTag(tag))
    }

    private fun snapshot(
        on: Boolean = true,
        connected: Boolean = true,
        reconnect: Boolean = false,
        schedule: BackupSchedule = BackupSchedule(),
        pause: AutoBackupPause? = null,
    ) = AutoBackupSnapshot(
        on = on,
        connected = connected,
        reconnect = reconnect,
        account = if (connected) "someone@example.com" else null,
        schedule = schedule,
        keepDays = 7,
        lastOk = Instant.parse("2026-11-20T02:00:00Z"),
        unavailable = 0,
        status = null,
        pause = pause,
    )

    @Test
    fun notConnectedOffersConnectAndDisablesBackUpNow() {
        var connects = 0
        compose.setContent {
            RemftTheme {
                AutoBackupContent(AutoBackupUi(snapshot(on = false, connected = false)), onBack = {}, actions = AutoBackupActions(onConnect = { connects++ }))
            }
        }
        compose.onNodeWithText("Connect OneDrive").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-onedrive-connect").performClick()
        assertEquals(1, connects)
        reveal("auto-backup-summary")
        compose.onNodeWithTag("auto-backup-now").assertIsNotEnabled()
        compose.onNodeWithText("Off").assertIsDisplayed()
    }

    @Test
    fun aMigratedSignInWithoutATokenOffersReconnect() {
        var connects = 0
        compose.setContent {
            RemftTheme {
                AutoBackupContent(AutoBackupUi(snapshot(on = true, connected = false)), onBack = {}, actions = AutoBackupActions(onConnect = { connects++ }))
            }
        }
        compose.onNodeWithText("Reconnect OneDrive").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-onedrive-reconnect").performClick()
        assertEquals(1, connects)
    }

    @Test
    fun connectedShowsTheAccountScheduleAndMirror() {
        var disconnects = 0
        compose.setContent {
            RemftTheme {
                AutoBackupContent(
                    AutoBackupUi(
                        snapshot(),
                        counts = MirrorCounts(files = 14, mirrored = 12, toDownload = 0),
                        dateFormat = DateFormatId.Iso,
                    ),
                    onBack = {},
                    actions = AutoBackupActions(onDisconnect = { disconnects++ }),
                )
            }
        }
        compose.onNodeWithText("someone@example.com").assertIsDisplayed()
        compose.onNodeWithText("Every day").assertIsDisplayed()
        reveal("auto-backup-next")
        compose.onNodeWithTag("auto-backup-next").assertIsDisplayed()
        reveal("auto-backup-mirror")
        compose.onNodeWithText("In OneDrive: 12 of 14", substring = true).assertIsDisplayed()
        reveal("auto-backup-onedrive-disconnect")
        compose.onNodeWithTag("auto-backup-onedrive-disconnect").performClick()
        compose.onNodeWithText("Disconnect OneDrive?").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-disconnect-confirm").performClick()
        assertEquals(1, disconnects)
    }

    @Test
    fun theScheduleEditorCoversEveryFrequency() {
        var schedule by mutableStateOf(BackupSchedule())
        compose.setContent {
            RemftTheme {
                AutoBackupContent(AutoBackupUi(snapshot(schedule = schedule)), onBack = {}, actions = AutoBackupActions(onSchedule = { schedule = it }))
            }
        }
        compose.onNodeWithTag("auto-backup-frequency").performClick()
        compose.onNodeWithTag("auto-backup-frequency-Weekly").performClick()
        assertEquals(BackupFrequency.Weekly, schedule.frequency)
        reveal("auto-backup-weekday")
        compose.onNodeWithTag("auto-backup-weekday").performClick()
        revealInDialog("auto-backup-weekday-3")
        compose.onNodeWithTag("auto-backup-weekday-3").performClick()
        assertEquals(3, schedule.weekday)
        compose.onNodeWithText("Wednesday").assertExists()

        compose.onNodeWithTag("auto-backup-frequency").performClick()
        compose.onNodeWithTag("auto-backup-frequency-TwiceDaily").performClick()
        reveal("auto-backup-second-time")
        compose.onNodeWithTag("auto-backup-second-time").assertIsDisplayed()

        compose.onNodeWithTag("auto-backup-frequency").performClick()
        compose.onNodeWithTag("auto-backup-frequency-EveryNDays").performClick()
        reveal("auto-backup-every")
        compose.onNodeWithTag("auto-backup-every").performClick()
        revealInDialog("auto-backup-every-5")
        compose.onNodeWithTag("auto-backup-every-5").performClick()
        assertEquals(5, schedule.everyDays)

        compose.onNodeWithTag("auto-backup-frequency").performClick()
        compose.onNodeWithTag("auto-backup-frequency-Monthly").performClick()
        reveal("auto-backup-month-day")
        compose.onNodeWithTag("auto-backup-month-day").performClick()
        revealInDialog("auto-backup-month-day-31")
        compose.onNodeWithTag("auto-backup-month-day-31").performClick()
        assertEquals(31, schedule.monthDay)
        compose.onNodeWithText("Day 31 — a shorter month uses its last day").assertExists()

        reveal("auto-backup-time")
        compose.onNodeWithTag("auto-backup-time").performClick()
        compose.onNodeWithTag("auto-backup-time-save").performClick()
        assertEquals(BackupSchedule.DEFAULT_MINUTES, schedule.minutes)
    }

    @Test
    fun keepDaysPicker() {
        var keep = 0
        compose.setContent {
            RemftTheme { AutoBackupContent(AutoBackupUi(snapshot()), onBack = {}, actions = AutoBackupActions(onKeepDays = { keep = it })) }
        }
        reveal("auto-backup-keep")
        compose.onNodeWithTag("auto-backup-keep").performClick()
        revealInDialog("auto-backup-keep-30")
        compose.onNodeWithTag("auto-backup-keep-30").performClick()
        assertEquals(30, keep)
    }

    @Test
    fun pausedAsksBeforeBackingUpAnyway() {
        var anyway = 0
        val pause = AutoBackupPause(Instant.parse("2026-11-20T02:00:00Z"), BackupDataSize(40, 10, 4, 0), BackupDataSize(5, 1, 1, 0))
        compose.setContent {
            RemftTheme {
                AutoBackupContent(AutoBackupUi(snapshot(pause = pause)), onBack = {}, actions = AutoBackupActions(onBackUpAnyway = { anyway++ }))
            }
        }
        compose.onNodeWithTag("auto-backup-paused").assertIsDisplayed()
        compose.onNodeWithText("The last backup had 54 tasks, notes and files; now there are 7.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-paused-anyway").performClick()
        compose.onNodeWithText("Back up anyway?").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-paused-confirm").performClick()
        assertEquals(1, anyway)
    }

    @Test
    fun homeCardsSayWhatIsWrong() {
        var fixes = 0
        var retries = 0
        compose.setContent {
            RemftTheme {
                Column {
                    BackupPausedCardContent(onOpen = {})
                    BackupTroubleCardContent(AutoBackupTrouble.Reconnect, onFix = { fixes++ })
                    MirrorDownloadsCardContent(
                        MirrorDownloadState(MirrorDownloadPhase.Waiting, total = 5, done = 2, error = "OFFLINE"),
                        onConnect = {},
                        onRetry = { retries++ },
                    )
                }
            }
        }
        compose.onNodeWithTag("backup-paused-card").assertIsDisplayed()
        compose.onNodeWithText("Automatic backups have stopped: OneDrive needs you to sign in again.").assertIsDisplayed()
        compose.onNodeWithTag("backup-trouble-banner").performClick()
        assertEquals(1, fixes)
        compose.onNodeWithText("Files still to download from OneDrive: 3. Trying again soon (no internet connection).").assertIsDisplayed()
        compose.onNodeWithTag("mirror-download-retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun restoreFlowListsOneDriveBackupsNewestFirst() {
        val picked = ArrayList<StoredBackup>()
        var oneDrive = 0
        var restore by mutableStateOf<RestoreUiState>(RestoreUiState.ChoosingSource)
        val backups = listOf(
            StoredBackup("A", "remft-auto-2026-11-20-020000Z.zip", 2048),
            StoredBackup("B", "remft-backup-2026-11-01.zip", 1024),
        )
        compose.setContent {
            RemftTheme {
                RestoreFlow(
                    restore,
                    DateFormatId.Iso,
                    onDismissSource = {},
                    onFile = {},
                    onConfirm = {},
                    oneDriveConnected = true,
                    onOneDrive = { oneDrive++ },
                    onPickCloud = { picked.add(it) },
                )
            }
        }
        compose.onNodeWithText("A backup in Apps › RemFT").assertIsDisplayed()
        compose.onNodeWithTag("restore-source-onedrive").performClick()
        assertEquals(1, oneDrive)
        restore = RestoreUiState.CloudList(loading = false, backups = backups)
        compose.onNodeWithText("Backups on OneDrive").assertIsDisplayed()
        compose.onNodeWithText("remft-backup-2026-11-01.zip").assertIsDisplayed()
        compose.onNodeWithTag("restore-onedrive-item-A").performClick()
        assertEquals(listOf("A"), picked.map { it.id })
        restore = RestoreUiState.CloudList(loading = false, failed = true)
        compose.onNodeWithText("Could not reach OneDrive. Check the connection and try again.").assertIsDisplayed()
        restore = RestoreUiState.CloudDownloading(512, 2048)
        compose.onNodeWithText("Downloading the backup…").assertIsDisplayed()
        restore = RestoreUiState.Running(RestoreStage.Files, 2, 5)
        compose.onNodeWithText("Downloading files from OneDrive… 2 of 5").assertIsDisplayed()
    }

    @Test
    fun syncNowShowsLastSyncedAndRunsOnTap() {
        var syncs = 0
        compose.setContent {
            RemftTheme {
                AutoBackupContent(
                    AutoBackupUi(
                        snapshot(),
                        counts = MirrorCounts(files = 3, mirrored = 3, toDownload = 0),
                        dateFormat = DateFormatId.Iso,
                        lastSynced = Instant.parse("2026-11-20T09:30:00Z"),
                    ),
                    onBack = {},
                    actions = AutoBackupActions(onSyncNow = { syncs++ }),
                )
            }
        }
        reveal("auto-backup-sync-now")
        compose.onNodeWithTag("auto-backup-mirror-last-synced").assertIsDisplayed()
        compose.onNodeWithText("Last synced", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-sync-now").assertIsEnabled()
        compose.onNodeWithTag("auto-backup-sync-now").performClick()
        assertEquals(1, syncs)
        compose.onNodeWithTag("auto-backup-mirror-progress").assertDoesNotExist()
    }

    @Test
    fun aRunningSyncShowsItsProgressAndBlocksASecondTap() {
        var syncs = 0
        var progress by mutableStateOf<MirrorProgress?>(MirrorProgress(MirrorPhase.Uploading, done = 2, total = 12, name = "invoice.pdf"))
        compose.setContent {
            RemftTheme {
                AutoBackupContent(
                    AutoBackupUi(snapshot(), counts = MirrorCounts(files = 12, mirrored = 2, toDownload = 0), syncing = true, progress = progress),
                    onBack = {},
                    actions = AutoBackupActions(onSyncNow = { syncs++ }),
                )
            }
        }
        reveal("auto-backup-sync-now")
        compose.onNodeWithText("Uploading 3 of 12 · invoice.pdf").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-sync-now").assertIsNotEnabled()
        compose.onNodeWithText("Syncing…").assertIsDisplayed()
        compose.onNodeWithTag("auto-backup-sync-now").performClick()
        assertEquals(0, syncs)
        progress = MirrorProgress(MirrorPhase.Uploading, done = 2, total = 12, name = "invoice.pdf", bytes = 50, size = 100)
        compose.onNodeWithText("Uploading 3 of 12 · invoice.pdf (50%)").assertIsDisplayed()
        progress = MirrorProgress(MirrorPhase.Moving, done = 1, total = 5)
        compose.onNodeWithText("Moving 2 of 5…").assertIsDisplayed()
        progress = MirrorProgress(MirrorPhase.Folders)
        compose.onNodeWithText("Checking folders…").assertIsDisplayed()
        progress = MirrorProgress(MirrorPhase.Downloading, done = 0, total = 3)
        compose.onNodeWithText("Downloading 1 of 3…").assertIsDisplayed()
    }
}
