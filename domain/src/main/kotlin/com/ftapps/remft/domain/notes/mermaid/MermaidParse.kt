package com.ftapps.remft.domain.notes.mermaid

enum class MermaidDirection {
    TopDown,
    BottomUp,
    LeftRight,
    RightLeft;

    val isVertical: Boolean get() = this == TopDown || this == BottomUp
}

enum class MermaidShape { Rect, Round, Stadium, Diamond, Circle }

enum class MermaidEdgeStyle { Solid, Dotted, Thick }

data class MermaidNode(val id: String, val label: String, val shape: MermaidShape)

data class MermaidEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val style: MermaidEdgeStyle = MermaidEdgeStyle.Solid,
    val arrow: Boolean = true,
)

data class MermaidParticipant(val id: String, val label: String)

data class MermaidMessage(
    val from: String,
    val to: String,
    val text: String,
    val dotted: Boolean = false,
    val arrow: Boolean = true,
) {
    val isSelf: Boolean get() = from == to
}

sealed interface MermaidChart

data class MermaidFlow(
    val direction: MermaidDirection,
    val nodes: List<MermaidNode>,
    val edges: List<MermaidEdge>,
) : MermaidChart {
    fun nodeById(id: String): MermaidNode? = nodes.firstOrNull { it.id == id }
}

data class MermaidSequence(
    val participants: List<MermaidParticipant>,
    val messages: List<MermaidMessage>,
) : MermaidChart

data class MermaidUnsupported(val type: String) : MermaidChart

data class MermaidParseError(val reason: String) : MermaidChart

object MermaidParse {
    val knownTypes: Set<String> = setOf(
        "classdiagram",
        "statediagram",
        "statediagram-v2",
        "erdiagram",
        "gantt",
        "pie",
        "journey",
        "gitgraph",
        "mindmap",
        "timeline",
        "quadrantchart",
        "requirementdiagram",
        "c4context",
        "sankey-beta",
        "xychart-beta",
        "block-beta",
    )

    private val whitespace = Regex("\\s+")

    private val nodeRe = Regex(
        "^([A-Za-z0-9_-]+)" +
            "(?:" +
            "\\(\\((.*?)\\)\\)" +
            "|\\(\\[(.*?)\\]\\)" +
            "|\\[(.*?)\\]" +
            "|\\((.*?)\\)" +
            "|\\{(.*?)\\}" +
            ")?$",
    )

    private val edgeRe = Regex("(-\\.->|-\\.-|==>|===|-->|---)(?:\\|(.*?)\\|)?")

    private val participantRe = Regex(
        "^(?:participant|actor)\\s+([A-Za-z0-9_-]+)(?:\\s+as\\s+(.*))?$",
        RegexOption.IGNORE_CASE,
    )

    private val messageRe = Regex(
        "^([A-Za-z0-9_]+)\\s*(-->>|->>|-->|->|--x|-x)\\s*([A-Za-z0-9_]+)\\s*:\\s*(.*)$",
    )

    fun parse(source: String): MermaidChart {
        val lines = meaningfulLines(source)
        if (lines.isEmpty()) return MermaidParseError("empty")

        val header = lines.first().trim()
        val first = header.split(whitespace).first()
        val keyword = first.lowercase()

        return when {
            keyword == "flowchart" || keyword == "graph" -> parseFlow(header, lines.drop(1))
            keyword == "sequencediagram" -> parseSequence(lines.drop(1))
            keyword in knownTypes -> MermaidUnsupported(first)
            else -> MermaidParseError("unknown-type")
        }
    }

    private fun meaningfulLines(source: String): List<String> =
        source.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("%%") }

    private fun direction(header: String): MermaidDirection {
        val parts = header.split(whitespace)
        val token = if (parts.size > 1) parts[1].uppercase() else "TD"
        return when (token) {
            "BT" -> MermaidDirection.BottomUp
            "LR" -> MermaidDirection.LeftRight
            "RL" -> MermaidDirection.RightLeft
            else -> MermaidDirection.TopDown
        }
    }

    private fun parseFlow(header: String, body: List<String>): MermaidChart {
        val nodes = HashMap<String, MermaidNode>()
        val order = ArrayList<String>()
        val edges = ArrayList<MermaidEdge>()

        fun define(raw: String): MermaidNode? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val m = nodeRe.find(text) ?: return null
            val g = m.groups
            val id = g[1]!!.value
            val label = g[2]?.value ?: g[3]?.value ?: g[4]?.value ?: g[5]?.value ?: g[6]?.value
            val shape = when {
                g[2] != null -> MermaidShape.Circle
                g[3] != null -> MermaidShape.Stadium
                g[4] != null -> MermaidShape.Rect
                g[5] != null -> MermaidShape.Round
                g[6] != null -> MermaidShape.Diamond
                else -> MermaidShape.Rect
            }
            val existing = nodes[id]
            if (existing != null) {
                if (existing.label.isEmpty() && !label.isNullOrEmpty()) {
                    nodes[id] = MermaidNode(id, label, shape)
                }
                return nodes[id]
            }
            order.add(id)
            val node = MermaidNode(id, (label ?: id).trim(), shape)
            nodes[id] = node
            return node
        }

        for (line in body) {
            val text = line.trim()
            if (text.isEmpty()) continue
            val lower = text.lowercase()
            if (lower.startsWith("subgraph") ||
                lower == "end" ||
                lower.startsWith("style ") ||
                lower.startsWith("classdef") ||
                lower.startsWith("class ") ||
                lower.startsWith("click ") ||
                lower.startsWith("linkstyle")
            ) {
                continue
            }

            val matches = edgeRe.findAll(text).toList()
            if (matches.isEmpty()) {
                if (define(text) == null) return MermaidParseError("bad-node: $text")
                continue
            }

            var cursor = 0
            var previous: String? = null
            for ((index, m) in matches.withIndex()) {
                val start = m.range.first
                val end = m.range.last + 1
                val node = define(text.substring(cursor, start))
                val fromId = node?.id ?: previous ?: return MermaidParseError("bad-edge: $text")

                val nextStart = if (index + 1 < matches.size) matches[index + 1].range.first else null
                val rightText = if (nextStart == null) text.substring(end) else text.substring(end, nextStart)
                val target = define(rightText) ?: return MermaidParseError("bad-edge: $text")

                val op = m.groupValues[1]
                edges.add(
                    MermaidEdge(
                        from = fromId,
                        to = target.id,
                        label = m.groups[2]?.value?.trim(),
                        style = when {
                            op.startsWith("-.") -> MermaidEdgeStyle.Dotted
                            op.startsWith("=") -> MermaidEdgeStyle.Thick
                            else -> MermaidEdgeStyle.Solid
                        },
                        arrow = op.endsWith(">"),
                    ),
                )
                previous = target.id
                cursor = end
            }
        }

        if (nodes.isEmpty()) return MermaidParseError("no-nodes")
        return MermaidFlow(direction(header), order.map { nodes.getValue(it) }, edges)
    }

    private fun parseSequence(body: List<String>): MermaidChart {
        val participants = HashMap<String, MermaidParticipant>()
        val order = ArrayList<String>()
        val messages = ArrayList<MermaidMessage>()

        fun ensure(id: String, label: String? = null) {
            if (participants.containsKey(id)) {
                if (!label.isNullOrEmpty()) participants[id] = MermaidParticipant(id, label)
                return
            }
            order.add(id)
            participants[id] = MermaidParticipant(id, label ?: id)
        }

        for (line in body) {
            val text = line.trim()
            if (text.isEmpty()) continue

            val p = participantRe.find(text)
            if (p != null) {
                ensure(p.groupValues[1], p.groups[2]?.value?.trim())
                continue
            }

            val m = messageRe.find(text)
            if (m != null) {
                val from = m.groupValues[1]
                val to = m.groupValues[3]
                ensure(from)
                ensure(to)
                val op = m.groupValues[2]
                messages.add(
                    MermaidMessage(
                        from = from,
                        to = to,
                        text = m.groupValues[4].trim(),
                        dotted = op.startsWith("--"),
                        arrow = op.endsWith(">") || op.endsWith("x"),
                    ),
                )
                continue
            }

            val lower = text.lowercase()
            if (lower.startsWith("activate") ||
                lower.startsWith("deactivate") ||
                lower.startsWith("note ") ||
                lower.startsWith("loop") ||
                lower.startsWith("alt") ||
                lower.startsWith("else") ||
                lower.startsWith("opt") ||
                lower.startsWith("par") ||
                lower.startsWith("autonumber") ||
                lower == "end"
            ) {
                continue
            }
            return MermaidParseError("bad-line: $text")
        }

        if (participants.isEmpty()) return MermaidParseError("no-participants")
        return MermaidSequence(order.map { participants.getValue(it) }, messages)
    }
}
