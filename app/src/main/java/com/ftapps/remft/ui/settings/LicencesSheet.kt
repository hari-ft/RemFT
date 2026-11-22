package com.ftapps.remft.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftBottomSheet
import com.ftapps.remft.ui.components.RemftDialog
import com.ftapps.remft.ui.components.RemftTextButton
import com.ftapps.remft.ui.theme.MonoStyle
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FontLicence(val id: String, @param:StringRes val font: Int, val asset: String) {
    Bricolage("bricolage", R.string.setx_fontBricolage, "licenses/bricolage_grotesque_OFL.txt"),
    Instrument("instrument", R.string.setx_fontInstrument, "licenses/instrument_sans_OFL.txt"),
    JetBrains("jetbrains", R.string.setx_fontJetBrains, "licenses/jetbrains_mono_OFL.txt"),
}

private fun readLicence(context: Context, licence: FontLicence): String? =
    runCatching { context.assets.open(licence.asset).bufferedReader().use { it.readText() } }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontLicencesSheet(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<FontLicence?>(null) }
    RemftBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("licences-sheet")) {
        FontLicencesList(onOpen = { open = it })
    }
    open?.let { licence -> FontLicenceDialog(licence, onClose = { open = null }) }
}

@Composable
fun FontLicencesList(onOpen: (FontLicence) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = Space.x4)) {
        Text(
            stringResource(R.string.setx_licences),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = Space.x2, top = Space.x1, bottom = Space.x1),
        )
        SettingsNote(stringResource(R.string.setx_licencesIntro))
        Spacer(Modifier.height(Space.x2))
        SettingsCard {
            FontLicence.entries.forEach { licence ->
                SettingsItem(
                    title = stringResource(licence.font),
                    subtitle = stringResource(R.string.setx_licenceKind),
                    icon = Icons.Outlined.Description,
                    modifier = Modifier.testTag("licence-${licence.id}").clickable { onOpen(licence) },
                    trailing = { SettingsChevron() },
                )
            }
        }
        Spacer(Modifier.height(Space.x6))
    }
}

@Composable
private fun FontLicenceDialog(licence: FontLicence, onClose: () -> Unit) {
    val context = LocalContext.current
    var text by remember(licence) { mutableStateOf<String?>(null) }
    var loaded by remember(licence) { mutableStateOf(false) }
    LaunchedEffect(licence) {
        text = withContext(Dispatchers.IO) { readLicence(context.applicationContext, licence) }
        loaded = true
    }
    RemftDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("licence-dialog"),
        title = stringResource(licence.font),
        confirmButton = { RemftTextButton(stringResource(R.string.common_close), onClick = onClose) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 460.dp)) {
                val body = text
                when {
                    !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    body == null -> Text(stringResource(R.string.setx_licenceFailed), style = MaterialTheme.typography.bodyMedium)
                    else -> Text(
                        body,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("licence-text"),
                    )
                }
            }
        },
    )
}

@Preview(name = "Licences light")
@Composable
private fun LicencesLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { FontLicencesList(onOpen = {}) } }
}

@Preview(name = "Licences dark")
@Composable
private fun LicencesDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { FontLicencesList(onOpen = {}) } }
}
