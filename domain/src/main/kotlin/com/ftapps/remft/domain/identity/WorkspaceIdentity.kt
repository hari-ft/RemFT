package com.ftapps.remft.domain.identity

data class IdentityDecision(
    val workspaceId: String,
    val reliable: Boolean,
    val persist: Boolean,
    val adopted: Boolean,
)

object WorkspaceIdentity {
    val ownedTables = listOf("tasks", "notes", "folders", "file_rows", "task_series", "trash_items")

    fun dominant(counts: Map<String, Int>): String? {
        var best: String? = null
        var bestCount = 0
        for ((workspace, count) in counts) {
            val current = best
            if (count > bestCount || (count == bestCount && current != null && workspace < current)) {
                best = workspace
                bestCount = count
            }
        }
        return best
    }

    fun decide(
        stored: String?,
        storeReadable: Boolean,
        counts: Map<String, Int>?,
        sessionId: String?,
        mint: () -> String,
    ): IdentityDecision {
        val owners = counts.orEmpty()
        val dominant = dominant(owners)
        val candidate = stored ?: sessionId
        return when {
            dominant != null && (candidate == null || !owners.containsKey(candidate)) ->
                IdentityDecision(
                    workspaceId = dominant,
                    reliable = owners.size == 1,
                    persist = storeReadable,
                    adopted = true,
                )
            candidate != null ->
                IdentityDecision(
                    workspaceId = candidate,
                    reliable = stored != null || owners.containsKey(candidate),
                    persist = stored == null && storeReadable,
                    adopted = false,
                )
            else -> {
                val known = storeReadable && counts != null
                IdentityDecision(workspaceId = mint(), reliable = known, persist = known, adopted = false)
            }
        }
    }
}
