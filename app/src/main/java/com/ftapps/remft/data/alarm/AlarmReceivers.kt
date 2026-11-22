package com.ftapps.remft.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmGateway.ACTION_FIRE) return
        val notification = AndroidAlarmGateway.decode(intent.getStringExtra(AndroidAlarmGateway.EXTRA_ALARM)) ?: return
        val env = runCatching { AlarmRuntime.get(context) }.getOrNull()
        val gateway = env?.gateway ?: AndroidAlarmGateway(context)
        val shownEarly = if (env == null || env.snoozeOrder.ready) runCatching { gateway.show(notification) } else null
        if (env == null) return
        val result: PendingResult? = goAsync()
        env.scope.launch {
            try {
                val shown = shownEarly ?: run {
                    env.snoozeOrder.awaitReady()
                    runCatching { gateway.show(notification) }
                }
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    runCatching { env.pending.remove(notification.id) }
                    val failure = shown.exceptionOrNull()
                    env.log.record(
                        if (failure == null) AlarmLogEvent.DELIVERED else AlarmLogEvent.DEGRADED,
                        AlarmLogLane.NOTIFICATION,
                        kind = notification.kind,
                        slotIndex = notification.slotIndex,
                        urgent = notification.urgent,
                        fireAt = notification.fireAt,
                        taskId = notification.taskId,
                        reminderId = notification.reminderId,
                        detail = if (failure == null) "id=${notification.id}" else "post id=${notification.id} failed: ${failure.javaClass.simpleName}",
                    )
                    env.scheduler.pass(reason = "fired")
                }
            } finally {
                result?.finish()
            }
        }
    }

    companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmGateway.ACTION_BUTTON) return
        val actionId = intent.getStringExtra(AndroidAlarmGateway.EXTRA_ACTION) ?: return
        val id = if (intent.hasExtra(AndroidAlarmGateway.EXTRA_ID)) intent.getIntExtra(AndroidAlarmGateway.EXTRA_ID, 0) else null
        val payload = intent.getStringExtra(AndroidAlarmGateway.EXTRA_PAYLOAD)
        val env = runCatching { AlarmRuntime.get(context) }.getOrNull() ?: return
        val result: PendingResult? = goAsync()
        env.scope.launch {
            try {
                withTimeoutOrNull(AlarmFireReceiver.RECEIVER_BUDGET_MS) { env.actions.handle(id, actionId, payload) }
            } finally {
                result?.finish()
            }
        }
    }
}

class AlarmSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        val env = runCatching { AlarmRuntime.get(context) }.getOrNull() ?: return
        val result: PendingResult? = goAsync()
        env.scope.launch {
            try {
                withTimeoutOrNull(AlarmFireReceiver.RECEIVER_BUDGET_MS) {
                    env.appLog.info("refresh.system", mapOf("action" to LogCode(action.substringAfterLast('.'))))
                    AlarmStartup.enqueuePeriodic(context)
                    env.scheduler.pass(rebook = true, reason = "system")
                }
            } finally {
                result?.finish()
            }
        }
    }

    companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
