package com.ftapps.remft.domain.backup.bin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BinPayloadTest {
    private val at = Instant.parse("2026-11-05T05:15:00Z")

    private val payload = BinPayload(
        tasks = listOf(
            BinTask(
                id = "T1", workspaceId = "W", title = "Pay rent", description = "**now**", priority = "high",
                dueAt = at, remindAt = Instant.parse("2026-11-05T05:00:00.123Z"), isUrgent = true,
                requiresAcknowledgement = true, occurrenceDate = "2026-11-05", sortOrder = 2, createdAt = at, updatedAt = at,
            ),
        ),
        checklist = listOf(BinChecklistItem("C1", "T1", "Step", isDone = true, sortOrder = 1, createdAt = at, updatedAt = at)),
        reminders = listOf(BinReminder("R1", "T1", "due", remindAt = at, alarmLevel = "urgent", requiresAcknowledgement = true, createdAt = at)),
        notes = listOf(BinNote("N1", "W", "Idea", contentMarkdown = "# Idea", isPinned = true, createdAt = at, updatedAt = at)),
        noteLinks = listOf(BinNoteLink("L1", "N1", "task", "T1")),
        files = listOf(BinFile("F1", "W", "task", "T1", "scan.pdf", "application/pdf", 42, localPath = "F1.pdf", createdAt = at)),
        folders = listOf(BinFolder("D1", "W", null, "Docs", createdAt = at)),
    )

    @Test
    fun serialisesEveryRowAsCompactJson() {
        val expected = "{" +
            "\"tasks\":[{\"id\":\"T1\",\"workspaceId\":\"W\",\"title\":\"Pay rent\",\"description\":\"**now**\",\"status\":\"open\"," +
            "\"priority\":\"high\",\"dueAt\":\"2026-11-05T05:15:00.000Z\",\"remindAt\":\"2026-11-05T05:00:00.123Z\"," +
            "\"snoozedUntil\":null,\"alarmsMutedAt\":null,\"timezone\":\"Asia/Kolkata\",\"isUrgent\":true," +
            "\"requiresAcknowledgement\":true,\"seriesId\":null,\"occurrenceDate\":\"2026-11-05\",\"spawnedFromTaskId\":null," +
            "\"sortOrder\":2,\"completedAt\":null,\"titleFold\":null,\"descriptionFold\":null," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":\"2026-11-05T05:15:00.000Z\"}]," +
            "\"checklist\":[{\"id\":\"C1\",\"taskId\":\"T1\",\"title\":\"Step\",\"isDone\":true,\"sortOrder\":1," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":\"2026-11-05T05:15:00.000Z\"}]," +
            "\"reminders\":[{\"id\":\"R1\",\"taskId\":\"T1\",\"kind\":\"due\",\"snoozeCount\":0," +
            "\"remindAt\":\"2026-11-05T05:15:00.000Z\",\"alarmLevel\":\"urgent\",\"requiresAcknowledgement\":true," +
            "\"status\":\"scheduled\",\"snoozedUntil\":null,\"acknowledgedAt\":null," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":null}]," +
            "\"notes\":[{\"id\":\"N1\",\"workspaceId\":\"W\",\"title\":\"Idea\",\"contentMarkdown\":\"# Idea\",\"plainText\":null," +
            "\"isPinned\":true,\"isArchived\":false,\"titleFold\":null,\"bodyFold\":null," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":\"2026-11-05T05:15:00.000Z\"}]," +
            "\"noteLinks\":[{\"id\":\"L1\",\"noteId\":\"N1\",\"entityType\":\"task\",\"entityId\":\"T1\"}]," +
            "\"files\":[{\"id\":\"F1\",\"workspaceId\":\"W\",\"targetType\":\"task\",\"targetId\":\"T1\",\"name\":\"scan.pdf\"," +
            "\"mime\":\"application/pdf\",\"sizeBytes\":42,\"folderId\":null,\"nameFold\":null,\"localPath\":\"F1.pdf\"," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":null}]," +
            "\"folders\":[{\"id\":\"D1\",\"workspaceId\":\"W\",\"parentId\":null,\"name\":\"Docs\"," +
            "\"createdAt\":\"2026-11-05T05:15:00.000Z\",\"updatedAt\":null}]}"
        assertEquals(expected, payload.encode())
        assertEquals(payload, BinPayload.parse(expected))
    }

    @Test
    fun epochMillisecondDatesAreRefused() {
        val epoch = payload.encode().replaceFirst("\"dueAt\":\"2026-11-05T05:15:00.000Z\"", "\"dueAt\":${at.toEpochMilli()}")
        assertTrue(runCatching { BinPayload.parse(epoch) }.isFailure)
    }

    @Test
    fun offsetDatesAreReadBackInUtc() {
        val withOffset = payload.encode().replaceFirst("\"dueAt\":\"2026-11-05T05:15:00.000Z\"", "\"dueAt\":\"2026-11-05T10:45:00.000+05:30\"")
        assertEquals(at, BinPayload.parse(withOffset).tasks.single().dueAt)
    }

    @Test
    fun missingListsReadAsEmptyAndBrokenRowsAreRefused() {
        assertEquals(BinPayload(), BinPayload.parse("{}"))
        assertTrue(runCatching { BinPayload.parse("{\"tasks\":[{\"id\":\"T1\"}]}") }.isFailure)
        assertTrue(runCatching { BinPayload.parse("{\"tasks\":{}}") }.isFailure)
        assertTrue(runCatching { BinPayload.parse("[]") }.isFailure)
        val badBool = payload.encode().replaceFirst("\"isUrgent\":true", "\"isUrgent\":1")
        assertTrue(runCatching { BinPayload.parse(badBool) }.isFailure)
    }

    @Test
    fun entryIdsNameEveryRowARestoreBringsBack() {
        assertEquals(setOf("T1", "N1", "F1", "D1"), BinPayload.entryIds("T1", payload.encode()))
        assertEquals(setOf("X"), BinPayload.entryIds("X", "{torn"))
    }

    @Test
    fun localPathsAndSize() {
        assertEquals("[\"F1.pdf\"]", payload.localPathsJson())
        assertEquals(42L, payload.sizeBytes)
        assertEquals(listOf("F1.pdf"), BinPayload.parseLocalPaths("[\"F1.pdf\"]"))
        assertEquals("[]", BinPayload().localPathsJson())
    }

    @Test
    fun aRestoredFolderGetsAFreeName() {
        assertEquals("Ringtones", BinPayload.freeFolderName(" Ringtones ", listOf("Docs")))
        assertEquals("Ringtones (2)", BinPayload.freeFolderName("Ringtones", listOf("ringtones")))
        assertEquals("Ringtones (3)", BinPayload.freeFolderName("Ringtones", listOf("RINGTONES", "Ringtones (2)")))
        assertEquals("Café (2)", BinPayload.freeFolderName("Café", listOf("cafe")))
    }

    @Test
    fun binSettingsFallBackToOnAutoDeleteAnd30Days() {
        assertEquals(BinSettings.Values(true, true, 30), BinSettings.of(null, null, null))
        assertEquals(BinSettings.Values(false, false, 7), BinSettings.of("false", "false", "7"))
        assertEquals(BinSettings.Values(true, true, 30), BinSettings.of("yes", "", "8"))
    }
}
