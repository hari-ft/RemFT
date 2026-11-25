package com.ftapps.remft.testing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.ftapps.remft.data.voice.GoogleAppState
import com.ftapps.remft.data.voice.LanguageSupport
import com.ftapps.remft.data.voice.VoiceFix
import com.ftapps.remft.data.voice.VoiceIntents
import com.ftapps.remft.data.voice.VoiceSupport

class FakeVoiceSupport(
    var recognizer: Boolean = true,
    var google: GoogleAppState = GoogleAppState.Enabled,
    var mic: Boolean? = true,
    var phone: String = "en-US",
    var onDevice: Boolean? = true,
    var internet: Boolean = true,
    var support: LanguageSupport? = LanguageSupport(
        online = listOf("en-US", "en-IN", "hi-IN"),
        onDevice = listOf("en-US", "en-IN", "hi-IN"),
        installed = listOf("en-US"),
    ),
    var voiceSettings: Boolean = true,
    var downloadWorks: Boolean = true,
) : VoiceSupport {
    val downloads = mutableListOf<String>()

    override fun recognizerAvailable(): Boolean = recognizer

    override fun googleApp(): GoogleAppState = google

    override fun googleMicAllowed(): Boolean? = mic

    override fun recognizerPackage(): String? = VoiceIntents.GOOGLE_PACKAGE.takeIf { google == GoogleAppState.Enabled }

    override fun phoneLanguage(): String = phone

    override fun onDeviceAvailable(): Boolean? = onDevice

    override fun internetAvailable(): Boolean = internet

    override suspend fun languageSupport(language: String): LanguageSupport? = support

    override fun requestDownload(language: String): Boolean {
        downloads += language
        return downloadWorks
    }

    override fun fixIntent(fix: VoiceFix): Intent? = when (fix) {
        VoiceFix.InstallGoogle -> VoiceIntents.playStoreWeb()
        VoiceFix.GoogleAppInfo -> VoiceIntents.googleAppInfo()
        VoiceFix.VoiceSettings -> if (voiceSettings) VoiceIntents.voiceInputSettings() else null
        VoiceFix.NetworkSettings -> VoiceIntents.networkSettings()
        VoiceFix.PickLanguage, VoiceFix.DownloadPack, VoiceFix.TurnOnButtons -> null
    }
}

class VoiceRegistry : ActivityResultRegistry() {
    val launched = mutableListOf<Intent>()
    var replyCode: Int = Activity.RESULT_OK
    var replyData: Intent? = null
    var missing = false

    fun replyWith(vararg results: String) {
        replyCode = Activity.RESULT_OK
        replyData = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*results))
    }

    fun replyResult(code: Int) {
        replyCode = code
        replyData = null
    }

    val owner: ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = this@VoiceRegistry
    }

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launched += input as Intent
        if (missing) throw ActivityNotFoundException()
        dispatchResult(requestCode, replyCode, replyData)
    }
}
