package com.ftapps.remft.domain.notes.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidParseTest {
    private val fixtureFlow = """
        flowchart TD
            A[Shared text] --> B{Is AI configured?}
            B -->|yes| C[Bubble + confirm card]
            B -->|no| D[Save to Inbox]
            D --> E([Dialog that says why])
            C --> F[Task]
            E --> F
    """.trimIndent()

    private fun flow(src: String): MermaidFlow = MermaidParse.parse(src) as MermaidFlow

    private fun seq(src: String): MermaidSequence = MermaidParse.parse(src) as MermaidSequence

    @Test
    fun readsFixtureFlowchart() {
        val f = flow(fixtureFlow)
        assertEquals(MermaidDirection.TopDown, f.direction)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), f.nodes.map { it.id })
        assertEquals(MermaidShape.Diamond, f.nodeById("B")!!.shape)
        assertEquals(MermaidShape.Stadium, f.nodeById("E")!!.shape)
        assertEquals(MermaidShape.Rect, f.nodeById("A")!!.shape)
        assertEquals("Shared text", f.nodeById("A")!!.label)
        assertEquals(6, f.edges.size)
        assertEquals(listOf("yes", "no"), f.edges.mapNotNull { it.label })
        assertEquals(2, f.edges.count { it.to == "F" })
    }

    @Test
    fun allShapes() {
        val f = flow("flowchart TD\n A[r] --> B(o)\n C([s]) --> D{d}\n E((c)) --> F")
        assertEquals(MermaidShape.Rect, f.nodeById("A")!!.shape)
        assertEquals(MermaidShape.Round, f.nodeById("B")!!.shape)
        assertEquals(MermaidShape.Stadium, f.nodeById("C")!!.shape)
        assertEquals(MermaidShape.Diamond, f.nodeById("D")!!.shape)
        assertEquals(MermaidShape.Circle, f.nodeById("E")!!.shape)
        assertEquals(MermaidShape.Rect, f.nodeById("F")!!.shape)
        assertEquals("F", f.nodeById("F")!!.label)
        assertEquals("c", f.nodeById("E")!!.label)
    }

    @Test
    fun everyDirectionToken() {
        fun dir(d: String) = flow("flowchart $d\n  A --> B").direction
        assertEquals(MermaidDirection.TopDown, dir("TD"))
        assertEquals(MermaidDirection.TopDown, dir("TB"))
        assertEquals(MermaidDirection.LeftRight, dir("LR"))
        assertEquals(MermaidDirection.RightLeft, dir("RL"))
        assertEquals(MermaidDirection.BottomUp, dir("BT"))
        assertEquals(MermaidDirection.TopDown, flow("flowchart\n A --> B").direction)
    }

    @Test
    fun graphIsFlowchart() {
        assertTrue(MermaidParse.parse("graph LR\n  A --> B") is MermaidFlow)
    }

    @Test
    fun edgeOperators() {
        val f = flow("flowchart LR\n A --> B\n B --- C\n C -.-> D\n D ==> E\n E -.- F\n F === G")
        assertEquals(MermaidEdgeStyle.Solid, f.edges[0].style)
        assertTrue(f.edges[0].arrow)
        assertFalse(f.edges[1].arrow)
        assertEquals(MermaidEdgeStyle.Dotted, f.edges[2].style)
        assertTrue(f.edges[2].arrow)
        assertEquals(MermaidEdgeStyle.Thick, f.edges[3].style)
        assertEquals(MermaidEdgeStyle.Dotted, f.edges[4].style)
        assertFalse(f.edges[4].arrow)
        assertEquals(MermaidEdgeStyle.Thick, f.edges[5].style)
        assertFalse(f.edges[5].arrow)
    }

    @Test
    fun chainBecomesOneEdgePerHop() {
        assertEquals(listOf("AB", "BC"), flow("flowchart LR\n  A --> B --> C").edges.map { it.from + it.to })
    }

    @Test
    fun bareMentionKeepsLabel() {
        assertEquals("Long name", flow("flowchart TD\n A[Long name] --> B\n A --> C").nodeById("A")!!.label)
    }

    @Test
    fun windowsLineEndings() {
        val f = flow("flowchart TD\r\n A --> B\r\n")
        assertEquals(listOf("A", "B"), f.nodes.map { it.id })
    }

    @Test
    fun unmodelledStatementsAreSkipped() {
        val f = flow("flowchart TD\n  %% comment\n  subgraph one\n  A --> B\n  end\n  style A fill:#f00\n")
        assertEquals(1, f.edges.size)
    }

    @Test
    fun readsFixtureSequence() {
        val s = seq(
            """
            sequenceDiagram
                participant U as Extension
                participant G as App Group
                actor A as App
                U->>G: didSelectPost() writes payload
                U-->>A: local notification (NO text)
                A->>G: read on every resume
                G-->>A: payload
                A->>A: read-and-delete
            """.trimIndent(),
        )
        assertEquals(listOf("U", "G", "A"), s.participants.map { it.id })
        assertEquals("Extension", s.participants.first().label)
        assertEquals(5, s.messages.size)
        assertTrue(s.messages[1].dotted)
        assertFalse(s.messages[0].dotted)
        assertTrue(s.messages.last().isSelf)
        assertEquals("read-and-delete", s.messages.last().text)
    }

    @Test
    fun participantFromMessageOnly() {
        val s = seq("sequenceDiagram\n  A->>B: hello")
        assertEquals(listOf("A", "B"), s.participants.map { it.id })
        assertEquals("A", s.participants.first().label)
    }

    @Test
    fun messageOperators() {
        val s = seq("sequenceDiagram\n A->B: a\n A-->B: b\n A-xB: c\n A--xB: d")
        assertTrue(s.messages[0].arrow)
        assertFalse(s.messages[0].dotted)
        assertTrue(s.messages[1].dotted)
        assertTrue(s.messages[2].arrow)
        assertTrue(s.messages[3].dotted)
    }

    @Test
    fun unmodelledSequenceBlocksAreSkipped() {
        val s = seq(
            "sequenceDiagram\n  autonumber\n  A->>B: one\n  activate B\n  Note right of B: a note\n" +
                "  loop every day\n  B-->>A: two\n  end\n",
        )
        assertEquals(2, s.messages.size)
    }

    @Test
    fun badSequenceLineFails() {
        assertTrue(MermaidParse.parse("sequenceDiagram\n  this is nonsense") is MermaidParseError)
    }

    @Test
    fun knownTypeNamesItself() {
        val result = MermaidParse.parse("gantt\n  title Not drawn\n  section One\n")
        assertEquals(MermaidUnsupported("gantt"), result)
    }

    @Test
    fun knownTypesAreDeclined() {
        for (type in listOf("pie", "classDiagram", "stateDiagram-v2", "journey", "erDiagram", "mindmap")) {
            val result = MermaidParse.parse("$type\n  x\n")
            assertEquals(type, MermaidUnsupported(type), result)
        }
    }

    @Test
    fun brokenFlowchartFails() {
        val src = "flowchart TD\n    A[Unclosed square bracket --> B\n    B -->\n"
        assertTrue(MermaidParse.parse(src) is MermaidParseError)
    }

    @Test
    fun emptyFails() {
        assertTrue(MermaidParse.parse("   \n\n") is MermaidParseError)
        assertTrue(MermaidParse.parse("") is MermaidParseError)
        assertTrue(MermaidParse.parse("flowchart TD\n") is MermaidParseError)
        assertTrue(MermaidParse.parse("sequenceDiagram") is MermaidParseError)
    }

    @Test
    fun unknownKeywordFails() {
        assertTrue(MermaidParse.parse("catDiagram\n A --> B") is MermaidParseError)
    }
}
