package com.ftapps.remft.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun markedCorner(tight: Dp, other: Dp): CornerBasedShape =
    RoundedCornerShape(topStart = tight, topEnd = other, bottomEnd = other, bottomStart = other)

enum class GroupPosition { Single, First, Middle, Last }

fun groupPosition(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.Single
    index == 0 -> GroupPosition.First
    index == count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

object MarkedShapes {
    val small: CornerBasedShape = markedCorner(Radius.tightSmall, Radius.small)
    val medium: CornerBasedShape = markedCorner(Radius.tightMedium, Radius.card)
    val large: CornerBasedShape = markedCorner(Radius.tightLarge, Radius.sheet)
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = Radius.tightLarge, topEnd = Radius.sheet)
    val pill: Shape = CircleShape
    val fab: CornerBasedShape = RoundedCornerShape(Radius.fab)
    val tile: CornerBasedShape = RoundedCornerShape(12.dp)

    fun group(position: GroupPosition): Shape = when (position) {
        GroupPosition.Single -> medium
        GroupPosition.First -> RoundedCornerShape(topStart = Radius.tightMedium, topEnd = Radius.card)
        GroupPosition.Middle -> RectangleShape
        GroupPosition.Last -> RoundedCornerShape(bottomStart = Radius.card, bottomEnd = Radius.card)
    }
}

val RemftShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = MarkedShapes.small,
    medium = MarkedShapes.medium,
    large = MarkedShapes.large,
    extraLarge = MarkedShapes.large,
)
