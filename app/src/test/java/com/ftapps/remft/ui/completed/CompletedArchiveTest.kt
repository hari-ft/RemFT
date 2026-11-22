package com.ftapps.remft.ui.completed

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.testing.TestFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CompletedArchiveTest {
    private val rig = TestFiles()

    @After
    fun tearDown() {
        rig.close()
    }

    private fun insert(id: String, status: String, due: String?, completed: String?, workspace: String = rig.workspace) {
        val dueSql = due?.let { "'$it'" } ?: "NULL"
        val completedSql = completed?.let { "'$it'" } ?: "NULL"
        rig.exec("INSERT INTO tasks (id, workspace_id, title, status, due_at, completed_at) VALUES ('$id', '$workspace', 'Task $id', '$status', $dueSql, $completedSql)")
    }

    @Test
    fun ordersByOwnDateElseCompletionNewestFirstThenIdAndPages() = runBlocking {
        insert("A", "completed", "2026-11-10T10:00:00.000Z", "2026-11-19T10:00:00.000Z")
        insert("B", "completed", null, "2026-11-15T10:00:00.000Z")
        insert("C", "completed", "2026-11-18T10:00:00.000Z", "2026-11-01T10:00:00.000Z")
        insert("D", "completed", null, "2026-11-15T10:00:00.000Z")
        insert("E", "open", "2026-11-20T10:00:00.000Z", null)
        insert("F", "completed", "2026-11-30T10:00:00.000Z", null, workspace = "OTHER")
        insert("G", "completed", null, null)

        val all = rig.files.watchCompleted(50).first().map { it.id }
        assertEquals(listOf("C", "D", "B", "A", "G"), all)
        assertEquals(listOf("C", "D"), rig.files.watchCompleted(2).first().map { it.id })
    }

    @Test
    fun rowsGroupByLocalDayWithHeaders() = runBlocking {
        insert("A", "completed", "2026-11-19T20:00:00.000Z", null)
        insert("B", "completed", "2026-11-19T17:00:00.000Z", null)
        insert("C", "completed", null, "2026-11-18T10:00:00.000Z")
        insert("D", "completed", null, null)
        val tasks = rig.files.watchCompleted(50).first()
        val rows = CompletedArchive.rows(tasks, ZoneId.of("Asia/Kolkata"))
        assertEquals(
            listOf(
                CompletedRow.Header(LocalDate.of(2026, 11, 20)),
                CompletedRow.Item(tasks[0]),
                CompletedRow.Header(LocalDate.of(2026, 11, 19)),
                CompletedRow.Item(tasks[1]),
                CompletedRow.Header(LocalDate.of(2026, 11, 18)),
                CompletedRow.Item(tasks[2]),
                CompletedRow.Header(null),
                CompletedRow.Item(tasks[3]),
            ),
            rows,
        )
    }

    @Test
    fun growsOnlyWhenAFullPageIsLoadedAndTheEndIsNear() {
        assertTrue(CompletedArchive.shouldGrow(loaded = 50, pages = 1, lastVisible = 55, total = 60))
        assertFalse(CompletedArchive.shouldGrow(loaded = 49, pages = 1, lastVisible = 55, total = 60))
        assertFalse(CompletedArchive.shouldGrow(loaded = 50, pages = 1, lastVisible = 10, total = 60))
        assertEquals(50, CompletedArchive.PAGE_SIZE)
    }
}
