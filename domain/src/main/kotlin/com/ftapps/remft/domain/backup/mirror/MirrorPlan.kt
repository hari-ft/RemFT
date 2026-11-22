package com.ftapps.remft.domain.backup.mirror

import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.MirrorRef
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object MirrorState {
    const val UPLOADING = "uploading"
    const val MIRRORED = "mirrored"
    const val FAILED = "failed"
    const val MISSING = "missing"
    const val REMOTE_MISSING = "remote-missing"

    val fetchable: Set<String> = setOf(MIRRORED, REMOTE_MISSING, MISSING)
}

data class MirrorRecord(
    val localPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val quickXorHash: String? = null,
    val state: String = MirrorState.UPLOADING,
    val remotePath: String? = null,
    val itemId: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val mirroredAt: Instant? = null,
    val listedAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class RemoteItem(val id: String, val name: String? = null, val size: Long, val quickXorHash: String? = null)

data class MirrorListingPlan(
    val plan: Map<String, MirrorRef>,
    val refound: List<String>,
    val reupload: List<String>,
    val notThere: List<String>,
    val rewrittenHeld: Int,
    val otherAccount: Boolean,
)

object MirrorPlan {
    const val OTHER_ACCOUNT_CODE = "ONEDRIVE_OTHER_ACCOUNT"
    const val MISSING_IN_ONEDRIVE = "MISSING_IN_ONEDRIVE"
    val REWRITE_SETTLE_TIME: Duration = Duration.ofHours(1)
    val MANUAL_LIST_PROTECTION: Duration = Duration.ofDays(365)
    val STOP_CODES: Set<String> = setOf("ONEDRIVE_RECONNECT", "ONEDRIVE_SIGNED_OUT", "OFFLINE")

    val rewritableExtensions: Set<String> = setOf(
        ".doc", ".docx", ".docm", ".dot", ".dotx", ".dotm",
        ".xls", ".xlsx", ".xlsm", ".xlsb", ".xlt", ".xltx", ".xltm",
        ".ppt", ".pptx", ".pptm", ".pps", ".ppsx", ".ppsm", ".pot", ".potx",
        ".potm", ".vsd", ".vsdx", ".vsdm", ".odt", ".ods", ".odp",
    )

    fun oneDriveMayRewrite(name: String): Boolean = PathText.extension(name).lowercase() in rewritableExtensions

    fun confirms(item: RemoteItem, size: Long, quickXor: String?): Boolean =
        item.size == size && (item.quickXorHash == null || quickXor == null || item.quickXorHash == quickXor)

    fun heldRewritten(item: RemoteItem, itemId: String?, remotePath: String?): Boolean {
        if (itemId.isNullOrEmpty() || item.id != itemId) return false
        if (remotePath == null || !oneDriveMayRewrite(remotePath)) return false
        val name = item.name
        return name == null || name.lowercase() == PathText.basename(remotePath).lowercase()
    }

    fun candidates(
        needed: Collection<String>,
        rows: Map<String, MirrorRecord>,
        hasLocal: (String) -> Boolean,
    ): Pair<List<MirrorRecord>, Map<String, Boolean>> {
        val local = LinkedHashMap<String, Boolean>()
        val out = ArrayList<MirrorRecord>()
        for (name in needed) {
            val row = rows[name] ?: continue
            if (row.itemId.isNullOrEmpty()) continue
            val present = hasLocal(name)
            local[name] = present
            if (row.state == MirrorState.MIRRORED || (!present && row.state in MirrorState.fetchable)) out.add(row)
        }
        return out to local
    }

    fun classify(
        candidates: List<MirrorRecord>,
        hasLocal: Map<String, Boolean>,
        items: Map<String, RemoteItem>,
        backupAt: Instant,
        unchangedOnPhone: (MirrorRecord) -> Boolean,
    ): MirrorListingPlan {
        val plan = LinkedHashMap<String, MirrorRef>()
        val refound = ArrayList<String>()
        val reupload = ArrayList<String>()
        val notThere = ArrayList<String>()
        val settled = backupAt.minus(REWRITE_SETTLE_TIME)
        var rewrittenHeld = 0
        for (row in candidates) {
            val item = items[row.itemId]
            val local = hasLocal[row.localPath] ?: false
            if (item != null && confirms(item, row.sizeBytes, row.quickXorHash)) {
                val mirroredAt = row.mirroredAt
                if (local && oneDriveMayRewrite(row.remotePath ?: row.localPath) && mirroredAt != null && mirroredAt.isAfter(settled)) {
                    continue
                }
                plan[row.localPath] = MirrorRef(item.id, row.remotePath ?: "", row.sizeBytes, row.sha256)
                if (row.state != MirrorState.MIRRORED) refound.add(row.localPath)
            } else if (item != null && heldRewritten(item, row.itemId, row.remotePath) && (!local || unchangedOnPhone(row))) {
                rewrittenHeld++
            } else if (local) {
                reupload.add(row.localPath)
            } else {
                notThere.add(row.localPath)
            }
        }
        val otherAccount = plan.isEmpty() &&
            candidates.size >= 2 &&
            notThere.isNotEmpty() &&
            candidates.all { items[it.itemId] == null }
        return MirrorListingPlan(plan, refound, reupload, notThere, rewrittenHeld, otherAccount)
    }

    fun listedStamp(backupAt: Instant): Instant = backupAt.truncatedTo(ChronoUnit.SECONDS)

    fun shouldStampListed(current: Instant?, at: Instant): Boolean = current == null || current.isBefore(at)

    fun refsForMissingLocal(
        needed: Collection<String>,
        rows: Map<String, MirrorRecord>,
        hasLocal: (String) -> Boolean,
    ): Map<String, MirrorRef> {
        val out = LinkedHashMap<String, MirrorRef>()
        for (name in needed) {
            val row = rows[name] ?: continue
            if (row.state != MirrorState.MIRRORED) continue
            if (row.itemId.isNullOrEmpty() && !MirrorPaths.isMirrorPath(row.remotePath ?: "")) continue
            if (hasLocal(name)) continue
            out[name] = MirrorRef(row.itemId ?: "", row.remotePath ?: "", row.sizeBytes, row.sha256)
        }
        return out
    }

    fun manualListStamp(now: Instant): Instant = now.plus(MANUAL_LIST_PROTECTION)
}
