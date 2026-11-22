package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.Fold

data class SortChoice(val id: String, val descendingByDefault: Boolean = true)

data class SortState(val id: String, val descending: Boolean = true) {
    fun encode(): String = "$id:${if (descending) "desc" else "asc"}"

    fun select(choice: SortChoice): SortState =
        if (choice.id == id) this else SortState(choice.id, choice.descendingByDefault)

    fun reversed(): SortState = SortState(id, !descending)

    fun <T> comparator(ascending: Comparator<T>): Comparator<T> =
        if (descending) Comparator { a, b -> ascending.compare(b, a) } else ascending

    companion object {
        fun parse(raw: String?, choices: List<SortChoice>): SortState {
            val parts = (raw ?: "").split(":")
            val known = choices.firstOrNull { it.id == parts.first() }
            val choice = known ?: choices.first()
            if (known == null || parts.size < 2 || (parts[1] != "asc" && parts[1] != "desc")) {
                return SortState(choice.id, choice.descendingByDefault)
            }
            return SortState(choice.id, parts[1] == "desc")
        }
    }
}

object TaskSort {
    val choices: List<SortChoice> = listOf(
        SortChoice("date", descendingByDefault = false),
        SortChoice("priority"),
        SortChoice("title", descendingByDefault = false),
    )

    const val DEFAULT = "date:asc"

    fun titleKey(title: String): String = Fold.searchText(title)

    fun priorityRank(priority: String?): Int = TaskPriority.rankOf(priority)
}
