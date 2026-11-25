package com.ftapps.remft.ui.voice

import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.voice.LanguageSupport
import com.ftapps.remft.data.voice.VoiceChecklist
import com.ftapps.remft.data.voice.VoiceFix
import com.ftapps.remft.data.voice.VoiceIntents
import com.ftapps.remft.data.voice.VoiceLanguages
import com.ftapps.remft.data.voice.VoiceProbe
import com.ftapps.remft.data.voice.VoiceSupport
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class VoiceEnvironment(val support: VoiceSupport, private val settings: SettingsStore) {
    private val availableState = MutableStateFlow(false)
    private val offlineState = MutableStateFlow<Pair<String, Boolean>?>(null)

    val available: StateFlow<Boolean> = availableState.asStateFlow()
    val buttonsOn: Flow<Boolean> = settings.flow(SettingKeys.VoiceButtons).map { it != "false" }
    val languageSetting: Flow<String?> = settings.flow(SettingKeys.VoiceLanguage).map { it?.takeIf(String::isNotBlank) }
    val preferOffline: Flow<Boolean> = settings.flow(SettingKeys.VoicePreferOffline).map { it == "true" }
    val showButtons: Flow<Boolean> = combine(buttonsOn, availableState) { on, available -> on && available }

    init {
        refresh()
    }

    fun refresh() {
        availableState.value = runCatching { support.recognizerAvailable() }.getOrDefault(false)
    }

    fun language(setting: String?): String = setting?.takeIf { it.isNotBlank() } ?: support.phoneLanguage()

    suspend fun probe(): VoiceProbe {
        refresh()
        val language = language(settings.get(SettingKeys.VoiceLanguage.name))
        val probe = VoiceProbe(
            recognizer = availableState.value,
            google = support.googleApp(),
            googleMic = support.googleMicAllowed(),
            language = language,
            support = support.languageSupport(language),
            onDevice = support.onDeviceAvailable(),
            voiceSettings = support.fixIntent(VoiceFix.VoiceSettings) != null,
            internet = support.internetAvailable(),
            buttonsOn = settings.value(SettingKeys.VoiceButtons) != "false",
        )
        offlineState.value = language to VoiceChecklist.offlineReady(probe)
        AppLogs.info("voice.check", VoiceChecklist.build(probe).associate { it.id.name.lowercase() to it.state })
        return probe
    }

    suspend fun warmOffline(language: String) {
        if (offlineState.value?.first == language) return
        val probe = VoiceProbe(
            recognizer = availableState.value,
            google = support.googleApp(),
            googleMic = null,
            language = language,
            support = support.languageSupport(language),
            onDevice = support.onDeviceAvailable(),
            voiceSettings = false,
            internet = true,
            buttonsOn = true,
        )
        offlineState.value = language to VoiceChecklist.offlineReady(probe)
    }

    fun offlineReady(language: String): Boolean = offlineState.value?.let { (cached, ready) -> cached == language && ready } == true

    fun recognizeIntent(prompt: String, languageSetting: String?, preferOffline: Boolean): Intent {
        val language = language(languageSetting)
        return VoiceIntents.recognize(prompt, language, preferOffline && offlineReady(language), support.recognizerPackage())
    }

    fun languages(support: LanguageSupport?): List<String> = support?.supported?.takeIf { it.isNotEmpty() } ?: VoiceLanguages.fallback

    suspend fun setButtons(on: Boolean) {
        settings.set(SettingKeys.VoiceButtons.name, on.toString())
    }

    suspend fun setPreferOffline(on: Boolean) {
        settings.set(SettingKeys.VoicePreferOffline.name, on.toString())
    }

    suspend fun setLanguage(tag: String?) {
        if (tag == null) settings.remove(SettingKeys.VoiceLanguage.name) else settings.set(SettingKeys.VoiceLanguage.name, tag)
    }
}

val LocalVoiceEnvironment = staticCompositionLocalOf<VoiceEnvironment?> { null }
