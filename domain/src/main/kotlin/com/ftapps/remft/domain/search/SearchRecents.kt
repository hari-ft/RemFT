package com.ftapps.remft.domain.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

enum class GlobalSearchKind(val id: String) {
    Task("task"), Note("note"), File("file");

    companion object {
        fun parse(value: String?): GlobalSearchKind? = entries.firstOrNull { it.id == value }
    }
}

data class SearchRecent(val kind: GlobalSearchKind, val id: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.id)
        put("id", id)
    }

    companion object {
        fun fromJson(json: Any?): SearchRecent? {
            if (json !is JsonObject) return null
            val kindValue = json["kind"] as? JsonPrimitive
            val idValue = json["id"] as? JsonPrimitive
            val kind = kindValue?.takeIf { it.isString }?.let { GlobalSearchKind.parse(it.content) } ?: return null
            val id = idValue?.takeIf { it.isString }?.content ?: return null
            if (id.isEmpty()) return null
            return SearchRecent(kind, id)
        }
    }
}

object SearchRecents {
    const val KEY = "search.recent"
    const val LIMIT = 8
    private val notNotes = setOf("new", "import", "file")

    fun parse(raw: String?): List<SearchRecent> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = mutableListOf<SearchRecent>()
        for (entry in decoded) {
            val recent = SearchRecent.fromJson(entry) ?: continue
            if (recent in out) continue
            out.add(recent)
            if (out.size == LIMIT) break
        }
        return out
    }

    fun push(recents: List<SearchRecent>, recent: SearchRecent): List<SearchRecent> =
        (listOf(recent) + recents.filter { it != recent }).take(LIMIT)

    fun record(recents: List<SearchRecent>, recent: SearchRecent): List<SearchRecent> =
        if (recents.firstOrNull() == recent) recents else push(recents, recent)

    fun encode(recents: List<SearchRecent>): String = JsonArray(recents.map { it.toJson() }).toString()

    fun fromLocation(path: String): SearchRecent? {
        val decodedPath = runCatching { URI(path).path }.getOrNull() ?: return null
        if (decodedPath.isEmpty()) return null
        val segments = decodedPath.removePrefix("/").split("/")
        if (segments.size != 2) return null
        val id = segments[1]
        if (id.isEmpty()) return null
        return when (segments[0]) {
            "tasks" -> SearchRecent(GlobalSearchKind.Task, id)
            "notes" -> if (id in notNotes) null else SearchRecent(GlobalSearchKind.Note, id)
            "edit-note" -> SearchRecent(GlobalSearchKind.Note, id)
            else -> null
        }
    }
}
