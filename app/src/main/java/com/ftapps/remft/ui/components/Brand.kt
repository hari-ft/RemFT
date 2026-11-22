package com.ftapps.remft.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.ui.theme.Brand
import com.ftapps.remft.ui.theme.NameMarkStyle
import com.ftapps.remft.ui.theme.RemftTheme

private class BellShapes(val bell: List<Path>, val check: Path, val dot: Path)

private val bellShapes by lazy {
    fun shape(data: String) = PathParser().parsePathString(data).toPath()
    BellShapes(
        bell = listOf(
            shape("M47.5,77.5A6.5,6.5 0,1 0,60.5,77.5A6.5,6.5 0,1 0,47.5,77.5Z"),
            shape("M54,29C41.5,29 35,38.5 35,51L35,66C35,69 33,71.5 30,73L78,73C75,71.5 73,69 73,66L73,51C73,38.5 66.5,29 54,29Z"),
            shape("M29.5,71L78.5,71A3.5,3.5 0,0 1,78.5,78L29.5,78A3.5,3.5 0,0 1,29.5,71Z"),
            shape("M49.8,26.5A4.2,4.2 0,1 0,58.2,26.5A4.2,4.2 0,1 0,49.8,26.5Z"),
        ),
        check = shape("M46.62,50.88L51,55.26L61.38,44.88A3,3 0,0 1,65.62,49.12L53.12,61.62A3,3 0,0 1,48.88,61.62L42.38,55.12A3,3 0,0 1,46.62,50.88Z"),
        dot = shape("M70.5,31A6.5,6.5 0,1 0,83.5,31A6.5,6.5 0,1 0,70.5,31Z"),
    )
}

private const val BELL_FRAME_LEFT = 24f
private const val BELL_FRAME_TOP = 22.5f
private const val BELL_FRAME_SIZE = 62f

@Composable
fun BellMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    bell: Color = MaterialTheme.colorScheme.primary,
    check: Color = MaterialTheme.colorScheme.onPrimary,
    dot: Color = Brand.MarigoldLight,
) {
    Canvas(modifier.size(size)) {
        val shapes = bellShapes
        val factor = this.size.minDimension / BELL_FRAME_SIZE
        withTransform({
            scale(factor, factor, pivot = Offset.Zero)
            translate(-BELL_FRAME_LEFT, -BELL_FRAME_TOP)
        }) {
            shapes.bell.forEach { drawPath(it, bell) }
            drawPath(shapes.check, check)
            drawPath(shapes.dot, dot)
        }
    }
}

@Composable
fun NameMark(modifier: Modifier = Modifier) {
    Row(modifier.testTag("name-mark"), verticalAlignment = Alignment.CenterVertically) {
        BellMark(size = 36.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.app_title),
            style = NameMarkStyle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Preview(name = "Name-mark light")
@Composable
private fun NameMarkLightPreview() {
    RemftTheme(darkTheme = false) { PreviewSurface { NameMark() } }
}

@Preview(name = "Name-mark dark")
@Composable
private fun NameMarkDarkPreview() {
    RemftTheme(darkTheme = true) { PreviewSurface { NameMark() } }
}
