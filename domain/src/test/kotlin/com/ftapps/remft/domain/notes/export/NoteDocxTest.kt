package com.ftapps.remft.domain.notes.export

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document

class NoteDocxTest {
    private val fileId = "01HZX3K5V7Q8R9S0T1V2W3X4Y5"

    private fun build(markdown: String, image: (String) -> ByteArray? = { null }, subtitle: String? = "Edited today"): Map<String, ByteArray> =
        DocxTestSupport.unzip(NoteDocx.build("My note", subtitle, MarkdownBlocks.parse(markdown), DocxTestSupport.labels, image = image))

    private fun documentXml(parts: Map<String, ByteArray>): String = String(parts.getValue("word/document.xml"), Charsets.UTF_8)

    @Test
    fun packageHasEveryPartAndAllXmlIsWellFormed() {
        val parts = build("# Hello\n\nWorld")
        val expected = listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "docProps/app.xml",
            "word/document.xml", "word/styles.xml", "word/numbering.xml", "word/settings.xml",
            "word/fontTable.xml", "word/header1.xml", "word/header2.xml", "word/footer1.xml",
            "word/footer2.xml", "word/_rels/document.xml.rels",
        )
        assertTrue(parts.keys.containsAll(expected))
        assertEquals("[Content_Types].xml", parts.keys.first())
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { DocxTestSupport.parse(it) }
    }

    @Test
    fun containsTitleSubtitleAndText() {
        val text = DocxTestSupport.text(DocxTestSupport.parse(build("# Hello\n\nWorld **bold**").getValue("word/document.xml")))
        assertTrue(text.contains("My note"))
        assertTrue(text.contains("Edited today"))
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World bold"))
        val xml = documentXml(build("**bold**"))
        assertTrue(xml.contains("<w:b/><w:bCs/>"))
        assertTrue(xml.contains("w:w=\"11906\""))
    }

    @Test
    fun escapesMarkupAndDropsControlChars() {
        val parts = build("a < b & c > d \u0001 \"q\"")
        val xml = documentXml(parts)
        assertTrue(xml.contains("a &lt; b &amp; c &gt; d"))
        assertFalse(xml.contains("\u0001"))
        assertTrue(DocxTestSupport.text(DocxTestSupport.parse(parts.getValue("word/document.xml"))).contains("a < b & c > d"))
    }

    @Test
    fun embedsImageWhenBytesExist() {
        val png = DocxTestSupport.png(40, 20)
        val parts = build("![pic](remft://file/$fileId)", image = { if (it == fileId) png else null })
        val media = parts.keys.filter { it.startsWith("word/media/") }
        assertEquals(listOf("word/media/image1.png"), media)
        assertArrayEquals(png, parts.getValue("word/media/image1.png"))
        val xml = documentXml(parts)
        assertTrue(xml.contains("<w:drawing>"))
        assertTrue(xml.contains("descr=\"pic\""))
        assertFalse(xml.contains("Media missing"))
        assertTrue(String(parts.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8).contains("media/image1.png"))
    }

    @Test
    fun missingImagePrintsLabel() {
        val xml = documentXml(build("![pic](remft://file/$fileId)"))
        assertFalse(xml.contains("<w:drawing>"))
        assertTrue(xml.contains("Media missing"))
    }

    @Test
    fun undecodableImagePrintsLabel() {
        val xml = documentXml(build("![pic](remft://file/$fileId)", image = { byteArrayOf(1, 2, 3) }))
        assertTrue(xml.contains("Media missing"))
    }

    @Test
    fun orderedListsGetTheirOwnNumbering() {
        val parts = build("1. a\n2. b\n\npara\n\n1. c\n\n- x")
        val numbering = String(parts.getValue("word/numbering.xml"), Charsets.UTF_8)
        DocxTestSupport.parse(parts.getValue("word/numbering.xml"))
        assertTrue(numbering.contains("w:abstractNumId=\"2\""))
        assertTrue(numbering.contains("<w:numFmt w:val=\"decimal\"/>"))
        assertTrue(numbering.contains("<w:numFmt w:val=\"bullet\"/>"))
        val xml = documentXml(parts)
        assertEquals(2, Regex("<w:numId w:val=\"2\"/>").findAll(xml).count())
        assertEquals(1, Regex("<w:numId w:val=\"3\"/>").findAll(xml).count())
        assertEquals(1, Regex("<w:numId w:val=\"1\"/>").findAll(xml).count())
    }

    @Test
    fun hyperlinksOnlyForAllowedSchemes() {
        val parts = build("[ok](https://example.com/a?b=1&c=2) [mail](mailto:someone@example.com) [bad](javascript:alert(1)) [app](remft://task/1) [rel](notes/x)")
        val rels = String(parts.getValue("word/_rels/document.xml.rels"), Charsets.UTF_8)
        DocxTestSupport.parse(parts.getValue("word/_rels/document.xml.rels"))
        assertTrue(rels.contains("Target=\"https://example.com/a?b=1&amp;c=2\" TargetMode=\"External\""))
        assertTrue(rels.contains("mailto:someone@example.com"))
        assertFalse(rels.contains("javascript"))
        assertFalse(rels.contains("remft://"))
        assertFalse(rels.contains("notes/x"))
        assertEquals(2, Regex("<w:hyperlink ").findAll(documentXml(parts)).count())
    }

    @Test
    fun checkboxesAlertsAndTables() {
        val xml = documentXml(build("- [ ] open\n- [x] done\n\n> [!TIP]\n> Use it\n\n| a | b |\n|---|--:|\n| 1 | 2 |"))
        assertTrue(xml.contains("☐"))
        assertTrue(xml.contains("☑"))
        assertFalse(xml.contains("<w:strike/>"))
        assertTrue(xml.contains(">Tip<"))
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("<w:jc w:val=\"right\"/>"))
    }

    @Test
    fun outputIsDeterministic() {
        val blocks = MarkdownBlocks.parse("# A\n\n- b")
        val a = NoteDocx.build("T", null, blocks, DocxTestSupport.labels) { null }
        val b = NoteDocx.build("T", null, blocks, DocxTestSupport.labels) { null }
        assertArrayEquals(a, b)
    }

    @Test
    fun pictureSizesFromHeaders() {
        assertEquals(NoteDocx.PictureSize(40, 20, "png"), NoteDocx.pictureSize(DocxTestSupport.png(40, 20)))
        val gif = "GIF89a".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(10, 0, 5, 0)
        assertEquals(NoteDocx.PictureSize(10, 5, "gif"), NoteDocx.pictureSize(gif))
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 30, 0, 60, 3, 0, 0, 0,
        )
        assertEquals(NoteDocx.PictureSize(60, 30, "jpeg"), NoteDocx.pictureSize(jpeg))
        val webp = ByteArray(30)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBPVP8X".toByteArray().copyInto(webp, 8)
        webp[24] = 99
        webp[27] = 49
        assertEquals(NoteDocx.PictureSize(100, 50, "webp"), NoteDocx.pictureSize(webp))
        assertEquals(null, NoteDocx.pictureSize(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun figuresPrintSourceWithoutRaster() {
        val xml = documentXml(build("```mermaid\ngraph TD\n```"))
        assertTrue(xml.contains("graph TD"))
        assertNotNull(xml)
    }
}

object DocxTestSupport {
    val labels = NoteExportLabels(
        alertNote = "Note",
        alertTip = "Tip",
        alertImportant = "Important",
        alertWarning = "Warning",
        alertCaution = "Caution",
        mediaMissing = "Media missing",
        unsupportedBlock = "Unsupported block",
        frontMatterTitle = "Properties",
    )

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    fun text(document: Document): String {
        val nodes = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
    }

    fun png(width: Int, height: Int): ByteArray {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return header + be(width) + be(height) + byteArrayOf(8, 6, 0, 0, 0)
    }
}
