package com.ftapps.remft.domain.notes.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MermaidLayoutTest {
    private val measure = MermaidMeasure { DiagramSize(it.length * 8.0, 16.0) }

    private fun layout(src: String): FlowLayout = FlowLayout.of(MermaidParse.parse(src) as MermaidFlow, measure)

    private fun seq(src: String): SequenceLayout =
        SequenceLayout.of(MermaidParse.parse(src) as MermaidSequence, measure)

    private fun FlowLayout.node(id: String): FlowNodeBox = nodeById(id)!!

    @Test
    fun chainRanksInOrder() {
        val l = layout("flowchart TD\n A --> B --> C")
        assertTrue(l.node("A").rect.top < l.node("B").rect.top)
        assertTrue(l.node("B").rect.top < l.node("C").rect.top)
    }

    @Test
    fun longestPathWins() {
        val l = layout("flowchart TD\n A --> B\n B --> C\n A --> C")
        assertTrue(l.node("C").rect.top > l.node("B").rect.top)
    }

    @Test
    fun cycleStillLaysOut() {
        val l = layout("flowchart TD\n A --> B\n B --> C\n C --> A")
        assertEquals(3, l.nodes.size)
        assertTrue(l.size.width > 0)
        assertTrue(l.size.height > 0)
    }

    @Test
    fun lrVersusTd() {
        val td = layout("flowchart TD\n A --> B")
        val lr = layout("flowchart LR\n A --> B")
        assertTrue(td.node("B").rect.top > td.node("A").rect.top)
        assertTrue(abs(td.node("B").rect.left - td.node("A").rect.left) < 40)
        assertTrue(lr.node("B").rect.left > lr.node("A").rect.left)
        assertTrue(abs(lr.node("B").rect.top - lr.node("A").rect.top) < 40)
        assertTrue(lr.size.width > lr.size.height)
        assertTrue(td.size.height > td.size.width)
    }

    @Test
    fun mirroredDirections() {
        val bt = layout("flowchart BT\n A --> B")
        assertTrue(bt.node("B").rect.top < bt.node("A").rect.top)
        val rl = layout("flowchart RL\n A --> B")
        assertTrue(rl.node("B").rect.left < rl.node("A").rect.left)
    }

    @Test
    fun obviousCrossingIsUntangled() {
        assertEquals(0, layout("flowchart TD\n A --> D\n B --> C").crossings)
    }

    @Test
    fun fixtureHasNoCrossings() {
        val src = """
            flowchart TD
                A[Shared text] --> B{Is AI configured?}
                B -->|yes| C[Bubble + confirm card]
                B -->|no| D[Save to Inbox]
                D --> E([Dialog that says why])
                C --> F[Task]
                E --> F
        """.trimIndent()
        val l = layout(src)
        assertEquals(6, l.nodes.size)
        assertEquals(0, l.crossings)
        assertTrue(abs(l.node("C").rect.left - l.node("D").rect.left) > 1)
    }

    @Test
    fun deterministic() {
        val src = "flowchart TD\n A --> B\n A --> C\n B --> D\n C --> D"
        assertEquals(layout(src), layout(src))
    }

    @Test
    fun nodesNeverOverlap() {
        for (src in listOf(
            "flowchart TD\n A --> B\n A --> C\n A --> D\n B --> E\n C --> E",
            "flowchart LR\n A --> B\n A --> C\n A --> D\n B --> E\n C --> E\n A --> E",
            "flowchart BT\n A((circle)) --> B{diamond}\n A --> C([stadium])\n A --> D(round)",
        )) {
            val l = layout(src)
            for (i in l.nodes.indices) {
                for (j in i + 1 until l.nodes.size) {
                    assertFalse("${l.nodes[i].id} overlaps ${l.nodes[j].id}", l.nodes[i].rect.overlaps(l.nodes[j].rect))
                }
            }
        }
    }

    @Test
    fun nodesInsideSizeAndPositive() {
        for (src in listOf(
            "flowchart LR\n A[a fairly long label] --> B{decision}",
            "flowchart RL\n A --> B --> C\n A --> C",
            "flowchart BT\n A --> B\n B --> A",
        )) {
            val l = layout(src)
            assertTrue(l.size.width > 0 && l.size.height > 0)
            for (node in l.nodes) {
                assertTrue(node.rect.width > 0 && node.rect.height > 0)
                assertTrue(node.rect.left >= 0)
                assertTrue(node.rect.top >= 0)
                assertTrue(node.rect.right <= l.size.width)
                assertTrue(node.rect.bottom <= l.size.height)
            }
        }
    }

    @Test
    fun edgesReferenceExistingNodes() {
        val src = "flowchart TD\n A --> B\n B --> C\n B --> D\n D --> E\n C --> F\n E --> F"
        val l = layout(src)
        assertEquals(6, l.edges.size)
        for (edge in l.edges) {
            assertTrue(edge.points.size >= 2)
            val first = edge.points.first()
            val last = edge.points.last()
            assertTrue(l.nodes.any { it.rect.inflate(1.0).contains(first) })
            assertTrue(l.nodes.any { it.rect.inflate(1.0).contains(last) })
        }
    }

    @Test
    fun diamondRoomierThanRect() {
        val l = layout("flowchart TD\n A[decision] --> B{decision}")
        assertTrue(l.node("B").rect.width > l.node("A").rect.width)
    }

    @Test
    fun edgesTouchBorders() {
        val l = layout("flowchart TD\n A --> B")
        val edge = l.edges.single()
        val a = l.node("A").rect
        val b = l.node("B").rect
        assertNotEquals(a.center, edge.points.first())
        assertTrue(abs(edge.points.first().y - a.bottom) < 1)
        assertTrue(abs(edge.points.last().y - b.top) < 1)
    }

    @Test
    fun edgeLabelHasAnchor() {
        val l = layout("flowchart TD\n A -->|evet| B")
        assertEquals("evet", l.edges.single().label)
        assertNotNull(l.edges.single().labelAnchor)
    }

    @Test
    fun selfLoopRoutes() {
        val l = layout("flowchart TD\n A --> A")
        assertEquals(1, l.edges.size)
        assertTrue(l.edges.single().points.size > 2)
    }

    @Test
    fun spanningEdgeRoutesAround() {
        val l = layout("flowchart TD\n A --> B\n B --> C\n B --> D\n D --> E\n C --> F\n E --> F")
        for (edge in l.edges) {
            for (node in l.nodes) {
                val isEnd = node.rect.inflate(2.0).contains(edge.points.first()) ||
                    node.rect.inflate(2.0).contains(edge.points.last())
                if (isEnd) continue
                assertFalse("an edge runs through ${node.id}", crosses(edge.points, node.rect.inflate(-2.0)))
            }
        }
    }

    @Test
    fun participantsInDeclarationOrder() {
        val l = seq(
            "sequenceDiagram\n  participant U as Extension\n  participant G as App Group\n" +
                "  participant A as App\n  U->>G: one\n",
        )
        assertEquals(listOf("U", "G", "A"), l.participants.map { it.id })
        val xs = l.participants.map { it.lifelineX }
        assertTrue(xs[0] < xs[1] && xs[1] < xs[2])
        for (i in 0 until l.participants.size - 1) {
            assertTrue(l.participants[i].header.right < l.participants[i + 1].header.left)
        }
    }

    @Test
    fun messagesStackDownwardOnLifelines() {
        val l = seq("sequenceDiagram\n A->>B: one\n B-->>A: two\n A->>A: three\n B->>A: four\n")
        assertEquals(4, l.messages.size)
        for (i in 1 until l.messages.size) {
            assertTrue(l.messages[i].points.first().y > l.messages[i - 1].points.last().y)
        }
        val a = l.byId("A")!!
        val b = l.byId("B")!!
        assertEquals(a.lifelineX, l.messages[0].points.first().x, 0.0)
        assertEquals(b.lifelineX, l.messages[0].points.last().x, 0.0)
        assertEquals(b.lifelineX, l.messages[1].points.first().x, 0.0)
        assertTrue(l.messages[1].dotted)
    }

    @Test
    fun selfMessageLoops() {
        val m = seq("sequenceDiagram\n A->>A: to itself\n").messages.single()
        assertTrue(m.isSelf)
        assertEquals(4, m.points.size)
        assertTrue(m.points[1].x > m.points[0].x)
    }

    @Test
    fun longSelfCaptionWidensColumn() {
        val narrow = seq("sequenceDiagram\n A->>A: short\n")
        val wide = seq("sequenceDiagram\n A->>A: quite a long message text right here\n B->>B: x\n")
        assertTrue(wide.size.width > narrow.size.width)
    }

    @Test
    fun longMessageTextFitsBetweenItsLifelinesAndInsideTheDiagram() {
        val text = "a rather long message that is much wider than one column"
        val l = seq("sequenceDiagram\n A->>B: $text\n B->>C: ok\n")
        val m = l.messages.first()
        val half = measure.measure(text).width / 2
        assertTrue(m.textAnchor.x - half >= l.byId("A")!!.lifelineX)
        assertTrue(m.textAnchor.x + half <= l.byId("B")!!.lifelineX)
        assertTrue(m.textAnchor.x + half <= l.size.width)
    }

    @Test
    fun selfMessageOnTheLastColumnStaysInsideTheDiagram() {
        val text = "checks its own cache"
        val l = seq("sequenceDiagram\n A->>B: hi\n B->>B: $text\n")
        val m = l.messages.last()
        assertTrue(m.textAnchor.x + measure.measure(text).width <= l.size.width)
    }

    @Test
    fun lifelinesRunPastLastMessage() {
        val l = seq("sequenceDiagram\n A->>B: one\n")
        assertTrue(l.participants.first().lifelineBottom > l.messages.single().points.first().y)
        assertTrue(l.size.height >= l.participants.first().lifelineBottom)
    }

    @Test
    fun sequenceDeterministic() {
        val src = "sequenceDiagram\n A->>B: one\n B->>C: two\n C-->>A: three\n"
        assertEquals(seq(src), seq(src))
    }

    private fun DiagramRect.inflate(delta: Double) = DiagramRect(left - delta, top - delta, right + delta, bottom + delta)

    private fun DiagramRect.contains(p: DiagramPoint) = p.x >= left && p.x < right && p.y >= top && p.y < bottom

    private fun crosses(points: List<DiagramPoint>, rect: DiagramRect): Boolean {
        for (i in 0 until points.size - 1) {
            for (s in 1 until 24) {
                val t = s / 24.0
                val p = DiagramPoint(
                    points[i].x + (points[i + 1].x - points[i].x) * t,
                    points[i].y + (points[i + 1].y - points[i].y) * t,
                )
                if (rect.contains(p)) return true
            }
        }
        return false
    }
}
