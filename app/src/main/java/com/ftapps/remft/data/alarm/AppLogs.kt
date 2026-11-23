package com.ftapps.remft.data.alarm

import android.content.Context
import com.ftapps.remft.data.onedrive.OneDriveException
import com.ftapps.remft.domain.backup.BackupException
import java.io.File
import java.time.Instant
import java.util.Locale

enum class AppLogCategory(val label: String) {
    STARTUP("Startup"),
    BACKUP("Backup"),
    RESTORE("Restore"),
    AUTO_BACKUP("Automatic backup"),
    ONEDRIVE("OneDrive"),
    FILES("Files"),
    IMPORT("Import"),
    NOTES("Notes"),
    TASKS("Tasks"),
    BIN("Bin"),
    SETTINGS("Settings"),
    ALARMS("Alarms"),
    APP("App"),
    ;

    companion object {
        private val restoreBackupEvents = setOf(
            "refused",
            "identity_adopt_failed",
            "mirror_refs_restore_failed",
            "settings_apply_failed",
            "settings_unreadable",
            "rollback_failed",
            "leftover_bak_kept",
            "validated",
        )

        fun parse(name: String): AppLogCategory? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        fun of(event: String): AppLogCategory {
            val rest = event.substringAfter('.', "")
            return when (event.substringBefore('.')) {
                "startup" -> STARTUP
                "app" -> if (rest == "launch") STARTUP else APP
                "backup" -> when {
                    rest.startsWith("onedrive_") -> ONEDRIVE
                    rest.startsWith("restore") || rest in restoreBackupEvents -> RESTORE
                    else -> BACKUP
                }
                "restore" -> RESTORE
                "autobackup" -> if (rest.startsWith("onedrive_")) ONEDRIVE else AUTO_BACKUP
                "onedrive", "mirror" -> ONEDRIVE
                "oldbackup", "import" -> IMPORT
                "lock" -> BACKUP
                "files" -> FILES
                "notes" -> NOTES
                "tasks", "series" -> TASKS
                "bin" -> BIN
                "settings" -> SETTINGS
                "alarm", "alarms", "refresh" -> ALARMS
                else -> APP
            }
        }
    }
}

object AppLogText {
    fun message(entry: AppLogEntry): String {
        val f = entry.fields
        return when (entry.event) {
            "backup.created" -> if (f.keys.containsAll(listOf("tasks", "notes", "files", "bytes"))) {
                "Backup created: ${f["tasks"]} tasks, ${f["notes"]} notes, ${f["files"]} files, ${size(f["bytes"])}"
            } else {
                "Backup created"
            }
            "backup.restored" -> "Restore finished"
            "oldbackup.restored" -> f["appVersion"]?.let { "Restored from old RemFT backup $it" } ?: "Restored from old RemFT backup"
            "app.launch" -> "App started"
            "app.crash" -> "App crashed"
            else -> humanize(entry.event)
        }
    }

    fun humanize(event: String): String {
        val words = event.substringAfter('.', event).replace('_', ' ').replace('.', ' ').trim()
        return words.replaceFirstChar { it.titlecase(Locale.ROOT) }.ifEmpty { event }
    }

    fun size(bytes: String?): String {
        val value = bytes?.toLongOrNull() ?: return "${bytes ?: "?"} B"
        return when {
            value >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
            value >= 1024L -> String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
            else -> "$value B"
        }
    }
}

object AppLogs {
    const val FOLDER = "logs"
    private const val MAX_EARLY = 256

    private val codeKeys = setOf(
        "action", "appVersion", "change", "code", "column", "destination", "error", "format", "kind", "key", "lock",
        "note", "outcome", "phase", "reason", "result", "source", "stage", "table", "thread", "trigger", "turn",
    )

    private class Early(
        val at: Instant,
        val level: AppLogLevel,
        val event: String,
        val fields: Map<String, Any?>,
        val error: Throwable?,
        val category: AppLogCategory?,
    )

    private val early = ArrayDeque<Early>()

    @Volatile
    private var installed: AppLog? = null

    val current: AppLog? get() = installed

    fun of(context: Context): AppLog = installed ?: synchronized(this) {
        installed ?: AppLog(File(context.filesDir, FOLDER)).also { install(it) }
    }

    fun install(log: AppLog?) {
        val waiting = synchronized(this) {
            installed = log
            if (log == null) emptyList() else early.toList().also { early.clear() }
        }
        if (log != null) waiting.forEach { log.add(it.level, it.event, it.fields, it.error, it.category, it.at) }
    }

    fun info(event: String, fields: Map<String, Any?> = emptyMap(), category: AppLogCategory? = null) =
        record(AppLogLevel.INFO, event, fields, null, category)

    fun warn(event: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap(), category: AppLogCategory? = null) =
        record(AppLogLevel.WARN, event, fields, error, category)

    fun error(event: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap(), category: AppLogCategory? = null) =
        record(AppLogLevel.ERROR, event, fields, error, category)

    fun record(level: AppLogLevel, event: String, fields: Map<String, Any?>, error: Throwable?, category: AppLogCategory? = null) {
        try {
            val safe = safeFields(fields, error)
            val log = installed
            if (log != null) {
                log.add(level, event, safe, error, category)
                return
            }
            synchronized(this) {
                val now = installed
                if (now != null) {
                    now.add(level, event, safe, error, category)
                } else {
                    early.addLast(Early(Instant.now(), level, event, safe, error, category))
                    while (early.size > MAX_EARLY) early.removeFirst()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun safeFields(fields: Map<String, Any?>, error: Throwable? = null): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in fields) {
            out[key] = if (value is String && key in codeKeys) LogCode(value) else value
        }
        reason(error)?.let { if ("reason" !in out) out["reason"] = LogCode(it) }
        return out
    }

    fun reason(error: Throwable?): String? = when (error) {
        is BackupException -> error.code
        is OneDriveException -> error.code
        else -> null
    }

    fun watchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashRecorder) return
        Thread.setDefaultUncaughtExceptionHandler(CrashRecorder(previous))
    }

    private class CrashRecorder(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, crash: Throwable) {
            try {
                AppLogs.error("app.crash", crash, mapOf("thread" to if (thread.name == "main") "main" else "background"))
                installed?.flush()
            } catch (_: Throwable) {
            }
            next?.uncaughtException(thread, crash)
        }
    }
}
