package com.ftapps.remft.ui.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RingFeedback(val playing: Boolean = false, val fallback: Boolean = false, val blocked: Boolean = false)

interface AlarmFeedback {
    val state: StateFlow<RingFeedback>

    fun start()

    fun stop()

    fun pulse()
}

class SilentFeedback : AlarmFeedback {
    override val state: StateFlow<RingFeedback> = MutableStateFlow(RingFeedback())

    override fun start() = Unit

    override fun stop() = Unit

    override fun pulse() = Unit
}

class MediaAlarmFeedback(
    private val context: Context,
    private val chosen: () -> Uri?,
    private val fallback: () -> Uri?,
) : AlarmFeedback {
    private val mutable = MutableStateFlow(RingFeedback())
    override val state: StateFlow<RingFeedback> = mutable.asStateFlow()
    private var player: MediaPlayer? = null

    override fun start() {
        stopPlayer()
        val primary = runCatching { chosen() }.getOrNull()
        if (primary != null && play(primary)) {
            mutable.value = RingFeedback(playing = true)
            return
        }
        val backup = runCatching { fallback() }.getOrNull()
        if (backup != null && play(backup)) {
            mutable.value = RingFeedback(playing = true, fallback = primary != null)
            return
        }
        mutable.value = RingFeedback(blocked = true)
    }

    private fun play(uri: Uri): Boolean = try {
        val next = MediaPlayer()
        next.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        next.setDataSource(context, uri)
        next.isLooping = true
        next.prepare()
        next.start()
        player = next
        true
    } catch (_: Exception) {
        false
    }

    override fun stop() {
        stopPlayer()
        mutable.value = mutable.value.copy(playing = false)
    }

    private fun stopPlayer() {
        player?.let { current ->
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        player = null
    }

    override fun pulse() {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(HAPTIC_MS)
            }
        }
    }

    companion object {
        private const val HAPTIC_MS = 60L
    }
}
