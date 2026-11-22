package com.ftapps.remft.ui.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.RingtoneFile
import com.ftapps.remft.domain.alarm.AlarmSounds
import com.ftapps.remft.domain.alarm.SoundChoice
import com.ftapps.remft.domain.settings.SettingKey
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SoundLane(val key: SettingKey, val usage: Int, @param:StringRes val title: Int) {
    Alarm(SettingKeys.AlarmSound, AudioAttributes.USAGE_ALARM, R.string.sound_alarmTitle),
    Reminder(SettingKeys.ReminderSound, AudioAttributes.USAGE_NOTIFICATION, R.string.sound_reminderTitle),
}

@StringRes
fun bundledSoundLabel(id: String): Int = when (id) {
    "chime" -> R.string.sound_bundled_chime
    "ping" -> R.string.sound_bundled_ping
    else -> R.string.sound_bundled_alarm
}

@StringRes
fun soundChoiceLabel(choice: SoundChoice): Int = when (choice) {
    SoundChoice.Os -> R.string.sound_osDefault
    is SoundChoice.Bundled -> bundledSoundLabel(choice.id)
    is SoundChoice.File -> R.string.sound_yours
}

private const val PREVIEW_LIMIT_MS = 4_000L

private class AlarmSoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri, usage: Int, onDone: () -> Unit, onFail: () -> Unit) {
        stop()
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            next.setDataSource(context, uri)
            next.setOnPreparedListener { it.start() }
            next.setOnCompletionListener { if (player === it) onDone() }
            next.setOnErrorListener { mp, _, _ ->
                if (player === mp) {
                    stop()
                    onFail()
                }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            stop()
            onFail()
        }
    }

    fun stop() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        current.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPickerSheet(env: AlarmEnvironment, lane: SoundLane, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choice = SoundChoice.parse(rememberAlarmSetting(env, lane.key))
    var ringtones by remember { mutableStateOf<List<RingtoneFile>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<String?>(null) }
    val preview = remember { AlarmSoundPreview(context.applicationContext) }
    val uploadFailed = stringResource(R.string.sound_uploadFailed)
    val installFailed = stringResource(R.string.sound_installFailed)
    val previewFailed = stringResource(R.string.sound_previewFailed)

    DisposableEffect(preview) { onDispose { preview.stop() } }
    LaunchedEffect(env, reload) {
        ringtones = runCatching { env.sounds.ringtones(env.identity.resolve().workspaceId) }.getOrDefault(emptyList())
    }
    LaunchedEffect(previewing) {
        if (previewing != null) {
            delay(PREVIEW_LIMIT_MS)
            preview.stop()
            previewing = null
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val id = runCatching { env.sounds.upload(env.identity.resolve().workspaceId, uri) }.getOrNull()
            busy = false
            if (id == null) onMessage(uploadFailed) else reload++
        }
    }

    fun pick(next: SoundChoice) {
        if (busy) return
        scope.launch {
            if (next is SoundChoice.File) {
                busy = true
                val installed = runCatching { env.sounds.ensureInstalled(next.fileId) }.getOrDefault(false)
                busy = false
                if (!installed) {
                    onMessage(installFailed)
                    return@launch
                }
            }
            env.settings.set(lane.key.name, next.encode())
        }
    }

    fun togglePreview(next: SoundChoice) {
        val id = next.encode()
        if (previewing == id) {
            preview.stop()
            previewing = null
            return
        }
        scope.launch {
            if (next is SoundChoice.File) runCatching { env.sounds.ensureInstalled(next.fileId) }
            val uri = runCatching { env.sounds.previewUri(next) }.getOrNull()
            if (uri == null) {
                onMessage(previewFailed)
                return@launch
            }
            previewing = id
            preview.play(
                uri,
                lane.usage,
                onDone = { if (previewing == id) previewing = null },
                onFail = {
                    if (previewing == id) previewing = null
                    onMessage(previewFailed)
                },
            )
        }
    }

    RemftBottomSheet(
        onDismissRequest = {
            preview.stop()
            onDismiss()
        },
        modifier = Modifier.testTag("sound-sheet-${lane.name.lowercase()}"),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.x4)) {
            Text(
                stringResource(lane.title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x1, bottom = Space.x3),
            )
            SettingsCard {
                SoundOption("sound-os", stringResource(R.string.sound_osDefault), choice == SoundChoice.Os, onSelect = { pick(SoundChoice.Os) })
                AlarmSounds.bundled.forEach { sound ->
                    val option = SoundChoice.Bundled(sound.id)
                    SoundOption(
                        "sound-bundled-${sound.id}",
                        stringResource(bundledSoundLabel(sound.id)),
                        choice == option,
                        onSelect = { pick(option) },
                        playing = previewing == option.encode(),
                        onPreview = { togglePreview(option) },
                    )
                }
            }
            SettingsSectionHeader(stringResource(R.string.sound_yours))
            if (ringtones.isEmpty()) {
                SettingsNote(stringResource(R.string.sound_noneYet))
            } else {
                SettingsCard {
                    ringtones.forEach { file ->
                        val option = SoundChoice.File(file.id)
                        SoundOption(
                            "sound-file-${file.id}",
                            file.name,
                            choice == option,
                            onSelect = { pick(option) },
                            playing = previewing == option.encode(),
                            onPreview = { togglePreview(option) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.x2))
            RemftTonalButton(
                stringResource(R.string.sound_upload),
                onClick = { launcher.launch(arrayOf("audio/*")) },
                enabled = !busy,
                icon = Icons.Outlined.UploadFile,
                modifier = Modifier.testTag("sound-upload"),
            )
            SettingsNote(stringResource(R.string.sound_rules))
            Spacer(Modifier.height(Space.x4))
        }
    }
}

@Composable
private fun SoundOption(
    tag: String,
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    playing: Boolean = false,
    onPreview: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = Space.x2, end = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(Space.x3))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onPreview != null) {
            RemftIconButton(label = stringResource(if (playing) R.string.sound_stopPreview else R.string.sound_preview), onClick = onPreview, modifier = Modifier.testTag("$tag-preview")) {
                Icon(
                    if (playing) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircleOutline,
                    contentDescription = stringResource(if (playing) R.string.sound_stopPreview else R.string.sound_preview),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview(name = "Sound options light")
@Composable
private fun SoundOptionsLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { SoundOptionsPreview() } }
}

@Preview(name = "Sound options dark")
@Composable
private fun SoundOptionsDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { SoundOptionsPreview() } }
}

@Composable
private fun SoundOptionsPreview() {
    SettingsCard {
        SoundOption("p-os", "The system sound", selected = false, onSelect = {})
        SoundOption("p-chime", "Chime", selected = true, onSelect = {}, playing = true, onPreview = {})
        SoundOption("p-ping", "Ping", selected = false, onSelect = {}, onPreview = {})
    }
}
