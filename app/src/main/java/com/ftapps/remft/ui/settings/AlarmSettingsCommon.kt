package com.ftapps.remft.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.PermissionStatus
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.SettingKey
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        content = content,
    )
}

@Composable
fun rememberAlarmSetting(env: AlarmEnvironment, key: SettingKey): String? {
    val flow = remember(env, key) { env.settings.flow(key) }
    val value by flow.collectAsStateWithLifecycle(key.default)
    return value
}

@Composable
fun rememberAlarmDateFormat(env: AlarmEnvironment): DateFormatId =
    DateFormatId.parse(rememberAlarmSetting(env, SettingKeys.DateFormat))

class AlarmPermissionProbe(val status: PermissionStatus?, val refresh: () -> Unit)

@Composable
fun rememberAlarmPermissionProbe(env: AlarmEnvironment): AlarmPermissionProbe {
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<PermissionStatus?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(env, tick) {
        status = withContext(Dispatchers.IO) { runCatching { env.health.status() }.getOrNull() } ?: PermissionStatus.Unknown
    }
    return AlarmPermissionProbe(status) { tick++ }
}

fun alarmLogStamp(env: AlarmEnvironment, utc: String, format: DateFormatId): String {
    val instant = UtcText.parse(utc) ?: return utc
    return DateFormats.short(LocalDateTime.ofInstant(instant, env.clock.zone), format)
}
