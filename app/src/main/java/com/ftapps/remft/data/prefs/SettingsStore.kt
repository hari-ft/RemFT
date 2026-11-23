package com.ftapps.remft.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.domain.settings.SettingKey
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

class SettingsUnavailable(cause: Throwable) : Exception(cause)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    private fun key(name: String) = stringPreferencesKey(name)

    @Volatile
    private var lastReadWarning = 0L

    private fun readFailed(e: Throwable) {
        val now = System.currentTimeMillis()
        if (now - lastReadWarning < READ_WARNING_GAP_MS) return
        lastReadWarning = now
        AppLogs.warn("settings.read_failed", e)
    }

    suspend fun snapshot(): Map<String, String> = try {
        withTimeout(READ_TIMEOUT_MS) {
            dataStore.data.first().asMap().mapNotNull { (k, v) -> (v as? String)?.let { k.name to it } }.toMap()
        }
    } catch (e: TimeoutCancellationException) {
        readFailed(e)
        throw SettingsUnavailable(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        readFailed(e)
        throw SettingsUnavailable(e)
    }

    suspend fun read(name: String): String? = snapshot()[name]

    suspend fun get(name: String): String? = try {
        read(name)
    } catch (_: SettingsUnavailable) {
        null
    }

    suspend fun value(key: SettingKey): String? = get(key.name) ?: key.default

    suspend fun set(name: String, value: String): Boolean = try {
        dataStore.edit { it[key(name)] = value }
        true
    } catch (e: Exception) {
        if (e !is CancellationException) AppLogs.warn("settings.write_failed", e, mapOf("key" to name))
        false
    }

    suspend fun setAll(values: Map<String, String>, removing: Collection<String> = emptyList()): Boolean = try {
        dataStore.edit { prefs ->
            removing.forEach { prefs.remove(key(it)) }
            values.forEach { (name, value) -> prefs[key(name)] = value }
        }
        true
    } catch (e: Exception) {
        if (e !is CancellationException) AppLogs.warn("settings.write_failed", e, mapOf("count" to values.size + removing.size))
        false
    }

    suspend fun remove(name: String): Boolean = try {
        dataStore.edit { it.remove(key(name)) }
        true
    } catch (e: Exception) {
        if (e !is CancellationException) AppLogs.warn("settings.remove_failed", e, mapOf("key" to name))
        false
    }

    suspend fun binEnabled(): Boolean = get(SettingKeys.BinEnabled.name) != "false"

    fun flow(name: String): Flow<String?> = dataStore.data
        .map { it[key(name)] }
        .catch { emit(null) }
        .distinctUntilChanged()

    fun flow(key: SettingKey): Flow<String?> = flow(key.name).map { it ?: key.default }

    suspend fun backupSettings(): Map<String, String> = snapshot().filterKeys { it in SettingKeys.backedUp }

    companion object {
        const val FILE_NAME = "remft_settings"
        private const val READ_TIMEOUT_MS = 3_000L
        private const val READ_WARNING_GAP_MS = 60_000L
    }
}
