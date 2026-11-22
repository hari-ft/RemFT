package com.ftapps.remft.data.backup

import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.prefs.SettingsUnavailable
import com.ftapps.remft.domain.backup.BackupSettings

object RestoreSettings {
    suspend fun hold(store: SettingsStore, settings: Map<String, String>) {
        if (!store.set(BackupSettings.pendingKey, BackupSettings.encode(settings))) {
            throw SettingsUnavailable(IllegalStateException("pending settings not stored"))
        }
    }

    suspend fun applyPending(store: SettingsStore): Boolean {
        val raw = store.snapshot()[BackupSettings.pendingKey] ?: return false
        val parsed = BackupSettings.parse(raw)
        if (parsed == null) {
            store.remove(BackupSettings.pendingKey)
            return false
        }
        val values = LinkedHashMap<String, String>()
        val removing = ArrayList<String>()
        for ((key, value) in BackupSettings.changesToApply(parsed)) {
            if (value == null) removing.add(key) else values[key] = value
        }
        removing.add(BackupSettings.pendingKey)
        if (!store.setAll(values, removing)) throw SettingsUnavailable(IllegalStateException("settings not applied"))
        return true
    }
}
