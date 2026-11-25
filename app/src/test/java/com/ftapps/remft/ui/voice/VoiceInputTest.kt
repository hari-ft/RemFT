package com.ftapps.remft.ui.voice

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AppLog
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.voice.VoiceIntents
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.testing.FakeVoiceSupport
import com.ftapps.remft.testing.TestFiles
import com.ftapps.remft.testing.VoiceRegistry
import com.ftapps.remft.ui.notes.editor.NoteEditorScreen
import com.ftapps.remft.ui.notes.editor.NoteEditorViewModel
import com.ftapps.remft.ui.notes.export.NoteExportViewModel
import com.ftapps.remft.ui.notes.export.NoteExporter
import com.ftapps.remft.ui.tasks.HomeTasksScreen
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TaskCreateSheet
import com.ftapps.remft.ui.tasks.onAllNodesWithTagCount
import com.ftapps.remft.ui.theme.RemftTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1800dp")
class VoiceInputTest {
    @get:Rule
    val compose = createComposeRule()

    private val rig = TestFiles()
    private val tasks = rig.tasks
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fake = FakeVoiceSupport()
    private val registry = VoiceRegistry()
    private val voice by lazy { VoiceEnvironment(fake, tasks.settings) }

    @After
    fun tearDown() = rig.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

    private fun count(tag: String) = compose.onAllNodesWithTagCount(tag)

    private fun show(content: @Composable () -> Unit) {
        val environment = voice
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides registry.owner,
                    LocalTasksEnvironment provides tasks.environment,
                    LocalVoiceEnvironment provides environment,
                ) { content() }
            }
        }
    }

    private fun showHome() = show { HomeTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}, onOpenTask = {}) }

    @Test
    fun quickAddMicAppendsSpokenTextSoChipsShowWithoutSubmitting() {
        showHome()
        waitFor { count("voice-home-quick-add") == 1 }
        compose.onNodeWithTag("home-quick-add").performTextInput("pay rent")
        registry.replyWith("tomorrow 9am", "tomorrow 9 am")
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        waitFor { count("home-quick-add-token-due") == 1 }
        compose.onNodeWithTag("home-quick-add").assert(hasText("pay rent tomorrow 9am"))
        assertEquals(0, tasks.query("SELECT id FROM tasks").size)
        val intent = registry.launched.single()
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("Speak a task", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
        assertEquals("en-US", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertEquals(VoiceIntents.GOOGLE_PACKAGE, intent.`package`)
        compose.onNodeWithTag("home-quick-add-submit").performClick()
        waitFor { tasks.query("SELECT id FROM tasks").size == 1 }
    }

    @Test
    fun resultsAreLoggedWithCodesAndCountsButNeverTheSpokenText() {
        val previous = AppLogs.current
        val log = AppLog(java.nio.file.Files.createTempDirectory("voice-log").toFile())
        AppLogs.install(log)
        try {
            showHome()
            waitFor { count("voice-home-quick-add") == 1 }
            registry.replyWith("secret plans", "secret plan")
            compose.onNodeWithTag("voice-home-quick-add").performClick()
            log.flush()
            val entry = log.read().first { it.event == "voice.result" }
            assertEquals(mapOf("code" to "ok", "count" to "2"), entry.fields)
            assertFalse(log.read().any { e -> e.fields.values.any { it.contains("secret") } })
        } finally {
            AppLogs.install(previous)
        }
    }

    @Test
    fun micsAreHiddenWhenNoRecognizerIsAvailable() {
        fake.recognizer = false
        showHome()
        waitFor { count("home-quick-add") == 1 }
        compose.onNodeWithTag("home-search-toggle").performClick()
        waitFor { count("home-search") == 1 }
        assertEquals(0, count("voice-home-quick-add"))
        assertEquals(0, count("voice-home-search"))
    }

    @Test
    fun micsAreHiddenWhenTurnedOffInSettingsAndReturnWhenOn() {
        runBlocking { tasks.settings.set(SettingKeys.VoiceButtons.name, "false") }
        showHome()
        waitFor { count("home-quick-add") == 1 }
        assertEquals(0, count("voice-home-quick-add"))
        runBlocking { tasks.settings.set(SettingKeys.VoiceButtons.name, "true") }
        waitFor { count("voice-home-quick-add") == 1 }
    }

    @Test
    fun micsAreHiddenWithoutAVoiceEnvironment() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) {
                    HomeTasksScreen(bottomPadding = 0.dp, onOpenSettings = {}, onOpenTask = {})
                }
            }
        }
        waitFor { count("home-quick-add") == 1 }
        assertEquals(0, count("voice-home-quick-add"))
    }

    @Test
    fun homeSearchMicFillsTheQuery() {
        showHome()
        waitFor { count("home-search-toggle") == 1 }
        compose.onNodeWithTag("home-search-toggle").performClick()
        waitFor { count("voice-home-search") == 1 }
        registry.replyWith(" rent receipt ")
        compose.onNodeWithTag("voice-home-search").performClick()
        compose.onNodeWithTag("home-search").assert(hasText("rent receipt"))
        assertEquals("Speak to search", registry.launched.single().getStringExtra(RecognizerIntent.EXTRA_PROMPT))
    }

    @Test
    fun newTaskSheetMicsAppendToTheTitleAndNotes() {
        show { TaskCreateSheet(onDismiss = {}) }
        waitFor { count("voice-task-title") == 1 && count("voice-task-notes") == 1 }
        compose.onNodeWithTag("task-sheet-title").performTextReplacement("Call")
        registry.replyWith("the bank")
        compose.onNodeWithTag("voice-task-title").performClick()
        compose.onNodeWithTag("task-sheet-title").assert(hasText("Call the bank"))
        registry.replyWith("ask about the loan")
        compose.onNodeWithTag("voice-task-notes").performClick()
        compose.onNodeWithTag("task-sheet-description").assert(hasText("ask about the loan"))
        assertEquals(listOf("Speak the title", "Speak the notes"), registry.launched.map { it.getStringExtra(RecognizerIntent.EXTRA_PROMPT) })
    }

    @Test
    fun noteEditorMicInsertsAtTheCursor() {
        val noteId = runBlocking { rig.notes.create("Shopping", "Buy milk") }
        val viewModel = NoteEditorViewModel(
            SavedStateHandle(mapOf("id" to noteId, "external" to false)),
            rig.notes,
            ExternalDocumentStore(context, rig.settings),
            rig.settings,
            tasks.environment,
        )
        val export = NoteExportViewModel(NoteExporter(context, rig.notes), rig.notes, rig.settings)
        show { NoteEditorScreen(viewModel, export, onBack = {}) }
        waitFor { viewModel.source.text == "Buy milk" && count("voice-note") == 1 }
        compose.runOnIdle { viewModel.select(3, 3) }
        registry.replyWith("oat")
        compose.onNodeWithTag("voice-note").performClick()
        waitFor { viewModel.source.text == "Buy oat milk" }
        assertEquals(7, viewModel.source.selection.start)
        compose.runOnIdle { viewModel.select(4, 7) }
        registry.replyWith("soy")
        compose.onNodeWithTag("voice-note").performClick()
        waitFor { viewModel.source.text == "Buy soy milk" }
    }

    @Test
    fun chosenLanguageAndOfflinePackGoOnTheIntent() {
        fake.support = fake.support!!.copy(installed = listOf("hi-IN"))
        runBlocking {
            tasks.settings.set(SettingKeys.VoiceLanguage.name, "hi-IN")
            tasks.settings.set(SettingKeys.VoicePreferOffline.name, "true")
        }
        showHome()
        waitFor { count("voice-home-quick-add") == 1 && voice.offlineReady("hi-IN") }
        registry.replyWith("kal")
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        val intent = registry.launched.single()
        assertEquals("hi-IN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
    }

    @Test
    fun preferOfflineIsLeftOffWhenThePackIsMissing() {
        runBlocking {
            tasks.settings.set(SettingKeys.VoiceLanguage.name, "en-IN")
            tasks.settings.set(SettingKeys.VoicePreferOffline.name, "true")
        }
        showHome()
        waitFor { count("voice-home-quick-add") == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        val intent = registry.launched.single()
        assertEquals("en-IN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
    }

    @Test
    fun cancelLeavesTheTextAloneSilently() {
        showHome()
        waitFor { count("voice-home-quick-add") == 1 }
        compose.onNodeWithTag("home-quick-add").performTextInput("pay rent")
        registry.replyResult(Activity.RESULT_CANCELED)
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        compose.onNodeWithTag("home-quick-add").assert(hasText("pay rent"))
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun noMatchNetworkAndMissingRecognizerExplainThemselves() {
        showHome()
        waitFor { count("voice-home-quick-add") == 1 }
        registry.replyResult(RecognizerIntent.RESULT_NO_MATCH)
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        assertEquals("Didn’t catch that. Try again.", ShadowToast.getTextOfLatestToast())
        registry.replyResult(RecognizerIntent.RESULT_NETWORK_ERROR)
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        assertEquals("Voice typing needs the internet or an offline language pack.", ShadowToast.getTextOfLatestToast())
        registry.replyWith()
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        assertEquals("Didn’t catch that. Try again.", ShadowToast.getTextOfLatestToast())
        registry.missing = true
        compose.onNodeWithTag("voice-home-quick-add").performClick()
        assertEquals("No voice typing app found. See Settings › Voice input.", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithTag("home-quick-add").assert(hasText(""))
    }
}
