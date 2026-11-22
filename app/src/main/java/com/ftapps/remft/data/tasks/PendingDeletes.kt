package com.ftapps.remft.data.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PendingDeletes(
    private val scope: CoroutineScope,
    val window: Long = UNDO_WINDOW_MS,
    private val backstop: Long = BACKSTOP_MS,
) {
    private val timers = mutableMapOf<String, Job>()
    private val commits = mutableMapOf<String, suspend () -> Unit>()
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val ids: StateFlow<Set<String>> = hidden.asStateFlow()

    @Synchronized
    fun schedule(id: String, commit: suspend () -> Unit) {
        timers.remove(id)?.cancel()
        commits[id] = commit
        timers[id] = scope.launch {
            delay(backstop)
            commitNow(id)
        }
        hidden.update { it + id }
    }

    @Synchronized
    fun undo(id: String): Boolean {
        val cancelled = commits.remove(id) != null
        timers.remove(id)?.cancel()
        hidden.update { it - id }
        return cancelled
    }

    fun commitNow(id: String) {
        val commit = synchronized(this) {
            timers.remove(id)?.cancel()
            commits.remove(id)
        } ?: return
        scope.launch {
            runCatching { commit() }.onFailure { hidden.update { set -> set - id } }
        }
    }

    fun commitAll() {
        val pending = synchronized(this) { commits.keys.toList() }
        pending.forEach(::commitNow)
    }

    fun isPending(id: String): Boolean = id in hidden.value

    companion object {
        const val UNDO_WINDOW_MS = 3_000L
        const val BACKSTOP_MS = 10_000L

        fun <T> without(items: List<T>, pending: Set<String>, idOf: (T) -> String): List<T> =
            if (pending.isEmpty()) items else items.filterNot { idOf(it) in pending }
    }
}
