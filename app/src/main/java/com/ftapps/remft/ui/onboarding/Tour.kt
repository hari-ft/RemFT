package com.ftapps.remft.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.ftapps.remft.R
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.SettingKeys
import com.ftapps.remft.ui.shell.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

enum class TourStep(val section: Section?, @param:StringRes val title: Int, @param:StringRes val body: Int) {
    Welcome(null, R.string.tour_welcomeTitle, R.string.tour_welcomeBody),
    Home(Section.Home, R.string.tour_homeTitle, R.string.tour_homeBody),
    Inbox(Section.Inbox, R.string.tour_inboxTitle, R.string.tour_inboxBody),
    Notes(Section.Notes, R.string.tour_notesTitle, R.string.tour_notesBody),
    Files(Section.Files, R.string.tour_filesTitle, R.string.tour_filesBody),
    Done(null, R.string.tour_doneTitle, R.string.tour_doneBody),
}

class TourController(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val autoStartGate: Flow<Boolean> = flowOf(true),
) {
    var running by mutableStateOf(false)
        private set
    var step by mutableIntStateOf(0)
        private set
    val anchors = mutableStateMapOf<Section, Rect>()
    private var autoAttempted = false

    val steps: List<TourStep> get() = TourStep.entries
    val current: TourStep get() = steps[step]
    val isLast: Boolean get() = step >= steps.size - 1

    suspend fun maybeAutoStart() {
        if (autoAttempted) return
        autoAttempted = true
        autoStartGate.first { it }
        if (settings.get(SettingKeys.OnboardingSeen.name) == "true") return
        start()
    }

    fun start() {
        step = 0
        running = true
    }

    fun next() {
        if (isLast) finish() else step += 1
    }

    fun skip() = finish()

    private fun finish() {
        running = false
        step = 0
        scope.launch { settings.set(SettingKeys.OnboardingSeen.name, "true") }
    }
}

val LocalTourController = staticCompositionLocalOf<TourController?> { null }

fun Modifier.tourAnchor(tour: TourController?, section: Section): Modifier =
    if (tour == null) this else onGloballyPositioned { tour.anchors[section] = it.boundsInWindow() }
