package com.ftapps.remft.domain.tasks

import com.ftapps.remft.domain.backup.BackupSettings
import com.ftapps.remft.domain.backup.oldapp.OldAppSettings
import com.ftapps.remft.domain.settings.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class InboxSortTest {
    private fun task(id: String, title: String = "Task $id", priority: String = "none", updated: String? = null) =
        Task(id = id, workspaceId = "W", title = title, status = "inbox", priority = priority, updatedAt = updated?.let(Instant::parse))

    private val tasks = listOf(
        task("01A", title = "banana", priority = "low", updated = "2026-11-03T10:00:00Z"),
        task("01C", title = "Apple", priority = "high", updated = "2026-11-01T10:00:00Z"),
        task("01B", title = "cherry", priority = "high", updated = null),
        task("01E", title = "apple", priority = "none", updated = "2026-11-03T10:00:00Z"),
        task("01D", title = "Échalote", priority = "urgent", updated = "2026-11-05T10:00:00Z"),
    )

    private fun order(raw: String?): List<String> = InboxSort.sort(tasks, SortState.parse(raw, InboxSort.choices)).map { it.id }

    @Test
    fun theDefaultIsNewestAddedFirstLikeTheOldIdDescendingQuery() {
        assertEquals(InboxSort.DEFAULT, SettingKeys.InboxSort.default)
        val default = SortState.parse(SettingKeys.InboxSort.default, InboxSort.choices)
        assertEquals(SortState("added", descending = true), default)
        assertEquals(tasks.map { it.id }.sortedDescending(), order(null))
        assertEquals(listOf("01E", "01D", "01C", "01B", "01A"), order("added:desc"))
    }

    @Test
    fun dateAddedAscendingIsOldestFirst() {
        assertEquals(listOf("01A", "01B", "01C", "01D", "01E"), order("added:asc"))
    }

    @Test
    fun titleIsCaseAndAccentFoldedAToZWithTiesById() {
        assertEquals(listOf("01C", "01E", "01A", "01B", "01D"), order("title:asc"))
        assertEquals(listOf("01D", "01B", "01A", "01E", "01C"), order("title:desc"))
    }

    @Test
    fun priorityPutsTheHighestFirstWithTiesById() {
        assertEquals(listOf("01D", "01C", "01B", "01A", "01E"), order("priority:desc"))
        assertEquals(listOf("01E", "01A", "01B", "01C", "01D"), order("priority:asc"))
    }

    @Test
    fun lastUpdatedPutsTheNewestFirstAndNeverUpdatedLast() {
        assertEquals(listOf("01D", "01E", "01A", "01C", "01B"), order("updated:desc"))
        assertEquals(listOf("01B", "01C", "01A", "01E", "01D"), order("updated:asc"))
    }

    @Test
    fun reverseFlipsTheWholeOrderForEveryOption() {
        for (choice in InboxSort.choices) {
            val natural = SortState.parse(choice.id, InboxSort.choices)
            val forward = InboxSort.sort(tasks, natural).map { it.id }
            val back = InboxSort.sort(tasks, natural.reversed()).map { it.id }
            assertEquals(choice.id, forward.reversed(), back)
        }
    }

    @Test
    fun sortingIsStableWhateverTheInputOrder() {
        val same = (1..6).map { task("0$it", title = "Same", priority = "medium", updated = "2026-11-01T00:00:00Z") }
        for (choice in InboxSort.choices) {
            val state = SortState.parse(choice.id, InboxSort.choices)
            assertEquals(InboxSort.sort(same, state), InboxSort.sort(same.shuffled(java.util.Random(7)), state))
            assertEquals(InboxSort.sort(same, state), InboxSort.sort(same.reversed(), state))
        }
    }

    @Test
    fun theNaturalDirectionsMatchHome() {
        assertTrue(SortState.parse("added", InboxSort.choices).descending)
        assertFalse(SortState.parse("title", InboxSort.choices).descending)
        assertTrue(SortState.parse("priority", InboxSort.choices).descending)
        assertTrue(SortState.parse("updated", InboxSort.choices).descending)
        assertEquals(SortState("added", descending = true), SortState.parse("date:asc", InboxSort.choices))
    }

    @Test
    fun theInboxSortHasItsOwnKeyAndIsBackedUpLikeHomes() {
        assertTrue(SettingKeys.InboxSort.name != SettingKeys.TasksSort.name)
        assertTrue(SettingKeys.TasksSort.name in BackupSettings.keys)
        assertTrue(SettingKeys.InboxSort.name in BackupSettings.keys)
        assertTrue(SettingKeys.InboxSort.name in SettingKeys.backedUp)
    }

    @Test
    fun anOldAppValueForTheInboxSortIsCheckedLikeTheOtherSorts() {
        assertEquals("title:asc", OldAppSettings.convertValue(SettingKeys.InboxSort.name, "title:asc"))
        assertNull(OldAppSettings.convertValue(SettingKeys.InboxSort.name, "date:asc"))
    }
}
