package com.ftapps.remft.data.files

import com.ftapps.remft.data.bin.RecycleBin

data class MaintenanceResult(val purged: Int, val sweep: SweepResult?)

class FilesMaintenance(private val bin: RecycleBin, private val attachments: AttachmentStore) {
    suspend fun purgeExpired(): Int = runCatching { bin.purgeExpired() }.getOrDefault(0)

    suspend fun sweepOrphans(): SweepResult? = runCatching { attachments.sweepOrphans() }.getOrNull()

    suspend fun runAtStart(): MaintenanceResult {
        val purged = purgeExpired()
        return MaintenanceResult(purged, sweepOrphans())
    }
}
