package com.ftapps.remft.ui.voice

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AppLog
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.voice.GoogleAppState
import com.ftapps.remft.data.voice.LanguageSupport
import com.ftapps.remft.data.voice.VoiceIntents
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.FakeVoiceSupport
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.testing.VoiceRegistry
import com.ftapps.remft.ui.shell.AppShell
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2400dp")
class VoiceSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tasks = TestTasks()
    private val fake = FakeVoiceSupport()
    private val registry = VoiceRegistry()
    private val voice by lazy { VoiceEnvironment(fake, tasks.settings) }
    private val application: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = tasks.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun count(tag: String) = compose.onAllNodesWithTagCount(tag)

    private fun show() {
        val environment = voice
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides registry.owner,
                    LocalVoiceEnvironment provides environment,
                ) { VoiceSettingsScreen(onBack = {}) }
            }
        }
        waitFor { count("voice-check-buttons-state") == 1 }
    }

    private fun state(id: String) = compose.onNodeWithTag("voice-check-$id-state", useUnmergedTree = true)

    private fun detail(id: String) = compose.onNodeWithTag("voice-check-$id-detail", useUnmergedTree = true)

    private fun fix(id: String) {
        compose.onNodeWithTag("voice-check-$id-fix").performScrollTo().performClick()
    }

    private fun nextStarted(): Intent? = shadowOf(application).nextStartedActivity

    @Test
    fun settingsIndexOpensTheVoicePage() {
        val environment = voice
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides registry.owner,
                    LocalTasksEnvironment provides tasks.environment,
                    LocalVoiceEnvironment provides environment,
                ) { AppShell() }
            }
        }
        compose.onNodeWithTag("open-settings").performClick()
        waitFor { count("settings-voice") == 1 }
        compose.onNodeWithTag("settings-voice").performClick()
        waitFor { count("voice-check-buttons-state") == 1 }
        state("recognizer").assertTextEquals("OK")
    }

    @Test
    fun aReadyPhoneShowsEveryCheckOk() {
        show()
        listOf("recognizer", "googleapp", "googlemic", "language", "offline", "internet", "buttons").forEach { state(it).assertTextEquals("OK") }
        detail("offline").assertTextContains("Installed for English (United States)", substring = true)
        assertEquals(0, count("voice-check-recognizer-fix"))
    }

    @Test
    fun everyMissingItemSaysSoAndItsFixOpensTheRightPlace() {
        fake.recognizer = false
        fake.google = GoogleAppState.NotInstalled
        fake.internet = false
        runBlocking {
            tasks.settings.set(SettingKeys.VoiceLanguage.name, "hi-IN")
            tasks.settings.set(SettingKeys.VoiceButtons.name, "false")
        }
        show()
        waitFor { count("voice-check-language-state") == 1 }
        state("recognizer").assertTextEquals("Missing")
        state("googleapp").assertTextEquals("Missing")
        state("googlemic").assertTextEquals("Unknown")
        state("language").assertTextEquals("OK")
        state("offline").assertTextEquals("Missing")
        state("internet").assertTextEquals("Missing")
        state("buttons").assertTextEquals("Missing")

        fix("recognizer")
        assertEquals(VoiceIntents.playStoreWeb().dataString, nextStarted()!!.dataString)
        fix("googleapp")
        assertEquals(VoiceIntents.playStoreWeb().dataString, nextStarted()!!.dataString)
        fix("internet")
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, nextStarted()!!.action)

        fix("offline")
        assertEquals(listOf("hi-IN"), fake.downloads)
        assertEquals("Download requested. It can take a few minutes.", ShadowToast.getTextOfLatestToast())

        fix("buttons")
        waitFor { runBlocking { tasks.settings.get(SettingKeys.VoiceButtons.name) } == "true" }
        waitFor { count("voice-check-buttons-fix") == 0 }
        state("buttons").assertTextEquals("OK")
    }

    @Test
    fun disabledGoogleAppAndBlockedMicOpenItsAppInfo() {
        fake.google = GoogleAppState.Disabled
        fake.mic = false
        show()
        state("googleapp").assertTextEquals("Missing")
        state("googlemic").assertTextEquals("Missing")
        fix("googleapp")
        val first = nextStarted()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, first.action)
        assertEquals("package:${VoiceIntents.GOOGLE_PACKAGE}", first.dataString)
        fix("googlemic")
        assertEquals("package:${VoiceIntents.GOOGLE_PACKAGE}", nextStarted()!!.dataString)
    }

    @Test
    fun anUnsupportedLanguageOffersThePickerAndPickingRechecks() {
        runBlocking { tasks.settings.set(SettingKeys.VoiceLanguage.name, "fr-FR") }
        show()
        waitFor { count("voice-check-language-fix") == 1 }
        state("language").assertTextEquals("Missing")
        fix("language")
        waitFor { count("voice-language-en-US") == 1 }
        assertEquals(1, count("voice-language-system"))
        assertEquals(1, count("voice-language-hi-IN"))
        compose.onNodeWithTag("voice-language-en-US").performClick()
        waitFor { runBlocking { tasks.settings.get(SettingKeys.VoiceLanguage.name) } == "en-US" }
        waitFor { count("voice-check-language-fix") == 0 }
        state("language").assertTextEquals("OK")
        state("offline").assertTextEquals("OK")
    }

    @Test
    fun followPhoneClearsTheChosenLanguage() {
        runBlocking { tasks.settings.set(SettingKeys.VoiceLanguage.name, "hi-IN") }
        show()
        compose.onNodeWithTag("voice-language").performClick()
        waitFor { count("voice-language-system") == 1 }
        compose.onNodeWithTag("voice-language-system").performClick()
        waitFor { runBlocking { tasks.settings.get(SettingKeys.VoiceLanguage.name) } == null }
    }

    @Test
    fun checksAreLoggedAsStates() {
        val previous = AppLogs.current
        val log = AppLog(java.nio.file.Files.createTempDirectory("voice-check-log").toFile())
        AppLogs.install(log)
        try {
            fake.mic = false
            show()
            log.flush()
            val entry = log.read().first { it.event == "voice.check" }
            assertEquals("Missing", entry.fields["googlemic"])
            assertEquals("Ok", entry.fields["recognizer"])
            assertEquals(7, entry.fields.size)
        } finally {
            AppLogs.install(previous)
        }
    }

    @Test
    fun aPendingPackShowsPendingDownload() {
        fake.support = LanguageSupport(online = listOf("en-US"), onDevice = listOf("en-US"), pending = listOf("en-US"))
        show()
        state("offline").assertTextEquals("Pending download")
        detail("offline").assertTextContains("Download pending", substring = true)
    }

    @Test
    fun olderPhonesShowUnknownWithHints() {
        fake.support = null
        fake.onDevice = null
        fake.mic = null
        show()
        state("language").assertTextEquals("Unknown")
        state("offline").assertTextEquals("Unknown")
        state("googlemic").assertTextEquals("Unknown")
        fix("offline")
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, nextStarted()!!.action)
    }

    @Test
    fun withoutVoiceSettingsTheOfflineItemGivesAHintInstead() {
        fake.support = null
        fake.onDevice = null
        fake.voiceSettings = false
        show()
        state("offline").assertTextEquals("Unknown")
        detail("offline").assertTextContains("Offline speech recognition", substring = true)
        assertEquals(0, count("voice-check-offline-fix"))
    }

    @Test
    fun checksRerunOnResume() {
        fake.google = GoogleAppState.NotInstalled
        show()
        state("googleapp").assertTextEquals("Missing")
        fake.google = GoogleAppState.Enabled
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor { count("voice-check-googleapp-fix") == 0 }
        state("googleapp").assertTextEquals("OK")
    }

    @Test
    fun togglesSaveTheirSettings() {
        show()
        compose.onNodeWithTag("voice-buttons-switch").performClick()
        waitFor { runBlocking { tasks.settings.get(SettingKeys.VoiceButtons.name) } == "false" }
        waitFor { count("voice-check-buttons-fix") == 1 }
        compose.onNodeWithTag("voice-prefer-offline-switch").performClick()
        waitFor { runBlocking { tasks.settings.get(SettingKeys.VoicePreferOffline.name) } == "true" }
    }

    @Test
    fun testVoiceInputShowsWhatWasHeard() {
        show()
        registry.replyWith("hello there")
        compose.onNodeWithTag("voice-test").performScrollTo().performClick()
        compose.onNodeWithTag("voice-test-result", useUnmergedTree = true).assertTextEquals("Heard: hello there")
    }

    @Test
    fun testVoiceInputIsOffWithoutARecognizer() {
        fake.recognizer = false
        show()
        compose.onNodeWithTag("voice-test").performScrollTo().assertIsNotEnabled()
    }
}
