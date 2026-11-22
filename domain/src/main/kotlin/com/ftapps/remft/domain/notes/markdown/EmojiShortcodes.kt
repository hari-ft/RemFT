package com.ftapps.remft.domain.notes.markdown

object EmojiShortcodes {
    private val table: Map<String, String> by lazy { load() }

    val size: Int get() = table.size

    fun lookup(name: String): String? = table[name]

    private fun load(): Map<String, String> {
        val stream = EmojiShortcodes::class.java.getResourceAsStream("emoji.tsv") ?: return emptyMap()
        val result = HashMap<String, String>(2048)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.length - 1) continue
                result[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return result
    }
}
