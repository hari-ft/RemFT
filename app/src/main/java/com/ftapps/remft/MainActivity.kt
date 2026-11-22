package com.ftapps.remft

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.ui.backup.BackupHost
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.ui.alarm.AlarmActivitySupport
import com.ftapps.remft.ui.alarm.AlarmRingHost
import com.ftapps.remft.ui.alarm.LocalAlarmEnvironment
import com.ftapps.remft.ui.alarm.NotificationPermissionRequest
import com.ftapps.remft.ui.components.ErrorState
import com.ftapps.remft.ui.search.GlobalSearchShortcut
import com.ftapps.remft.ui.search.LocalSearchEnvironment
import com.ftapps.remft.ui.search.SearchEnvironment
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.files.FilesEnvironment
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.onboarding.LocalTourController
import com.ftapps.remft.ui.onboarding.TourController
import com.ftapps.remft.ui.shell.AppShell
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var tasksEnvironment: TasksEnvironment

    @Inject
    lateinit var searchEnvironment: SearchEnvironment

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var alarms: AlarmEnvironment

    @Inject
    lateinit var filesEnvironment: FilesEnvironment

    private var deepLink by mutableStateOf<String?>(null)

    private val tour by lazy {
        TourController(
            settings,
            lifecycleScope,
            combine(alarms.ring.state, alarms.ring.missedShown) { ring, missed -> ring.decided && ring.ringing == null && missed == null },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink = intent?.dataString
        startup.start()
        splash.setKeepOnScreenCondition {
            val state = startup.state.value
            state is StartupState.Starting || (state is StartupState.Ready && !alarms.ring.state.value.decided)
        }
        handleAlarmIntent(intent)
        lifecycleScope.launch {
            alarms.ring.state.collect { ring ->
                if (ring.decided && ring.ringing == null) AlarmActivitySupport.setOverLock(this@MainActivity, false)
            }
        }
        enableEdgeToEdge()
        setContent {
            val dynamicColor by remember { settings.flow(SettingKeys.DynamicColor) }.collectAsStateWithLifecycle(SettingKeys.DynamicColor.default)
            RemftTheme(dynamicColor = dynamicColor == "true") {
                val state by startup.state.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    when (state) {
                        is StartupState.Ready -> CompositionLocalProvider(
                            LocalTasksEnvironment provides tasksEnvironment,
                            LocalSearchEnvironment provides searchEnvironment,
                            LocalAlarmEnvironment provides alarms,
                            LocalFilesEnvironment provides filesEnvironment,
                            LocalTourController provides tour,
                        ) {
                            AlarmRingHost(alarms, onOpenTask = { deepLink = "remft://task/$it" }) {
                                AppShell(deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
                            }
                            NotificationPermissionRequest(ready = tour.settled)
                        }
                        is StartupState.Failed -> Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center,
                        ) { ErrorState(onRetry = startup::retry) }
                        StartupState.Starting -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                    BackupHost()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (startup.state.value is StartupState.Ready && !tour.running && GlobalSearchShortcut.handle(event, searchEnvironment.palette)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
        intent.dataString?.let { deepLink = it }
    }

    private fun handleAlarmIntent(intent: Intent?) {
        if (AlarmActivitySupport.isActiveFullScreenAlarm(this, intent)) AlarmActivitySupport.setOverLock(this, true)
        AlarmActivitySupport.logTap(alarms, intent)
    }
}
