/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowNodeView
import de.visualtasker.flowchart.domain.FlowPoint
import de.visualtasker.flowchart.domain.FlowSemanticKind
import de.visualtasker.flowchart.domain.FlowSemanticValue
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

    @Test public fun `default edge stroke is touch readable`() {
        assertTrue(FlowchartShapeTokens().edgeStrokeWidthDp >= 2.6f)
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
    public fun `port drag preview is orthogonal and compact`() {
        val route = portDragPreviewRoute(Offset(10f, 20f), Offset(90f, 80f))

        assertEquals(listOf(Offset(10f, 20f), Offset(50f, 20f), Offset(50f, 80f), Offset(90f, 80f)), route)
        assertTrue(route.zipWithNext().all { (from, to) -> from.x == to.x || from.y == to.y })
    }

    @Test
    public fun `edge bridges are emitted for orthogonal crossings`() {
        val current = listOf(Offset(0f, 50f), Offset(100f, 50f))
        val previous = listOf(listOf(Offset(40f, 0f), Offset(40f, 100f)))

        val bridges = edgeBridgeIntersections(current, previous, minDistanceFromEnds = 8f)

        assertEquals(1, bridges.size)
        assertEquals(40f, bridges.single().center.x)
        assertEquals(50f, bridges.single().center.y)
    }

    @Test
    public fun `edge bridges ignore near endpoint crossings`() {
        val current = listOf(Offset(0f, 50f), Offset(100f, 50f))
        val previous = listOf(listOf(Offset(4f, 0f), Offset(4f, 100f)))

        assertTrue(edgeBridgeIntersections(current, previous, minDistanceFromEnds = 8f).isEmpty())
    }

    @Test
    public fun `facet handle is placed above region and exposes actions`() {
        val node = FlowGraphNode(
            id = FlowNodeId("block:wait"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Wait",
        )
        val facet = FlowGraphNode(
            id = FlowNodeId("facet:bulk"),
            kind = FlowSemanticKind(FlowNodeKind.SYNTHETIC),
            label = "Bulk",
            properties = mapOf(
                "visualFacet" to FlowSemanticValue.BooleanValue(true),
                "nodeIds" to FlowSemanticValue.ListValue(listOf(FlowSemanticValue.StringValue(node.id.value))),
            ),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("graph"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(node, facet),
        )
        val view = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = listOf(FlowNodeView(node.id, FlowPoint(100.0, 80.0), FlowSize(120.0, 48.0))),
        )

        val region = flowFacetRegions(graph, view) { Offset(it.x.toFloat(), it.y.toFloat()) }.single()

        assertTrue(region.handleBounds.bottom < region.bounds.top)
        assertEquals(FlowFacetHandleAction.Drag, hitFlowFacetHandle(region.gripBounds.center, graph, view)?.action)
        assertEquals(FlowFacetHandleAction.ToggleCollapse, hitFlowFacetHandle(region.collapseBounds.center, graph, view)?.action)
        assertEquals(FlowFacetHandleAction.ToggleLock, hitFlowFacetHandle(region.lockBounds.center, graph, view)?.action)
    }

    @Test
    public fun `port targets require output to matching foreign input`() {
        val source = FlowchartNodePortRef(
            nodeId = FlowNodeId("source"),
            portName = "output",
            kind = FlowEdgeKind.DATA_FLOW,
            inputSide = false,
        )
        val target = FlowchartNodePortRef(
            nodeId = FlowNodeId("target"),
            portName = "LEFT",
            kind = FlowEdgeKind.DATA_FLOW,
            inputSide = true,
        )
        val sameNode = target.copy(nodeId = source.nodeId)
        val wrongKind = target.copy(kind = FlowEdgeKind.CONDITION)
        val outputTarget = target.copy(inputSide = false)

        assertTrue(target.isCompatibleTargetFor(source))
        assertFalse(sameNode.isCompatibleTargetFor(source))
        assertTrue(wrongKind.isCompatibleTargetFor(source))
        assertFalse(outputTarget.isCompatibleTargetFor(source))
    }

    @Test
    public fun `edge visual categories exhaust every supported edge kind`() {
        val categories = FlowEdgeKind.entries.associateWith(::flowEdgeVisualCategory)

        assertEquals(FlowEdgeKind.entries.size, categories.size)
        assertEquals(FlowchartEdgeVisualCategory.BRANCH, categories.getValue(FlowEdgeKind.TRUE_BRANCH))
        assertEquals(FlowchartEdgeVisualCategory.BRANCH, categories.getValue(FlowEdgeKind.CONDITION))
        assertEquals(FlowchartEdgeVisualCategory.DATA, categories.getValue(FlowEdgeKind.DATA_FLOW))
        assertEquals(FlowchartEdgeVisualCategory.LOOP, categories.getValue(FlowEdgeKind.LOOP_BACK))
        assertEquals(FlowchartEdgeVisualCategory.ERROR, categories.getValue(FlowEdgeKind.ERROR))
        assertEquals(FlowchartEdgeVisualCategory.DEFAULT, categories.getValue(FlowEdgeKind.SEQUENCE))
    }

    @Test
    public fun `node fill color maps block type families`() {
        val tokens = FlowchartColorTokens()
        val logicNode = FlowGraphNode(
            id = FlowNodeId("logic"),
            kind = FlowSemanticKind(FlowNodeKind.ASSIGNMENT),
            label = "Logic",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("logic.compare")),
        )
        val variableNode = FlowGraphNode(
            id = FlowNodeId("var"),
            kind = FlowSemanticKind(FlowNodeKind.PROPERTY_ACCESS),
            label = "Var",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("variable.reporter.v1")),
        )
        val feedbackNode = FlowGraphNode(
            id = FlowNodeId("feedback"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Feedback",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("feedback.beep")),
        )
        val actionNode = FlowGraphNode(
            id = FlowNodeId("action"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Action",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("action.clickText")),
        )
        val chromeNode = FlowGraphNode(
            id = FlowNodeId("chrome"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Chrome",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("chromeTab.open")),
        )
        val noBlockTypeAction = FlowGraphNode(
            id = FlowNodeId("kind-action"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Kind Action",
            properties = emptyMap(),
        )

        assertEquals(tokens.logicNodeFill, flowNodeFillColor(logicNode, tokens))
        assertEquals(tokens.variableNodeFill, flowNodeFillColor(variableNode, tokens))
        assertEquals(tokens.feedbackNodeFill, flowNodeFillColor(feedbackNode, tokens))
        assertEquals(tokens.actionNodeFill, flowNodeFillColor(actionNode, tokens))
        assertEquals(tokens.chromeTabNodeFill, flowNodeFillColor(chromeNode, tokens))
        assertEquals(tokens.actionNodeFill, flowNodeFillColor(noBlockTypeAction, tokens))
    }

    @Test
    public fun `node ports are parsed from presentation metadata`() {
        val node = FlowGraphNode(
            id = FlowNodeId("if"),
            kind = FlowSemanticKind(FlowNodeKind.DECISION),
            label = "IF",
            properties = mapOf(
                "inputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("CONDITION"),
                                "label" to FlowSemanticValue.StringValue("Condition"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.CONDITION.name),
                            )
                        )
                    )
                ),
                "outputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("THEN"),
                                "label" to FlowSemanticValue.StringValue("then"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.TRUE_BRANCH.name),
                            )
                        )
                    )
                ),
            ),
        )

        val inputs = flowNodePorts(node, "inputPorts")
        val outputs = flowNodePorts(node, "outputPorts")

        assertEquals(listOf(FlowchartNodePort("CONDITION", "Condition", FlowEdgeKind.CONDITION)), inputs)
        assertEquals(listOf(FlowchartNodePort("THEN", "then", FlowEdgeKind.TRUE_BRANCH)), outputs)
        assertTrue(flowNodePorts(node, "missingPorts").isEmpty())
    }

    @Test
    public fun `node port hitboxes follow rendered port geometry`() {
        val source = FlowGraphNode(
            id = FlowNodeId("source"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Source",
            properties = mapOf(
                "outputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("next"),
                                "label" to FlowSemanticValue.StringValue("Next"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.SEQUENCE.name),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val target = FlowGraphNode(
            id = FlowNodeId("target"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "Target",
            properties = mapOf(
                "inputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("previous"),
                                "label" to FlowSemanticValue.StringValue("Previous"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.SEQUENCE.name),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("doc"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(source, target),
        )
        val view = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = listOf(
                FlowNodeView(source.id, FlowPoint(10.0, 20.0), FlowSize(100.0, 60.0)),
                FlowNodeView(target.id, FlowPoint(180.0, 20.0), FlowSize(100.0, 60.0)),
            ),
        )

        val hits = flowNodePortHits(graph, view, portWidthPx = 18f, portHeightPx = 8f)

        assertEquals(2, hits.size)
        assertEquals(source.id, hits[0].ref.nodeId)
        assertEquals("next", hits[0].ref.portName)
        assertFalse(hits[0].ref.inputSide)
        assertEquals(target.id, hits[1].ref.nodeId)
        assertEquals("previous", hits[1].ref.portName)
        assertTrue(hits[1].ref.inputSide)
        assertTrue(hits[0].bounds.contains(Offset(60f, 78f)))
        assertTrue(hits[1].bounds.contains(Offset(230f, 18f)))
    }

    @Test
    public fun `reporter node ports use left inputs and right output`() {
        val reporter = FlowGraphNode(
            id = FlowNodeId("reporter"),
            kind = FlowSemanticKind(FlowNodeKind.INPUT),
            label = "Compare",
            properties = mapOf(
                "inputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("Input1"),
                                "label" to FlowSemanticValue.StringValue("Input1"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.DATA_FLOW.name),
                            ),
                        ),
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("Input2"),
                                "label" to FlowSemanticValue.StringValue("Input2"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.DATA_FLOW.name),
                            ),
                        ),
                    ),
                ),
                "outputPorts" to FlowSemanticValue.ListValue(
                    listOf(
                        FlowSemanticValue.ObjectValue(
                            mapOf(
                                "name" to FlowSemanticValue.StringValue("output"),
                                "label" to FlowSemanticValue.StringValue("Output"),
                                "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.DATA_FLOW.name),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("reporter-doc"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(reporter),
        )
        val view = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = listOf(FlowNodeView(reporter.id, FlowPoint(100.0, 80.0), FlowSize(120.0, 56.0))),
        )

        val hits = flowNodePortHits(graph, view, portWidthPx = 24f, portHeightPx = 10f)

        assertEquals(3, hits.size)
        assertTrue(hits.first { it.ref.portName == "Input1" }.bounds.contains(Offset(96f, 99f)))
        assertTrue(hits.first { it.ref.portName == "Input2" }.bounds.contains(Offset(96f, 117f)))
        assertTrue(hits.first { it.ref.portName == "output" }.bounds.contains(Offset(218f, 108f)))
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
