/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.IntSize
import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowNodeView
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowPoint
import de.visualtasker.flowchart.domain.FlowSemanticKind
import de.visualtasker.flowchart.domain.FlowSize
import de.visualtasker.flowchart.domain.FlowSurfaceId
import de.visualtasker.flowchart.domain.FlowViewDocument
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

    @Test public fun `grid and iconbar labels are explicit host presentation contract`() {
        val config = FlowchartUiConfig()

        assertTrue(config.gridEnabled)
        assertFalse(config.soundEffectsEnabled)
        assertFalse(config.hapticFeedbackEnabled)
        assertTrue(config.colorTokens.gridDot.alpha > 0f)
        assertEquals(48f, FlowchartToolbarTouchTargetDp.value, 0f)
        assertTrue(config.accessibilityLabels.undoView.isNotBlank())
        assertTrue(config.accessibilityLabels.redoView.isNotBlank())
        assertTrue(config.accessibilityLabels.toggleGrid.isNotBlank())
    }

    @Test public fun `default visual tokens avoid white nodes on white background`() {
        val tokens = FlowchartColorTokens()
        val fills = listOf(
            tokens.triggerNodeFill,
            tokens.actionNodeFill,
            tokens.decisionNodeFill,
            tokens.ioNodeFill,
            tokens.unknownNodeFill,
        )

        fills.forEach { fill ->
            assertFalse(fill == Color.White && tokens.background == Color.White)
            assertTrue(contrastRatio(fill, tokens.nodeStroke) >= 3.0)
            assertTrue(colorDistance(fill, tokens.background) > 0.03)
        }
        assertTrue(contrastRatio(tokens.edge, tokens.background) >= 3.0)
        assertTrue(contrastRatio(tokens.errorEdge, tokens.background) >= 3.0)
        assertTrue(tokens.gridDot.alpha in 0.10f..0.50f)
    }

    @Test public fun `node fill varies by semantic node kind`() {
        val config = FlowchartUiConfig(
            colorTokens = FlowchartColorTokens(
                triggerNodeFill = Color.Red,
                actionNodeFill = Color.Green,
                decisionNodeFill = Color.Blue,
                ioNodeFill = Color.Yellow,
                unknownNodeFill = Color.Magenta,
            ),
        )

        assertEquals(Color.Red, flowNodeFill(FlowGraphNode(FlowNodeId("entry"), FlowSemanticKind(FlowNodeKind.ENTRY), "Entry"), config))
        assertEquals(Color.Green, flowNodeFill(FlowGraphNode(FlowNodeId("action"), FlowSemanticKind(FlowNodeKind.ACTION), "Action"), config))
        assertEquals(Color.Blue, flowNodeFill(FlowGraphNode(FlowNodeId("decision"), FlowSemanticKind(FlowNodeKind.DECISION), "Decision"), config))
        assertEquals(Color.Yellow, flowNodeFill(FlowGraphNode(FlowNodeId("input"), FlowSemanticKind(FlowNodeKind.INPUT), "Input"), config))
        assertEquals(Color.Magenta, flowNodeFill(FlowGraphNode(FlowNodeId("unknown"), FlowSemanticKind(FlowNodeKind.UNKNOWN_SOURCE), "Unknown"), config))
    }

    @Test public fun `initial viewport fit centers all nodes inside host bounds`() {
        val fitted = fitFlowViewToViewport(
            view = FlowViewDocument(
                documentId = FlowDocumentId("doc"),
                compatibleDocumentRevision = FlowDocumentRevision("1"),
                surfaceId = FlowSurfaceId("surface"),
                nodeViews = listOf(
                    FlowNodeView(FlowNodeId("n1"), FlowPoint(0.0, 0.0), FlowSize(160.0, 72.0)),
                    FlowNodeView(FlowNodeId("n2"), FlowPoint(0.0, 440.0), FlowSize(160.0, 72.0)),
                ),
            ),
            hostSize = IntSize(360, 640),
        )

        assertTrue(fitted.viewport.zoom > 0.0)
        fitted.nodeViews.forEach { node ->
            val size = requireNotNull(node.size)
            val left = node.position.x * fitted.viewport.zoom + fitted.viewport.pan.x
            val top = node.position.y * fitted.viewport.zoom + fitted.viewport.pan.y
            val right = (node.position.x + size.width) * fitted.viewport.zoom + fitted.viewport.pan.x
            val bottom = (node.position.y + size.height) * fitted.viewport.zoom + fitted.viewport.pan.y
            assertTrue(left >= 0.0)
            assertTrue(top >= 0.0)
            assertTrue(right <= 360.0)
            assertTrue(bottom <= 640.0)
        }
    }

    @Test public fun `visibility check rejects nodes panned completely outside a small panel`() {
        val view = FlowViewDocument(
            documentId = FlowDocumentId("doc"),
            compatibleDocumentRevision = FlowDocumentRevision("1"),
            surfaceId = FlowSurfaceId("surface"),
            viewport = de.visualtasker.flowchart.domain.FlowViewport(FlowPoint(-2000.0, -2000.0), 1.0),
            nodeViews = listOf(
                FlowNodeView(FlowNodeId("n1"), FlowPoint(0.0, 0.0), FlowSize(160.0, 72.0)),
            ),
        )

        assertFalse(flowViewHasVisibleNode(view, IntSize(360, 640)))
    }

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

    private fun contrastRatio(a: Color, b: Color): Double {
        val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
        val darker = minOf(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun colorDistance(a: Color, b: Color): Double {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
