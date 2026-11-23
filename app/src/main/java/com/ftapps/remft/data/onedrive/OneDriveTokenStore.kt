package com.ftapps.remft.data.onedrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import androidx.core.content.edit
import android.security.keystore.KeyProperties
import com.ftapps.remft.data.backup.AndroidBackupLog
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
    fun forget()
}

interface TokenSlot {
    fun get(): String?
    fun put(value: String)
    fun clear()
}

class TokenUnreadable(cause: Throwable? = null) : Exception("The stored OneDrive sign-in cannot be read", cause)

class OneDriveTokenStore(private val slot: TokenSlot, private val cipher: TokenCipher) {
    fun read(): String? {
        val sealed = try {
            slot.get()
        } catch (e: Exception) {
            throw TokenUnreadable(e)
        } ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw TokenUnreadable(e)
        }
    }

    fun write(token: String) {
        slot.put(Base64.getEncoder().encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8))))
    }

    fun remove() {
        try {
            slot.clear()
        } finally {
            cipher.forget()
        }
    }

    companion object {
        const val KEY = "remft_onedrive_refresh_token"
    }
}

class PrefsTokenSlot(context: Context) : TokenSlot {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun get(): String? = prefs.getString(OneDriveTokenStore.KEY, null)

    override fun put(value: String) {
        prefs.edit(commit = true) { putString(OneDriveTokenStore.KEY, value) }
        check(prefs.getString(OneDriveTokenStore.KEY, null) == value) { "Token not saved" }
    }

    override fun clear() {
        prefs.edit(commit = true) { remove(OneDriveTokenStore.KEY) }
    }

    companion object {
        const val FILE_NAME = "remft_onedrive_sign_in"
    }
}

class KeystoreTokenCipher(private val alias: String = ALIAS) : TokenCipher {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        val existing = keyStore().getKey(alias, null) as? SecretKey
        if (existing != null || !create) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val key = key(create = false) ?: throw IllegalStateException("No key")
        val ivSize = sealed[0].toInt()
        require(ivSize in 12..16 && sealed.size > 1 + ivSize) { "Malformed token" }
        val iv = sealed.copyOfRange(1, 1 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize)
    }

    override fun forget() {
        runCatching { keyStore().deleteEntry(alias) }.onFailure { AndroidBackupLog.warn("autobackup.onedrive_key_forget_failed", it) }
    }

    companion object {
        const val ALIAS = "remft_onedrive_refresh_token_key"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
