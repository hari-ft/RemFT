package com.ftapps.remft.domain.search

data class GlobalSearchItem(
    val kind: GlobalSearchKind,
    val id: String,
    val title: String,
    val context: String? = null,
    val done: Boolean = false,
    val archived: Boolean = false,
    val mime: String? = null,
)

data class GlobalSearchGroup(val kind: GlobalSearchKind, val items: List<GlobalSearchItem>, val total: Int)

sealed interface PaletteEntry {
    data class Item(val item: GlobalSearchItem) : PaletteEntry
    data class Create(val title: String) : PaletteEntry
}

object GlobalSearch {
    const val GROUP_LIMIT = 5

    val planningStatuses: List<String> = listOf("open", "scheduled", "in_progress", "waiting")
    val taskStatuses: List<String> = planningStatuses + listOf("inbox", "completed")
    val homeTaskStatuses: List<String> = planningStatuses + listOf("inbox")

    fun groups(
        query: String,
        hits: Map<GlobalSearchKind, List<SearchHit>>,
        resolve: (GlobalSearchKind, List<SearchHit>) -> List<GlobalSearchItem>,
    ): List<GlobalSearchGroup> {
        if (query.trim().isEmpty()) return emptyList()
        val out = mutableListOf<GlobalSearchGroup>()
        for (kind in GlobalSearchKind.entries) {
            val kindHits = hits[kind].orEmpty()
            if (kindHits.isEmpty()) continue
            val items = resolve(kind, kindHits.take(GROUP_LIMIT))
            if (items.isEmpty()) continue
            out.add(GlobalSearchGroup(kind, items, kindHits.size))
        }
        return out
    }

    fun context(tier: SearchTier?, body: String?, words: List<String>, title: String? = null): String? =
        if (tier == SearchTier.Body && body != null) SearchSnippet.build(body, SearchSnippet.contextWord(words, title)) else null

    fun resolveRecents(recents: List<SearchRecent>, lookup: (SearchRecent) -> GlobalSearchItem?): List<GlobalSearchItem> =
        recents.mapNotNull(lookup)

    fun entries(
        text: String,
        recents: List<GlobalSearchItem>,
        groups: List<GlobalSearchGroup>,
    ): List<PaletteEntry> {
        val trimmed = text.trim()
        return buildList {
            if (trimmed.isEmpty()) recents.forEach { add(PaletteEntry.Item(it)) }
            groups.forEach { group -> group.items.forEach { add(PaletteEntry.Item(it)) } }
            if (trimmed.isNotEmpty()) add(PaletteEntry.Create(trimmed))
        }
    }
}
