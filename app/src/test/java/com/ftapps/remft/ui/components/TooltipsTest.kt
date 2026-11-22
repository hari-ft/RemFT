package com.ftapps.remft.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TooltipsTest {
    @get:Rule
    val compose = createComposeRule()

    private val clicks = mutableMapOf<String, Int>()

    private fun show() {
        compose.setContent {
            RemftTheme {
                Column {
                    RemftIconButton(Icons.Outlined.Delete, "Delete note", onClick = { clicks.merge("plain", 1, Int::plus) }, modifier = Modifier.testTag("plain"))
                    RoundIconButton(Icons.Outlined.Search, "Search everywhere", onClick = { clicks.merge("round", 1, Int::plus) }, modifier = Modifier.testTag("round"))
                    SquareFab(Icons.Filled.Add, "New task", onClick = { clicks.merge("fab", 1, Int::plus) }, modifier = Modifier.testTag("fab"))
                }
            }
        }
    }

    private fun textCount(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun descriptionCount(text: String) = compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().size

    private fun longPressShowsName(tag: String, name: String) {
        assertEquals(0, textCount(name))
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.waitUntil(5_000) { textCount(name) == 1 }
        assertEquals(1, descriptionCount(name))
        assertEquals(null, clicks[tag])
    }

    @Test
    fun longPressOnAnIconButtonShowsItsNameWithoutClicking() {
        show()
        longPressShowsName("plain", "Delete note")
    }

    @Test
    fun longPressOnARoundButtonShowsItsName() {
        show()
        longPressShowsName("round", "Search everywhere")
    }

    @Test
    fun longPressOnTheFabShowsItsName() {
        show()
        longPressShowsName("fab", "New task")
    }

    @Test
    fun aTapStillClicksAndShowsNoTooltip() {
        show()
        compose.onNodeWithTag("plain").performClick()
        compose.onNodeWithTag("round").performClick()
        compose.onNodeWithTag("fab").performClick()
        compose.waitForIdle()
        assertEquals(mapOf("plain" to 1, "round" to 1, "fab" to 1), clicks.toMap())
        assertEquals(0, textCount("Delete note"))
        assertEquals(1, descriptionCount("Delete note"))
    }
}
