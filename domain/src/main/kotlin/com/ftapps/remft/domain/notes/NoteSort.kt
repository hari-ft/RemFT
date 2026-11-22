package com.ftapps.remft.domain.notes

import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.time.UtcText

enum class NotesFilter { All, Pinned, Archived }

enum class NoteSortField(val id: String, val descendingByDefault: Boolean) {
    Updated("updated", true), Created("created", true), Title("title", false);

    companion object {
        fun of(id: String?): NoteSortField? = entries.firstOrNull { it.id == id }
    }
}

data class NoteSortState(val field: NoteSortField = NoteSortField.Updated, val descending: Boolean = true) {
    fun encode(): String = "${field.id}:${if (descending) "desc" else "asc"}"

    fun select(next: NoteSortField): NoteSortState =
        if (next == field) this else NoteSortState(next, next.descendingByDefault)

    fun reversed(): NoteSortState = copy(descending = !descending)

    companion object {
        fun parse(raw: String?): NoteSortState {
            val parts = raw.orEmpty().split(':')
            val known = NoteSortField.of(parts.first())
            val field = known ?: NoteSortField.Updated
            if (known == null || parts.size < 2 || (parts[1] != "asc" && parts[1] != "desc")) {
                return NoteSortState(field, field.descendingByDefault)
            }
            return NoteSortState(field, parts[1] == "desc")
        }
    }
}

data class NoteSortKey(
    val id: String,
    val title: String,
    val isPinned: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)

data class NoteSearchRow(
    val key: NoteSortKey,
    val isArchived: Boolean,
    val titleFold: String?,
    val bodyFold: String?,
    val plainText: String?,
)

object NoteListing {
    private fun instant(key: NoteSortKey, field: NoteSortField): Long {
        val text = if (field == NoteSortField.Created) key.createdAt else key.updatedAt ?: key.createdAt
        return UtcText.parseMillis(text) ?: 0L
    }

    fun comparator(sort: NoteSortState): Comparator<NoteSortKey> {
        val ascending = Comparator<NoteSortKey> { a, b ->
            when (sort.field) {
                NoteSortField.Title -> Fold.searchText(a.title).compareTo(Fold.searchText(b.title))
                else -> instant(a, sort.field).compareTo(instant(b, sort.field))
            }
        }
        val ordered = if (sort.descending) ascending.reversed() else ascending
        return Comparator { a, b ->
            if (a.isPinned != b.isPinned) return@Comparator if (a.isPinned) -1 else 1
            val result = ordered.compare(a, b)
            if (result != 0) result else b.id.compareTo(a.id)
        }
    }

    fun words(search: String): List<String> =
        if (search.isBlank()) emptyList() else Fold.searchText(search).split(' ').filter { it.isNotEmpty() }

    fun matches(row: NoteSearchRow, filter: NotesFilter, words: List<String>): Boolean {
        when (filter) {
            NotesFilter.Archived -> if (!row.isArchived) return false
            NotesFilter.Pinned -> if (!row.key.isPinned || row.isArchived) return false
            NotesFilter.All -> if (row.isArchived) return false
        }
        if (words.isEmpty()) return true
        val haystack = "${row.titleFold ?: Fold.searchText(row.key.title)} ${row.bodyFold ?: Fold.searchText(row.plainText.orEmpty())}"
        return words.all { haystack.contains(it) }
    }

    fun tier(row: NoteSearchRow, words: List<String>): Int {
        if (words.isEmpty()) return 0
        val title = row.titleFold ?: Fold.searchText(row.key.title)
        return if (words.all { title.contains(it) }) 0 else 2
    }

    fun <T> arrange(rows: List<T>, filter: NotesFilter, search: String, sort: NoteSortState, row: (T) -> NoteSearchRow): List<T> {
        val words = words(search)
        val order = comparator(sort)
        return rows
            .map { it to row(it) }
            .filter { matches(it.second, filter, words) }
            .sortedWith { a, b ->
                val tier = tier(a.second, words).compareTo(tier(b.second, words))
                if (tier != 0) tier else order.compare(a.second.key, b.second.key)
            }
            .map { it.first }
    }
}
