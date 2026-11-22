package com.ftapps.remft.ui.tasks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.ui.theme.RemftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h800dp")
class SwipeToRevealTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(direction: LayoutDirection) {
        compose.setContent {
            RemftTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.width(320.dp)) {
                        SwipeToReveal(id = "a", deleteDescription = "Delete a", onDelete = {}) {
                            Box(Modifier.fillMaxWidth().height(56.dp))
                        }
                    }
                }
            }
        }
    }

    private fun rowLeft() = compose.onNodeWithTag("swipe-a").getBoundsInRoot().left

    @Test
    fun leftToRightTheRowFollowsALeftSwipeAndShowsDeleteOnTheRight() {
        show(LayoutDirection.Ltr)
        val start = rowLeft()
        compose.onNodeWithTag("swipe-a").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(start, rowLeft())
        compose.onNodeWithTag("swipe-a").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("swipe-a").getBoundsInRoot()
        val strip = compose.onNodeWithTag("swipe-delete-a").getBoundsInRoot()
        assertEquals(start - 96.dp, row.left)
        assertTrue("strip ${strip.left} row ${row.right}", strip.left >= row.right)
    }

    @Test
    fun rightToLeftTheRowFollowsARightSwipeAndShowsDeleteOnTheLeft() {
        show(LayoutDirection.Rtl)
        val start = rowLeft()
        compose.onNodeWithTag("swipe-a").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(start, rowLeft())
        compose.onNodeWithTag("swipe-a").performTouchInput { swipeRight() }
        compose.waitForIdle()
        val row = compose.onNodeWithTag("swipe-a").getBoundsInRoot()
        val strip = compose.onNodeWithTag("swipe-delete-a").getBoundsInRoot()
        assertEquals(start + 96.dp, row.left)
        assertTrue("strip ${strip.right} row ${row.left}", strip.right <= row.left)
    }
}
