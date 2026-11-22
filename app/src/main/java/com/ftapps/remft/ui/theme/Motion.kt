package com.ftapps.remft.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

object Motion {
    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 300
    const val REDUCED = 100
    val Easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

enum class MotionSpeed(val millis: Int) { Quick(Motion.SHORT), Standard(Motion.MEDIUM), Emphasised(Motion.LONG) }

@Immutable
class RemftMotion(val reduced: Boolean) {
    fun <T> spec(speed: MotionSpeed): FiniteAnimationSpec<T> =
        if (reduced) tween(Motion.REDUCED, easing = LinearEasing) else tween(speed.millis, easing = Motion.Easing)

    fun <T> quick(): FiniteAnimationSpec<T> = spec(MotionSpeed.Quick)

    fun <T> standard(): FiniteAnimationSpec<T> = spec(MotionSpeed.Standard)

    fun <T> emphasised(): FiniteAnimationSpec<T> = spec(MotionSpeed.Emphasised)

    fun enter(speed: MotionSpeed = MotionSpeed.Standard): EnterTransition =
        if (reduced) fadeIn(spec(speed)) else fadeIn(spec(speed)) + expandVertically(spec(speed))

    fun exit(speed: MotionSpeed = MotionSpeed.Standard): ExitTransition =
        if (reduced) fadeOut(spec(speed)) else fadeOut(spec(speed)) + shrinkVertically(spec(speed))

    fun fadeEnter(speed: MotionSpeed = MotionSpeed.Standard): EnterTransition = fadeIn(spec(speed))

    fun fadeExit(speed: MotionSpeed = MotionSpeed.Quick): ExitTransition = fadeOut(spec(speed))
}

val LocalRemftMotion = staticCompositionLocalOf { RemftMotion(reduced = false) }
