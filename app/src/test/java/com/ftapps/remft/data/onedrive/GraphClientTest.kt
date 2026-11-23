package com.ftapps.remft.data.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.alarm.AppLog
import com.ftapps.remft.data.alarm.AppLogCategory
import com.ftapps.remft.data.alarm.AppLogLevel
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.domain.backup.BackupLog
import com.ftapps.remft.domain.backup.auto.StoredBackup
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GraphClientTest {
    private val server = MockWebServer()
    private lateinit var rig: OneDriveRig
    private lateinit var auth: OneDriveAuth

    @Before
    fun setUp() {
        server.start()
        rig = OneDriveRig()
        auth = rig.auth(server.url("/token").toString())
        runBlocking { auth.connect(OneDriveGrant("A1", "R1", rig.now.plus(Duration.ofHours(1)))) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        rig.close()
    }

    private fun client(chunkSize: Int = GraphClient.DEFAULT_CHUNK) = GraphClient(
        auth = auth,
        http = rig.http,
        baseUrl = server.url("/v1.0").toString(),
        chunkSize = chunkSize,
        sleeper = { rig.sleeps.add(it) },
        log = BackupLog.None,
    )

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("no request")

    private fun file(bytes: ByteArray): File = File(rig.root, "upload.bin").apply { writeBytes(bytes) }

    @Test
    fun smallFileIsOneAuthorisedPutThatReplaces() = runBlocking {
        server.enqueue(json(201, """{"id":"I1","name":"receipt (F1).png","size":10,"file":{"hashes":{"quickXorHash":"QX"}}}"""))
        val item = client().putFile(file(ByteArray(10) { it.toByte() }), "Files/Tasks/Pay rent/receipt (F1).png")
        assertEquals("I1", item.id)
        assertEquals(10L, item.size)
        assertEquals("QX", item.quickXorHash)
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("/v1.0/me/drive/special/approot:/Files/Tasks/Pay%20rent/receipt%20(F1).png:/content", request.requestUrl!!.encodedPath)
        assertEquals("replace", request.requestUrl!!.queryParameter("@microsoft.graph.conflictBehavior"))
        assertEquals("Bearer A1", request.getHeader("Authorization"))
        assertEquals(10L, request.bodySize)
    }

    @Test
    fun largeFileUsesAnUploadSessionResumesAndStartsOverOnA404() = runBlocking {
        val chunk = 6 * GraphClient.CHUNK_UNIT
        val size = GraphClient.SIMPLE_UPLOAD_MAX.toInt()
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val first = server.url("/upload/1").toString()
        val second = server.url("/upload/2").toString()
        server.enqueue(json(200, """{"uploadUrl":"$first"}"""))
        server.enqueue(json(202, """{"nextExpectedRanges":["$chunk-"]}"""))
        server.enqueue(json(404, """{"error":{"code":"itemNotFound"}}"""))
        server.enqueue(json(200, """{"uploadUrl":"$second"}"""))
        server.enqueue(json(202, """{"nextExpectedRanges":["$chunk-"]}"""))
        server.enqueue(json(500, "{}"))
        server.enqueue(json(200, """{"nextExpectedRanges":["${2 * chunk}-"]}"""))
        server.enqueue(json(201, """{"id":"BIG","size":$size}"""))

        client(chunk).upload(file(bytes), "remft-auto-2026-11-20-100000Z.zip")

        val session = take()
        assertEquals("POST", session.method)
        assertEquals("/v1.0/me/drive/special/approot:/remft-auto-2026-11-20-100000Z.zip:/createUploadSession", session.requestUrl!!.encodedPath)
        assertEquals("Bearer A1", session.getHeader("Authorization"))
        val c1 = take()
        assertEquals("/upload/1", c1.path)
        assertEquals("bytes 0-${chunk - 1}/$size", c1.getHeader("Content-Range"))
        assertNull(c1.getHeader("Authorization"))
        val c2 = take()
        assertEquals("bytes $chunk-${2 * chunk - 1}/$size", c2.getHeader("Content-Range"))
        assertEquals("POST", take().method)
        val c3 = take()
        assertEquals("/upload/2", c3.path)
        assertEquals("bytes 0-${chunk - 1}/$size", c3.getHeader("Content-Range"))
        val c4 = take()
        assertEquals("bytes $chunk-${2 * chunk - 1}/$size", c4.getHeader("Content-Range"))
        val status = take()
        assertEquals("GET", status.method)
        assertEquals("/upload/2", status.path)
        assertNull(status.getHeader("Authorization"))
        val c5 = take()
        assertEquals("bytes ${2 * chunk}-${size - 1}/$size", c5.getHeader("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(2 * chunk, size), c5.body.readByteArray())
        assertNull(c5.getHeader("Authorization"))
        assertEquals(8, server.requestCount)
    }

    @Test
    fun listFollowsTheNextLinkAndKeepsOnlyFiles() = runBlocking {
        val next = server.url("/v1.0/me/drive/special/approot/children?page=2").toString()
        server.enqueue(
            json(
                200,
                """{"value":[{"id":"1","name":"remft-auto-2026-11-19-020000Z.zip","size":100,"file":{}},{"id":"2","name":"Files","folder":{}}],"@odata.nextLink":"$next"}""",
            ),
        )
        server.enqueue(json(200, """{"value":[{"id":"3","name":"remft-backup-2026-11-01.zip","size":50,"file":{}}]}"""))
        val all = client().list()
        assertEquals(listOf("1", "3"), all.map { it.id })
        assertEquals(100L, all[0].size)
        val firstPage = take()
        assertEquals("/v1.0/me/drive/special/approot/children", firstPage.requestUrl!!.encodedPath)
        assertEquals("id,name,file,size", firstPage.requestUrl!!.queryParameter("\$select"))
        assertEquals("200", firstPage.requestUrl!!.queryParameter("\$top"))
        assertEquals("2", take().requestUrl!!.queryParameter("page"))
    }

    @Test
    fun downloadFollowsTheRedirectWithoutAuthAndResumesWithRange() = runBlocking {
        val to = File(rig.root, "part/zip.part").apply {
            parentFile!!.mkdirs()
            writeBytes("hello ".toByteArray())
        }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/blob/1").toString()))
        server.enqueue(MockResponse().setResponseCode(206).setBody("world"))
        val progress = ArrayList<Long>()
        assertTrue(client().download("ITEM", null, 11, to) { progress.add(it) })
        assertEquals("hello world", to.readText())
        assertEquals(11L, progress.last())
        val content = take()
        assertEquals("/v1.0/me/drive/items/ITEM/content", content.path)
        assertEquals("Bearer A1", content.getHeader("Authorization"))
        val blob = take()
        assertEquals("/blob/1", blob.path)
        assertNull(blob.getHeader("Authorization"))
        assertEquals("bytes=6-", blob.getHeader("Range"))
    }

    @Test
    fun downloadFindsACopyInItsStructuredReviewFolderWithoutCrawling() = runBlocking {
        server.enqueue(json(404, "{}"))
        server.enqueue(json(404, "{}"))
        server.enqueue(json(200, """{"value":[{"id":"R2","name":"bill (F3) (2).pdf","size":5,"file":{}},{"id":"R9","name":"other.pdf","size":5,"file":{}}]}"""))
        server.enqueue(json(404, "{}"))
        server.enqueue(json(200, """{"value":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        val to = File(rig.root, "found.part")
        assertTrue(client().download("GONE", "Files/Bills/2025/bill (F3).pdf", 5, to))
        assertEquals("hello", to.readText())
        assertEquals("/v1.0/me/drive/items/GONE/content", take().path)
        assertTrue(take().requestUrl!!.encodedPath.endsWith("/Files/Bills/2025/bill%20(F3).pdf:/content"))
        assertTrue(take().requestUrl!!.encodedPath.endsWith("/Files/reviewneeded/Bills/2025:/children"))
        assertTrue(take().requestUrl!!.encodedPath.endsWith("/Files/Recycle%20bin/Bills/2025:/children"))
        assertTrue(take().requestUrl!!.encodedPath.endsWith("/Files/reviewneeded:/children"))
        assertEquals("/v1.0/me/drive/items/R2/content", take().path)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun downloadSaysFalseWhenOneDriveHasNoCopy() = runBlocking {
        server.enqueue(json(404, "{}"))
        assertTrue(!client().download("GONE", null, null, File(rig.root, "x.part")))
    }

    @Test
    fun a401RefreshesTheTokenOnceAndStoresTheRotatedOne() = runBlocking {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":3600}"""))
        server.enqueue(json(200, """{"displayName":"Someone","mail":"someone@example.com"}"""))
        assertEquals("someone@example.com", client().accountName())
        assertEquals("Bearer A1", take().getHeader("Authorization"))
        val refresh = take()
        assertEquals("/token", refresh.path)
        val form = refresh.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=R1"))
        val again = take()
        assertEquals("Bearer A2", again.getHeader("Authorization"))
        assertEquals("displayName,mail,userPrincipalName", again.requestUrl!!.queryParameter("\$select"))
        assertEquals("R2", rig.storedToken())
    }

    @Test
    fun aSecond401AsksForReconnect() = runBlocking {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(200, """{"access_token":"A2","expires_in":3600}"""))
        server.enqueue(json(401, "{}"))
        try {
            client().accountName()
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.RECONNECT, e.code)
        }
        assertEquals("true", rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun throttlingWaitsForRetryAfter() = runBlocking {
        server.enqueue(json(429, "{}").setHeader("Retry-After", "7"))
        server.enqueue(json(503, "{}"))
        server.enqueue(json(200, """{"value":[]}"""))
        assertEquals(emptyList<StoredBackup>(), client().list())
        assertEquals(listOf(Duration.ofSeconds(7), Duration.ofSeconds(4)), rig.sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun throttlingGivesUpAfterFiveTries() = runBlocking {
        repeat(5) { server.enqueue(json(503, "{}")) }
        try {
            client().list()
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.ERROR, e.code)
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun insufficientStorageIsFull() = runBlocking {
        server.enqueue(json(507, """{"error":{"code":"insufficientStorage"}}"""))
        try {
            client().putFile(file(ByteArray(3)), "Files/Other/a (F1).txt")
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.FULL, e.code)
        }
    }

    @Test
    fun forbiddenIsDenied() = runBlocking {
        server.enqueue(json(403, "{}"))
        try {
            client().list()
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.DENIED, e.code)
        }
    }

    @Test
    fun deleteOnlyEverTouchesAutomaticBackups() = runBlocking {
        for (name in listOf("remft-backup-2026-11-01.zip", "notes.txt", "Files")) {
            try {
                client().delete(StoredBackup("X", name))
                fail()
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(204))
        client().delete(StoredBackup("AUTO1", "remft-auto-2026-11-01-020000Z.zip"))
        val request = take()
        assertEquals("DELETE", request.method)
        assertEquals("/v1.0/me/drive/items/AUTO1", request.path)
    }

    @Test
    fun batchCheckMapsMissingItemsToNull() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"responses":[{"id":"0","status":200,"body":{"id":"A","name":"a.png","size":4,"file":{"hashes":{"quickXorHash":"Q"}}}},{"id":"1","status":404,"body":{}}]}""",
            ),
        )
        val items = client().itemsById(listOf("A", "B"))
        assertEquals(4L, items["A"]!!.size)
        assertTrue(items.containsKey("B"))
        assertNull(items["B"])
        val request = take()
        assertEquals("/v1.0/\$batch", request.requestUrl!!.encodedPath.replace("%24", "\$"))
        assertTrue(request.body.readUtf8().contains("/me/drive/items/A?\$select=id,name,size,file,deleted"))
    }

    @Test
    fun offlineIsReportedAsOffline() = runBlocking {
        val dead = GraphClient(auth, rig.http, baseUrl = "http://127.0.0.1:1/v1.0", sleeper = {}, log = BackupLog.None)
        try {
            dead.list()
            fail()
        } catch (e: OneDriveException) {
            assertEquals(OneDriveErrors.OFFLINE, e.code)
        }
    }

    @Test
    fun oneDriveFailuresAreWarnedInTheAppLog() = runBlocking {
        val previous = AppLogs.current
        val log = AppLog(File(rig.root, AppLogs.FOLDER))
        AppLogs.install(log)
        try {
            server.enqueue(json(507, """{"error":{"code":"insufficientStorage"}}"""))
            val refused = GraphClient(auth, rig.http, baseUrl = server.url("/v1.0").toString(), sleeper = {})
            runCatching { refused.putFile(file(ByteArray(3)), "Files/Other/Secret plan (F1).txt") }
            val dead = GraphClient(auth, rig.http, baseUrl = "http://127.0.0.1:1/v1.0", sleeper = {})
            runCatching { dead.list() }

            val entries = log.read()
            val full = entries.single { it.event == "autobackup.onedrive_refused" }
            assertEquals(AppLogLevel.WARN, full.level)
            assertEquals(AppLogCategory.ONEDRIVE, full.category)
            assertEquals("507", full.fields["status"])
            assertEquals("insufficientStorage", full.fields["code"])
            val offline = entries.single { it.event == "autobackup.onedrive_offline" }
            assertEquals(AppLogLevel.WARN, offline.level)
            assertEquals(AppLogCategory.ONEDRIVE, offline.category)
            assertFalse(log.exportText().contains("Secret"))
            assertFalse(log.exportText().contains("Bearer"))
        } finally {
            AppLogs.install(previous)
        }
    }

    @Test
    fun pathsAreEncodedSegmentBySegment() {
        assertEquals("Files/Tasks/a%20b%23c/x%25y.pdf", GraphClient.encodePath("Files/Tasks/a b#c/x%y.pdf"))
        assertEquals("caf%C3%A9", GraphClient.encodeComponent("café"))
    }
}
