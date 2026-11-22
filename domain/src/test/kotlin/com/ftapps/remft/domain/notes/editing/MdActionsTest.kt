package com.ftapps.remft.domain.notes.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdActionsTest {
    @Test
    fun wrapsSelection() {
        assertEquals(MdEdit("a **b** c", 7), MdActions.apply(MdActionId.Bold, "a b c", 2, 3))
        assertEquals(MdEdit("a ==== c", 4), MdActions.apply(MdActionId.Highlight, "a  c", 2, 2))
        assertEquals(MdEdit("[x]()", 4), MdActions.apply(MdActionId.Link, "x", 0, 1))
    }

    @Test
    fun prefixesLines() {
        assertEquals("# a\n# b", MdActions.apply(MdActionId.H1, "a\nb", 0, 3).text)
        assertEquals("- [ ] a", MdActions.apply(MdActionId.Todo, "a", 0, 0).text)
        assertEquals("1. a\n2. b", MdActions.apply(MdActionId.Numbered, "a\nb", 0, 3).text)
        assertEquals("> q", MdActions.apply(MdActionId.Quote, "> q", 0, 0).text)
    }

    @Test
    fun insertsBlocks() {
        assertEquals("text\n---\n", MdActions.apply(MdActionId.Divider, "text", 2, 2).text)
        assertEquals("| H | H |\n| --- | --- |\n|  |  |\n", MdActions.apply(MdActionId.Table, "", 0, 0, "H").text)
        assertEquals("```\n\n```\n", MdActions.apply(MdActionId.CodeBlock, "", 0, 0).text)
        assertEquals("```\nx\n```", MdActions.apply(MdActionId.CodeBlock, "x", 0, 1).text)
        assertEquals("x", MdActions.apply(MdActionId.CodeBlock, "```\nx\n```", 0, 9).text)
    }

    @Test
    fun noActionDestroysTheSelection() {
        val text = "before SELECTED after"
        MdActionId.entries.filter { it != MdActionId.CodeBlock }.forEach { id ->
            val result = MdActions.apply(id, text, 7, 15).text
            assertTrue(id.name, result.contains("SELECTED"))
            assertTrue(id.name, result.contains("before"))
            assertTrue(id.name, result.contains("after"))
        }
    }

    @Test
    fun slashTokensAndMatching() {
        assertEquals("/ta", MdActions.slashTokenAt("hello /ta", 9))
        assertNull(MdActions.slashTokenAt("a/b", 3))
        assertNull(MdActions.slashTokenAt("/ab c", 5))
        assertEquals(listOf(MdActionId.Table, MdActionId.Todo).toSet(), MdActions.matchSlash("/t").toSet())
        assertEquals(MdActionId.entries, MdActions.match(""))
        assertEquals(listOf(MdActionId.Bold), MdActions.match("bol") { it.name })
        assertEquals("x\n---\n", MdActions.applySlash(MdActionId.Divider, "x\n/div", 6).text)
    }
}
