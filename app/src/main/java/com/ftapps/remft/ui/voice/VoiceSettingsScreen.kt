package com.ftapps.remft.ui.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ftapps.remft.R
import com.ftapps.remft.data.voice.VoiceCheck
import com.ftapps.remft.data.voice.VoiceCheckId
import com.ftapps.remft.data.voice.VoiceChecklist
import com.ftapps.remft.data.voice.VoiceDetail
import com.ftapps.remft.data.voice.VoiceFix
import com.ftapps.remft.data.voice.VoiceProbe
import com.ftapps.remft.data.voice.VoiceState
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.CenteredList
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftTonalButton
import com.ftapps.remft.ui.settings.AlarmSettingsScaffold
import com.ftapps.remft.ui.settings.SettingsCard
import com.ftapps.remft.ui.settings.SettingsItem
import com.ftapps.remft.ui.settings.SettingsNote
import com.ftapps.remft.ui.settings.SettingsRow
import com.ftapps.remft.ui.settings.SettingsSectionHeader
import com.ftapps.remft.ui.settings.SettingsSwitch
import com.ftapps.remft.ui.settings.SettingsTone
import com.ftapps.remft.ui.theme.Breakpoints
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable data object SettingsVoiceRoute

fun NavGraphBuilder.voiceSettingsDestination(onBack: () -> Unit) {
    composable<SettingsVoiceRoute> { VoiceSettingsScreen(onBack = onBack) }
}

fun languageName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(Locale.getDefault()).ifBlank { tag }
}

@StringRes
private fun VoiceCheckId.title(): Int = when (this) {
    VoiceCheckId.Recognizer -> R.string.voice_checkRecognizer
    VoiceCheckId.GoogleApp -> R.string.voice_checkGoogle
    VoiceCheckId.GoogleMic -> R.string.voice_checkMic
    VoiceCheckId.Language -> R.string.voice_checkLanguage
    VoiceCheckId.Offline -> R.string.voice_checkOffline
    VoiceCheckId.Internet -> R.string.voice_checkInternet
    VoiceCheckId.Buttons -> R.string.voice_checkButtons
}

@StringRes
private fun VoiceDetail.text(): Int = when (this) {
    VoiceDetail.RecognizerOk -> R.string.voice_recognizerOk
    VoiceDetail.RecognizerMissing -> R.string.voice_recognizerMissing
    VoiceDetail.GoogleOk -> R.string.voice_googleOk
    VoiceDetail.GoogleMissing -> R.string.voice_googleMissing
    VoiceDetail.GoogleDisabled -> R.string.voice_googleDisabled
    VoiceDetail.MicOk -> R.string.voice_micOk
    VoiceDetail.MicMissing -> R.string.voice_micMissing
    VoiceDetail.MicUnknown -> R.string.voice_micUnknown
    VoiceDetail.MicNeedsGoogle -> R.string.voice_micNeedsGoogle
    VoiceDetail.LanguageOk -> R.string.voice_languageOk
    VoiceDetail.LanguageMissing -> R.string.voice_languageMissing
    VoiceDetail.LanguageUnknown -> R.string.voice_languageUnknown
    VoiceDetail.OfflineOk -> R.string.voice_offlineOk
    VoiceDetail.OfflinePending -> R.string.voice_offlinePending
    VoiceDetail.OfflineMissing -> R.string.voice_offlineMissing
    VoiceDetail.OfflineUnavailable -> R.string.voice_offlineUnavailable
    VoiceDetail.OfflineNoOnDevice -> R.string.voice_offlineNoOnDevice
    VoiceDetail.OfflineUnknown -> R.string.voice_offlineUnknown
    VoiceDetail.OfflineUnknownNoSettings -> R.string.voice_offlineUnknownNoSettings
    VoiceDetail.InternetOk -> R.string.voice_internetOk
    VoiceDetail.InternetNotNeeded -> R.string.voice_internetNotNeeded
    VoiceDetail.InternetMissing -> R.string.voice_internetMissing
    VoiceDetail.ButtonsOk -> R.string.voice_buttonsOk
    VoiceDetail.ButtonsMissing -> R.string.voice_buttonsMissing
}

@StringRes
private fun VoiceState.label(): Int = when (this) {
    VoiceState.Ok -> R.string.voice_stateOk
    VoiceState.Missing -> R.string.voice_stateMissing
    VoiceState.Pending -> R.string.voice_statePending
    VoiceState.Unknown -> R.string.voice_stateUnknown
}

@StringRes
private fun VoiceFix.label(): Int = when (this) {
    VoiceFix.InstallGoogle -> R.string.voice_fixInstallGoogle
    VoiceFix.GoogleAppInfo -> R.string.voice_fixAppInfo
    VoiceFix.PickLanguage -> R.string.voice_fixPickLanguage
    VoiceFix.DownloadPack -> R.string.voice_fixDownload
    VoiceFix.VoiceSettings -> R.string.voice_fixVoiceSettings
    VoiceFix.NetworkSettings -> R.string.voice_fixNetwork
    VoiceFix.TurnOnButtons -> R.string.voice_fixTurnOn
}

private fun tagOf(id: VoiceCheckId): String = "voice-check-${id.name.lowercase()}"

@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val env = LocalVoiceEnvironment.current
    AlarmSettingsScaffold(stringResource(R.string.voice_title), onBack) { padding ->
        if (env != null) {
            CenteredList(padding, Breakpoints.settingsMaxWidth) {
                item { VoiceSettingsBody(env) }
            }
        }
    }
}

@Composable
private fun VoiceSettingsBody(env: VoiceEnvironment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val buttonsOn by env.buttonsOn.collectAsStateWithLifecycle(true)
    val languageSetting by env.languageSetting.collectAsStateWithLifecycle(null)
    val preferOffline by env.preferOffline.collectAsStateWithLifecycle(false)
    var probe by remember { mutableStateOf<VoiceProbe?>(null) }
    var resumes by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes, languageSetting) {
        if (resumes > 0) probe = env.probe()
    }
    val language = env.language(languageSetting)
    val languageLabel = languageName(language)
    val downloadStarted = stringResource(R.string.voice_downloadStarted)
    val test = rememberVoiceLauncher(stringResource(R.string.voice_testPrompt)) { heard = it }
    val onFix: (VoiceFix) -> Unit = { fix ->
        when (fix) {
            VoiceFix.TurnOnButtons -> scope.launch { env.setButtons(true) }
            VoiceFix.PickLanguage -> picking = true
            VoiceFix.DownloadPack -> {
                if (env.support.requestDownload(language)) {
                    Toast.makeText(context, downloadStarted, Toast.LENGTH_LONG).show()
                    scope.launch { probe = env.probe() }
                } else {
                    open(context, env, VoiceFix.VoiceSettings)
                }
            }
            else -> open(context, env, fix)
        }
    }
    Column {
        SettingsNote(stringResource(R.string.voice_intro))
        SettingsSectionHeader(stringResource(R.string.voice_sectionOptions))
        SettingsCard {
            SettingsRow(
                Icons.Outlined.Mic,
                stringResource(R.string.voice_showButtons),
                stringResource(R.string.voice_showButtonsSub),
                "voice-buttons",
                trailing = {
                    SettingsSwitch(
                        checked = buttonsOn,
                        onCheckedChange = { on -> scope.launch { env.setButtons(on) } },
                        modifier = Modifier.testTag("voice-buttons-switch"),
                    )
                },
                onClick = { scope.launch { env.setButtons(!buttonsOn) } },
            )
            SettingsRow(
                Icons.Outlined.Language,
                stringResource(R.string.voice_language),
                if (languageSetting == null) stringResource(R.string.voice_languageSystem).fill("language" to languageLabel) else languageLabel,
                "voice-language",
                onClick = { picking = true },
            )
            SettingsRow(
                Icons.Outlined.CloudOff,
                stringResource(R.string.voice_preferOffline),
                stringResource(R.string.voice_preferOfflineSub),
                "voice-prefer-offline",
                trailing = {
                    SettingsSwitch(
                        checked = preferOffline,
                        onCheckedChange = { on -> scope.launch { env.setPreferOffline(on) } },
                        modifier = Modifier.testTag("voice-prefer-offline-switch"),
                    )
                },
                onClick = { scope.launch { env.setPreferOffline(!preferOffline) } },
            )
        }
        SettingsSectionHeader(stringResource(R.string.voice_sectionStatus))
        val current = probe
        if (current == null) {
            SettingsNote(stringResource(R.string.voice_checking), Modifier.testTag("voice-checking"))
        } else {
            VoiceChecklistCard(VoiceChecklist.build(current.copy(buttonsOn = buttonsOn)), languageName(current.language), fixAvailable = { fix -> fixAvailable(env, fix) }, onFix = onFix)
        }
        Spacer(Modifier.height(Space.x3))
        SettingsCard {
            SettingsItem(
                title = stringResource(R.string.voice_test),
                icon = Icons.Outlined.RecordVoiceOver,
                subtitle = heard?.let { stringResource(R.string.voice_testResult).fill("text" to it) } ?: stringResource(R.string.voice_testSub),
                subtitleModifier = Modifier.testTag("voice-test-result"),
                below = {
                    RemftTonalButton(
                        stringResource(R.string.voice_testAction),
                        onClick = test::start,
                        enabled = test.ready,
                        modifier = Modifier.padding(top = Space.x2).testTag("voice-test"),
                    )
                },
            )
        }
        Spacer(Modifier.height(Space.x4))
    }
    if (picking) {
        LanguageSheet(
            current = languageSetting,
            phone = env.support.phoneLanguage(),
            languages = env.languages(probe?.support),
            onPick = { tag ->
                picking = false
                scope.launch { env.setLanguage(tag) }
            },
            onDismiss = { picking = false },
        )
    }
}

private fun fixAvailable(env: VoiceEnvironment, fix: VoiceFix): Boolean = when (fix) {
    VoiceFix.TurnOnButtons, VoiceFix.PickLanguage, VoiceFix.DownloadPack -> true
    else -> env.support.fixIntent(fix) != null
}

private fun open(context: Context, env: VoiceEnvironment, fix: VoiceFix) {
    val intent = env.support.fixIntent(fix) ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}

@Composable
fun VoiceChecklistCard(checks: List<VoiceCheck>, language: String, fixAvailable: (VoiceFix) -> Boolean, onFix: (VoiceFix) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val semantic = RemftTheme.colors
    SettingsCard {
        checks.forEach { check ->
            val tag = tagOf(check.id)
            val stateText = stringResource(check.state.label())
            val color = when (check.state) {
                VoiceState.Ok -> semantic.success.color
                VoiceState.Missing -> semantic.warning.color
                VoiceState.Pending, VoiceState.Unknown -> scheme.onSurfaceVariant
            }
            SettingsItem(
                title = stringResource(check.id.title()),
                modifier = Modifier.testTag(tag),
                icon = when (check.state) {
                    VoiceState.Ok -> Icons.Outlined.CheckCircle
                    VoiceState.Missing -> Icons.Outlined.ErrorOutline
                    VoiceState.Pending -> Icons.Outlined.Downloading
                    VoiceState.Unknown -> Icons.AutoMirrored.Outlined.HelpOutline
                },
                tone = when (check.state) {
                    VoiceState.Ok -> SettingsTone.Success
                    VoiceState.Missing -> SettingsTone.Warning
                    VoiceState.Pending, VoiceState.Unknown -> SettingsTone.Neutral
                },
                subtitle = stringResource(check.detail.text()).fill("language" to language),
                subtitleModifier = Modifier.testTag("$tag-detail"),
                below = {
                    Column {
                        Text(
                            stateText,
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            modifier = Modifier.padding(top = Space.x1).testTag("$tag-state"),
                        )
                        val fix = check.fix
                        if (fix != null && check.state != VoiceState.Ok && fixAvailable(fix)) {
                            RemftTonalButton(
                                stringResource(fix.label()),
                                onClick = { onFix(fix) },
                                modifier = Modifier.padding(top = Space.x2).testTag("$tag-fix"),
                            )
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(current: String?, phone: String, languages: List<String>, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val sorted = remember(languages) { languages.distinct().sortedBy { languageName(it).lowercase(Locale.getDefault()) } }
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("voice-language-sheet")) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x4).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.voice_language),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Space.x2, end = Space.x2, top = Space.x1, bottom = Space.x3),
            )
            SettingsCard {
                LanguageOption(
                    label = stringResource(R.string.voice_languageSystem).fill("language" to languageName(phone)),
                    selected = current == null,
                    tag = "voice-language-system",
                    onClick = { onPick(null) },
                )
                sorted.forEach { tag ->
                    LanguageOption(
                        label = languageName(tag),
                        selected = current == tag,
                        tag = "voice-language-$tag",
                        onClick = { onPick(tag) },
                    )
                }
            }
            Spacer(Modifier.height(Space.x4))
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    SettingsItem(
        title = label,
        modifier = Modifier.testTag(tag).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        trailing = if (selected) {
            { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
    )
}
