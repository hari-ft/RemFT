package com.ftapps.remft.domain.notes.export

object NoteMarkdownFile {
    const val MIME_TYPE: String = "text/markdown"

    private val frontMatter = Regex("^---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(\\r?\\n|\\z)")
    private val openingHeading = Regex("^#\\s")
    private val unsafeFileChars = Regex("[\\\\/:*?\"<>|\\n\\r\\t]")
    private val whitespace = Regex("\\s+")
    private const val MAX_FILE_BASE = 80

    fun text(title: String, markdown: String): String {
        val front = frontMatter.find(markdown)?.value.orEmpty()
        val body = markdown.substring(front.length).trimStart()
        val heading = title.trim()
        val opensWithHeading = openingHeading.containsMatchIn(body)
        val text = if (heading.isEmpty() || opensWithHeading) {
            markdown
        } else {
            buildString {
                append(front)
                if (front.isNotEmpty()) append('\n')
                append("# ").append(heading).append('\n')
                if (body.isNotEmpty()) append('\n').append(body)
            }
        }
        return if (text.endsWith("\n")) text else "$text\n"
    }

    fun bytes(title: String, markdown: String): ByteArray = text(title, markdown).toByteArray(Charsets.UTF_8)

    fun fileName(title: String, extension: String): String {
        val cleaned = title.replace(unsafeFileChars, " ").replace(whitespace, " ").trim()
        val base = cleaned.ifEmpty { "note" }
        val clipped = if (base.length <= MAX_FILE_BASE) {
            base
        } else {
            val end = if (base[MAX_FILE_BASE - 1].isHighSurrogate()) MAX_FILE_BASE - 1 else MAX_FILE_BASE
            base.substring(0, end).trimEnd()
        }
        return "$clipped.$extension"
    }
}
