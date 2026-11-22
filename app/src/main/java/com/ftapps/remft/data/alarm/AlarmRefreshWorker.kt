package com.ftapps.remft.data.alarm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface BackgroundTurn {
    val name: String

    val beforeAlarms: Boolean get() = false

    suspend fun run()
}

class BackgroundRefresh(
    private val env: AlarmEnvironment,
    private val turns: Set<BackgroundTurn>,
    private val locks: WorkLocks? = null,
) {
    suspend fun run(): PassOutcome {
        if (locks?.isHeld(WorkLockNames.RESTORE) == true) {
            env.appLog.info("refresh.skipped_restore")
            return PassOutcome.Skipped("restore")
        }
        val hold = locks?.let { withContext(Dispatchers.IO) { it.tryAcquire(WorkLockNames.HEADLESS_TURN) } }
        if (locks != null && hold == null) {
            env.appLog.info("refresh.skipped_busy")
            return PassOutcome.Skipped("busy")
        }
        try {
            return runTurns()
        } finally {
            hold?.release()
        }
    }

    private suspend fun runTurns(): PassOutcome {
        val started = env.clock.millis()
        env.appLog.info("refresh.start")
        val (early, late) = turns.partition { it.beforeAlarms }
        early.forEach { runTurn(it) }
        val outcome = env.scheduler.pass(rebook = true, reason = "background")
        late.forEach { runTurn(it) }
        env.appLog.info(
            "refresh.done",
            mapOf("took" to (env.clock.millis() - started), "skipped" to (outcome is PassOutcome.Skipped)),
        )
        env.appLog.flush()
        return outcome
    }

    private suspend fun runTurn(turn: BackgroundTurn) {
        try {
            turn.run()
        } catch (e: Exception) {
            env.appLog.error("refresh.turn_failed", e, mapOf("turn" to LogCode(turn.name)))
        }
    }
}

@HiltWorker
class AlarmRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val env: AlarmEnvironment,
    private val turns: Set<@JvmSuppressWildcards BackgroundTurn>,
    private val locks: WorkLocks,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BackgroundRefresh(env, turns, locks).run()
        return Result.success()
    }
}
