package com.ftapps.remft.ui.shell

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestTasks
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h300dp-land")
class ShellLandscapeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tasks = TestTasks()

    @After
    fun tearDown() {
        tasks.close()
    }

    private fun show() {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalTasksEnvironment provides tasks.environment) { AppShell() }
            }
        }
    }

    @Test
    fun aShortRailScrollsSoEverySectionCanBeReached() {
        show()
        compose.onNodeWithTag("nav-rail").assertIsDisplayed()
        compose.onNodeWithTag("nav-files").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("screen-files").assertIsDisplayed()
    }

    @Test
    fun theContentStaysClearOfANavigationBarOnTheRight() {
        show()
        compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val navBar = (48 * density).toInt()
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, navBar, 0))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(content, insets) }
        compose.waitForIdle()
        val root = compose.onRoot().getBoundsInRoot()
        val shell = compose.onNodeWithTag("shell-content").getBoundsInRoot()
        val home = compose.onNodeWithTag("screen-home").getBoundsInRoot()
        assertEquals((root.right - 48.dp).value, home.right.value, 1f)
        assertEquals(home.right.value, shell.right.value, 1f)
    }
}
