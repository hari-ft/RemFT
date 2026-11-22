package com.ftapps.remft.domain.notes.export

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteMarkdownFileTest {
    @Test
    fun addsTitleHeading() {
        assertEquals("# Groceries\n\n- milk\n", NoteMarkdownFile.text("Groceries", "- milk"))
    }

    @Test
    fun keepsBodyThatOpensWithHeading() {
        assertEquals("# Own\n\ntext\n", NoteMarkdownFile.text("Title", "# Own\n\ntext"))
    }

    @Test
    fun blankTitleLeavesBody() {
        assertEquals("body\n", NoteMarkdownFile.text("  ", "body"))
    }

    @Test
    fun emptyBodyIsJustTheHeading() {
        assertEquals("# Title\n", NoteMarkdownFile.text("Title", ""))
    }

    @Test
    fun headingGoesUnderFrontMatter() {
        assertEquals(
            "---\na: 1\n---\n\n# Title\n\nbody\n",
            NoteMarkdownFile.text("Title", "---\na: 1\n---\nbody"),
        )
    }

    @Test
    fun bytesAreUtf8() {
        assertEquals("# Ça\n", String(NoteMarkdownFile.bytes("Ça", ""), Charsets.UTF_8))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("a b c.md", NoteMarkdownFile.fileName("a/b:\n c", "md"))
        assertEquals("note.pdf", NoteMarkdownFile.fileName(" ?* ", "pdf"))
        assertEquals(80, NoteMarkdownFile.fileName("x".repeat(200), "docx").removeSuffix(".docx").length)
    }
}
