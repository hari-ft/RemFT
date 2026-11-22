package com.ftapps.remft.testing

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ftapps.remft.data.alarm.AlarmEnvironment
import com.ftapps.remft.data.alarm.AlarmGateway
import com.ftapps.remft.data.alarm.AlarmHealth
import com.ftapps.remft.data.alarm.AndroidAlarmGateway
import com.ftapps.remft.data.alarm.PermissionKind
import com.ftapps.remft.data.alarm.PermissionStatus
import com.ftapps.remft.data.alarm.SnoozeOrderSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class MutableClock(var now: Instant, private val zoneId: ZoneId = ZoneId.of("Asia/Kolkata")) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advanceMinutes(minutes: Long) {
        now = now.plusSeconds(minutes * 60)
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

class FakeHealth(var current: PermissionStatus = AllAllowed) : AlarmHealth {
    val opened = mutableListOf<PermissionKind>()

    override fun status(): PermissionStatus = current

    override fun intentsFor(kind: PermissionKind): List<Intent> = emptyList()

    override fun open(context: Context, kind: PermissionKind): Boolean {
        opened += kind
        return true
    }

    companion object {
        val AllAllowed = PermissionStatus(
            notifications = true,
            exactAlarms = true,
            fullScreen = true,
            battery = true,
            backgroundData = true,
            alarmVolumeOff = false,
        )
    }
}

class TestAlarms(
    val clock: MutableClock = MutableClock(Instant.parse("2026-11-20T04:30:00Z")),
    gateway: AlarmGateway? = null,
    val health: FakeHealth = FakeHealth(),
    seed: (android.database.sqlite.SQLiteDatabase) -> Unit = {},
    snoozeSource: Flow<String?>? = null,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val tasks = TestTasks(clock, seed)
    val snoozeOrder = snoozeSource?.let(::SnoozeOrderSnapshot) ?: SnoozeOrderSnapshot.of(tasks.settings)
    val env = AlarmEnvironment(
        context = context,
        database = { tasks.holder.db },
        identity = tasks.identity,
        settings = tasks.settings,
        tasks = tasks.repository,
        clock = clock,
        snoozeOrder = snoozeOrder,
        gateway = gateway ?: AndroidAlarmGateway(context) { snoozeOrder.current() },
        filesDir = tasks.dir,
        scope = tasks.scope,
        health = health,
    )

    fun close() = tasks.close()
}
