package com.ftapps.remft.domain.alarm

import java.io.ByteArrayOutputStream

data class BundledSound(val id: String, val androidRaw: String, val seconds: Double)

sealed interface SoundChoice {
    fun encode(): String

    val isOsDefault: Boolean
        get() = this is Os

    data object Os : SoundChoice {
        override fun encode(): String = "os"
    }

    data class Bundled(val id: String) : SoundChoice {
        override fun encode(): String = "bundled:$id"
    }

    data class File(val fileId: String) : SoundChoice {
        override fun encode(): String = "file:$fileId"
    }

    companion object {
        fun parse(raw: String?): SoundChoice {
            val value = (raw ?: "").trim()
            if (value.startsWith(AlarmSounds.BUNDLED_PREFIX)) {
                val id = value.substring(AlarmSounds.BUNDLED_PREFIX.length)
                return when {
                    id.isEmpty() -> Os
                    AlarmSounds.bundledById(id) == null -> AlarmSounds.DefaultAlarmChoice
                    else -> Bundled(id)
                }
            }
            if (value.startsWith(AlarmSounds.FILE_PREFIX)) {
                val id = value.substring(AlarmSounds.FILE_PREFIX.length)
                return if (id.isEmpty()) Os else File(id)
            }
            return Os
        }
    }
}

data class ResolvedSound(val name: String? = null, val degradedReason: String? = null)

object AlarmSounds {
    const val BUNDLED_PREFIX = "bundled:"
    const val FILE_PREFIX = "file:"
    const val ALARM = "remft_alarm"
    const val ALARM_ID = "alarm"
    const val CUSTOM_SOUND_MISSING = "custom-sound-missing"

    const val SOUND_AUTHORITY = "com.ftapps.remft.sounds"
    const val SOUND_PATH = "sounds"
    const val SOUNDS_DIRECTORY = "Sounds"

    const val ALARM_CHANNEL_PREFIX = "alarm."
    const val REMINDER_CHANNEL_PREFIX = "reminder."
    const val DEFAULT_SOUND_CHANNEL = "system"

    val DefaultAlarmChoice: SoundChoice = SoundChoice.Bundled(ALARM_ID)
    val DefaultReminderChoice: SoundChoice = SoundChoice.Os

    val bundled: List<BundledSound> = listOf(
        BundledSound(ALARM_ID, ALARM, 28.0),
        BundledSound("chime", "remft_chime", 1.6),
        BundledSound("ping", "remft_ping", 0.9),
    )

    fun bundledById(id: String): BundledSound? = bundled.firstOrNull { it.id == id }

    fun resolve(choice: SoundChoice, installedNames: Collection<String>): ResolvedSound = when (choice) {
        SoundChoice.Os -> ResolvedSound()
        is SoundChoice.Bundled -> ResolvedSound(name = bundledById(choice.id)?.androidRaw)
        is SoundChoice.File -> {
            val installed = installedNameFor(choice.fileId, installedNames)
            if (installed == null) ResolvedSound(degradedReason = CUSTOM_SOUND_MISSING) else ResolvedSound(name = contentUri(installed))
        }
    }

    fun installedNameFor(fileId: String, installedNames: Collection<String>): String? =
        installedNames.firstOrNull { it.startsWith("$fileId.") }

    fun inAppRawFor(choice: SoundChoice): String = when (choice) {
        is SoundChoice.Bundled -> bundledById(choice.id)?.androidRaw ?: ALARM
        else -> ALARM
    }

    fun contentUri(installedName: String): String =
        "content://$SOUND_AUTHORITY/${encodePathSegment(SOUND_PATH)}/${encodePathSegment(installedName)}"

    fun channelIdFor(urgent: Boolean, soundName: String?): String {
        val prefix = if (urgent) ALARM_CHANNEL_PREFIX else REMINDER_CHANNEL_PREFIX
        if (soundName == null) return "$prefix$DEFAULT_SOUND_CHANNEL"
        if (!soundName.startsWith("content://")) return "${prefix}raw.$soundName"
        val file = lastPathSegment(soundName)
        val dot = file.lastIndexOf('.')
        val stem = (if (dot < 0) file else file.substring(0, dot)).replace(Regex("[^A-Za-z0-9_]"), "_")
        return "${prefix}file.$stem"
    }

    fun isSoundChannelId(channelId: String): Boolean =
        channelId.startsWith(ALARM_CHANNEL_PREFIX) || channelId.startsWith(REMINDER_CHANNEL_PREFIX)

    fun channelsToKeep(alarmSoundName: String?, reminderSoundName: String?): Set<String> = linkedSetOf(
        channelIdFor(urgent = true, soundName = alarmSoundName),
        channelIdFor(urgent = false, soundName = reminderSoundName),
    )

    fun staleChannels(existing: Collection<String>, keep: Set<String>, showing: Set<String>): List<String> =
        existing.filter { isSoundChannelId(it) && it !in keep && it !in showing }

    private const val PATH_SAFE = "-._~!$&'()*+,;=:@"

    private fun encodePathSegment(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (code < 0x80 && (char.isLetterOrDigit() || char in PATH_SAFE)) {
                out.append(char)
            } else {
                out.append('%').append("%02X".format(code))
            }
        }
        return out.toString()
    }

    private fun lastPathSegment(uri: String): String {
        val withoutQuery = uri.substringBefore('#').substringBefore('?')
        val afterScheme = withoutQuery.substringAfter("://")
        val path = afterScheme.substringAfter('/', "")
        return decodePercent(path.substringAfterLast('/'))
    }

    private fun decodePercent(text: String): String {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '%' && index + 2 < text.length) {
                val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    index += 3
                    continue
                }
            }
            bytes.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
