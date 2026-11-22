package com.ftapps.remft.domain.notes.export

import com.ftapps.remft.domain.notes.markdown.AlertKind

data class NoteSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val mark: Boolean = false,
    val superscript: Boolean = false,
)

enum class NoteListKind { Bullet, Ordered, Checked, Unchecked }

enum class NoteFigureKind { Mermaid, Math }

enum class NoteTableAlignment { Default, Left, Center, Right }

sealed interface NoteBlock {
    val text: String

    data class Paragraph(val spans: List<NoteSpan>) : NoteBlock {
        override val text: String get() = spans.plainText()
    }

    data class Heading(val level: Int, val spans: List<NoteSpan>) : NoteBlock {
        override val text: String get() = spans.plainText()
    }

    data class ListItem(
        val kind: NoteListKind,
        val spans: List<NoteSpan>,
        val indent: Int = 0,
        val ordinal: Int? = null,
    ) : NoteBlock {
        override val text: String get() = spans.plainText()
    }

    data class Quote(val spans: List<NoteSpan>) : NoteBlock {
        override val text: String get() = spans.plainText()
    }

    data class Callout(val kind: AlertKind, val spans: List<NoteSpan>) : NoteBlock {
        override val text: String get() = spans.plainText()
    }

    data class Code(val source: String, val language: String? = null) : NoteBlock {
        override val text: String get() = source
    }

    data class Figure(val kind: NoteFigureKind, val source: String, val index: Int) : NoteBlock {
        override val text: String get() = source
    }

    data class Image(val fileId: String, val alt: String) : NoteBlock {
        override val text: String get() = alt
    }

    data class Table(
        val rows: List<List<String>>,
        val alignments: List<NoteTableAlignment> = emptyList(),
    ) : NoteBlock {
        override val text: String get() = rows.joinToString("\n") { it.joinToString("\t") }

        val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0

        fun alignment(column: Int): NoteTableAlignment = alignments.getOrNull(column) ?: NoteTableAlignment.Default
    }

    data class FrontMatter(val source: String) : NoteBlock {
        override val text: String get() = source
    }

    data object Divider : NoteBlock {
        override val text: String get() = ""
    }

    data object Unsupported : NoteBlock {
        override val text: String get() = ""
    }
}

fun List<NoteSpan>.plainText(): String = joinToString("") { it.text }
