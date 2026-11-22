package com.ftapps.remft.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTextTest {
    @Test
    fun plainTextStripsMarkdownSyntax() {
        val markdown = "# Plan\n\n- [ ] buy **milk**\n1. call [shop](https://example.com)\n> quoted ==mark==\n\n```\ncode here\n```\n![pic](remft://file/x)"
        assertEquals("Plan buy milk call shop quoted mark", NoteText.plainTextFromMarkdown(markdown))
    }

    @Test
    fun plainTextOfNothingIsEmpty() {
        assertEquals("", NoteText.plainTextFromMarkdown(null))
        assertEquals("", NoteText.plainTextFromMarkdown(""))
    }

    @Test
    fun plainTextIsCapped() {
        assertEquals(NoteText.MAX_PLAIN_TEXT, NoteText.plainTextFromMarkdown("a".repeat(70_000)).length)
    }

    @Test
    fun frontMatterIsDropped() {
        assertEquals("Body", NoteText.plainTextFromMarkdown("---\ntitle: x\n---\nBody"))
    }

    @Test
    fun snippetIsCut() {
        assertEquals(160, NoteText.snippet("b".repeat(300)).length)
    }

    @Test
    fun emptyTitleSavesAsUntitled() {
        assertEquals("Untitled", NoteText.savedTitle("   ", "Untitled"))
        assertEquals("Trip", NoteText.savedTitle("  Trip ", "Untitled"))
    }

    @Test
    fun leadingH1BecomesTheTitle() {
        val split = NoteText.splitMarkdownTitle("\n# Hiking plan\n\nroute one\n", "file")
        assertEquals("Hiking plan", split.title)
        assertEquals("route one\n", split.body)
        val none = NoteText.splitMarkdownTitle("## Sub\ntext", "file")
        assertEquals("file", none.title)
        assertEquals("## Sub\ntext", none.body)
    }

    @Test
    fun countsWordsAndCharacters() {
        assertEquals(TextCounts(0, 3), NoteText.countText("   "))
        assertEquals(TextCounts(3, 15), NoteText.countText("one two\n three "))
    }

    @Test
    fun fileStemDropsExtension() {
        assertEquals("notes.v2", NoteText.fileStem("notes.v2.md"))
        assertEquals(".md", NoteText.fileStem(".md"))
    }
}
