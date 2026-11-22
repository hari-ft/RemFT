package com.ftapps.remft.domain.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalDocumentsTest {
    private fun handle(n: Int) = ExternalDocHandle("content://doc/$n", ExternalDocuments.KIND_ANDROID_URI, "doc$n.md")

    @Test
    fun recentsRoundTripAndCap() {
        var recents = emptyList<ExternalDocHandle>()
        (1..15).forEach { recents = ExternalDocuments.push(recents, handle(it)) }
        assertEquals(12, recents.size)
        assertEquals("content://doc/15", recents.first().token)
        recents = ExternalDocuments.push(recents, handle(10))
        assertEquals("content://doc/10", recents.first().token)
        assertEquals(12, recents.size)
        assertEquals(recents, ExternalDocuments.parseRecents(ExternalDocuments.encodeRecents(recents)))
        assertEquals(11, ExternalDocuments.drop(recents, "content://doc/10").size)
    }

    @Test
    fun malformedRecentsAreSkipped() {
        assertEquals(emptyList<ExternalDocHandle>(), ExternalDocuments.parseRecents("not json"))
        val raw = "[{\"token\":\"a\",\"kind\":\"androidUri\",\"name\":\"A\"},{\"token\":\"\",\"kind\":\"androidUri\",\"name\":\"B\"}," +
            "{\"token\":\"c\",\"kind\":\"other\",\"name\":\"C\"},{\"token\":\"a\",\"kind\":\"androidUri\",\"name\":\"dup\"}]"
        assertEquals(listOf(ExternalDocHandle("a", "androidUri", "A")), ExternalDocuments.parseRecents(raw))
    }

    @Test
    fun decodeKeepsBomAndFlagsNonUtf8() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        val decoded = ExternalDocuments.decode(withBom)
        assertEquals(ExternalDecoded("hi", ExternalEncoding.Utf8Bom), decoded)
        assertArrayEquals(withBom, ExternalDocuments.encode(decoded.text, decoded.encoding))
        assertEquals(ExternalEncoding.NotText, ExternalDocuments.decode(byteArrayOf(0xC3.toByte(), 0x28)).encoding)
        assertEquals(ExternalEncoding.Utf8, ExternalDocuments.decode("é".toByteArray()).encoding)
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ExternalDocuments.sha256("abc".toByteArray()))
    }
}
