package com.ftapps.remft.ui.tasks

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ftapps.remft.MainActivity
import com.ftapps.remft.app.AppStartup
import com.ftapps.remft.app.StartupState
import com.ftapps.remft.data.alarm.AlarmStartup
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.domain.tasks.TaskDraft
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "w411dp-h1800dp")
class UndoRecreateTest {
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
    lateinit var env: TasksEnvironment

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
        runBlocking { settings.set(SettingKeys.OnboardingSeen.name, "true") }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForText(text: String, count: Int = 1) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size == count
    }

    private fun awaitHome() {
        compose.waitUntil(TIMEOUT_MS) { startup.state.value is StartupState.Ready }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag("screen-home")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun rotatingTheRealActivityDuringTheUndoWindowKeepsAWorkingUndo() {
        compose.waitUntil(TIMEOUT_MS) { startup.state.value is StartupState.Ready }
        val id = runBlocking { env.repository.create(TaskDraft(title = "Old errand")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitHome()
        waitForText("Old errand")
        compose.onNodeWithTag("swipe-$id").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-delete-$id").performClick()
        waitForText("Undo")

        scenario!!.recreate()

        awaitHome()
        waitForText("Undo")
        assertNotNull(runBlocking { env.repository.detail(id) })
        compose.onNodeWithText("Undo").performClick()
        waitForText("Old errand")
        assertNotNull(runBlocking { env.repository.detail(id) })
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
