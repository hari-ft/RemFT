package com.ftapps.remft.domain.notes.mermaid

import kotlin.math.sqrt

data class DiagramPoint(val x: Double, val y: Double) {
    operator fun plus(other: DiagramPoint): DiagramPoint = DiagramPoint(x + other.x, y + other.y)
    operator fun minus(other: DiagramPoint): DiagramPoint = DiagramPoint(x - other.x, y - other.y)
    operator fun times(factor: Double): DiagramPoint = DiagramPoint(x * factor, y * factor)
    operator fun div(factor: Double): DiagramPoint = DiagramPoint(x / factor, y / factor)
    val distance: Double get() = sqrt(x * x + y * y)

    companion object {
        val Zero = DiagramPoint(0.0, 0.0)
    }
}

data class DiagramSize(val width: Double, val height: Double) {
    companion object {
        val Zero = DiagramSize(0.0, 0.0)
    }
}

data class DiagramRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val center: DiagramPoint get() = DiagramPoint((left + right) / 2, (top + bottom) / 2)
    val size: DiagramSize get() = DiagramSize(width, height)

    fun overlaps(other: DiagramRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        val Zero = DiagramRect(0.0, 0.0, 0.0, 0.0)

        fun fromLTWH(left: Double, top: Double, width: Double, height: Double): DiagramRect =
            DiagramRect(left, top, left + width, top + height)

        fun fromCenter(center: DiagramPoint, width: Double, height: Double): DiagramRect =
            DiagramRect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2)
    }
}

fun interface MermaidMeasure {
    fun measure(label: String): DiagramSize
}
