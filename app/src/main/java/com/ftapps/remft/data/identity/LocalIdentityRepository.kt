package com.ftapps.remft.data.identity

import com.ftapps.remft.data.db.IdentityDao
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.Ulid
import com.ftapps.remft.domain.identity.WorkspaceIdentity
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalIdentity(val workspaceId: String, val reliable: Boolean)

class LocalIdentityRepository(
    private val dao: () -> IdentityDao,
    private val store: SettingsStore,
) {
    private val mutex = Mutex()
    private var sessionId: String? = null

    suspend fun resolve(): LocalIdentity = mutex.withLock {
        val name = SettingKeys.LocalWorkspaceId.name
        var readable = true
        val stored = try {
            store.read(name)?.takeIf { it.isNotEmpty() }
        } catch (_: SettingsUnavailable) {
            readable = false
            null
        }
        val counts = runCatching { dao().workspaceCounts().associate { it.ws to it.n } }.getOrNull()
        val decision = WorkspaceIdentity.decide(stored, readable, counts, sessionId) { Ulid.next() }
        val persisted = decision.persist && store.set(name, decision.workspaceId)
        sessionId = if (persisted || (stored != null && stored == decision.workspaceId)) null else decision.workspaceId
        LocalIdentity(decision.workspaceId, decision.reliable)
    }

    suspend fun adoptFromDatabase(): String? = mutex.withLock {
        val owner = WorkspaceIdentity.dominant(dao().workspaceCounts().associate { it.ws to it.n }) ?: return@withLock null
        if (store.set(SettingKeys.LocalWorkspaceId.name, owner)) sessionId = null else sessionId = owner
        owner
    }

    fun forgetSession() {
        sessionId = null
    }
}
