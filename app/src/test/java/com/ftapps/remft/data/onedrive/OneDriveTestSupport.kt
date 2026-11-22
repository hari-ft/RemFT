package com.ftapps.remft.data.onedrive

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.BackupLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class FakeCipher : TokenCipher {
    var broken = false
    var forgotten = 0

    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (broken) throw IllegalStateException("Key gone")
        return sealed.reversedArray()
    }

    override fun forget() {
        forgotten++
    }
}

class MemorySlot : TokenSlot {
    @Volatile
    var value: String? = null

    override fun get(): String? = value

    override fun put(value: String) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}

class OneDriveRig {
    val root: File = Files.createTempDirectory("od").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") })
    val slot = MemorySlot()
    val cipher = FakeCipher()
    val tokens = OneDriveTokenStore(slot, cipher)
    val http: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build()
    var now: Instant = Instant.parse("2026-11-20T10:00:00Z")
    val sleeps = ArrayList<Duration>()

    fun auth(tokenEndpoint: String = "http://127.0.0.1:1/token"): OneDriveAuth = OneDriveAuth(
        tokens = tokens,
        store = store,
        http = http,
        tokenEndpoint = tokenEndpoint,
        clock = { now },
        sleeper = { sleeps.add(it) },
        log = BackupLog.None,
    )

    fun storedToken(): String? = tokens.read()

    fun close() {
        scope.cancel()
        root.deleteRecursively()
    }
}
