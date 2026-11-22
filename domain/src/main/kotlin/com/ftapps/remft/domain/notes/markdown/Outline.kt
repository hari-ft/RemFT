package com.ftapps.remft.domain.notes.markdown

import com.ftapps.remft.domain.Fold
import org.commonmark.node.Heading
import org.commonmark.node.Node
import java.net.URLDecoder

data class OutlineHeading(val level: Int, val text: String, val slug: String, val blockIndex: Int, val startLine: Int)

object Outline {
    private val stripped = Regex("[^\\p{L}\\p{N}\\s-]")
    private val spaces = Regex("\\s+")

    fun slug(heading: String): String = spaces.replace(stripped.replace(Fold.searchText(heading), ""), "-")

    fun headings(blocks: List<Node>): List<OutlineHeading> {
        val seen = HashMap<String, Int>()
        val out = ArrayList<OutlineHeading>()
        blocks.forEachIndexed { index, block ->
            if (block !is Heading) return@forEachIndexed
            val text = block.textContent().trim()
            val base = slug(text)
            val count = seen[base]?.plus(1) ?: 0
            seen[base] = count
            out.add(OutlineHeading(block.level, text, if (count == 0) base else "$base-$count", index, block.startLine()))
        }
        return out
    }

    fun headings(parsed: ParsedMarkdown): List<OutlineHeading> = headings(parsed.document.children())

    fun forAnchor(headings: List<OutlineHeading>, anchor: String): OutlineHeading? {
        val wanted = slug(decode(anchor))
        return headings.firstOrNull { it.slug == wanted } ?: headings.firstOrNull { it.slug == decode(anchor) }
    }

    private fun decode(anchor: String): String {
        if (!anchor.contains('%')) return anchor
        return runCatching { URLDecoder.decode(anchor.replace("+", "%2B"), "UTF-8") }.getOrDefault(anchor)
    }

    fun foldedRange(blockCount: Int, headings: List<OutlineHeading>, heading: OutlineHeading): IntRange {
        val start = heading.blockIndex + 1
        val next = headings.firstOrNull { it.blockIndex > heading.blockIndex && it.level <= heading.level }
        val end = next?.blockIndex ?: blockCount
        return start until end
    }

    fun hiddenBlocks(blockCount: Int, headings: List<OutlineHeading>, collapsed: Set<Int>): Set<Int> {
        val hidden = HashSet<Int>()
        headings.filter { it.blockIndex in collapsed }.forEach { hidden.addAll(foldedRange(blockCount, headings, it)) }
        return hidden
    }
}
