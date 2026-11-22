package com.ftapps.remft.ui.tasks

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.height

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w900dp-h400dp-land")
class SheetSaveButtonInsetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun applyInsets(left: Int, bottom: Int, right: Int) {
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, right, bottom))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(content, insets) }
        compose.waitForIdle()
    }

    private fun show() {
        compose.setContent {
            RemftTheme {
                Column(Modifier.fillMaxWidth().testTag("sheet")) {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("field"))
                    SheetSaveButton("Create task", saving = false, onClick = {})
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aNavigationBarOnTheSideDoesNotShortenTheButton() {
        show()
        val density = compose.activity.resources.displayMetrics.density
        applyInsets(left = 0, bottom = 0, right = (48 * density).toInt())
        val field = compose.onNodeWithTag("field").getBoundsInRoot()
        val button = compose.onNodeWithTag("task-sheet-create").getBoundsInRoot()
        assertEquals((field.right - Space.x6).value, button.right.value, 1f)
        assertEquals((field.left + Space.x6).value, button.left.value, 1f)
    }

    @Test
    fun aNavigationBarAtTheBottomStillLiftsTheButton() {
        show()
        val before = compose.onNodeWithTag("sheet").getBoundsInRoot()
        val density = compose.activity.resources.displayMetrics.density
        applyInsets(left = 0, bottom = (48 * density).toInt(), right = 0)
        val after = compose.onNodeWithTag("sheet").getBoundsInRoot()
        assertEquals((before.height + 48.dp).value, after.height.value, 1f)
    }
}
