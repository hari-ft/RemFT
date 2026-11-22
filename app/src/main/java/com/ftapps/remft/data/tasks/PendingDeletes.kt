package com.ftapps.remft.data.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PendingDeletes(
    private val scope: CoroutineScope,
    val window: Long = UNDO_WINDOW_MS,
    private val settle: Long = SETTLE_MS,
) {
    private class Entry(val commit: suspend () -> Unit, val timer: Job)

    private val entries = mutableMapOf<String, Entry>()
    private val hiddenBy = mutableMapOf<Any, Set<String>>()
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val ids: StateFlow<Set<String>> = hidden.asStateFlow()

    @Synchronized
    fun schedule(id: String, hide: Set<String> = emptySet(), commit: suspend () -> Unit) {
        entries.remove(id)?.timer?.cancel()
        val timer = scope.launch {
            delay(window)
            commitNow(id)
        }
        entries[id] = Entry(commit, timer)
        hiddenBy[id] = hide + id
        publish()
    }

    @Synchronized
    fun undo(id: String): Boolean {
        val entry = entries.remove(id) ?: return false
        entry.timer.cancel()
        hiddenBy.remove(id)
        publish()
        return true
    }

    fun commitNow(id: String) {
        val token = Any()
        val commit = synchronized(this) {
            val entry = entries.remove(id) ?: return
            entry.timer.cancel()
            hiddenBy.remove(id)?.let { hiddenBy[token] = it }
            publish()
            entry.commit
        }
        scope.launch {
            try {
                if (runCatching { commit() }.isSuccess) delay(settle)
            } finally {
                synchronized(this@PendingDeletes) {
                    hiddenBy.remove(token)
                    publish()
                }
            }
        }
    }

    fun commitAll() {
        val pending = synchronized(this) { entries.keys.toList() }
        pending.forEach(::commitNow)
    }

    fun isPending(id: String): Boolean = id in hidden.value

    private fun publish() {
        hidden.value = if (hiddenBy.isEmpty()) emptySet() else hiddenBy.values.flatMapTo(mutableSetOf()) { it }
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val SETTLE_MS = 1_000L

        fun <T> without(items: List<T>, pending: Set<String>, idOf: (T) -> String): List<T> =
            if (pending.isEmpty()) items else items.filterNot { idOf(it) in pending }
    }
}
