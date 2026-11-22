package com.ftapps.remft.ui.files

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPanAndTrailTest {
    @Test
    fun aZoomedImageCannotBePannedPastItsEdges() {
        assertEquals(Offset(400f, -600f), ViewerZoom.clampOffset(Offset(5_000f, -9_000f), 2f, 800f, 1200f))
        assertEquals(Offset(-120f, 30f), ViewerZoom.clampOffset(Offset(-120f, 30f), 2f, 800f, 1200f))
    }

    @Test
    fun atFullSizeThereIsNothingToPan() {
        assertEquals(Offset.Zero, ViewerZoom.clampOffset(Offset(50f, 50f), 1f, 800f, 1200f))
    }

    @Test
    fun theFolderTrailArrowMirrorsInRightToLeft() {
        assertTrue(CrumbArrow.autoMirror)
    }
}
