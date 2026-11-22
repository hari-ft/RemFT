package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPlainTextTest {
    @Test
    fun nothingGivesNothing() {
        assertEquals("", MarkdownPlainText.from(null))
        assertEquals("", MarkdownPlainText.from(""))
        assertEquals("", MarkdownPlainText.from("   \n\t "))
    }

    @Test
    fun blockMarkersAreStripped() {
        val markdown = "# Title\n\n> quoted line\n- [ ] open item\n- [x] done item\n* bullet\n1. first\n2) second"
        assertEquals("Title quoted line open item done item bullet first second", MarkdownPlainText.from(markdown))
    }

    @Test
    fun fencedCodeAndFrontMatterAreDropped() {
        val markdown = "---\ntitle: x\n---\nBefore\n```kotlin\nval x = 1\n```\nmiddle\n~~~\ncode\n~~~\nafter"
        assertEquals("Before middle after", MarkdownPlainText.from(markdown))
    }

    @Test
    fun imagesVanishLinksKeepTheirLabel() {
        assertEquals(
            "see the spec and a here",
            MarkdownPlainText.from("see ![diagram](img.png) the [spec](https://example.com) and a `<tag>` here"),
        )
        assertEquals("a b", MarkdownPlainText.from("a <span> b"))
    }

    @Test
    fun emphasisMarksAreRemoved() {
        assertEquals(
            "bold strong struck marked italic and also",
            MarkdownPlainText.from("**bold** __strong__ ~~struck~~ ==marked== *italic* and _also_"),
        )
        assertEquals("snake_case_name stays", MarkdownPlainText.from("snake_case_name stays"))
    }

    @Test
    fun rulesAndTablePipesBecomeSpace() {
        assertEquals("above below", MarkdownPlainText.from("above\n\n---\n\nbelow"))
        assertEquals("a b c d", MarkdownPlainText.from("| a | b |\n| c | d |"))
    }

    @Test
    fun theTextIsCappedAtTheLimit() {
        val long = "word ".repeat(20000)
        assertEquals(MarkdownPlainText.MAX_LENGTH, MarkdownPlainText.from(long).length)
    }
}
