package com.ftapps.remft.data.alarm

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AlarmStartup(private val env: AlarmEnvironment) {
    @Volatile
    private var started = false

    private var dataJob: Job? = null

    @Volatile
    private var exactAllowed: Boolean? = null

    fun start(observeProcess: Boolean = true) {
        if (started) return
        started = true
        val scheduler = env.scheduler
        exactAlarmsRegained()
        scheduler.launchWorker(env.scope)
        env.scope.launch {
            env.appLog.info("app.launch")
            DirectBootAlarms.settle(env)
            scheduler.request()
        }
        observeData()
        observeSettings()
        env.scope.launch {
            while (true) {
                delay(AlarmScheduler.HEARTBEAT.toMillis())
                scheduler.request()
            }
        }
        env.ring.start()
        enqueuePeriodic(env.context)
        if (observeProcess) {
            env.scope.launch(Dispatchers.Main) {
                runCatching {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) = onForeground()
                    })
                }
            }
        }
    }

    fun onForeground() {
        env.scheduler.request(rebook = exactAlarmsRegained())
        env.ring.reconsider()
    }

    fun exactAlarmsRegained(): Boolean {
        val now = runCatching { env.health.status().exactAlarms }.getOrNull()
        val before = exactAllowed
        exactAllowed = now
        return before == false && now == true
    }

    fun afterRestore() {
        if (!started) return
        observeData()
        env.scheduler.request(rebook = true)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeData() = synchronized(this) {
        dataJob?.cancel()
        dataJob = env.scope.launch {
            flow { emitAll(env.database().invalidationTracker.createFlow("tasks", "reminders", emitInitialState = false)) }
                .retryWhen { _, _ ->
                    delay(RETRY_MS)
                    true
                }
                .debounce(DEBOUNCE_MS)
                .collect { env.scheduler.request() }
        }
    }

    private fun observeSettings() {
        val flows = AlarmSettings.plannerKeys.map { env.settings.flow(it) }
        env.scope.launch {
            combine(flows) { values -> values.toList() }
                .drop(1)
                .collect { env.scheduler.request() }
        }
    }

    companion object {
        const val WORK_NAME = "remft_alarm_refresh"
        private const val DEBOUNCE_MS = 300L
        private const val RETRY_MS = 5_000L

        fun enqueuePeriodic(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<AlarmRefreshWorker>(AlarmScheduler.HEARTBEAT.toHours(), TimeUnit.HOURS).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
