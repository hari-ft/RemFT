package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant

data class OneDriveGrant(val accessToken: String, val refreshToken: String, val expiresAt: Instant)

class OneDriveAuth(
    private val tokens: OneDriveTokenStore,
    private val store: SettingsStore,
    private val http: OkHttpClient,
    private val tokenEndpoint: String = OneDriveConfig.TOKEN_ENDPOINT,
    private val clock: () -> Instant = Instant::now,
    private val maxAttempts: Int = 3,
    private val sleeper: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val log: BackupLog = AndroidBackupLog,
) {
    private val mutex = Mutex()
    private val connectedState = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = connectedState.asStateFlow()

    @Volatile
    private var access: String? = null

    @Volatile
    private var expiresAt: Instant? = null

    @Volatile
    private var accessFor: String? = null

    private fun forget() {
        access = null
        expiresAt = null
        accessFor = null
    }

    suspend fun isConnected(): Boolean = try {
        storedToken() != null
    } catch (_: TokenUnreadable) {
        true
    }.also { connectedState.value = it }

    suspend fun canWork(): Boolean = isConnected() && store.get(SettingKeys.OneDriveReconnect.name) != TRUE

    private suspend fun storedToken(): String? = withContext(Dispatchers.IO) {
        try {
            tokens.read()
        } catch (e: TokenUnreadable) {
            log.warn("autobackup.onedrive_token_unreadable", e)
            throw e
        }
    }

    private suspend fun currentToken(): String? = try {
        storedToken()
    } catch (_: TokenUnreadable) {
        forget()
        store.set(SettingKeys.OneDriveReconnect.name, TRUE)
        throw OneDriveException(OneDriveErrors.RECONNECT)
    }

    suspend fun connect(grant: OneDriveGrant) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                tokens.write(grant.refreshToken)
            } catch (e: Exception) {
                log.warn("autobackup.onedrive_token_write_failed", e)
                try {
                    tokens.remove()
                    tokens.write(grant.refreshToken)
                } catch (again: Exception) {
                    log.warn("autobackup.onedrive_token_write_failed", again)
                    throw OneDriveException(OneDriveErrors.SIGN_IN_FAILED)
                }
            }
        }
        store.remove(SettingKeys.OneDriveReconnect.name)
        connectedState.value = true
        access = grant.accessToken
        expiresAt = grant.expiresAt
        accessFor = grant.refreshToken
    }

    suspend fun disconnect() = mutex.withLock {
        forget()
        store.remove(SettingKeys.OneDriveReconnect.name)
        try {
            withContext(Dispatchers.IO) { tokens.remove() }
        } finally {
            connectedState.value = false
        }
    }

    suspend fun needsReconnect(): Nothing = mutex.withLock { refused(accessFor) }

    private suspend fun refused(used: String?): Nothing {
        forget()
        val current = currentToken() ?: throw OneDriveException(OneDriveErrors.SIGNED_OUT)
        if (used == null || used == current) store.set(SettingKeys.OneDriveReconnect.name, TRUE)
        throw OneDriveException(OneDriveErrors.RECONNECT)
    }

    suspend fun accessToken(refresh: Boolean = false): String = mutex.withLock { accessTokenLocked(refresh, again = true) }

    private suspend fun accessTokenLocked(refresh: Boolean, again: Boolean): String {
        val stored = currentToken()
        if (stored == null) {
            forget()
            throw OneDriveException(OneDriveErrors.SIGNED_OUT)
        }
        if (stored != accessFor) forget()
        val cached = access
        val until = expiresAt
        if (!refresh && cached != null && until != null && clock().isBefore(until.minus(EARLY_REFRESH))) return cached

        val (status, json) = exchange(stored)
        val token = JsonText.stringOf(json["access_token"])
        if (status == 200 && token != null) {
            val seconds = JsonText.intOf(json["expires_in"]) ?: JsonText.numberOf(json["expires_in"])?.toLong() ?: DEFAULT_EXPIRY_SECONDS
            val current = currentToken()
            if (current == null) {
                forget()
                throw OneDriveException(OneDriveErrors.SIGNED_OUT)
            }
            if (current != stored) {
                forget()
                if (!again) throw OneDriveException(OneDriveErrors.ERROR)
                return accessTokenLocked(refresh = true, again = false)
            }
            var holder = stored
            val next = JsonText.stringOf(json["refresh_token"])
            if (next != null) {
                try {
                    withContext(Dispatchers.IO) { tokens.write(next) }
                    holder = next
                } catch (e: Exception) {
                    log.warn("autobackup.onedrive_token_write_failed", e)
                }
            }
            store.remove(SettingKeys.OneDriveReconnect.name)
            access = token
            expiresAt = clock().plusSeconds(seconds)
            accessFor = holder
            return token
        }
        val error = JsonText.stringOf(json["error"])
        log.warn("autobackup.onedrive_refresh_failed", fields = mapOf("status" to status, "error" to error))
        if ((status == 400 || status == 401) && error in reconnectErrors) refused(stored)
        throw OneDriveException(OneDriveErrors.ERROR)
    }

    private suspend fun exchange(stored: String): Pair<Int, JsonObject> {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(tokenEndpoint)
                .post(
                    FormBody.Builder()
                        .add("client_id", OneDriveConfig.CLIENT_ID)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", stored)
                        .add("scope", OneDriveConfig.scope)
                        .build(),
                )
                .build()
            val answer = try {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        Triple(response.code, response.header("Retry-After"), response.body.string())
                    }
                }
            } catch (_: IOException) {
                if (++attempt >= maxAttempts) throw OneDriveException(OneDriveErrors.OFFLINE)
                sleeper(backoff(attempt, null))
                continue
            }
            val (status, retryAfter, body) = answer
            if (transient(status) && ++attempt < maxAttempts) {
                sleeper(backoff(attempt, retryAfter))
                continue
            }
            return status to (JsonText.parseOrNull(body) as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    companion object {
        const val TRUE = "true"
        val EARLY_REFRESH: Duration = Duration.ofMinutes(2)
        private const val DEFAULT_EXPIRY_SECONDS = 3600L
        private val reconnectErrors = setOf("invalid_grant", "interaction_required")

        fun transient(status: Int): Boolean = status == 408 || status == 429 || status >= 500

        fun backoff(attempt: Int, retryAfter: String?): Duration {
            val after = retryAfter?.trim()?.toLongOrNull()
            if (after != null) return Duration.ofSeconds(after.coerceIn(1, 60))
            return Duration.ofSeconds(minOf(30L, 1L shl attempt.coerceAtMost(5)))
        }
    }
}
