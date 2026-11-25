package com.ftapps.remft.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.ftapps.remft.data.alarm.AppLogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class AndroidVoiceSupport(private val context: Context) : VoiceSupport {
    private val packages: PackageManager get() = context.packageManager

    override fun recognizerAvailable(): Boolean {
        val resolves = runCatching { packages.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }.getOrDefault(false)
        return resolves || runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }

    override fun googleApp(): GoogleAppState = try {
        if (packages.getApplicationInfo(VoiceIntents.GOOGLE_PACKAGE, 0).enabled) GoogleAppState.Enabled else GoogleAppState.Disabled
    } catch (_: PackageManager.NameNotFoundException) {
        GoogleAppState.NotInstalled
    } catch (_: RuntimeException) {
        GoogleAppState.NotInstalled
    }

    override fun googleMicAllowed(): Boolean? = runCatching {
        packages.checkPermission(Manifest.permission.RECORD_AUDIO, VoiceIntents.GOOGLE_PACKAGE) == PackageManager.PERMISSION_GRANTED
    }.getOrNull()

    override fun recognizerPackage(): String? {
        if (googleApp() != GoogleAppState.Enabled) return null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setPackage(VoiceIntents.GOOGLE_PACKAGE)
        return VoiceIntents.GOOGLE_PACKAGE.takeIf { runCatching { packages.queryIntentActivities(intent, 0).isNotEmpty() }.getOrDefault(false) }
    }

    override fun phoneLanguage(): String = Locale.getDefault().toLanguageTag()

    override fun onDeviceAvailable(): Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrNull()
    } else {
        null
    }

    override fun internetAvailable(): Boolean = runCatching {
        val manager = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return@runCatching false
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    override suspend fun languageSupport(language: String): LanguageSupport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return try {
            withContext(Dispatchers.Main) {
                val online = query(language, onDevice = false)
                val local = if (onDeviceAvailable() == true) query(language, onDevice = true) else null
                if (online == null && local == null) {
                    null
                } else {
                    LanguageSupport(
                        online = online?.online.orEmpty(),
                        onDevice = (online?.onDevice.orEmpty() + local?.onDevice.orEmpty()).distinct(),
                        installed = (online?.installed.orEmpty() + local?.installed.orEmpty()).distinct(),
                        pending = (online?.pending.orEmpty() + local?.pending.orEmpty()).distinct(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogs.warn("voice.support_failed", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun query(language: String, onDevice: Boolean): LanguageSupport? {
        val recognizer = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: return null
        return try {
            withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    LanguageSupport(
                                        online = support.onlineLanguages,
                                        onDevice = support.supportedOnDeviceLanguages,
                                        installed = support.installedOnDeviceLanguages,
                                        pending = support.pendingOnDeviceLanguages,
                                    ),
                                )
                            }
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    recognizer.checkRecognitionSupport(recognizeIntent(language), ContextCompat.getMainExecutor(context), callback)
                }
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    override fun requestDownload(language: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val recognizer = if (onDeviceAvailable() == true) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer.triggerModelDownload(recognizeIntent(language))
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { recognizer.destroy() } }, DOWNLOAD_HOLD_MS)
            true
        }.getOrElse {
            AppLogs.warn("voice.download_failed", it)
            false
        }
    }

    override fun fixIntent(fix: VoiceFix): Intent? = when (fix) {
        VoiceFix.InstallGoogle -> firstResolving(VoiceIntents.playStore()) ?: VoiceIntents.playStoreWeb()
        VoiceFix.GoogleAppInfo -> VoiceIntents.googleAppInfo()
        VoiceFix.VoiceSettings -> voiceSettingsIntent()
        VoiceFix.NetworkSettings -> firstResolving(VoiceIntents.networkSettings())
        VoiceFix.PickLanguage, VoiceFix.DownloadPack, VoiceFix.TurnOnButtons -> null
    }

    fun voiceSettingsIntent(): Intent? {
        val pack = VoiceIntents.offlinePack()
        val packInfo = runCatching { packages.resolveActivity(pack, 0) }.getOrNull()
        if (packInfo?.activityInfo?.exported == true) return pack
        return firstResolving(VoiceIntents.voiceInputSettings())
    }

    private fun firstResolving(vararg intents: Intent): Intent? =
        intents.firstOrNull { runCatching { packages.resolveActivity(it, 0) != null }.getOrDefault(false) }

    private fun recognizeIntent(language: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)

    private companion object {
        const val QUERY_TIMEOUT_MS = 4_000L
        const val DOWNLOAD_HOLD_MS = 15_000L
    }
}
