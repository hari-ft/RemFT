package com.ftapps.remft.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChecklistTest {
    private val ready = VoiceProbe(
        recognizer = true,
        google = GoogleAppState.Enabled,
        googleMic = true,
        language = "en-US",
        support = LanguageSupport(online = listOf("en-US", "hi-IN"), onDevice = listOf("en-US", "hi-IN"), installed = listOf("en-US")),
        onDevice = true,
        voiceSettings = true,
        internet = true,
        buttonsOn = true,
    )

    private fun check(probe: VoiceProbe, id: VoiceCheckId): VoiceCheck = VoiceChecklist.build(probe).single { it.id == id }

    @Test
    fun aReadyPhoneHasEveryCheckOk() {
        val checks = VoiceChecklist.build(ready)
        assertEquals(VoiceCheckId.entries, checks.map { it.id })
        assertTrue(checks.all { it.state == VoiceState.Ok })
        assertTrue(checks.all { it.fix == null })
        assertTrue(VoiceChecklist.offlineReady(ready))
    }

    @Test
    fun missingRecognizerAndGoogleAppPointToThePlayStore() {
        val probe = ready.copy(recognizer = false, google = GoogleAppState.NotInstalled, googleMic = null)
        assertEquals(VoiceCheck(VoiceCheckId.Recognizer, VoiceState.Missing, VoiceDetail.RecognizerMissing, VoiceFix.InstallGoogle), check(probe, VoiceCheckId.Recognizer))
        assertEquals(VoiceCheck(VoiceCheckId.GoogleApp, VoiceState.Missing, VoiceDetail.GoogleMissing, VoiceFix.InstallGoogle), check(probe, VoiceCheckId.GoogleApp))
        assertEquals(VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Unknown, VoiceDetail.MicNeedsGoogle, VoiceFix.InstallGoogle), check(probe, VoiceCheckId.GoogleMic))
    }

    @Test
    fun aDisabledGoogleAppAndBlockedMicOpenAppInfo() {
        val probe = ready.copy(google = GoogleAppState.Disabled, googleMic = false)
        assertEquals(VoiceCheck(VoiceCheckId.GoogleApp, VoiceState.Missing, VoiceDetail.GoogleDisabled, VoiceFix.GoogleAppInfo), check(probe, VoiceCheckId.GoogleApp))
        assertEquals(VoiceCheck(VoiceCheckId.GoogleMic, VoiceState.Missing, VoiceDetail.MicMissing, VoiceFix.GoogleAppInfo), check(probe, VoiceCheckId.GoogleMic))
        assertEquals(VoiceState.Unknown, check(ready.copy(googleMic = null), VoiceCheckId.GoogleMic).state)
    }

    @Test
    fun anUnsupportedLanguageAsksForAnotherOne() {
        val probe = ready.copy(language = "fr-FR")
        assertEquals(VoiceCheck(VoiceCheckId.Language, VoiceState.Missing, VoiceDetail.LanguageMissing, VoiceFix.PickLanguage), check(probe, VoiceCheckId.Language))
        assertEquals(VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineUnavailable, VoiceFix.VoiceSettings), check(probe, VoiceCheckId.Offline))
    }

    @Test
    fun offlinePackStatesFollowTheRecognizerReport() {
        val missing = ready.copy(language = "hi-IN")
        assertEquals(VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineMissing, VoiceFix.DownloadPack), check(missing, VoiceCheckId.Offline))
        assertFalse(VoiceChecklist.offlineReady(missing))
        val pending = missing.copy(support = missing.support!!.copy(pending = listOf("hi-IN")))
        assertEquals(VoiceCheck(VoiceCheckId.Offline, VoiceState.Pending, VoiceDetail.OfflinePending), check(pending, VoiceCheckId.Offline))
        val noOnDevice = ready.copy(support = LanguageSupport(online = listOf("en-US")), onDevice = false)
        assertEquals(VoiceCheck(VoiceCheckId.Offline, VoiceState.Missing, VoiceDetail.OfflineNoOnDevice, VoiceFix.VoiceSettings), check(noOnDevice, VoiceCheckId.Offline))
    }

    @Test
    fun olderPhonesReportUnknownLanguageAndOfflinePack() {
        val older = ready.copy(support = null, onDevice = null)
        assertEquals(VoiceCheck(VoiceCheckId.Language, VoiceState.Unknown, VoiceDetail.LanguageUnknown), check(older, VoiceCheckId.Language))
        assertEquals(VoiceCheck(VoiceCheckId.Offline, VoiceState.Unknown, VoiceDetail.OfflineUnknown, VoiceFix.VoiceSettings), check(older, VoiceCheckId.Offline))
        val noSettings = older.copy(voiceSettings = false)
        val offline = check(noSettings, VoiceCheckId.Offline)
        assertEquals(VoiceDetail.OfflineUnknownNoSettings, offline.detail)
        assertNull(offline.fix)
        assertFalse(VoiceChecklist.offlineReady(older))
        assertTrue(VoiceChecklist.offlineReady(older.copy(onDevice = true)))
    }

    @Test
    fun internetOnlyMattersWithoutTheOfflinePack() {
        assertEquals(VoiceCheck(VoiceCheckId.Internet, VoiceState.Ok, VoiceDetail.InternetNotNeeded), check(ready.copy(internet = false), VoiceCheckId.Internet))
        val noPack = ready.copy(language = "hi-IN", internet = false)
        assertEquals(VoiceCheck(VoiceCheckId.Internet, VoiceState.Missing, VoiceDetail.InternetMissing, VoiceFix.NetworkSettings), check(noPack, VoiceCheckId.Internet))
    }

    @Test
    fun hiddenButtonsCanBeTurnedOn() {
        assertEquals(VoiceCheck(VoiceCheckId.Buttons, VoiceState.Missing, VoiceDetail.ButtonsMissing, VoiceFix.TurnOnButtons), check(ready.copy(buttonsOn = false), VoiceCheckId.Buttons))
    }

    @Test
    fun languageTagsMatchLooselyByCaseSeparatorAndBase() {
        assertTrue(VoiceLanguages.matches(listOf("en_us"), "en-US"))
        assertTrue(VoiceLanguages.matches(listOf("hi"), "hi-IN"))
        assertTrue(VoiceLanguages.matches(listOf("en-GB"), "en"))
        assertFalse(VoiceLanguages.matches(listOf("en-GB"), "en-US"))
        assertFalse(VoiceLanguages.matches(emptyList(), "en-US"))
    }
}
