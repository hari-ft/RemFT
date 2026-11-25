package com.ftapps.remft

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.share.FakeShareProvider
import com.ftapps.remft.ui.share.ShareInbox
import com.ftapps.remft.ui.share.ShareReceiverActivity
import com.ftapps.remft.ui.share.ShareState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h1800dp")
class MainActivityShareTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject
    lateinit var startup: AppStartup

    @Inject
    lateinit var alarmStartup: AlarmStartup

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    lateinit var inbox: ShareInbox

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var provider: FakeShareProvider

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        hilt.inject()
        startup.start()
        alarmStartup.start(observeProcess = false)
        runBlocking { settings.set(SettingKeys.OnboardingSeen.name, "true") }
        provider = FakeShareProvider.install()
        provider.entries["a"] = FakeShareProvider.Entry("Beach.png", "image/png", "pixels".toByteArray())
        provider.entries["b"] = FakeShareProvider.Entry("Forest.png", "image/png", "trees".toByteArray())
    }

    @After
    fun tearDown() {
        inbox.dismiss()
        controller?.let { runCatching { it.pause().stop().destroy() } }
    }

    private fun galleryShare(key: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, FakeShareProvider.uri(key))
        .addFlags(GALLERY_FLAGS)

    private fun shareIntent(key: String = "a"): Intent = ShareReceiverActivity.forwardIntent(context, galleryShare(key))!!

    private fun awaitHome() {
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("screen-home")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSheetWith(name: String) {
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("sharein-sheet")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { compose.onAllNodes(hasText(name), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aColdStartFromTheShareSheetShowsTheSaveSheet() {
        controller = Robolectric.buildActivity(MainActivity::class.java, shareIntent()).setup()
        awaitSheetWith("Beach.png")
        assertEquals("Beach.png", (inbox.state.value as ShareState.Ready).intake.items.single().name)
    }

    @Test
    fun everyShareWhileTheAppIsOpenArrivesInTheSameActivity() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).setup()
        val activity = controller!!.get()
        awaitHome()

        controller!!.newIntent(shareIntent("a"))
        awaitSheetWith("Beach.png")
        controller!!.newIntent(shareIntent("b"))
        awaitSheetWith("Forest.png")

        assertSame(activity, controller!!.get())
        assertFalse(activity.isFinishing)
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertTrue(started.none { it.component?.packageName == context.packageName })
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { (inbox.state.value as? ShareState.Ready)?.intake?.items?.singleOrNull()?.name == "Forest.png" }
    }

    @Test
    fun reopeningTheShareTaskFromRecentsDoesNotReplayTheShare() {
        val fromRecents = shareIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        controller = Robolectric.buildActivity(MainActivity::class.java, fromRecents).setup()
        awaitHome()
        compose.waitForIdle()
        assertNull(inbox.state.value)
        assertEquals(0, compose.onAllNodes(hasTestTag("sharein-sheet")).fetchSemanticsNodes().size)
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val GALLERY_FLAGS = 0x1b080101
    }
}
