package com.ftapps.remft.domain.backup.lock

import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.time.UtcText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Timer
import java.util.TimerTask

object WorkLockNames {
    const val ONEDRIVE_WORK = "onedrive-work"
    const val HEADLESS_TURN = "headless-turn"
    const val RESTORE = "restore"
}

data class LockOwner(val token: String, val pid: Long, val beat: Instant) {
    fun toJson(): JsonObject = buildJsonObject {
        put("token", token)
        put("pid", pid)
        put("beat", UtcText.format(beat))
    }

    fun encode(): String = JsonText.encode(toJson())

    companion object {
        val STALE_AFTER: Duration = Duration.ofMinutes(2)
        val HEARTBEAT_EVERY: Duration = Duration.ofSeconds(20)

        fun parse(raw: String): LockOwner? = try {
            val json = JsonText.parse(raw)
            if (json !is JsonObject) {
                null
            } else {
                val beatRaw = json["beat"]
                val beatText = if (beatRaw == null) "null" else JsonText.stringOf(beatRaw) ?: beatRaw.toString()
                val beat = UtcText.parse(beatText)
                val pid = JsonText.intOf(json["pid"])
                val token = JsonText.stringOf(json["token"])
                if (beat == null || pid == null || token == null) null else LockOwner(token, pid, beat)
            }
        } catch (_: Exception) {
            null
        }

        fun isStale(
            owner: LockOwner?,
            fileModified: Instant?,
            now: Instant,
            ownPid: Long,
            staleAfter: Duration = STALE_AFTER,
        ): Boolean {
            if (owner == null) {
                if (fileModified == null) return true
                return Duration.between(fileModified, now) > staleAfter
            }
            if (owner.pid != ownPid) return true
            return Duration.between(owner.beat, now) > staleAfter
        }
    }
}

class BackgroundWorkBusy : Exception("A background refresh is still running; try again.")

class WorkLocks(
    val dir: File,
    private val pid: Long,
    private val clock: () -> Instant = Instant::now,
    val staleAfter: Duration = LockOwner.STALE_AFTER,
    val heartbeatEvery: Duration = LockOwner.HEARTBEAT_EVERY,
    private val log: BackupLog = BackupLog.None,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val random = SecureRandom()

    internal fun fileOf(name: String): File = File(dir, "$name.lock")

    fun tryAcquire(name: String): WorkLockHold? {
        val file = fileOf(name)
        val token = pid.toString() + "_" + (random.nextInt().toLong() and 0xffffffffL).toString(36)
        repeat(2) {
            try {
                Files.createDirectories(dir.toPath())
                Files.createFile(file.toPath())
            } catch (_: IOException) {
                val owner = readOwner(file)
                if (!isStale(owner, file)) return null
                val again = readOwner(file)
                if (again?.token != owner?.token || !isStale(again, file)) return null
                log.warn("lock.stale_taken_over", fields = mapOf("lock" to name))
                delete(file)
                return@repeat
            }
            val hold = WorkLockHold(this, name, file, token)
            hold.beat()
            if (readOwner(file)?.token != token) return null
            hold.startHeartbeat()
            return hold
        }
        return null
    }

    fun acquire(name: String, wait: Duration, poll: Duration = Duration.ofMillis(250)): WorkLockHold? {
        val until = clock().plus(wait)
        while (true) {
            val hold = tryAcquire(name)
            if (hold != null) return hold
            if (!clock().isBefore(until)) return null
            sleeper(poll.toMillis())
        }
    }

    fun isHeld(name: String): Boolean {
        val file = fileOf(name)
        if (!file.exists()) return false
        return !isStale(readOwner(file), file)
    }

    fun waitUntilFree(name: String, wait: Duration, poll: Duration = Duration.ofMillis(250)): Boolean {
        val until = clock().plus(wait)
        while (isHeld(name)) {
            if (!clock().isBefore(until)) return false
            sleeper(poll.toMillis())
        }
        return true
    }

    fun <T> withLock(name: String, wait: Duration = Duration.ZERO, block: () -> T): T? {
        val hold = if (wait.isZero) tryAcquire(name) else acquire(name, wait)
        if (hold == null) {
            log.info("lock.busy", mapOf("lock" to name))
            return null
        }
        try {
            return block()
        } finally {
            hold.release()
        }
    }

    fun beginRestore(wait: Duration = Duration.ofSeconds(45)): WorkLockHold {
        val hold = acquire(WorkLockNames.RESTORE, wait) ?: throw BackgroundWorkBusy()
        if (!waitUntilFree(WorkLockNames.HEADLESS_TURN, wait)) {
            hold.release()
            log.warn("restore.background_busy")
            throw BackgroundWorkBusy()
        }
        val oneDrive = acquire(WorkLockNames.ONEDRIVE_WORK, wait)
        if (oneDrive == null) {
            hold.release()
            log.warn("restore.onedrive_busy")
            throw BackgroundWorkBusy()
        }
        hold.alsoRelease(oneDrive)
        return hold
    }

    internal fun now(): Instant = clock()

    internal fun ownPid(): Long = pid

    internal fun readOwner(file: File): LockOwner? = try {
        LockOwner.parse(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun isStale(owner: LockOwner?, file: File): Boolean {
        val modified = if (owner == null) file.lastModified().takeIf { it > 0L }?.let { Instant.ofEpochMilli(it) } else null
        return LockOwner.isStale(owner, modified, clock(), pid, staleAfter)
    }

    internal fun delete(file: File) {
        repeat(20) {
            try {
                Files.deleteIfExists(file.toPath())
                return
            } catch (_: IOException) {
                if (!file.exists()) return
                sleeper(25)
            }
        }
    }
}

class WorkLockHold internal constructor(
    private val locks: WorkLocks,
    val name: String,
    private val file: File,
    private val token: String,
) {
    @Volatile
    private var released = false
    private var timer: Timer? = null
    private val companions = ArrayList<WorkLockHold>()

    @Synchronized
    internal fun alsoRelease(other: WorkLockHold) {
        companions.add(other)
    }

    internal fun startHeartbeat() {
        val every = locks.heartbeatEvery.toMillis().coerceAtLeast(1L)
        timer = Timer("work-lock-$name", true).also {
            it.schedule(
                object : TimerTask() {
                    override fun run() = beat()
                },
                every,
                every,
            )
        }
    }

    @Synchronized
    internal fun beat() {
        if (released) return
        try {
            FileOutputStream(file).use { out ->
                out.write(LockOwner(token, locks.ownPid(), locks.now()).encode().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        } catch (_: IOException) {
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        timer?.cancel()
        timer = null
        try {
            if (locks.readOwner(file)?.token == token) locks.delete(file)
        } catch (_: IOException) {
        }
        companions.forEach { it.release() }
        companions.clear()
    }
}
