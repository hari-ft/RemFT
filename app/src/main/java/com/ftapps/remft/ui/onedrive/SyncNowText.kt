package com.ftapps.remft.ui.onedrive

import android.content.res.Resources
import com.ftapps.remft.R
import com.ftapps.remft.data.mirror.MirrorPhase
import com.ftapps.remft.data.mirror.MirrorProgress
import com.ftapps.remft.data.onedrive.SyncNowResult
import com.ftapps.remft.domain.text.fill

object SyncNowText {
    fun message(resources: Resources, result: SyncNowResult): String = when (result) {
        SyncNowResult.UpToDate -> resources.getString(R.string.syncnow_upToDate)
        is SyncNowResult.Synced -> {
            val parts = listOf(
                R.string.syncnow_partUploaded to result.uploaded,
                R.string.syncnow_partMoved to result.moved,
                R.string.syncnow_partReview to result.movedToReview,
                R.string.syncnow_partDownloaded to result.downloaded,
                R.string.syncnow_partFailed to result.failed,
            ).filter { it.second > 0 }.joinToString(", ") { (id, n) -> resources.getString(id).fill("n" to n) }
            resources.getString(R.string.syncnow_synced).fill("parts" to parts)
        }
        is SyncNowResult.StoppedEarly -> resources.getString(R.string.syncnow_stopped).fill("n" to result.remaining)
        SyncNowResult.Off -> resources.getString(R.string.syncnow_off)
        SyncNowResult.SignedOut -> resources.getString(R.string.syncnow_signedOut)
        SyncNowResult.RestoreBusy -> resources.getString(R.string.syncnow_restoreBusy)
        SyncNowResult.Busy -> resources.getString(R.string.syncnow_busy)
        is SyncNowResult.Failed -> resources.getString(R.string.syncnow_failed)
            .fill("error" to resources.getString(AutoBackupText.error(result.code)))
    }

    fun needsConnect(result: SyncNowResult): Boolean = result == SyncNowResult.SignedOut

    fun progress(resources: Resources, p: MirrorProgress): String {
        val step = (p.done + 1).coerceAtMost(p.total.coerceAtLeast(1))
        return when (p.phase) {
            MirrorPhase.Folders -> resources.getString(R.string.syncnow_phaseFolders)
            MirrorPhase.Moving -> resources.getString(R.string.syncnow_phaseMoving).fill("done" to step, "total" to p.total)
            MirrorPhase.Uploading -> if (p.size > 0 && p.bytes > 0 && p.bytes < p.size) {
                resources.getString(R.string.syncnow_phaseUploadingBytes)
                    .fill("done" to step, "total" to p.total, "name" to p.name.orEmpty(), "percent" to (p.bytes * 100 / p.size))
            } else {
                resources.getString(R.string.syncnow_phaseUploading).fill("done" to step, "total" to p.total, "name" to p.name.orEmpty())
            }
            MirrorPhase.Checking -> resources.getString(R.string.syncnow_phaseChecking)
            MirrorPhase.Downloading -> resources.getString(R.string.syncnow_phaseDownloading).fill("done" to step, "total" to p.total)
        }
    }
}
