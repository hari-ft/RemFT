package com.ftapps.remft.data.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class OneDriveAuthTest {
    private val server = MockWebServer()
    private lateinit var rig: OneDriveRig
    private lateinit var auth: OneDriveAuth

    @Before
    fun setUp() {
        server.start()
        rig = OneDriveRig()
        auth = rig.auth(server.url("/token").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        rig.close()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private suspend fun expectCode(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: OneDriveException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun signedOutWithoutAToken() = runBlocking {
        assertFalse(auth.isConnected())
        expectCode(OneDriveErrors.SIGNED_OUT) { auth.accessToken() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun connectStoresTheRefreshTokenEncryptedAndClearsReconnect() = runBlocking {
        rig.store.set(SettingKeys.OneDriveReconnect.name, "true")
        auth.connect(OneDriveGrant("A1", "R1", rig.now.plus(Duration.ofHours(1))))
        assertEquals("R1", rig.storedToken())
        assertTrue(rig.slot.value != "R1")
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertTrue(auth.isConnected())
        assertEquals("A1", auth.accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theAccessTokenIsCachedUntilTwoMinutesBeforeItExpires() = runBlocking {
        auth.connect(OneDriveGrant("A1", "R1", rig.now.plus(Duration.ofMinutes(10))))
        rig.now = rig.now.plus(Duration.ofMinutes(7))
        assertEquals("A1", auth.accessToken())
        assertEquals(0, server.requestCount)
        rig.now = rig.now.plus(Duration.ofMinutes(1)).plusSeconds(1)
        server.enqueue(json(200, """{"access_token":"A2","refresh_token":"R2","expires_in":600}"""))
        assertEquals("A2", auth.accessToken())
        assertEquals(1, server.requestCount)
        assertEquals("R2", rig.storedToken())
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("client_id=${OneDriveConfig.CLIENT_ID}"))
        assertTrue(form.contains("refresh_token=R1"))
        assertTrue(form.contains("scope=Files.ReadWrite.AppFolder"))
    }

    @Test
    fun aRefreshWithoutANewRefreshTokenKeepsTheOldOne() = runBlocking {
        rig.tokens.write("R1")
        server.enqueue(json(200, """{"access_token":"A2","expires_in":3600}"""))
        assertEquals("A2", auth.accessToken())
        assertEquals("R1", rig.storedToken())
        assertEquals("A2", auth.accessToken())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun transientFailuresAreRetriedWithRetryAfter() = runBlocking {
        rig.tokens.write("R1")
        server.enqueue(json(503, "{}").setHeader("Retry-After", "120"))
        server.enqueue(json(429, "{}"))
        server.enqueue(json(200, """{"access_token":"A2","expires_in":3600}"""))
        assertEquals("A2", auth.accessToken())
        assertEquals(listOf(Duration.ofSeconds(60), Duration.ofSeconds(4)), rig.sleeps)
    }

    @Test
    fun transientFailuresGiveUpAfterThreeTries() = runBlocking {
        rig.tokens.write("R1")
        repeat(3) { server.enqueue(json(500, "{}")) }
        expectCode(OneDriveErrors.ERROR) { auth.accessToken() }
        assertEquals(3, server.requestCount)
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
    }

    @Test
    fun invalidGrantFlagsReconnectWhileTheRefusedTokenIsStillStored() = runBlocking {
        rig.tokens.write("R1")
        server.enqueue(json(400, """{"error":"invalid_grant"}"""))
        expectCode(OneDriveErrors.RECONNECT) { auth.accessToken() }
        assertEquals("true", rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertEquals("R1", rig.storedToken())
    }

    @Test
    fun interactionRequiredAlsoFlagsReconnect() = runBlocking {
        rig.tokens.write("R1")
        server.enqueue(json(401, """{"error":"interaction_required"}"""))
        expectCode(OneDriveErrors.RECONNECT) { auth.accessToken() }
        assertEquals("true", rig.store.get(SettingKeys.OneDriveReconnect.name))
    }

    @Test
    fun aRefusalOfAReplacedTokenDoesNotFlagReconnect() = runBlocking {
        rig.tokens.write("R1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                rig.tokens.write("R9")
                return json(400, """{"error":"invalid_grant"}""")
            }
        }
        expectCode(OneDriveErrors.RECONNECT) { auth.accessToken() }
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertEquals("R9", rig.storedToken())
    }

    @Test
    fun aRefusalAfterDisconnectIsSignedOut() = runBlocking {
        rig.tokens.write("R1")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                rig.slot.clear()
                return json(400, """{"error":"invalid_grant"}""")
            }
        }
        expectCode(OneDriveErrors.SIGNED_OUT) { auth.accessToken() }
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
    }

    @Test
    fun otherRefusalsAreErrorsNotReconnects() = runBlocking {
        rig.tokens.write("R1")
        server.enqueue(json(400, """{"error":"invalid_client"}"""))
        expectCode(OneDriveErrors.ERROR) { auth.accessToken() }
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
    }

    @Test
    fun anUnreadableTokenAsksForReconnectAndNeverSignsOut() = runBlocking {
        rig.tokens.write("R1")
        rig.cipher.broken = true
        assertTrue(auth.isConnected())
        expectCode(OneDriveErrors.RECONNECT) { auth.accessToken() }
        assertEquals("true", rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertTrue(rig.slot.value != null)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun offlineAfterRetriesIsOffline() = runBlocking {
        rig.tokens.write("R1")
        val dead = rig.auth("http://127.0.0.1:1/token")
        expectCode(OneDriveErrors.OFFLINE) { dead.accessToken() }
        assertEquals(2, rig.sleeps.size)
    }

    @Test
    fun disconnectForgetsTheTokenAndTheKey() = runBlocking {
        auth.connect(OneDriveGrant("A1", "R1", rig.now.plus(Duration.ofHours(1))))
        rig.store.set(SettingKeys.OneDriveReconnect.name, "true")
        auth.disconnect()
        assertNull(rig.slot.value)
        assertEquals(1, rig.cipher.forgotten)
        assertNull(rig.store.get(SettingKeys.OneDriveReconnect.name))
        assertFalse(auth.isConnected())
        expectCode(OneDriveErrors.SIGNED_OUT) { auth.accessToken() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectUriUsesTheUrlEncodedSignatureHash() {
        assertEquals(
            "msauth://com.ftapps.remft/oO1NXGPKCxPLZtYrmG6TFOENHGI%3D",
            OneDriveConfig.redirectUri("com.ftapps.remft", "oO1NXGPKCxPLZtYrmG6TFOENHGI="),
        )
        assertEquals(
            "msauth://com.ftapps.remft/1voKiGFm5vIWyJNpUr5qKujCQDE%3D",
            OneDriveConfig.redirectUri("com.ftapps.remft", "1voKiGFm5vIWyJNpUr5qKujCQDE="),
        )
    }
}
