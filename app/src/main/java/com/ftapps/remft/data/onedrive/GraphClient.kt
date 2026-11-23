package com.ftapps.remft.data.onedrive

import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.BackupNames
import com.ftapps.remft.domain.backup.JsonText
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.backup.mirror.MirrorPaths
import com.ftapps.remft.domain.backup.mirror.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Duration

enum class MirrorMove { Moved, NameTaken, Gone }

data class RemoteEntry(val id: String, val name: String, val size: Long, val isFolder: Boolean)

interface BackupDestination {
    val id: String
    suspend fun upload(zip: File, name: String)
    suspend fun list(): List<StoredBackup>
    suspend fun delete(backup: StoredBackup)
}

interface MirrorRemote {
    suspend fun putFile(file: File, path: String, contentType: String = OCTET_STREAM): RemoteItem
    suspend fun itemsById(ids: Collection<String>): Map<String, RemoteItem?>
    suspend fun listFolder(path: String): List<RemoteEntry>?
    suspend fun childrenOf(itemId: String): List<RemoteEntry>?
    suspend fun ensureFolder(path: String): String
    suspend fun folderAt(path: String): String?
    suspend fun parentOf(itemId: String): String?
    suspend fun moveInto(itemId: String? = null, path: String? = null, folderId: String, name: String): MirrorMove
    suspend fun download(itemId: String?, path: String?, size: Long?, to: File, onProgress: ((Long) -> Unit)? = null): Boolean

    companion object {
        const val OCTET_STREAM = "application/octet-stream"
    }
}

class GraphResponse(val code: Int, val headers: Headers, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val json: JsonObject get() = JsonText.parseOrNull(text) as? JsonObject ?: JsonObject(emptyMap())
}

class GraphClient(
    private val auth: OneDriveAuth,
    private val http: OkHttpClient,
    baseUrl: String = OneDriveConfig.GRAPH_BASE,
    private val chunkSize: Int = DEFAULT_CHUNK,
    private val maxAttempts: Int = 5,
    private val sleeper: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    private val log: BackupLog = AndroidBackupLog,
) : BackupDestination, MirrorRemote {
    private val base = baseUrl.trimEnd('/')
    private val noRedirects: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    init {
        require(chunkSize > 0 && chunkSize % CHUNK_UNIT == 0)
    }

    override val id: String get() = OneDriveConfig.DESTINATION_ID

    private fun item(path: String, action: String, query: Map<String, String> = emptyMap()): HttpUrl =
        withQuery("$base/me/drive/special/approot:/${encodePath(path)}:/$action", query)

    private fun itemByPath(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        withQuery("$base/me/drive/special/approot:/${encodePath(path)}", query)

    private fun byId(itemId: String, suffix: String = "", query: Map<String, String> = emptyMap()): HttpUrl =
        withQuery("$base/me/drive/items/${encodeComponent(itemId)}$suffix", query)

    private fun withQuery(url: String, query: Map<String, String>): HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        for ((k, v) in query) builder.addQueryParameter(k, v)
        return builder.build()
    }

    override suspend fun upload(zip: File, name: String) {
        guard { put(zip, name, ZIP) }
    }

    override suspend fun putFile(file: File, path: String, contentType: String): RemoteItem = guard {
        val res = put(file, path, contentType)
        remoteItem(res.json) ?: throw OneDriveException(OneDriveErrors.ERROR)
    }

    private suspend fun put(file: File, path: String, contentType: String): GraphResponse {
        if (!file.isFile) throw LocalFileGone()
        val size = file.length()
        if (size >= SIMPLE_UPLOAD_MAX) return uploadInChunks(file, path, size)
        return ensureOk(
            graph(
                "PUT",
                item(path, "content", mapOf(CONFLICT to "replace")),
                file.asRequestBody(contentType.toMediaType()),
            ),
        )
    }

    override suspend fun itemsById(ids: Collection<String>): Map<String, RemoteItem?> = guard {
        val out = LinkedHashMap<String, RemoteItem?>()
        val all = ids.toSet().toList()
        var start = 0
        while (start < all.size) {
            var pending = all.subList(start, minOf(start + BATCH_SIZE, all.size)).toList()
            var attempt = 0
            while (pending.isNotEmpty()) {
                val batch = pending
                val payload = buildJsonObject {
                    put(
                        "requests",
                        buildJsonArray {
                            batch.forEachIndexed { i, itemId ->
                                add(
                                    buildJsonObject {
                                        put("id", "$i")
                                        put("method", "GET")
                                        put("url", "/me/drive/items/${encodeComponent(itemId)}?\$select=id,name,size,file,deleted")
                                    },
                                )
                            }
                        },
                    )
                }
                val res = ensureOk(graph("POST", "$base/\$batch".toHttpUrl(), jsonBody(payload)))
                val retry = ArrayList<String>()
                var wait: Duration? = null
                for (r in (res.json["responses"] as? JsonArray).orEmpty()) {
                    if (r !is JsonObject) continue
                    val i = JsonText.stringOf(r["id"])?.toIntOrNull() ?: JsonText.intOf(r["id"])?.toInt() ?: continue
                    if (i < 0 || i >= batch.size) continue
                    val itemId = batch[i]
                    val status = JsonText.intOf(r["status"])?.toInt()
                    val body = r["body"]
                    when {
                        status == 200 && body is JsonObject ->
                            out[itemId] = if (JsonText.isNull(body["deleted"])) remoteItem(body) else null
                        status == 404 -> out[itemId] = null
                        status != null && transient(status) -> {
                            retry.add(itemId)
                            val after = (r["headers"] as? JsonObject)?.let { JsonText.stringOf(it["Retry-After"]) ?: it["Retry-After"]?.toString() }
                            after?.trim()?.toLongOrNull()?.let { wait = Duration.ofSeconds(it.coerceIn(1, 60)) }
                        }
                        else -> throw OneDriveException(if (status == 403) OneDriveErrors.DENIED else OneDriveErrors.ERROR)
                    }
                }
                for (itemId in batch) {
                    if (!out.containsKey(itemId) && itemId !in retry) throw OneDriveException(OneDriveErrors.ERROR)
                }
                if (retry.isEmpty()) break
                if (++attempt >= maxAttempts) throw OneDriveException(OneDriveErrors.ERROR)
                sleeper(wait ?: OneDriveAuth.backoff(attempt, null))
                pending = retry
            }
            start += BATCH_SIZE
        }
        out
    }

    override suspend fun listFolder(path: String): List<RemoteEntry>? = guard {
        children(item(path, "children", mapOf(SELECT to "id,name,size,file,folder", TOP to "200")))
    }

    override suspend fun childrenOf(itemId: String): List<RemoteEntry>? = guard {
        children(byId(itemId, "/children", mapOf(SELECT to "id,name,size,file,folder", TOP to "200")))
    }

    private suspend fun children(start: HttpUrl): List<RemoteEntry>? {
        val out = ArrayList<RemoteEntry>()
        var next: HttpUrl? = start
        var first = true
        while (next != null) {
            val res = graph("GET", next)
            if (res.code == 404 && first) return null
            first = false
            val json = ensureOk(res).json
            for (entry in (json["value"] as? JsonArray).orEmpty()) {
                if (entry !is JsonObject) continue
                val id = JsonText.stringOf(entry["id"]) ?: continue
                val name = JsonText.stringOf(entry["name"]) ?: continue
                out.add(RemoteEntry(id, name, sizeOf(entry["size"]) ?: 0L, !JsonText.isNull(entry["folder"])))
            }
            next = JsonText.stringOf(json[NEXT_LINK])?.toHttpUrl()
        }
        return out
    }

    override suspend fun ensureFolder(path: String): String = guard { ensureFolderInner(path) }

    private suspend fun ensureFolderInner(path: String): String {
        check(path == MirrorPaths.ROOT || MirrorPaths.isMirrorPath(path)) { "Not a mirror path" }
        folderId(path)?.let { return it }
        val cut = path.lastIndexOf('/')
        val parentUrl = if (cut < 0) {
            "$base/me/drive/special/approot/children".toHttpUrl()
        } else {
            val parent = path.substring(0, cut)
            ensureFolderInner(parent)
            item(parent, "children")
        }
        val payload = buildJsonObject {
            put("name", path.substring(cut + 1))
            putJsonObject("folder") {}
            put(CONFLICT, "fail")
        }
        val res = graph("POST", parentUrl, jsonBody(payload))
        if (res.code == 409) return folderId(path) ?: throw OneDriveException(OneDriveErrors.ERROR)
        return JsonText.stringOf(ensureOk(res).json["id"]) ?: throw OneDriveException(OneDriveErrors.ERROR)
    }

    override suspend fun folderAt(path: String): String? = guard {
        check(MirrorPaths.isMirrorPath(path)) { "Not a mirror path" }
        val res = graph("GET", itemByPath(path, mapOf(SELECT to "id,folder")))
        if (res.code == 404) return@guard null
        val json = ensureOk(res).json
        if (JsonText.isNull(json["folder"])) null else JsonText.stringOf(json["id"])
    }

    private suspend fun folderId(path: String): String? {
        val res = graph("GET", itemByPath(path, mapOf(SELECT to "id,folder")))
        if (res.code == 404) return null
        val json = ensureOk(res).json
        val id = JsonText.stringOf(json["id"])
        if (id == null || JsonText.isNull(json["folder"])) throw OneDriveException(OneDriveErrors.ERROR)
        return id
    }

    override suspend fun parentOf(itemId: String): String? = guard {
        val res = graph("GET", byId(itemId, query = mapOf(SELECT to "id,parentReference")))
        if (res.code == 404) return@guard null
        val parent = ensureOk(res).json["parentReference"] as? JsonObject
        JsonText.stringOf(parent?.get("id")) ?: throw OneDriveException(OneDriveErrors.ERROR)
    }

    override suspend fun moveInto(itemId: String?, path: String?, folderId: String, name: String): MirrorMove = guard {
        val query = mapOf(CONFLICT to "fail")
        val url = when {
            !itemId.isNullOrEmpty() -> byId(itemId, query = query)
            path != null && MirrorPaths.isMirrorPath(path) -> itemByPath(path, query)
            else -> throw IllegalStateException("Not a mirror path")
        }
        val payload = buildJsonObject {
            putJsonObject("parentReference") { put("id", folderId) }
            put("name", name)
        }
        val res = graph("PATCH", url, jsonBody(payload))
        when (res.code) {
            404 -> MirrorMove.Gone
            409 -> MirrorMove.NameTaken
            else -> {
                ensureOk(res)
                MirrorMove.Moved
            }
        }
    }

    override suspend fun download(itemId: String?, path: String?, size: Long?, to: File, onProgress: ((Long) -> Unit)?): Boolean = guard {
        val candidates = ArrayList<HttpUrl>()
        if (!itemId.isNullOrEmpty()) candidates.add(byId(itemId, "/content"))
        if (path != null && MirrorPaths.isMirrorPath(path)) candidates.add(item(path, "content"))
        for (url in candidates) {
            if (fetchContent(url, to, onProgress)) return@guard true
        }
        if (path == null || !MirrorPaths.isMirrorPath(path) || MirrorPaths.isReviewPath(path)) return@guard false
        val name = path.substringAfterLast('/')
        val inReview = MirrorPaths.fallbackFolders(path).flatMap { listFolder(it).orEmpty() }
        val matches = inReview
            .filter { !it.isFolder && MirrorPaths.isReviewNameOf(it.name, name) && (size == null || it.size == size) }
            .sortedBy { it.name.length }
        for (entry in matches) {
            if (fetchContent(byId(entry.id, "/content"), to, onProgress)) {
                log.info("mirror.found_in_review")
                return@guard true
            }
        }
        false
    }

    private suspend fun fetchContent(url: HttpUrl, to: File, onProgress: ((Long) -> Unit)?): Boolean {
        val res = graph("GET", url, followRedirects = false)
        if (res.code == 404) return false
        if (res.code == 302 || res.code == 303 || res.code == 301 || res.code == 307) {
            val location = res.headers["Location"] ?: throw OneDriveException(OneDriveErrors.ERROR)
            fetchTo(url.resolve(location) ?: throw OneDriveException(OneDriveErrors.ERROR), to, onProgress)
            return true
        }
        ensureOk(res)
        withContext(Dispatchers.IO) {
            to.parentFile?.mkdirs()
            to.writeBytes(res.body)
        }
        onProgress?.invoke(res.body.size.toLong())
        return true
    }

    private suspend fun fetchTo(url: HttpUrl, to: File, onProgress: ((Long) -> Unit)?) {
        var attempt = 0
        while (true) {
            val have = if (to.isFile) to.length() else 0L
            val request = Request.Builder().url(url).get().apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    noRedirects.newCall(request).execute().use { response ->
                        val code = response.code
                        if (code != 200 && code != 206) return@use code
                        to.parentFile?.mkdirs()
                        val append = code == 206
                        var received = if (append) have else 0L
                        FileOutputStream(to, append).use { out ->
                            val input = response.body.byteStream()
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                received += read
                                onProgress?.invoke(received)
                            }
                            out.fd.sync()
                        }
                        0
                    }
                }
                if (outcome == 0) return
                if (outcome == 416 && have > 0) return
                if (!transient(outcome) || ++attempt >= maxAttempts) throw OneDriveException(OneDriveErrors.ERROR)
                sleeper(OneDriveAuth.backoff(attempt, null))
            } catch (e: IOException) {
                if (e is FileNotFoundException || ++attempt >= maxAttempts) throw e
                sleeper(OneDriveAuth.backoff(attempt, null))
            }
        }
    }

    private suspend fun createSession(name: String): String {
        val payload = buildJsonObject { putJsonObject("item") { put(CONFLICT, "replace") } }
        val res = ensureOk(graph("POST", item(name, "createUploadSession"), jsonBody(payload)))
        return JsonText.stringOf(res.json["uploadUrl"]) ?: throw OneDriveException(OneDriveErrors.ERROR)
    }

    private fun nextOffset(res: GraphResponse): Long? {
        val ranges = res.json["nextExpectedRanges"] as? JsonArray ?: return null
        val first = ranges.firstOrNull()?.let { JsonText.stringOf(it) } ?: return null
        return first.substringBefore('-').toLongOrNull()
    }

    private suspend fun uploadInChunks(file: File, name: String, size: Long): GraphResponse {
        var url = createSession(name)
        var sessions = 1
        var offset = 0L
        var failures = 0
        withContext(Dispatchers.IO) { RandomAccessFile(file, "r") }.use { raf ->
            while (offset < size) {
                val end = minOf(offset + chunkSize, size)
                val bytes = withContext(Dispatchers.IO) {
                    ByteArray((end - offset).toInt()).also {
                        raf.seek(offset)
                        raf.readFully(it)
                    }
                }
                val request = Request.Builder()
                    .url(url)
                    .header("Content-Range", "bytes $offset-${end - 1}/$size")
                    .put(bytes.toRequestBody(null))
                    .build()
                val res = try {
                    send(http, request)
                } catch (e: IOException) {
                    if (e is FileNotFoundException) throw e
                    null
                }
                val status = res?.code
                if (res != null && (res.code == 200 || res.code == 201)) return res
                if (res != null && res.code == 202) {
                    offset = nextOffset(res) ?: end
                    failures = 0
                    continue
                }
                if (status == 404 && sessions < MAX_SESSIONS) {
                    url = createSession(name)
                    sessions++
                    offset = 0
                    continue
                }
                if (res != null && !transient(res.code) && res.code != 416) {
                    cancel(url)
                    ensureOk(res)
                }
                if (++failures >= maxAttempts) {
                    cancel(url)
                    if (res == null) throw OneDriveException(OneDriveErrors.OFFLINE)
                    ensureOk(res)
                    throw OneDriveException(OneDriveErrors.ERROR)
                }
                log.warn("autobackup.onedrive_chunk_retry", fields = mapOf("status" to status, "attempt" to failures))
                sleeper(OneDriveAuth.backoff(failures, res?.headers?.get("Retry-After")))
                offset = sessionOffset(url) ?: offset
            }
        }
        throw OneDriveException(OneDriveErrors.ERROR)
    }

    private suspend fun sessionOffset(url: String): Long? = try {
        val res = send(http, Request.Builder().url(url).get().build())
        if (res.code == 200) nextOffset(res) else null
    } catch (_: IOException) {
        null
    }

    private suspend fun cancel(url: String) {
        runCatching { send(http, Request.Builder().url(url).delete().build()) }
    }

    override suspend fun list(): List<StoredBackup> = guard {
        val out = ArrayList<StoredBackup>()
        var next: HttpUrl? = withQuery("$base/me/drive/special/approot/children", mapOf(SELECT to "id,name,file,size", TOP to "200"))
        while (next != null) {
            val json = ensureOk(graph("GET", next)).json
            for (entry in (json["value"] as? JsonArray).orEmpty()) {
                if (entry !is JsonObject || JsonText.isNull(entry["file"])) continue
                val id = JsonText.stringOf(entry["id"]) ?: continue
                val name = JsonText.stringOf(entry["name"]) ?: continue
                out.add(StoredBackup(id, name, sizeOf(entry["size"])))
            }
            next = JsonText.stringOf(json[NEXT_LINK])?.toHttpUrl()
        }
        out
    }

    override suspend fun delete(backup: StoredBackup) {
        check(BackupNames.isAutomatic(backup.name)) { "Not an automatic backup" }
        guard {
            val res = graph("DELETE", byId(backup.id))
            if (res.code != 404) ensureOk(res)
        }
    }

    suspend fun accountName(): String? = guard {
        val json = ensureOk(graph("GET", withQuery("$base/me", mapOf(SELECT to "displayName,mail,userPrincipalName")))).json
        listOf("mail", "userPrincipalName", "displayName").firstNotNullOfOrNull { key ->
            JsonText.stringOf(json[key])?.takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun graph(method: String, url: HttpUrl, body: RequestBody? = null, followRedirects: Boolean = true): GraphResponse {
        var refreshed = false
        var attempt = 0
        val client = if (followRedirects) http else noRedirects
        while (true) {
            val token = auth.accessToken(refresh = refreshed)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(null) else null)
                .build()
            val res = try {
                send(client, request)
            } catch (e: IOException) {
                if (e is FileNotFoundException || ++attempt >= maxAttempts) throw e
                sleeper(OneDriveAuth.backoff(attempt, null))
                continue
            }
            if (res.code == 401) {
                if (refreshed) auth.needsReconnect()
                refreshed = true
                continue
            }
            if (transient(res.code) && ++attempt < maxAttempts) {
                sleeper(OneDriveAuth.backoff(attempt, res.headers["Retry-After"]))
                continue
            }
            return res
        }
    }

    private suspend fun send(client: OkHttpClient, request: Request): GraphResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response -> GraphResponse(response.code, response.headers, response.body.bytes()) }
    }

    private fun ensureOk(res: GraphResponse): GraphResponse {
        if (res.code in 200..299) return res
        val code = JsonText.stringOf((res.json["error"] as? JsonObject)?.get("code"))
        log.warn("autobackup.onedrive_refused", fields = mapOf("status" to res.code, "code" to code))
        throw OneDriveException(
            when {
                res.code == 403 -> OneDriveErrors.DENIED
                res.code == 507 -> OneDriveErrors.FULL
                code == "quotaLimitReached" -> OneDriveErrors.FULL
                else -> OneDriveErrors.ERROR
            },
        )
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: OneDriveException) {
        throw e
    } catch (_: FileNotFoundException) {
        throw LocalFileGone()
    } catch (e: IOException) {
        log.warn("autobackup.onedrive_offline", e)
        throw OneDriveException(OneDriveErrors.OFFLINE)
    }

    private fun jsonBody(json: JsonObject): RequestBody = JsonText.encode(json).toByteArray(Charsets.UTF_8).toRequestBody(JSON)

    companion object {
        const val SIMPLE_UPLOAD_MAX = 4L * 1024 * 1024
        const val CHUNK_UNIT = 320 * 1024
        const val DEFAULT_CHUNK = 10 * CHUNK_UNIT
        const val BATCH_SIZE = 20
        const val MAX_SESSIONS = 3
        private const val BUFFER = 64 * 1024
        private const val CONFLICT = "@microsoft.graph.conflictBehavior"
        private const val SELECT = "\$select"
        private const val TOP = "\$top"
        private const val NEXT_LINK = "@odata.nextLink"
        private const val ZIP = "application/zip"
        private val JSON = "application/json".toMediaType()

        fun transient(status: Int): Boolean = status == 429 || status == 500 || status == 502 || status == 503 || status == 504

        fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeComponent(it) }

        fun encodeComponent(text: String): String {
            val out = StringBuilder()
            for (b in text.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED)) {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
                }
            }
            return out.toString()
        }

        fun remoteItem(json: JsonObject): RemoteItem? {
            val id = JsonText.stringOf(json["id"]) ?: return null
            val size = sizeOf(json["size"]) ?: return null
            val hashes = (json["file"] as? JsonObject)?.get("hashes") as? JsonObject
            return RemoteItem(
                id = id,
                name = JsonText.stringOf(json["name"]),
                size = size,
                quickXorHash = JsonText.stringOf(hashes?.get("quickXorHash")),
            )
        }

        private fun sizeOf(element: JsonElement?): Long? =
            JsonText.intOf(element) ?: JsonText.numberOf(element)?.toLong() ?: (element as? JsonPrimitive)?.content?.toLongOrNull()

        private const val UNRESERVED = "-_.!~*'()"
        private const val HEX = "0123456789ABCDEF"
    }
}
