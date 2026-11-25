package com.ftapps.remft.data.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class VoiceIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun recognizeCarriesTheModelPromptLanguageAndOfflineChoice() {
        val intent = VoiceIntents.recognize("Speak a task", "hi-IN", preferOffline = true, recognizerPackage = VoiceIntents.GOOGLE_PACKAGE)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("Speak a task", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
        assertEquals("hi-IN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(VoiceIntents.GOOGLE_PACKAGE, intent.`package`)

        val online = VoiceIntents.recognize("Speak", "en-US", preferOffline = false, recognizerPackage = null)
        assertFalse(online.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        assertNull(online.`package`)
    }

    @Test
    fun resultsMapToOutcomes() {
        val heard = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("  buy milk ", "by milk"))
        assertEquals(VoiceOutcome.Heard("buy milk", 2), VoiceIntents.outcome(Activity.RESULT_OK, heard))
        assertEquals(VoiceOutcome.NoMatch, VoiceIntents.outcome(Activity.RESULT_OK, Intent()))
        assertEquals(VoiceOutcome.NoMatch, VoiceIntents.outcome(Activity.RESULT_OK, null))
        assertEquals(VoiceOutcome.Cancelled, VoiceIntents.outcome(Activity.RESULT_CANCELED, null))
        assertEquals(VoiceOutcome.NoMatch, VoiceIntents.outcome(RecognizerIntent.RESULT_NO_MATCH, null))
        assertEquals(VoiceOutcome.Network, VoiceIntents.outcome(RecognizerIntent.RESULT_NETWORK_ERROR, null))
        assertEquals(VoiceOutcome.Failed, VoiceIntents.outcome(RecognizerIntent.RESULT_AUDIO_ERROR, null))
        assertEquals(VoiceOutcome.Failed, VoiceIntents.outcome(RecognizerIntent.RESULT_SERVER_ERROR, null))
        assertEquals("ok", VoiceIntents.code(VoiceOutcome.Heard("x", 1)))
        assertEquals("no_match", VoiceIntents.code(VoiceOutcome.NoMatch))
    }

    @Test
    fun spokenTextIsSpacedAgainstItsNeighbours() {
        assertEquals("buy milk", VoiceText.append("", " buy milk "))
        assertEquals("buy milk tomorrow", VoiceText.append("buy milk", "tomorrow"))
        assertEquals("buy milk tomorrow", VoiceText.append("buy milk ", "tomorrow"))
        assertEquals("buy milk", VoiceText.append("buy milk", "  "))
        assertEquals(" and eggs", VoiceText.insertion("Buy milk", 3, 3, "and eggs"))
        assertEquals("and eggs ", VoiceText.insertion("Buy milk", 0, 0, "and eggs"))
        assertEquals("bread", VoiceText.insertion("Buy milk now", 4, 8, "bread"))
        assertEquals("bread", VoiceText.insertion("", 0, 0, "bread"))
    }

    @Test
    fun fixIntentsTargetTheGoogleAppAndSystemSettings() {
        val support = AndroidVoiceSupport(context)
        val store = support.fixIntent(VoiceFix.InstallGoogle)!!
        assertEquals("https://play.google.com/store/apps/details?id=${VoiceIntents.GOOGLE_PACKAGE}", store.dataString)

        shadowOf(context.packageManager).addResolveInfoForIntent(VoiceIntents.playStore(), resolveInfo("com.android.vending", "Store"))
        assertEquals("market://details?id=${VoiceIntents.GOOGLE_PACKAGE}", support.fixIntent(VoiceFix.InstallGoogle)!!.dataString)

        val info = support.fixIntent(VoiceFix.GoogleAppInfo)!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.action)
        assertEquals("package:${VoiceIntents.GOOGLE_PACKAGE}", info.dataString)

        assertNull(support.fixIntent(VoiceFix.VoiceSettings))
        shadowOf(context.packageManager).addResolveInfoForIntent(VoiceIntents.voiceInputSettings(), resolveInfo("com.android.settings", "Voice"))
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, support.fixIntent(VoiceFix.VoiceSettings)!!.action)

        shadowOf(context.packageManager).addResolveInfoForIntent(VoiceIntents.offlinePack(), resolveInfo(VoiceIntents.GOOGLE_PACKAGE, VoiceIntents.OFFLINE_PACK_ACTIVITY))
        assertEquals(VoiceIntents.OFFLINE_PACK_ACTIVITY, support.fixIntent(VoiceFix.VoiceSettings)!!.component!!.className)

        assertNull(support.fixIntent(VoiceFix.TurnOnButtons))
    }

    @Test
    fun androidSupportReadsTheGoogleAppAndRecognizer() {
        val support = AndroidVoiceSupport(context)
        assertEquals(GoogleAppState.NotInstalled, support.googleApp())
        assertNull(support.recognizerPackage())
        assertFalse(support.recognizerAvailable())

        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            resolveInfo(VoiceIntents.GOOGLE_PACKAGE, "com.google.android.voicesearch.IntentApiActivity"),
        )
        assertTrue(support.recognizerAvailable())
    }

    private fun resolveInfo(packageName: String, name: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = name
            exported = true
        }
    }
}
