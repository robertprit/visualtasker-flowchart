/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowSemanticKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class FlowchartUiConfigTest {
    @Test public fun `public host is semantically read only`() { assertFalse(FlowchartUiConfig().semanticEditingEnabled) }
    @Test public fun `semantic editing cannot be enabled`() { assertThrows(IllegalArgumentException::class.java) { FlowchartUiConfig(semanticEditingEnabled = true) } }

    @Test public fun `legacy rendering remains default when no provider exists`() {
        val node = FlowGraphNode(FlowNodeId("node"), FlowSemanticKind(FlowNodeKind.ACTION), "Action")

        assertNull(resolveNodeShape(null, node, 160f, 72f))
    }

    @Test public fun `provider-selected node shape is returned without graph mutation`() {
        val path = Path()
        val node = FlowGraphNode(FlowNodeId("node"), FlowSemanticKind(FlowNodeKind.DECISION), "Decision")
        val before = node.copy()
        val provider = FlowchartNodeShapeProvider { suppliedNode, width, height ->
            assertSame(node, suppliedNode)
            assertEquals(160f, width)
            assertEquals(72f, height)
            path
        }

        assertSame(path, resolveNodeShape(provider, node, 160f, 72f))
        assertEquals(before, node)
    }

    @Test public fun `provider supports every standard node kind without semantic mutation`() {
        val seen = mutableListOf<FlowNodeKind>()
        val path = Path()
        val provider = FlowchartNodeShapeProvider { node, _, _ ->
            seen.add(requireNotNull(node.kind.standard))
            path
        }
        val nodes = FlowNodeKind.entries.map { kind ->
            FlowGraphNode(FlowNodeId(kind.name), FlowSemanticKind(kind), kind.name)
        }
        val before = nodes.map(FlowGraphNode::copy)

        nodes.forEach { node -> assertSame(path, resolveNodeShape(provider, node, 160f, 72f)) }

        assertEquals(FlowNodeKind.entries, seen)
        assertEquals(before, nodes)
    }

    @Test public fun `missing provider shape explicitly falls back`() {
        val node = FlowGraphNode(FlowNodeId("node"), FlowSemanticKind(FlowNodeKind.ACTION), "Action")
        val provider = FlowchartNodeShapeProvider { _, _, _ -> null }

        assertNull(resolveNodeShape(provider, node, 160f, 72f))
    }

    @Test public fun `shape tokens reject non-finite and non-positive drawing dimensions`() {
        assertThrows(IllegalArgumentException::class.java) { FlowchartShapeTokens(edgeStrokeWidthDp = 0f) }
        assertThrows(IllegalArgumentException::class.java) { FlowchartShapeTokens(connectorRadiusDp = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { FlowchartShapeTokens(arrowLengthDp = -1f) }
    }

    @Test
    public fun `arrow head follows final routed segment without changing route`() {
        val route = listOf(Offset(10f, 10f), Offset(40f, 10f), Offset(40f, 50f))

        val arrow = flowArrowHead(route, length = 10.0, width = 8.0)

        assertEquals(3, arrow.size)
        assertEquals(40.0, arrow[0].x, 0.0)
        assertEquals(50.0, arrow[0].y, 0.0)
        assertEquals(36.0, arrow[1].x, 0.0)
        assertEquals(40.0, arrow[1].y, 0.0)
        assertEquals(44.0, arrow[2].x, 0.0)
        assertEquals(40.0, arrow[2].y, 0.0)
        assertEquals(listOf(Offset(10f, 10f), Offset(40f, 10f), Offset(40f, 50f)), route)
    }

    @Test
    public fun `arrow head rejects missing and degenerate routes`() {
        assertTrue(flowArrowHead(emptyList(), 10.0, 8.0).isEmpty())
        assertTrue(flowArrowHead(listOf(Offset.Zero, Offset.Zero), 10.0, 8.0).isEmpty())
        assertTrue(flowArrowHead(listOf(Offset.Zero, Offset(1f, 1f)), Double.NaN, 8.0).isEmpty())
    }

    @Test
    public fun `edge visual categories exhaust every supported edge kind`() {
        val categories = FlowEdgeKind.entries.associateWith(::flowEdgeVisualCategory)

        assertEquals(FlowEdgeKind.entries.size, categories.size)
        assertEquals(FlowchartEdgeVisualCategory.BRANCH, categories.getValue(FlowEdgeKind.TRUE_BRANCH))
        assertEquals(FlowchartEdgeVisualCategory.LOOP, categories.getValue(FlowEdgeKind.LOOP_BACK))
        assertEquals(FlowchartEdgeVisualCategory.ERROR, categories.getValue(FlowEdgeKind.ERROR))
        assertEquals(FlowchartEdgeVisualCategory.DEFAULT, categories.getValue(FlowEdgeKind.SEQUENCE))
    }

    @Test
    public fun `connector remains at routed source for forward loop and reverse paths`() {
        listOf(
            listOf(Offset(0f, 0f), Offset(40f, 0f)),
            listOf(Offset(0f, 0f), Offset(30f, 0f), Offset(30f, 40f), Offset(0f, 40f)),
            listOf(Offset(40f, 40f), Offset(10f, 40f), Offset(10f, 5f)),
        ).forEach { route ->
            val presentation = flowEdgePresentation(route, arrowLength = 10.0, arrowWidth = 8.0)
            assertEquals(route.first(), presentation.connector)
            assertEquals(route.last().x.toDouble(), presentation.arrowHead.first().x, 0.0)
            assertEquals(route.last().y.toDouble(), presentation.arrowHead.first().y, 0.0)
        }
    }

    @Test
    public fun `edge presentation is deterministic and leaves its route unchanged`() {
        val route = listOf(Offset.Zero, Offset(30f, 0f), Offset(30f, 20f))
        val before = route.toList()

        val first = flowEdgePresentation(route, arrowLength = 10.0, arrowWidth = 8.0)
        val second = flowEdgePresentation(route, arrowLength = 10.0, arrowWidth = 8.0)

        assertEquals(first, second)
        assertEquals(before, route)
    }

    @Test
    public fun `arrow orientation covers horizontal vertical diagonal and reverse segments`() {
        val routes = listOf(
            listOf(Offset.Zero, Offset(20f, 0f)) to Offset(20f, 0f),
            listOf(Offset.Zero, Offset(0f, 20f)) to Offset(0f, 20f),
            listOf(Offset.Zero, Offset(20f, 20f)) to Offset(20f, 20f),
            listOf(Offset(20f, 0f), Offset.Zero) to Offset.Zero,
        )

        routes.forEach { (route, tip) ->
            val arrow = flowArrowHead(route, 10.0, 8.0)
            assertEquals(tip.x.toDouble(), arrow.first().x, 0.0)
            assertEquals(tip.y.toDouble(), arrow.first().y, 0.0)
        }
    }

    @Test
    public fun `zero-length final segment uses prior direction and short segment is bounded`() {
        val repeatedTip = flowArrowHead(
            listOf(Offset.Zero, Offset(10f, 0f), Offset(10f, 0f)),
            length = 8.0,
            width = 6.0,
        )
        val short = flowArrowHead(listOf(Offset.Zero, Offset(2f, 0f)), length = 10.0, width = 8.0)

        assertEquals(2.0, repeatedTip[1].x, 0.0)
        assertTrue(short.drop(1).all { point -> point.x >= 0.0 })
        assertTrue(short.drop(1).all { point -> kotlin.math.abs(point.y) <= 1.0 })
    }
}
