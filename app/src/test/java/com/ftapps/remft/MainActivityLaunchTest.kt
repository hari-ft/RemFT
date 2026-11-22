package com.ftapps.remft

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.search.SearchEnvironment
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class MainActivityLaunchTest {
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
    lateinit var search: SearchEnvironment

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        hilt.inject()
        startup.start()
        alarmStartup.start(observeProcess = false)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun awaitShell() {
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { startup.state.value !is StartupState.Starting }
        assertTrue("startup failed: ${startup.state.value}", startup.state.value is StartupState.Ready)
        compose.waitUntil(LAUNCH_TIMEOUT_MS) {
            compose.onAllNodes(hasTestTag("screen-home")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun pressCtrlK() {
        val down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_K, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        scenario!!.onActivity { it.onKeyDown(KeyEvent.KEYCODE_K, down) }
        compose.waitForIdle()
    }

    private fun markTourSeen() = runBlocking { settings.set(SettingKeys.OnboardingSeen.name, "true") }

    @Test
    fun theRealActivityStartsAndReachesTheAppShell() {
        markTourSeen()
        launch()
        awaitShell()

        compose.onNodeWithTag("screen-home").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsDisplayed()
        scenario!!.onActivity { assertFalse(it.isFinishing) }
    }

    @Test
    fun ctrlKOpensSearchOnceTheShellIsUp() {
        markTourSeen()
        launch()
        awaitShell()

        pressCtrlK()

        assertTrue(search.palette.isOpen.value)
    }

    @Test
    fun ctrlKDoesNothingWhileTheTourIsShowing() {
        launch()
        awaitShell()
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("tour")).fetchSemanticsNodes().isNotEmpty() }

        pressCtrlK()

        assertFalse(search.palette.isOpen.value)
        compose.onNodeWithTag("tour").assertIsDisplayed()

        compose.onNodeWithTag("tour-skip").performClick()
        compose.waitUntil(LAUNCH_TIMEOUT_MS) { compose.onAllNodes(hasTestTag("tour")).fetchSemanticsNodes().isEmpty() }
        pressCtrlK()

        assertTrue(search.palette.isOpen.value)
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 20_000L
    }
}
