package com.ftapps.remft.ui.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.domain.tasks.TaskDraft
import com.ftapps.remft.testing.TestTasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class BackupReminderDueTest {
    private val rig = TestTasks()

    @After
    fun tearDown() = rig.close()

    @Test
    fun theReminderTurnsOnWhenTheFirstDataIsAddedWhileItIsWatched() = runBlocking {
        val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, ZoneId.of("UTC"))
        val due = backupReminderDue(rig.holder, rig.settings) { now }
        assertFalse(withTimeout(5_000) { due.first() })
        val turnedOn = async(Dispatchers.IO) { withTimeout(10_000) { due.first { it } } }
        Thread.sleep(300)
        rig.repository.create(TaskDraft(title = "First task"))
        turnedOn.await()
        Unit
    }
}
