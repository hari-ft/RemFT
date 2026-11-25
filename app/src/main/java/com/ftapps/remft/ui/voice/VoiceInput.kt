package com.ftapps.remft.ui.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.voice.VoiceIntents
import com.ftapps.remft.data.voice.VoiceOutcome
import com.ftapps.remft.ui.components.RemftIconButton
import kotlinx.coroutines.flow.flowOf

class VoiceLauncher(val visible: Boolean, val ready: Boolean, private val launch: () -> Unit) {
    fun start() = launch()
}

private class VoiceMessages(val noMatch: String, val network: String, val failed: String, val noRecognizer: String)

@Composable
fun rememberVoiceLauncher(prompt: String, onText: (String) -> Unit): VoiceLauncher {
    val env = LocalVoiceEnvironment.current
    val context = LocalContext.current
    val messages = VoiceMessages(
        noMatch = stringResource(R.string.voice_noMatch),
        network = stringResource(R.string.voice_networkError),
        failed = stringResource(R.string.voice_failed),
        noRecognizer = stringResource(R.string.voice_noRecognizer),
    )
    val currentOnText by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val outcome = VoiceIntents.outcome(result.resultCode, result.data)
        report(context, outcome, messages)
        if (outcome is VoiceOutcome.Heard) currentOnText(outcome.text)
    }
    val show by remember(env) { env?.showButtons ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    val available by remember(env) { env?.available ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    val language by remember(env) { env?.languageSetting ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val offline by remember(env) { env?.preferOffline ?: flowOf(false) }.collectAsStateWithLifecycle(false)
    LaunchedEffect(env, offline, language) {
        if (env != null && offline) env.warmOffline(env.language(language))
    }
    return VoiceLauncher(visible = env != null && show, ready = env != null && available) {
        if (env == null) return@VoiceLauncher
        try {
            launcher.launch(env.recognizeIntent(prompt, language, offline))
        } catch (_: ActivityNotFoundException) {
            report(context, VoiceOutcome.NoRecognizer, messages)
        }
    }
}

private fun report(context: Context, outcome: VoiceOutcome, messages: VoiceMessages) {
    AppLogs.info("voice.result", mapOf("code" to VoiceIntents.code(outcome), "count" to ((outcome as? VoiceOutcome.Heard)?.count ?: 0)))
    val text = when (outcome) {
        is VoiceOutcome.Heard, VoiceOutcome.Cancelled -> null
        VoiceOutcome.NoMatch -> messages.noMatch
        VoiceOutcome.Network -> messages.network
        VoiceOutcome.Failed -> messages.failed
        VoiceOutcome.NoRecognizer -> messages.noRecognizer
    }
    if (text != null) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

@Composable
fun VoiceMicButton(tag: String, label: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    if (LocalVoiceEnvironment.current == null) return
    val voice = rememberVoiceLauncher(label, onText)
    if (!voice.visible) return
    RemftIconButton(label = label, onClick = voice::start, modifier = modifier.testTag(tag)) {
        Icon(Icons.Outlined.Mic, contentDescription = label)
    }
}
