package com.ftapps.remft.domain.search

import com.ftapps.remft.domain.Fold

enum class SearchTier(val rank: Int) { Title(0), Body(2) }

data class SearchHit(val id: String, val tier: SearchTier)

data class RankedMatch<T>(val item: T, val tier: SearchTier)

object SearchMatch {
    fun queryWords(query: String): List<String> =
        Fold.searchText(query).split(' ').filter { it.isNotEmpty() }.distinct()

    fun matchesSearch(query: String, fields: Iterable<String?>): Boolean {
        val words = queryWords(query)
        if (words.isEmpty()) return true
        val haystack = fields.filter { !it.isNullOrEmpty() }.joinToString(" ") { Fold.searchText(it!!) }
        return words.all { haystack.contains(it) }
    }

    fun tier(words: List<String>, title: String?, body: String? = null): SearchTier? {
        if (words.isEmpty()) return null
        val foldedTitle = Fold.searchText(title.orEmpty())
        val foldedBody = Fold.searchText(body.orEmpty())
        if (words.all { foldedTitle.contains(it) }) return SearchTier.Title
        val everyWordSomewhere = words.all { foldedTitle.contains(it) || foldedBody.contains(it) }
        return if (everyWordSomewhere) SearchTier.Body else null
    }

    fun <T> rank(
        items: Iterable<T>,
        query: String,
        title: (T) -> String?,
        body: (T) -> String? = { null },
    ): List<RankedMatch<T>> {
        val words = queryWords(query)
        if (words.isEmpty()) return emptyList()
        return items
            .mapNotNull { item -> tier(words, title(item), body(item))?.let { RankedMatch(item, it) } }
            .sortedBy { it.tier.rank }
    }
}
