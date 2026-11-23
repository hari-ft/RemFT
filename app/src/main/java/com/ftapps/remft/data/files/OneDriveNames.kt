package com.ftapps.remft.data.files

import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.data.db.entity.FileMirrorEntity
import com.ftapps.remft.domain.backup.PathText
import com.ftapps.remft.domain.backup.mirror.MirrorState
import com.ftapps.remft.domain.time.UtcText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import java.time.Instant

sealed interface OneDriveName {
    data object Off : OneDriveName
    data object NotYet : OneDriveName
    data class Saved(val path: String) : OneDriveName
    data class Missing(val path: String?, val itemId: String? = null, val lastSeen: String? = null) : OneDriveName
}

@OptIn(ExperimentalCoroutinesApi::class)
class OneDriveNames(
    private val database: () -> RemftDatabase,
    private val connected: Flow<Boolean?>,
    private val checkConnected: suspend () -> Unit = {},
) {
    fun watch(localPath: String?): Flow<OneDriveName> {
        val storedName = localPath?.let { PathText.basename(it) }?.takeIf { it.isNotEmpty() }
        val mirror: Flow<FileMirrorEntity?> =
            if (storedName == null) flowOf(null)
            else flow { emit(Unit) }.flatMapLatest { database().filesDao().watchMirror(storedName) }
        val known = connected.onStart { runCatching { checkConnected() } }.filterNotNull()
        return combine(known, mirror) { on, row -> if (on) of(row) else OneDriveName.Off }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    companion object {
        fun of(row: FileMirrorEntity?): OneDriveName {
            if (row == null) return OneDriveName.NotYet
            val path = row.remotePath?.takeIf { it.isNotBlank() }
            return when (row.state) {
                MirrorState.MIRRORED -> if (path != null) OneDriveName.Saved(path) else OneDriveName.NotYet
                MirrorState.REMOTE_MISSING, MirrorState.MISSING -> OneDriveName.Missing(
                    path = path,
                    itemId = row.itemId?.takeIf { it.isNotBlank() },
                    lastSeen = listOfNotNull(row.listedAt, row.mirroredAt).maxByOrNull { UtcText.parse(it) ?: Instant.MIN },
                )
                else -> OneDriveName.NotYet
            }
        }

        fun display(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString(" › ")
    }
}
