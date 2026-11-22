package com.ftapps.remft.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkifyTest {
    @Test
    fun plainTextStaysOnePlainSegment() {
        val out = Linkify.segments("just plain text, no link")
        assertEquals(1, out.size)
        assertNull(out.single().url)
    }

    @Test
    fun aMidSentenceHttpsUrlBecomesALinkSegment() {
        val text = "the design lives at https://example.com/spec today"
        val out = Linkify.segments(text)
        assertEquals(text, out.joinToString("") { it.text })
        val link = out.single { it.url != null }
        assertEquals("https://example.com/spec", link.text)
        assertEquals("https://example.com/spec", link.url)
    }

    @Test
    fun trailingSentencePunctuationStaysOutOfTheLink() {
        for (tail in listOf(".", ",", "!", "?", ":", ";", "\"", "'", "”", "’", "»")) {
            val out = Linkify.segments("bak: https://x.dev$tail")
            assertEquals(tail, "https://x.dev", out.single { it.url != null }.text)
            assertEquals(tail, out.last().text)
        }
    }

    @Test
    fun aWrappingParenIsTrimmedAWikipediaStyleOneSurvives() {
        val wrapped = Linkify.segments("(kaynak: https://x.dev/a)")
        assertEquals("https://x.dev/a", wrapped.single { it.url != null }.text)
        val wiki = Linkify.segments("https://en.wikipedia.org/wiki/Task_(project)")
        assertEquals("https://en.wikipedia.org/wiki/Task_(project)", wiki.single { it.url != null }.text)
    }

    @Test
    fun bareWwwHostsLinkWithAnHttpsScheme() {
        val out = Linkify.segments("go to the site via www.remft.dev now")
        val link = out.single { it.url != null }
        assertEquals("www.remft.dev", link.text)
        assertEquals("https://www.remft.dev", link.url)
    }

    @Test
    fun multipleUrlsSplitTheTextLosslessly() {
        val text = "a https://one.dev b www.two.dev c"
        val out = Linkify.segments(text)
        assertEquals(text, out.joinToString("") { it.text })
        assertEquals(2, out.count { it.url != null })
    }

    @Test
    fun schemesAreMatchedCaseInsensitivelyAndKept() {
        val out = Linkify.segments("see HTTPS://Example.com/x and WWW.example.org")
        val links = out.filter { it.url != null }
        assertEquals(listOf("HTTPS://Example.com/x", "WWW.example.org"), links.map { it.text })
        assertEquals(listOf("HTTPS://Example.com/x", "https://WWW.example.org"), links.map { it.url })
    }

    @Test
    fun angleBracketsAndUnicodeSpacesEndAUrl() {
        val out = Linkify.segments("<https://x.dev/a> https://y.dev　z")
        assertEquals(listOf("https://x.dev/a", "https://y.dev"), out.filter { it.url != null }.map { it.text })
        assertEquals("<https://x.dev/a> https://y.dev　z", out.joinToString("") { it.text })
    }

    @Test
    fun emptyTextHasNoSegments() {
        assertEquals(emptyList<LinkSegment>(), Linkify.segments(""))
    }

    @Test
    fun linkUrlOf() {
        assertEquals("http://a.dev", Linkify.linkUrlOf("http://a.dev"))
        assertEquals("https://www.a.dev", Linkify.linkUrlOf("www.a.dev"))
    }
}
