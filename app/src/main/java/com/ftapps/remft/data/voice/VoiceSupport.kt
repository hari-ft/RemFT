package com.ftapps.remft.data.voice

import android.content.Intent

enum class GoogleAppState { NotInstalled, Disabled, Enabled }

enum class VoiceState { Ok, Missing, Pending, Unknown }

enum class VoiceCheckId { Recognizer, GoogleApp, GoogleMic, Language, Offline, Internet, Buttons }

enum class VoiceFix { InstallGoogle, GoogleAppInfo, PickLanguage, DownloadPack, VoiceSettings, NetworkSettings, TurnOnButtons }

enum class VoiceDetail {
    RecognizerOk, RecognizerMissing,
    GoogleOk, GoogleMissing, GoogleDisabled,
    MicOk, MicMissing, MicUnknown, MicNeedsGoogle,
    LanguageOk, LanguageMissing, LanguageUnknown,
    OfflineOk, OfflinePending, OfflineMissing, OfflineUnavailable, OfflineNoOnDevice, OfflineUnknown, OfflineUnknownNoSettings,
    InternetOk, InternetNotNeeded, InternetMissing,
    ButtonsOk, ButtonsMissing,
}

data class VoiceCheck(val id: VoiceCheckId, val state: VoiceState, val detail: VoiceDetail, val fix: VoiceFix? = null)

data class LanguageSupport(
    val online: List<String> = emptyList(),
    val onDevice: List<String> = emptyList(),
    val installed: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
) {
    val supported: List<String> get() = (online + onDevice + installed + pending).distinct()
}

data class VoiceProbe(
    val recognizer: Boolean,
    val google: GoogleAppState,
    val googleMic: Boolean?,
    val language: String,
    val support: LanguageSupport?,
    val onDevice: Boolean?,
    val voiceSettings: Boolean,
    val internet: Boolean,
    val buttonsOn: Boolean,
)

interface VoiceSupport {
    fun recognizerAvailable(): Boolean

    fun googleApp(): GoogleAppState

    fun googleMicAllowed(): Boolean?

    fun recognizerPackage(): String?

    fun phoneLanguage(): String

    fun onDeviceAvailable(): Boolean?

    fun internetAvailable(): Boolean

    suspend fun languageSupport(language: String): LanguageSupport?

    fun requestDownload(language: String): Boolean

    fun fixIntent(fix: VoiceFix): Intent?
}

object VoiceLanguages {
    val fallback: List<String> = listOf(
        "en-US", "en-GB", "en-IN", "en-AU", "en-CA", "hi-IN", "bn-IN", "gu-IN", "kn-IN", "ml-IN", "mr-IN", "ta-IN", "te-IN", "ur-IN",
        "ar-SA", "de-DE", "es-ES", "es-US", "fr-FR", "id-ID", "it-IT", "ja-JP", "ko-KR", "nl-NL", "pl-PL", "pt-BR", "ru-RU", "tr-TR", "zh-CN",
    )

    fun normalize(tag: String): String = tag.trim().replace('_', '-').lowercase()

    fun matches(list: List<String>, language: String): Boolean {
        val wanted = normalize(language)
        if (wanted.isEmpty()) return false
        val base = wanted.substringBefore('-')
        return list.any { candidate ->
            val have = normalize(candidate)
            have == wanted || have == base || (!wanted.contains('-') && have.substringBefore('-') == base)
        }
    }
}

object VoiceChecklist {
    fun build(probe: VoiceProbe): List<VoiceCheck> {
        val offline = offline(probe)
        return listOf(
            recognizer(probe),
            google(probe),
            mic(probe),
            language(probe),
            offline,
            internet(probe, offline.state == VoiceState.Ok),
            if (probe.buttonsOn) {
                VoiceCheck(VoiceCheckId.Buttons, VoiceState.Ok, VoiceDetail.ButtonsOk)
            } else {
                VoiceCheck(VoiceCheckId.Buttons, VoiceState.Missing, VoiceDetail.ButtonsMissing, VoiceFix.TurnOnButtons)
            },
        )
    }

    fun offlineReady(probe: VoiceProbe): Boolean {
        val state = offline(probe).state
        return state == VoiceState.Ok || (state == VoiceState.Unknown && probe.onDevice == true)
    }

    private fun recognizer(probe: VoiceProbe): VoiceCheck = if (probe.recognizer) {
        VoiceCheck(VoiceCheckId.Recognizer, VoiceState.Ok, VoiceDetail.RecognizerOk)
    } else {
        VoiceCheck(VoiceCheckId.Recognizer, VoiceState.Missing, VoiceDetail.RecognizerMissing, VoiceFix.InstallGoogle)
    }

    private fun google(probe: VoiceProbe): VoiceCheck = when (probe.google) {
        GoogleAppState.Enabled -> VoiceCheck(VoiceCheckId.GoogleApp, VoiceState.Ok, VoiceDetail.GoogleOk)
        GoogleAppState.Disabled -> VoiceCheck(VoiceCheckId.GoogleApp, VoiceState.Missing, VoiceDetail.GoogleDisabled, VoiceFix.GoogleAppInfo)
        GoogleAppState.NotInstalled -> VoiceCheck(VoiceCheckId.GoogleApp, VoiceState.Missing, VoiceDetail.GoogleMissing, VoiceFix.InstallGoogle)
    }

    private fun mic(probe: VoiceProbe): VoiceCheck = when {
        probe.google == GoogleAppState.NotInstalled -> VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Unknown, VoiceDetail.MicNeedsGoogle, VoiceFix.InstallGoogle)
        probe.googleMic == true -> VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Ok, VoiceDetail.MicOk)
        probe.googleMic == false -> VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Missing, VoiceDetail.MicMissing, VoiceFix.GoogleAppInfo)
        else -> VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Unknown, VoiceDetail.MicUnknown, VoiceFix.GoogleAppInfo)
    }

    private fun language(probe: VoiceProbe): VoiceCheck {
        val support = probe.support
        return when {
            support == null || support.supported.isEmpty() -> VoiceCheck(VoiceCheckId.Language, VoiceState.Unknown, VoiceDetail.LanguageUnknown)
            VoiceLanguages.matches(support.supported, probe.language) -> VoiceCheck(VoiceCheckId.Language, VoiceState.Ok, VoiceDetail.LanguageOk)
            else -> VoiceCheck(VoiceCheckId.Language, VoiceState.Missing, VoiceDetail.LanguageMissing, VoiceFix.PickLanguage)
        }
    }

    private fun offline(probe: VoiceProbe): VoiceCheck {
        val support = probe.support
        val unknownFix = if (probe.voiceSettings) VoiceFix.VoiceSettings else null
        val unknownDetail = if (probe.voiceSettings) VoiceDetail.OfflineUnknown else VoiceDetail.OfflineUnknownNoSettings
        return when {
            support != null && VoiceLanguages.matches(support.installed, probe.language) ->
                VoiceCheck(VoiceCheckId.Offline, VoiceState.Ok, VoiceDetail.OfflineOk)
            support != null && VoiceLanguages.matches(support.pending, probe.language) ->
                VoiceCheck(VoiceCheckId.Offline, VoiceState.Pending, VoiceDetail.OfflinePending)
            support != null && VoiceLanguages.matches(support.onDevice, probe.language) ->
                VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineMissing, VoiceFix.DownloadPack)
            probe.onDevice == false ->
                VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineNoOnDevice, unknownFix)
            support != null && (support.onDevice.isNotEmpty() || support.installed.isNotEmpty()) ->
                VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineUnavailable, unknownFix)
            else -> VoiceCheck(VoiceCheckId.Offline, VoiceState.Unknown, unknownDetail, unknownFix)
        }
    }

    private fun internet(probe: VoiceProbe, offlineOk: Boolean): VoiceCheck = when {
        probe.internet -> VoiceCheck(VoiceCheckId.Internet, VoiceState.Ok, VoiceDetail.InternetOk)
        offlineOk -> VoiceCheck(VoiceCheckId.Internet, VoiceState.Ok, VoiceDetail.InternetNotNeeded)
        else -> VoiceCheck(VoiceCheckId.Internet, VoiceState.Missing, VoiceDetail.InternetMissing, VoiceFix.NetworkSettings)
    }
}
