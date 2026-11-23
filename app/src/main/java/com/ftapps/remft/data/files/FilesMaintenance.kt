package com.ftapps.remft.data.files

import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.bin.RecycleBin

data class MaintenanceResult(val purged: Int, val sweep: SweepResult?)

class FilesMaintenance(private val bin: RecycleBin, private val attachments: AttachmentStore) {
    suspend fun purgeExpired(): Int = runCatching { bin.purgeExpired() }
        .onSuccess { if (it > 0) AppLogs.info("bin.purged", mapOf("items" to it)) }
        .onFailure { AppLogs.warn("bin.purge_failed", it) }
        .getOrDefault(0)

    suspend fun sweepOrphans(): SweepResult? = runCatching { attachments.sweepOrphans() }
        .onSuccess { result ->
            if (result.skipped) {
                AppLogs.info("files.sweep_skipped")
            } else if (result.removed > 0 || result.failed > 0) {
                AppLogs.info("files.orphans_swept", mapOf("removed" to result.removed, "failed" to result.failed, "young" to result.young))
            }
        }
        .onFailure { AppLogs.warn("files.sweep_failed", it) }
        .getOrNull()

    suspend fun runAtStart(): MaintenanceResult {
        val purged = purgeExpired()
        return MaintenanceResult(purged, sweepOrphans())
    }
}
