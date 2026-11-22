package com.ftapps.remft.domain.notes.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiShortcodesTest {
    @Test
    fun knownShortcodes() {
        assertEquals("😄", EmojiShortcodes.lookup("smile"))
        assertEquals("👍", EmojiShortcodes.lookup("+1"))
        assertEquals("🎉", EmojiShortcodes.lookup("tada"))
        assertEquals("🚀", EmojiShortcodes.lookup("rocket"))
    }

    @Test
    fun unknownIsNull() {
        assertNull(EmojiShortcodes.lookup("definitely_not_an_emoji"))
        assertNull(EmojiShortcodes.lookup(""))
        assertNull(EmojiShortcodes.lookup("Smile"))
    }

    @Test
    fun tableIsComplete() {
        assertTrue(EmojiShortcodes.size > 1000)
    }
}
