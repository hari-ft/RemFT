package com.ftapps.remft.domain.notes.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdEditingTest {
    @Test
    fun parsesListItems() {
        val item = MdEditing.parseListItem("  - [X] done")!!
        assertEquals("  ", item.indent)
        assertEquals("-", item.marker)
        assertEquals("x", item.checkbox)
        assertEquals("done", item.content)
        assertEquals(3, MdEditing.parseListItem("3) three")!!.number)
        assertNull(MdEditing.parseListItem("plain"))
    }

    @Test
    fun enterContinuesBulletsTodosAndNumbers() {
        assertEquals(MdEdit("- a\n- ", 6), MdEditing.continueList("- a", 3))
        assertEquals(MdEdit("- [x] a\n- [ ] ", 14), MdEditing.continueList("- [x] a", 7))
        assertEquals(MdEdit("1. a\n2. ", 8), MdEditing.continueList("1. a", 4))
        assertNull(MdEditing.continueList("text", 4))
    }

    @Test
    fun enterOnEmptyItemEndsTheList() {
        assertEquals(MdEdit("- a\n\n", 5), MdEditing.continueList("- a\n- ", 6))
    }

    @Test
    fun enterRenumbersFollowingItems() {
        val edit = MdEditing.enterPressed("1. a\n2. b", 4)!!
        assertEquals("1. a\n2. \n3. b", edit.text)
        assertEquals(8, edit.selection)
    }

    @Test
    fun newlineTypedIsDetected() {
        val edit = MdEditing.newlineTyped("- a", "- a\n", 4)!!
        assertEquals("- a\n- ", edit.text)
        assertNull(MdEditing.newlineTyped("- a", "- ab", 4))
        assertNull(MdEditing.newlineTyped("text", "text\n", 5))
    }

    @Test
    fun tabIndentsAndOutdents() {
        assertEquals(MdEdit("  - a", 5), MdEditing.indentListItem("- a", 3, outdent = false))
        assertEquals(MdEdit("- a", 3), MdEditing.indentListItem("  - a", 5, outdent = true))
        assertNull(MdEditing.indentListItem("- a", 3, outdent = true))
        assertNull(MdEditing.indentListItem("text", 2, outdent = false))
    }

    @Test
    fun renumbersPerLevelAndResetsOnProse() {
        val text = "5. a\n9. b\n  7. c\n  1. d\n3. e\n\n8. f\ntext\n4. g"
        assertEquals("1. a\n2. b\n  1. c\n  2. d\n3. e\n\n4. f\ntext\n1. g", MdEditing.renumberOrderedLists(text))
    }

    @Test
    fun pasteUrlOverSelectionMakesALink() {
        assertEquals(MdEdit("[site](https://example.com) x", 27), MdEditing.pasteOverSelection("site x", 0, 4, "https://example.com"))
        assertEquals(MdEdit("plain x", 5), MdEditing.pasteOverSelection("site x", 0, 4, "plain"))
    }

    @Test
    fun htmlBecomesMarkdown() {
        val md = MdEditing.htmlToMarkdown("<h2>Hi</h2><p><b>bold</b> &amp; <a href=\"https://e.com\">e</a></p><ul><li>one</li></ul>")
        assertTrue(md.startsWith("## Hi"))
        assertTrue(md.contains("**bold** & [e](https://e.com)"))
        assertTrue(md.contains("- one"))
    }
}
