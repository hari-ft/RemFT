package com.ftapps.remft.ui.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ftapps.remft.data.notes.ImportedMarkdown
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownImportViewModelTest {
    private val env = NotesTestEnvironment()

    @After
    fun tearDown() = env.close()

    @Test
    fun aDoubleTapOnImportCreatesOneNote() {
        env.external.setPendingImport(ImportedMarkdown("trip.md", "# Trip\n\nPack the bags"))
        val viewModel = MarkdownImportViewModel(env.external, env.repository)
        val results = runBlocking { listOf(async { viewModel.save() }, async { viewModel.save() }).awaitAll() }
        assertEquals(1, results.count { it is ImportSave.Saved })
        assertTrue(ImportSave.Busy in results)
        assertEquals(1, runBlocking { env.repository.observeList().first().size })
    }

    @Test
    fun aFailedImportCanBeTriedAgain() {
        val viewModel = MarkdownImportViewModel(env.external, env.repository)
        assertEquals(ImportSave.Failed, runBlocking { viewModel.save() })
        env.external.setPendingImport(ImportedMarkdown("later.md", "body"))
        assertTrue(runBlocking { viewModel.save() } is ImportSave.Saved)
    }
}
