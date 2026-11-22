package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFixturesTest {
    private fun exportAndCheck(title: String, markdown: String) {
        val blocks = MarkdownBlocks.parse(markdown)
        val bytes = NoteDocx.build(title, "Edited today", blocks, DocxTestSupport.labels) { DocxTestSupport.png(8, 8) }
        val parts = DocxTestSupport.unzip(bytes)
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach { DocxTestSupport.parse(it) }
        NoteMarkdownFile.text(title, markdown)
    }

    @Test
    fun conformanceFixtureExports() {
        val markdown = Fixtures.text("markdown_conformance.md")
        val blocks = MarkdownBlocks.parse(markdown)
        assertTrue(blocks.first() is NoteBlock.FrontMatter)
        assertTrue(blocks.any { it is NoteBlock.Table })
        assertTrue(blocks.any { it is NoteBlock.Callout })
        assertTrue(blocks.any { it is NoteBlock.ListItem && it.kind == NoteListKind.Checked })
        assertTrue(blocks.any { it is NoteBlock.Code })
        exportAndCheck("Markdown conformance fixture", markdown)
    }

    @Test
    fun attackCorpusExportsInertly() {
        val root = Json.parseToJsonElement(Fixtures.text("markdown_attack_corpus.json")).jsonObject
        val cases = root.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val text = case.jsonObject.getValue("text").jsonPrimitive.content
            exportAndCheck(text, text)
            exportAndCheck("Attack", "# Heading\n\n$text\n\n- $text\n\n> [!NOTE]\n> $text\n\n| $text |\n|---|\n| $text |")
            val rels = String(
                DocxTestSupport.unzip(NoteDocx.build("t", null, MarkdownBlocks.parse(text), DocxTestSupport.labels) { null })
                    .getValue("word/_rels/document.xml.rels"),
                Charsets.UTF_8,
            )
            assertTrue(!rels.contains("javascript:", ignoreCase = true))
            assertTrue(!rels.contains("TargetMode=\"External\"") || rels.contains("Target=\"http") || rels.contains("Target=\"mailto"))
        }
    }

    @Test
    fun hostileControlCharactersStayWellFormed() {
        val hostile = (0 until 32).map { it.toChar() }.joinToString("") + "￾￿\uD800 tail <![CDATA[ ]]> &amp; </w:t>"
        exportAndCheck(hostile, hostile)
    }
}
