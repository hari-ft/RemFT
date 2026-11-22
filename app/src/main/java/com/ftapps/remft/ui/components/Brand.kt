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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OrbitMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    ring: Color = MaterialTheme.colorScheme.primary,
    dot: Color = Brand.Marigold,
) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 72f
        val stroke = 10f * unit
        val radius = 22f * unit
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawArc(
            color = ring,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val angle = Math.toRadians(-45.0)
        drawCircle(
            color = dot,
            radius = 7f * unit,
            center = Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat()),
        )
    }
}

@Composable
fun NameMark(modifier: Modifier = Modifier) {
    Row(modifier.testTag("name-mark"), verticalAlignment = Alignment.CenterVertically) {
        OrbitMark(size = 36.dp)
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
