package com.ftapps.remft.data.alarm

import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.alarm.AlarmBooking
import com.ftapps.remft.domain.alarm.AlarmInput
import com.ftapps.remft.domain.alarm.AlarmPlanner
import com.ftapps.remft.domain.alarm.AlarmTexts
import com.ftapps.remft.domain.alarm.HeldRing
import com.ftapps.remft.domain.alarm.RebookPolicy
import com.ftapps.remft.domain.alarm.SoundChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PassOutcome {
    data class Skipped(val reason: String) : PassOutcome
    data class Done(
        val desired: Int,
        val scheduled: Int,
        val rebooked: Int,
        val cancelled: Int,
        val failed: Int,
        val dropped: Int,
        val rebook: Boolean,
    ) : PassOutcome
}

data class ResolvedSounds(val alarm: String?, val reminder: String?)

class AlarmScheduler(
    private val identity: LocalIdentityRepository,
    private val settings: SettingsStore,
    private val store: AlarmStore,
    private val gateway: AlarmGateway,
    private val pending: PendingAlarms,
    private val log: AlarmEventLog,
    private val appLog: AppLog,
    private val sounds: AlarmSoundStore,
    private val planner: AlarmPlanner,
    private val clock: Clock,
    private val ready: suspend () -> Unit = {},
    private val mirror: DirectBootMirror? = null,
    private val texts: AlarmTexts? = null,
) {
    val policy = RebookPolicy()
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val rebookRequested = AtomicBoolean(false)
    private val heldState = MutableStateFlow<Map<HeldRing, Instant>>(emptyMap())
    private val passCount = MutableStateFlow(0L)
    private val degradedSounds = HashSet<String>()

    val held: StateFlow<Map<HeldRing, Instant>> = heldState.asStateFlow()
    val passes: StateFlow<Long> = passCount.asStateFlow()

    fun request(rebook: Boolean = false) {
        if (rebook) rebookRequested.set(true)
        requests.trySend(Unit)
    }

    fun launchWorker(scope: CoroutineScope) {
        scope.launch {
            for (unit in requests) {
                runCatching { pass(rebookRequested.getAndSet(false), "change") }
            }
        }
    }

    suspend fun pass(rebook: Boolean = false, reason: String = "change"): PassOutcome = mutex.withLock {
        try {
            runPass(rebook, reason)
        } catch (e: Exception) {
            appLog.warn("alarms.apply_failed", mapOf("reason" to LogCode(reason)), e)
            PassOutcome.Skipped("failed")
        } finally {
            passCount.value = passCount.value + 1
        }
    }

    suspend fun resolveSounds(config: AlarmSettings): ResolvedSounds =
        ResolvedSounds(resolve(config.alarmSound), resolve(config.reminderSound))

    private suspend fun resolve(choice: SoundChoice): String? {
        if (choice is SoundChoice.File) runCatching { sounds.ensureInstalled(choice.fileId) }
        val resolved = sounds.resolve(choice)
        val reason = resolved.degradedReason
        if (reason != null && degradedSounds.add("${choice.encode()}|$reason")) {
            log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, detail = "sound=${choice.encode()} $reason")
        }
        return resolved.name
    }

    private suspend fun runPass(rebook: Boolean, reason: String): PassOutcome {
        ready()
        val values = try {
            settings.snapshot()
        } catch (_: SettingsUnavailable) {
            appLog.warn("alarms.skipped", mapOf("prefs_unreadable" to true, "reason" to LogCode(reason)))
            return PassOutcome.Skipped("prefs_unreadable")
        }
        val who = identity.resolve()
        if (!who.reliable) {
            appLog.warn("alarms.skipped", mapOf("identity_unconfirmed" to true, "reason" to LogCode(reason)))
            return PassOutcome.Skipped("identity_unconfirmed")
        }
        val config = AlarmSettings.from(values)
        val feed: List<AlarmInput> = store.feed(who.workspaceId)
        val resolved = resolveSounds(config)
        val now = clock.instant()

        var forceRebook = false
        val booked = try {
            pending.read()
        } catch (e: PendingUnreadable) {
            log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, detail = "pending set unreadable: ${e.cause?.javaClass?.simpleName}")
            forceRebook = true
            emptyMap()
        }
        val droppedCutoff = now.minus(IN_FLIGHT_GRACE)
        val dropped = booked.values.filter { it.fireAt.isBefore(droppedCutoff) }
        if (dropped.isNotEmpty()) {
            pending.update { map -> dropped.forEach { map.remove(it.id) } }
            for (entry in dropped) {
                log.record(
                    AlarmLogEvent.DROPPED,
                    AlarmLogLane.NOTIFICATION,
                    kind = entry.kind,
                    slotIndex = entry.slotIndex,
                    urgent = entry.urgent,
                    fireAt = entry.fireAt,
                    taskId = entry.taskId,
                    reminderId = entry.reminderId,
                    detail = "id=${entry.id}",
                )
            }
        }
        val live = booked - dropped.map { it.id }.toSet()
        val inFlight = live.values.filter { !it.fireAt.isAfter(now) }.map { it.id }.toSet()
        val held = AlarmPlanner.heldRingsFromPayloads(live.values.map { it.payload }, now)
        heldState.value = held

        val desired = planner.planNotifications(
            alarms = feed,
            now = now,
            privacyMode = config.privacyMode,
            maxPending = AlarmPlanner.MAX_PENDING,
            profile = config.profile,
            alarmSoundName = resolved.alarm,
            reminderSoundName = resolved.reminder,
            held = held,
        )
        if (rebook || forceRebook) policy.requestRebook()
        val rebookNow = policy.beginPass()
        val plan = AlarmBooking.plan(desired, live.keys - inFlight, rebookNow)

        var cancelled = 0
        var scheduled = 0
        var rebooked = 0
        var failed = 0
        for (id in plan.toCancel) {
            try {
                gateway.cancel(id)
                pending.remove(id)
                cancelled++
                val entry = live[id]
                log.record(
                    AlarmLogEvent.CANCELLED,
                    AlarmLogLane.NOTIFICATION,
                    kind = entry?.kind,
                    slotIndex = entry?.slotIndex,
                    urgent = entry?.urgent ?: false,
                    fireAt = entry?.fireAt,
                    taskId = entry?.taskId,
                    reminderId = entry?.reminderId,
                    detail = "id=$id",
                )
            } catch (e: Exception) {
                failed++
                log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, detail = "cancel id=$id failed: ${e.javaClass.simpleName}")
            }
        }
        for (notification in plan.toBook) {
            try {
                val result = gateway.book(notification)
                pending.put(BookedAlarm.of(notification))
                if (result == BookResult.INEXACT) {
                    log.record(
                        AlarmLogEvent.DEGRADED,
                        AlarmLogLane.NOTIFICATION,
                        urgent = notification.urgent,
                        fireAt = notification.fireAt,
                        taskId = notification.taskId,
                        reminderId = notification.reminderId,
                        detail = "exact alarms refused; booked inexact id=${notification.id}",
                    )
                }
                if (notification.id in live) {
                    rebooked++
                } else {
                    scheduled++
                    log.recordScheduled(notification, AlarmBooking.deliveryFor(notification.urgent, notification.soundName))
                }
            } catch (e: Exception) {
                failed++
                log.record(
                    AlarmLogEvent.DEGRADED,
                    AlarmLogLane.NOTIFICATION,
                    urgent = notification.urgent,
                    fireAt = notification.fireAt,
                    taskId = notification.taskId,
                    reminderId = notification.reminderId,
                    detail = "schedule failed: ${e.javaClass.simpleName}",
                )
            }
        }
        runCatching { gateway.pruneChannels(resolved.alarm, resolved.reminder) }
            .onFailure { appLog.warn("alarms.prune_channels_failed", emptyMap(), it) }
        policy.endPass(rebookNow, failed)
        syncMirror(config, now)
        if (cancelled + scheduled + rebooked + failed + dropped.size > 0 || rebookNow) {
            appLog.info(
                "alarms.applied",
                mapOf(
                    "reason" to LogCode(reason),
                    "desired" to desired.size,
                    "pending" to live.size,
                    "scheduled" to scheduled,
                    "rebooked" to rebooked,
                    "cancelled" to cancelled,
                    "dropped" to dropped.size,
                    "failed" to failed,
                    "rebook" to rebookNow,
                ),
            )
        }
        return PassOutcome.Done(desired.size, scheduled, rebooked, cancelled, failed, dropped.size, rebookNow)
    }

    suspend fun bookTest(): Boolean = mutex.withLock {
        try {
            val config = AlarmSettings.from(settings.snapshot())
            val notification = planner.testAlarm(clock.instant(), resolve(config.alarmSound))
            gateway.book(notification)
            pending.put(BookedAlarm.of(notification))
            syncMirror(config, clock.instant())
            log.record(
                AlarmLogEvent.TEST,
                AlarmLogLane.NOTIFICATION,
                urgent = true,
                sound = AlarmBooking.deliveryFor(true, notification.soundName).sound,
                fireAt = notification.fireAt,
                detail = "id=${notification.id}",
            )
            true
        } catch (e: Exception) {
            appLog.warn("alarms.test_failed", emptyMap(), e)
            log.record(AlarmLogEvent.DEGRADED, AlarmLogLane.NOTIFICATION, urgent = true, detail = "test alarm failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun syncMirror(config: AlarmSettings, now: Instant) {
        val target = mirror ?: return
        val words = texts ?: return
        runCatching {
            val booked = pending.read()
            val gone = target.replaceAlarms(booked.values, now, config.privacyMode, words, config.snoozeOrder)
            for (id in gone) {
                if (id !in booked) runCatching { gateway.cancel(id) }
            }
        }.onFailure { appLog.warn("alarms.mirror_failed", emptyMap(), it) }
    }

    companion object {
        val IN_FLIGHT_GRACE: Duration = Duration.ofMinutes(2)
        val HEARTBEAT: Duration = Duration.ofHours(6)
    }
}
