package com.ftapps.remft.data.alarm

import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmRinging
import com.ftapps.remft.domain.alarm.HeldRing
import com.ftapps.remft.domain.alarm.ReminderProfile
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

data class RingState(
    val ringing: AlarmInput? = null,
    val missed: List<AlarmInput> = emptyList(),
    val feed: List<AlarmInput> = emptyList(),
    val profile: ReminderProfile = ReminderProfile.Factory,
    val decided: Boolean = false,
)

class AlarmRingController(private val env: AlarmEnvironment) {
    private val mutable = MutableStateFlow(RingState())
    val state: StateFlow<RingState> = mutable.asStateFlow()
    private val missedOpen = MutableStateFlow<AlarmInput?>(null)
    val missedShown: StateFlow<AlarmInput?> = missedOpen.asStateFlow()

    fun showMissed(alarm: AlarmInput) {
        missedOpen.value = alarm
    }

    fun dismissMissed() {
        missedOpen.value = null
    }

    private val lock = Any()
    private val handled = HashSet<String>()
    private val deferred = HashMap<String, Instant>()
    private var feed: List<AlarmInput> = emptyList()
    private var profile: ReminderProfile = ReminderProfile.Factory
    private var held: Map<HeldRing, Instant> = emptyMap()
    private var timer: Job? = null
    private var started = false

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        env.scope.launch {
            withTimeoutOrNull(DECIDE_TIMEOUT_MS) { runCatching { reload() } }
            if (!mutable.value.decided) mutable.value = mutable.value.copy(decided = true)
            val changes = merge(
                flow { emitAll(env.database().invalidationTracker.createFlow("tasks", "reminders", emitInitialState = false)) }
                    .retryWhen { _, _ ->
                        delay(RETRY_MS)
                        true
                    }
                    .map { },
                env.scheduler.passes.map { },
                env.settings.flow(SettingKeys.ReminderProfile).map { },
            )
            changes.collect { runCatching { reload() } }
        }
    }

    suspend fun reload() {
        val alarms = env.workspaceFeed()
        val raw = env.settings.get(SettingKeys.ReminderProfile.name)
        synchronized(lock) {
            feed = alarms
            profile = ReminderProfile.parse(raw)
            held = env.scheduler.held.value
        }
        evaluate()
    }

    fun reconsider() {
        if (!mutable.value.decided) return
        env.scope.launch { runCatching { reload() } }
    }

    fun handled(reminderId: String) {
        synchronized(lock) {
            (feed + listOfNotNull(mutable.value.ringing)).filter { it.reminderId == reminderId }.forEach { handled.add(AlarmRinging.handledKey(it)) }
        }
        evaluate()
    }

    fun deferred(reminderId: String) {
        val now = env.clock.instant()
        synchronized(lock) {
            (feed + listOfNotNull(mutable.value.ringing)).filter { it.reminderId == reminderId }.forEach { deferred[AlarmRinging.handledKey(it)] = now }
        }
        evaluate()
    }

    fun evaluate() {
        val next: RingState
        var wake: Instant? = null
        synchronized(lock) {
            timer?.cancel()
            val current = feed.map(AlarmRinging::handledKey).toSet()
            handled.retainAll(current)
            deferred.keys.retainAll(current)
            val now = env.clock.instant()
            val times = AlarmPlanner.plannedRingTimes(feed, profile, held)
            var nextRealert: Instant? = null
            for (alarm in feed) {
                val key = AlarmRinging.handledKey(alarm)
                val since = deferred[key] ?: continue
                val realert = AlarmRinging.nextRealertAfter(alarm, since, profile, times) ?: continue
                if (!realert.isAfter(now)) {
                    deferred.remove(key)
                } else if (nextRealert == null || realert.isBefore(nextRealert)) {
                    nextRealert = realert
                }
            }
            val ringing = AlarmRinging.ringingAlarm(feed, now, profile = profile, times = times) { alarm ->
                val key = AlarmRinging.handledKey(alarm)
                key in handled || key in deferred
            }
            wake = AlarmRinging.nextUrgentFireAfter(feed, now, profile, times)
            if (nextRealert != null && (wake == null || nextRealert.isBefore(wake))) wake = nextRealert
            AlarmRinging.liveWindowEnds(feed, now, times)?.let { ends -> if (wake == null || ends.isBefore(wake)) wake = ends }
            val missed = AlarmRinging.missedAlarms(feed, now, profile = profile, held = held)
            next = RingState(ringing, missed, feed, profile, decided = true)
            mutable.value = next
            val at = wake
            if (at != null) {
                val wait = Duration.between(now, at).toMillis().coerceIn(0L, MAX_WAIT_MS)
                timer = env.scope.launch {
                    delay(wait)
                    evaluate()
                }
            }
        }
    }

    companion object {
        const val DECIDE_TIMEOUT_MS = 4_000L
        private const val RETRY_MS = 5_000L
        private const val MAX_WAIT_MS = 6L * 60 * 60 * 1000
    }
}
