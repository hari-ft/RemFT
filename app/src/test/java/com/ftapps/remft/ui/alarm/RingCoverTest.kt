package com.ftapps.remft.ui.alarm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RingCoverTest {
    @get:Rule
    val compose = createComposeRule()

    private var covered by mutableStateOf(false)
    private var taps = 0

    private fun show() {
        compose.setContent {
            RemftTheme {
                RingCover(
                    covered = covered,
                    content = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            RemftIconButton(Icons.Outlined.Settings, "Open settings", onClick = { taps++ }, modifier = Modifier.testTag("under"))
                        }
                    },
                ) {
                    if (covered) Box(Modifier.fillMaxSize().testTag("ring"))
                }
            }
        }
    }

    @Test
    fun homeUnderTheRingIsHiddenFromAccessibilityAndTouch() {
        show()
        compose.onNodeWithTag("under").assertExists()
        covered = true
        compose.waitForIdle()
        compose.onNodeWithTag("ring").assertExists()
        compose.onNodeWithTag("under").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(0, taps)
        covered = false
        compose.waitForIdle()
        compose.onNodeWithTag("under").assertExists()
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun anOpenTooltipIsDismissedWhenTheRingCovers() {
        show()
        compose.onNodeWithTag("under").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Open settings", useUnmergedTree = true).assertExists()
        covered = true
        compose.waitForIdle()
        compose.onNodeWithText("Open settings", useUnmergedTree = true).assertDoesNotExist()
    }
}
