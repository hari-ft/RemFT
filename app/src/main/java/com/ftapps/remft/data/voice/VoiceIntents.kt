package com.ftapps.remft.data.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent

sealed interface VoiceOutcome {
    data class Heard(val text: String, val count: Int) : VoiceOutcome
    data object Cancelled : VoiceOutcome
    data object NoMatch : VoiceOutcome
    data object Network : VoiceOutcome
    data object Failed : VoiceOutcome
    data object NoRecognizer : VoiceOutcome
}

object VoiceIntents {
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    const val OFFLINE_PACK_ACTIVITY = "com.google.android.voicesearch.greco3.languagepack.InstallActivity"

    fun recognize(prompt: String, language: String, preferOffline: Boolean, recognizerPackage: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (recognizerPackage != null) setPackage(recognizerPackage)
        }

    fun playStore(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_PACKAGE"))

    fun playStoreWeb(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_PACKAGE"))

    fun googleAppInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", GOOGLE_PACKAGE, null))

    fun offlinePack(): Intent = Intent().setComponent(ComponentName(GOOGLE_PACKAGE, OFFLINE_PACK_ACTIVITY))

    fun voiceInputSettings(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    fun networkSettings(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)

    fun outcome(resultCode: Int, data: Intent?): VoiceOutcome = when (resultCode) {
        Activity.RESULT_OK -> {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            results.firstOrNull()?.let { VoiceOutcome.Heard(it, results.size) } ?: VoiceOutcome.NoMatch
        }
        Activity.RESULT_CANCELED -> VoiceOutcome.Cancelled
        RecognizerIntent.RESULT_NO_MATCH -> VoiceOutcome.NoMatch
        RecognizerIntent.RESULT_NETWORK_ERROR -> VoiceOutcome.Network
        else -> VoiceOutcome.Failed
    }

    fun code(outcome: VoiceOutcome): String = when (outcome) {
        is VoiceOutcome.Heard -> "ok"
        VoiceOutcome.Cancelled -> "cancelled"
        VoiceOutcome.NoMatch -> "no_match"
        VoiceOutcome.Network -> "network"
        VoiceOutcome.Failed -> "error"
        VoiceOutcome.NoRecognizer -> "no_recognizer"
    }
}

object VoiceText {
    fun append(existing: String, spoken: String): String {
        val heard = spoken.trim()
        if (heard.isEmpty()) return existing
        if (existing.isEmpty()) return heard
        return if (existing.last().isWhitespace()) existing + heard else "$existing $heard"
    }

    fun insertion(text: String, start: Int, end: Int, spoken: String): String {
        val heard = spoken.trim()
        if (heard.isEmpty()) return ""
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        val lead = if (before != null && !before.isWhitespace()) " " else ""
        val trail = if (after != null && !after.isWhitespace()) " " else ""
        return lead + heard + trail
    }
}
