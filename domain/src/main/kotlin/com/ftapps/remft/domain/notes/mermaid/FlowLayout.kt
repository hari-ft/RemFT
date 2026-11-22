package com.ftapps.remft.domain.notes.mermaid

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FlowNodeBox(
    val id: String,
    val label: String,
    val shape: MermaidShape,
    val rect: DiagramRect,
)

data class FlowEdgePath(
    val points: List<DiagramPoint>,
    val style: MermaidEdgeStyle,
    val arrow: Boolean,
    val label: String? = null,
    val labelAnchor: DiagramPoint? = null,
)

data class FlowLayout(
    val nodes: List<FlowNodeBox>,
    val edges: List<FlowEdgePath>,
    val size: DiagramSize,
    val crossings: Int,
) {
    fun nodeById(id: String): FlowNodeBox? = nodes.firstOrNull { it.id == id }

    companion object {
        const val NODE_PAD_H = 14.0
        const val NODE_PAD_V = 10.0
        const val MIN_NODE_WIDTH = 48.0
        const val RANK_GAP = 56.0
        const val NODE_GAP = 28.0
        const val MARGIN = 12.0

        fun of(flow: MermaidFlow, measure: MermaidMeasure): FlowLayout {
            val sizes = LinkedHashMap<String, DiagramSize>()
            for (node in flow.nodes) sizes[node.id] = nodeSize(node, measure)

            val ranks = assignRanks(flow)
            val chains = chainSpanningEdges(flow, ranks, sizes)
            val layers = layers(flow, ranks, chains.virtualRanks)
            val crossings = order(layers, chains.adjacency)

            val positions = positions(flow, layers, sizes)
            val bounds = bounds(positions, sizes, flow)

            val boxes = flow.nodes.map { node ->
                val p = positions.getValue(node.id)
                val s = sizes.getValue(node.id)
                FlowNodeBox(
                    id = node.id,
                    label = node.label,
                    shape = node.shape,
                    rect = DiagramRect.fromLTWH(p.x - bounds.left, p.y - bounds.top, s.width, s.height),
                )
            }

            val byId = boxes.associateBy { it.id }
            val waypoints = LinkedHashMap<String, DiagramPoint>()
            for (id in chains.virtualRanks.keys) {
                val p = positions[id] ?: continue
                waypoints[id] = DiagramPoint(p.x - bounds.left, p.y - bounds.top)
            }

            val paths = ArrayList<FlowEdgePath>()
            flow.edges.forEachIndexed { i, edge ->
                val from = byId[edge.from]
                val to = byId[edge.to]
                if (from != null && to != null) {
                    val hops = (chains.edgeChains[i] ?: emptyList()).mapNotNull { waypoints[it] }
                    paths.add(route(edge, from, to, hops))
                }
            }

            return FlowLayout(
                nodes = boxes,
                edges = paths,
                size = DiagramSize(bounds.width, bounds.height),
                crossings = crossings,
            )
        }

        private fun assignRanks(flow: MermaidFlow): Map<String, Int> {
            val outgoing = HashMap<String, MutableList<String>>()
            val indegree = LinkedHashMap<String, Int>()
            for (n in flow.nodes) indegree[n.id] = 0
            for (e in flow.edges) {
                if (e.from == e.to) continue
                outgoing.getOrPut(e.from) { ArrayList() }.add(e.to)
                indegree[e.to] = (indegree[e.to] ?: 0) + 1
            }

            val rank = LinkedHashMap<String, Int>()
            for (n in flow.nodes) rank[n.id] = 0
            val queue = ArrayList<String>()
            for (n in flow.nodes) if ((indegree[n.id] ?: 0) == 0) queue.add(n.id)
            val seen = HashSet<String>(queue)
            val remaining = HashMap(indegree)

            var head = 0
            while (head < queue.size) {
                val id = queue[head++]
                for (next in outgoing[id] ?: emptyList()) {
                    rank[next] = max(rank[next] ?: 0, (rank[id] ?: 0) + 1)
                    val left = (remaining[next] ?: 1) - 1
                    remaining[next] = left
                    if (left == 0 && seen.add(next)) queue.add(next)
                }
            }

            for (n in flow.nodes) {
                if (n.id in seen) continue
                var best = 0
                for (e in flow.edges) {
                    if (e.to == n.id && e.from in seen) best = max(best, (rank[e.from] ?: 0) + 1)
                }
                rank[n.id] = best
            }
            return rank
        }

        private class Chains(
            val virtualRanks: Map<String, Int>,
            val edgeChains: Map<Int, List<String>>,
            val adjacency: Map<String, List<String>>,
        )

        private fun chainSpanningEdges(
            flow: MermaidFlow,
            ranks: Map<String, Int>,
            sizes: MutableMap<String, DiagramSize>,
        ): Chains {
            val virtualRanks = LinkedHashMap<String, Int>()
            val edgeChains = HashMap<Int, List<String>>()
            val adjacency = LinkedHashMap<String, MutableList<String>>()

            fun link(from: String, to: String) {
                adjacency.getOrPut(from) { ArrayList() }.add(to)
            }

            flow.edges.forEachIndexed { i, edge ->
                if (edge.from == edge.to) return@forEachIndexed
                val a = ranks[edge.from] ?: return@forEachIndexed
                val b = ranks[edge.to] ?: return@forEachIndexed
                if (abs(b - a) <= 1) {
                    link(edge.from, edge.to)
                    return@forEachIndexed
                }
                val step = if (b > a) 1 else -1
                val chain = ArrayList<String>()
                var previous = edge.from
                var rank = a + step
                while (rank != b) {
                    val id = "\u0000v$i@$rank"
                    virtualRanks[id] = rank
                    sizes[id] = DiagramSize.Zero
                    chain.add(id)
                    link(previous, id)
                    previous = id
                    rank += step
                }
                link(previous, edge.to)
                edgeChains[i] = chain
            }
            return Chains(virtualRanks, edgeChains, adjacency)
        }

        private fun layers(
            flow: MermaidFlow,
            ranks: Map<String, Int>,
            virtualRanks: Map<String, Int>,
        ): MutableList<MutableList<String>> {
            val maxRank = (ranks.values + virtualRanks.values).fold(0) { a, b -> max(a, b) }
            val layers = MutableList(maxRank + 1) { ArrayList<String>() as MutableList<String> }
            for (node in flow.nodes) layers[ranks.getValue(node.id)].add(node.id)
            for ((id, r) in virtualRanks) layers[r].add(id)
            return layers
        }

        private fun order(layers: MutableList<MutableList<String>>, adjacency: Map<String, List<String>>): Int {
            val up = HashMap<String, MutableList<String>>()
            val down = HashMap<String, MutableList<String>>()
            for ((from, tos) in adjacency) {
                for (to in tos) {
                    down.getOrPut(from) { ArrayList() }.add(to)
                    up.getOrPut(to) { ArrayList() }.add(from)
                }
            }

            var best = countCrossings(layers, down)
            var bestSnapshot = layers.map { it.toList() }

            for (sweep in 0 until 4) {
                val forward = sweep % 2 == 0
                val indices = if (forward) layers.indices.toList() else layers.indices.reversed().toList()
                for (i in indices) {
                    val neighbours = if (forward) up else down
                    val reference = if (forward) i - 1 else i + 1
                    if (reference < 0 || reference >= layers.size) continue

                    val positionInReference = HashMap<String, Int>()
                    layers[reference].forEachIndexed { k, id -> positionInReference[id] = k }

                    val current = layers[i]
                    val keyed = current.mapIndexed { k, id ->
                        val known = (neighbours[id] ?: emptyList()).mapNotNull { positionInReference[it] }
                        val key = if (known.isEmpty()) k.toDouble() else known.sum().toDouble() / known.size
                        Triple(id, key, k)
                    }.sortedWith(compareBy<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
                    layers[i] = keyed.map { it.first }.toMutableList()
                }

                val crossings = countCrossings(layers, down)
                if (crossings < best) {
                    best = crossings
                    bestSnapshot = layers.map { it.toList() }
                }
            }

            for (i in layers.indices) layers[i] = bestSnapshot[i].toMutableList()
            return best
        }

        private fun countCrossings(layers: List<List<String>>, down: Map<String, List<String>>): Int {
            var total = 0
            for (i in 0 until layers.size - 1) {
                val upper = HashMap<String, Int>()
                layers[i].forEachIndexed { k, id -> upper[id] = k }
                val lower = HashMap<String, Int>()
                layers[i + 1].forEachIndexed { k, id -> lower[id] = k }

                val pairs = ArrayList<IntArray>()
                for (from in layers[i]) {
                    for (to in down[from] ?: emptyList()) {
                        val l = lower[to] ?: continue
                        pairs.add(intArrayOf(upper.getValue(from), l))
                    }
                }
                for (a in pairs.indices) {
                    for (b in a + 1 until pairs.size) {
                        if ((pairs[a][0] - pairs[b][0]) * (pairs[a][1] - pairs[b][1]) < 0) total++
                    }
                }
            }
            return total
        }

        private fun positions(
            flow: MermaidFlow,
            layers: List<List<String>>,
            sizes: Map<String, DiagramSize>,
        ): MutableMap<String, DiagramPoint> {
            val vertical = flow.direction.isVertical
            val positions = LinkedHashMap<String, DiagramPoint>()

            val rankOffsets = ArrayList<Double>()
            var cursor = 0.0
            for (layer in layers) {
                rankOffsets.add(cursor)
                val extent = layer.fold(0.0) { a, id ->
                    val s = sizes.getValue(id)
                    max(a, if (vertical) s.height else s.width)
                }
                cursor += extent + RANK_GAP
            }

            val layerExtents = layers.map { layer ->
                layer.fold(0.0) { a, id ->
                    val s = sizes.getValue(id)
                    a + if (vertical) s.width else s.height
                } + NODE_GAP * max(0, layer.size - 1)
            }
            val widest = layerExtents.fold(0.0) { a, b -> max(a, b) }

            for (i in layers.indices) {
                var across = (widest - layerExtents[i]) / 2
                for (id in layers[i]) {
                    val size = sizes.getValue(id)
                    positions[id] = if (vertical) DiagramPoint(across, rankOffsets[i]) else DiagramPoint(rankOffsets[i], across)
                    across += (if (vertical) size.width else size.height) + NODE_GAP
                }
            }

            if (flow.direction == MermaidDirection.BottomUp || flow.direction == MermaidDirection.RightLeft) {
                val total = cursor - RANK_GAP
                for ((id, p) in positions.entries.toList()) {
                    val size = sizes.getValue(id)
                    positions[id] = if (vertical) {
                        DiagramPoint(p.x, total - p.y - size.height)
                    } else {
                        DiagramPoint(total - p.x - size.width, p.y)
                    }
                }
            }
            return positions
        }

        private fun bounds(
            positions: Map<String, DiagramPoint>,
            sizes: Map<String, DiagramSize>,
            flow: MermaidFlow,
        ): DiagramRect {
            val real = flow.nodes.mapTo(HashSet()) { it.id }
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for ((id, p) in positions) {
                if (id !in real) continue
                val size = sizes.getValue(id)
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x + size.width)
                maxY = max(maxY, p.y + size.height)
            }
            if (minX == Double.POSITIVE_INFINITY) return DiagramRect.Zero
            return DiagramRect(minX - MARGIN, minY - MARGIN, maxX + MARGIN, maxY + MARGIN)
        }

        private fun route(
            edge: MermaidEdge,
            from: FlowNodeBox,
            to: FlowNodeBox,
            waypoints: List<DiagramPoint>,
        ): FlowEdgePath {
            if (from.id == to.id) {
                val r = from.rect
                val c = r.center
                return FlowEdgePath(
                    points = listOf(
                        DiagramPoint(r.right, c.y),
                        DiagramPoint(r.right + 34, c.y),
                        DiagramPoint(r.right + 34, r.top - 18),
                        DiagramPoint(c.x, r.top - 18),
                        DiagramPoint(c.x, r.top),
                    ),
                    style = edge.style,
                    arrow = edge.arrow,
                    label = edge.label,
                    labelAnchor = DiagramPoint(r.right + 40, r.top - 24),
                )
            }

            val firstTarget = if (waypoints.isEmpty()) to.rect.center else waypoints.first()
            val lastSource = if (waypoints.isEmpty()) from.rect.center else waypoints.last()
            val start = anchor(from.rect, firstTarget)
            val end = anchor(to.rect, lastSource)

            val points = ArrayList<DiagramPoint>(waypoints.size + 2)
            points.add(start)
            points.addAll(waypoints)
            points.add(end)
            val mid = if (waypoints.isNotEmpty()) {
                waypoints[waypoints.size / 2]
            } else {
                DiagramPoint((start.x + end.x) / 2, (start.y + end.y) / 2)
            }

            return FlowEdgePath(
                points = points,
                style = edge.style,
                arrow = edge.arrow,
                label = edge.label,
                labelAnchor = mid,
            )
        }

        private fun anchor(rect: DiagramRect, target: DiagramPoint): DiagramPoint {
            val c = rect.center
            val dx = target.x - c.x
            val dy = target.y - c.y
            if (dx == 0.0 && dy == 0.0) return c
            val halfW = rect.width / 2
            val halfH = rect.height / 2
            val scale = min(
                if (dx == 0.0) Double.POSITIVE_INFINITY else halfW / abs(dx),
                if (dy == 0.0) Double.POSITIVE_INFINITY else halfH / abs(dy),
            )
            return DiagramPoint(c.x + dx * scale, c.y + dy * scale)
        }

        private fun nodeSize(node: MermaidNode, measure: MermaidMeasure): DiagramSize {
            val text = measure.measure(node.label)
            var w = max(text.width + NODE_PAD_H * 2, MIN_NODE_WIDTH)
            var h = text.height + NODE_PAD_V * 2
            when (node.shape) {
                MermaidShape.Diamond -> {
                    w *= 1.45
                    h *= 1.7
                }
                MermaidShape.Circle -> {
                    val side = max(w, h) * 1.15
                    w = side
                    h = side
                }
                MermaidShape.Stadium, MermaidShape.Round -> w += NODE_PAD_H
                MermaidShape.Rect -> Unit
            }
            return DiagramSize(w, h)
        }
    }
}
