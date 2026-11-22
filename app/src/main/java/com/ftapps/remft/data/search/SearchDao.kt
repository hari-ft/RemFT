package com.ftapps.remft.data.search

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ftapps.remft.data.db.RemftDatabase
import com.ftapps.remft.domain.search.GlobalSearchItem
import com.ftapps.remft.domain.search.GlobalSearchKind
import com.ftapps.remft.domain.search.SearchHit
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.domain.search.SearchSnippet
import com.ftapps.remft.domain.search.SearchTier

private class SearchEntity(
    val table: String,
    val alias: String,
    val tiers: List<Pair<SearchTier, String>>,
    val order: String,
) {
    val fields: List<String> get() = tiers.map { it.second }
}

class SearchDao(private val database: () -> RemftDatabase) {
    fun tasks(workspaceId: String, query: String, statuses: List<String>): List<SearchHit> {
        if (statuses.isEmpty()) return emptyList()
        return run(
            tasksEntity,
            workspaceId,
            query,
            extraWhere = "t.status IN (${statuses.joinToString(", ") { "?" }})",
            extraArgs = statuses,
        )
    }

    fun notes(workspaceId: String, query: String): List<SearchHit> =
        run(notesEntity, workspaceId, query, extraWhere = "n.is_archived = 0")

    fun files(workspaceId: String, query: String): List<SearchHit> =
        run(filesEntity, workspaceId, query, extraWhere = "f.target_type IN ('workspace', 'task', 'note')")

    fun items(kind: GlobalSearchKind, hits: List<SearchHit>, words: List<String> = emptyList()): List<GlobalSearchItem> {
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map { it.id }
        val tiers = hits.associate { it.id to it.tier }
        val firstWord = words.firstOrNull().orEmpty()
        val placeholders = ids.joinToString(", ") { "?" }
        fun snippet(id: String, body: String?): String? =
            if (tiers[id] == SearchTier.Body && body != null) SearchSnippet.build(body, firstWord) else null
        val byId = mutableMapOf<String, GlobalSearchItem>()
        when (kind) {
            GlobalSearchKind.Task -> select("SELECT id, title, status, description FROM tasks WHERE id IN ($placeholders)", ids) { c ->
                val id = c.getString(0)
                byId[id] = GlobalSearchItem(
                    kind = kind,
                    id = id,
                    title = c.getString(1),
                    context = snippet(id, c.stringOrNull(3)),
                    done = c.getString(2) == "completed",
                )
            }
            GlobalSearchKind.Note -> select("SELECT id, title, plain_text, is_archived FROM notes WHERE id IN ($placeholders)", ids) { c ->
                val id = c.getString(0)
                byId[id] = GlobalSearchItem(
                    kind = kind,
                    id = id,
                    title = c.getString(1),
                    context = snippet(id, c.stringOrNull(2)),
                    archived = c.getInt(3) != 0,
                )
            }
            GlobalSearchKind.File -> select("SELECT id, name, mime FROM file_rows WHERE id IN ($placeholders)", ids) { c ->
                val id = c.getString(0)
                byId[id] = GlobalSearchItem(kind = kind, id = id, title = c.getString(1), mime = c.getString(2))
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    private fun run(
        entity: SearchEntity,
        workspaceId: String,
        query: String,
        extraWhere: String = "",
        extraArgs: List<String> = emptyList(),
    ): List<SearchHit> {
        val words = SearchMatch.queryWords(query)
        if (words.isEmpty()) return emptyList()
        val caseArgs = mutableListOf<String>()
        val tierSql = if (entity.tiers.size == 1) {
            "${entity.tiers.single().first.rank}"
        } else {
            val cases = entity.tiers.dropLast(1).joinToString(" ") { (tier, field) ->
                "WHEN ${allWords(field, words, caseArgs)} THEN ${tier.rank}"
            }
            "CASE $cases ELSE ${entity.tiers.last().first.rank} END"
        }
        val whereArgs = mutableListOf(workspaceId)
        whereArgs += extraArgs
        val match = eachWordSomewhere(entity.fields, words, whereArgs)
        val extra = if (extraWhere.isEmpty()) "" else " AND $extraWhere"
        val sql = "SELECT ${entity.alias}.id AS id, $tierSql AS tier FROM ${entity.table} ${entity.alias} " +
            "WHERE ${entity.alias}.workspace_id = ?$extra AND $match ORDER BY tier ASC, ${entity.order}"
        val hits = mutableListOf<SearchHit>()
        select(sql, caseArgs + whereArgs) { c ->
            val rank = c.getInt(1)
            hits += SearchHit(c.getString(0), SearchTier.entries.firstOrNull { it.rank == rank } ?: SearchTier.Body)
        }
        return hits
    }

    private fun select(sql: String, args: List<String>, row: (Cursor) -> Unit) {
        database().query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
            while (cursor.moveToNext()) row(cursor)
        }
    }

    private fun allWords(field: String, words: List<String>, args: MutableList<String>): String =
        words.joinToString(" AND ", prefix = "(", postfix = ")") { word ->
            args += pattern(word)
            "$field LIKE ? ESCAPE '\\'"
        }

    private fun eachWordSomewhere(fields: List<String>, words: List<String>, args: MutableList<String>): String =
        words.joinToString(" AND ", prefix = "(", postfix = ")") { word ->
            fields.joinToString(" OR ", prefix = "(", postfix = ")") { field ->
                args += pattern(word)
                "$field LIKE ? ESCAPE '\\'"
            }
        }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        fun pattern(word: String): String =
            "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        private val tasksEntity = SearchEntity(
            table = "tasks",
            alias = "t",
            tiers = listOf(SearchTier.Title to "IFNULL(t.title_fold, '')", SearchTier.Body to "IFNULL(t.description_fold, '')"),
            order = "t.due_at IS NULL ASC, t.due_at ASC, t.id DESC",
        )

        private val notesEntity = SearchEntity(
            table = "notes",
            alias = "n",
            tiers = listOf(SearchTier.Title to "IFNULL(n.title_fold, '')", SearchTier.Body to "IFNULL(n.body_fold, '')"),
            order = "n.updated_at IS NULL, n.updated_at DESC, n.id DESC",
        )

        private val filesEntity = SearchEntity(
            table = "file_rows",
            alias = "f",
            tiers = listOf(SearchTier.Title to "IFNULL(f.name_fold, '')"),
            order = "f.created_at IS NULL, f.created_at DESC, f.id DESC",
        )
    }
}
