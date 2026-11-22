package com.ftapps.remft.domain.notes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class ExternalDocHandle(val token: String, val kind: String, val displayName: String)

enum class ExternalEncoding { Utf8, Utf8Bom, NotText }

data class ExternalDecoded(val text: String, val encoding: ExternalEncoding)

object ExternalDocuments {
    const val RECENTS_LIMIT = 12
    const val KIND_ANDROID_URI = "androidUri"
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun parseRecents(raw: String?): List<ExternalDocHandle> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = ArrayList<ExternalDocHandle>()
        val seen = HashSet<String>()
        for (entry in decoded) {
            val handle = handleOf(entry as? JsonObject) ?: continue
            if (!seen.add(handle.token)) continue
            out.add(handle)
            if (out.size == RECENTS_LIMIT) break
        }
        return out
    }

    private fun handleOf(entry: JsonObject?): ExternalDocHandle? {
        entry ?: return null
        val token = (entry["token"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val name = (entry["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val kind = (entry["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (token.isEmpty() || kind != KIND_ANDROID_URI) return null
        return ExternalDocHandle(token, kind, name)
    }

    fun push(recents: List<ExternalDocHandle>, handle: ExternalDocHandle): List<ExternalDocHandle> =
        (listOf(handle) + recents.filter { it.token != handle.token }).take(RECENTS_LIMIT)

    fun drop(recents: List<ExternalDocHandle>, token: String): List<ExternalDocHandle> = recents.filter { it.token != token }

    fun encodeRecents(recents: List<ExternalDocHandle>): String = buildJsonArray {
        recents.forEach {
            add(buildJsonObject {
                put("token", JsonPrimitive(it.token))
                put("kind", JsonPrimitive(it.kind))
                put("name", JsonPrimitive(it.displayName))
            })
        }
    }.toString()

    fun decode(bytes: ByteArray): ExternalDecoded {
        val hasBom = bytes.size >= 3 && bytes[0] == bom[0] && bytes[1] == bom[1] && bytes[2] == bom[2]
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            ExternalDecoded(decoder.decode(ByteBuffer.wrap(body)).toString(), if (hasBom) ExternalEncoding.Utf8Bom else ExternalEncoding.Utf8)
        } catch (_: CharacterCodingException) {
            ExternalDecoded(String(body, Charsets.UTF_8), ExternalEncoding.NotText)
        }
    }

    fun encode(text: String, encoding: ExternalEncoding): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return if (encoding == ExternalEncoding.Utf8Bom) bom + body else body
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
