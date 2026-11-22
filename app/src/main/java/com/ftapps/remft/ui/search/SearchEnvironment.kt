package com.ftapps.remft.ui.search

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import com.ftapps.remft.data.search.SearchRecentsStore
import com.ftapps.remft.data.search.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GlobalSearchRequests {
    private val state = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = state.asStateFlow()

    fun open() {
        state.value = true
    }

    fun close() {
        state.value = false
    }

    fun toggle() {
        state.update { !it }
    }
}

class SearchEnvironment(
    val repository: SearchRepository,
    val recents: SearchRecentsStore,
    val palette: GlobalSearchRequests = GlobalSearchRequests(),
)

val LocalSearchEnvironment = staticCompositionLocalOf<SearchEnvironment?> { null }

object GlobalSearchShortcut {
    fun isShortcut(keyCode: Int, ctrl: Boolean): Boolean = keyCode == KeyEvent.KEYCODE_K && ctrl

    fun handle(keyCode: Int, action: Int, ctrl: Boolean, repeatCount: Int, requests: GlobalSearchRequests): Boolean {
        if (!isShortcut(keyCode, ctrl)) return false
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) requests.toggle()
        return true
    }

    fun handle(event: KeyEvent, requests: GlobalSearchRequests): Boolean =
        handle(event.keyCode, event.action, event.isCtrlPressed, event.repeatCount, requests)
}
