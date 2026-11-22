package com.ftapps.remft.ui.alarm

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmLogEvent
import com.ftapps.remft.data.alarm.AlarmLogLane
import com.ftapps.remft.data.alarm.AndroidAlarmGateway
import com.ftapps.remft.domain.alarm.AlarmPlanner
import kotlinx.coroutines.launch

object AlarmActivitySupport {
    fun isActiveFullScreenAlarm(activity: Activity, intent: Intent?): Boolean {
        if (intent?.action != AndroidAlarmGateway.ACTION_FULL_SCREEN) return false
        if (!intent.hasExtra(AndroidAlarmGateway.EXTRA_ID)) return false
        val id = intent.getIntExtra(AndroidAlarmGateway.EXTRA_ID, 0)
        return runCatching {
            activity.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == id && it.notification.fullScreenIntent != null
            }
        }.getOrDefault(false)
    }

    fun setOverLock(activity: Activity, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(on)
            activity.setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) activity.window.addFlags(flags) else activity.window.clearFlags(flags)
        }
    }

    fun logTap(env: AlarmEnvironment, intent: Intent?) {
        if (intent?.getBooleanExtra(AndroidAlarmGateway.EXTRA_TAPPED, false) != true) return
        val payload = AlarmPlanner.parsePayload(intent.getStringExtra(AndroidAlarmGateway.EXTRA_PAYLOAD)) ?: return
        intent.removeExtra(AndroidAlarmGateway.EXTRA_TAPPED)
        env.scope.launch {
            env.log.record(
                AlarmLogEvent.INTERACTED,
                AlarmLogLane.NOTIFICATION,
                kind = payload.kind,
                slotIndex = payload.chainIndex,
                taskId = payload.taskId,
                reminderId = payload.reminderId,
            )
        }
    }
}

@Composable
fun NotificationPermissionRequest(ready: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    PermissionPromptGate(
        ready = ready,
        needed = { ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED },
        onRequest = { runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } },
    )
}

@Composable
fun PermissionPromptGate(ready: Boolean, needed: () -> Boolean, onRequest: () -> Unit) {
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready || asked) return@LaunchedEffect
        asked = true
        if (needed()) onRequest()
    }
}
