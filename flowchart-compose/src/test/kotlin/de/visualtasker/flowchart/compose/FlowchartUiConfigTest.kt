/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowEdgeId
import de.visualtasker.flowchart.domain.FlowExecutionKind
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphEdge
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
import de.visualtasker.flowchart.interaction.FlowInteractionState
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
    public fun `ports scale down visually while staying readable`() {
        assertEquals(1.0f, flowPortVisualScale(1.0), 0.0f)
        assertEquals(0.5f, flowPortVisualScale(0.5), 0.0f)
        assertEquals(0.42f, flowPortVisualScale(0.1), 0.0f)
    }

    @Test
    public fun `auxiliary nodes use compact semantic labels at overview zoom`() {
        val variable = FlowGraphNode(
            id = FlowNodeId("var"),
            kind = FlowSemanticKind(FlowNodeKind.PROPERTY_ACCESS),
            label = "thresholdLow",
            properties = mapOf("blockType" to FlowSemanticValue.StringValue("variable.reporter")),
        )
        val action = FlowGraphNode(
            id = FlowNodeId("action"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "click",
        )

        assertEquals(FlowchartNodeDetailLevel.Compact, flowNodeDetailLevel(variable, 0.6))
        assertEquals(FlowchartNodeDetailLevel.Compact, flowNodeDetailLevel(action, 1.0, screenWidth = 80.0, screenHeight = 60.0))
        assertEquals(FlowchartNodeDetailLevel.Full, flowNodeDetailLevel(action, 0.6))
        assertEquals("VAR", flowNodeCompactLabel(variable))
    }

    @Test
    public fun `very low zoom hides node labels for shape first overview`() {
        val node = FlowGraphNode(FlowNodeId("node"), FlowSemanticKind(FlowNodeKind.ACTION), "Action")

        assertEquals(FlowchartNodeDetailLevel.Dot, flowNodeDetailLevel(node, 0.18))
    }

    @Test
    public fun `edge labels use semantic zoom and remain visible when selected`() {
        val dataEdge = FlowGraphEdge(
            FlowEdgeId("data"),
            FlowNodeId("a"),
            FlowNodeId("b"),
            FlowEdgeKind.DATA_FLOW,
        )
        val branchEdge = FlowGraphEdge(
            FlowEdgeId("branch"),
            FlowNodeId("a"),
            FlowNodeId("b"),
            FlowEdgeKind.TRUE_BRANCH,
        )

        assertFalse(flowEdgeLabelVisible(dataEdge, FlowInteractionState(), zoom = 0.6))
        assertTrue(flowEdgeLabelVisible(dataEdge, FlowInteractionState(selectedEdgeIds = setOf(dataEdge.id)), zoom = 0.2))
        assertTrue(flowEdgeLabelVisible(branchEdge, FlowInteractionState(), zoom = 0.5))
    }

    @Test
    public fun `active rem group dims nodes outside its facet`() {
        val active = FlowGraphNode(FlowNodeId("active"), FlowSemanticKind(FlowNodeKind.ACTION), "active")
        val outside = FlowGraphNode(FlowNodeId("outside"), FlowSemanticKind(FlowNodeKind.ACTION), "outside")
        val facet = FlowGraphNode(
            id = FlowNodeId("facet:group"),
            kind = FlowSemanticKind(FlowNodeKind.SYNTHETIC),
            label = "group",
            properties = mapOf(
                "visualFacet" to FlowSemanticValue.BooleanValue(true),
                "remFlowKind" to FlowSemanticValue.StringValue("GROUP"),
                "remFlow.active" to FlowSemanticValue.BooleanValue(true),
                "nodeIds" to FlowSemanticValue.ListValue(listOf(FlowSemanticValue.StringValue(active.id.value))),
            ),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("groups"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(active, outside, facet),
        )

        assertEquals(1f, flowNodeGroupAlpha(active.id, graph), 0f)
        assertEquals(0.28f, flowNodeGroupAlpha(outside.id, graph), 0f)
    }

    @Test
    public fun `execution kind gives terminators distinct labels and colors`() {
        val start = FlowGraphNode(
            FlowNodeId("start"),
            FlowSemanticKind(FlowNodeKind.ENTRY),
            "Script Start",
        )
        val end = FlowGraphNode(
            FlowNodeId("end"),
            FlowSemanticKind(FlowNodeKind.EXIT),
            "End",
        )
        val tokens = FlowchartColorTokens()

        assertEquals("Workflow Start", flowNodeDisplayLabel(start, FlowExecutionKind.WORKFLOW))
        assertEquals("Recording Start", flowNodeDisplayLabel(start, FlowExecutionKind.RECORDING))
        assertEquals("DryRun End", flowNodeDisplayLabel(end, FlowExecutionKind.DRY_RUN))
        assertEquals(tokens.eventNodeFill, flowNodeFillColor(start, tokens, FlowExecutionKind.WORKFLOW))
        assertEquals(tokens.feedbackNodeFill, flowNodeFillColor(start, tokens, FlowExecutionKind.RECORDING))
        assertEquals(tokens.debugNodeFill, flowNodeFillColor(end, tokens, FlowExecutionKind.DRY_RUN))
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
    public fun `facet card is placed outside region and exposes visible actions`() {
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

        assertTrue(region.handleBounds.right < region.bounds.left)
        assertTrue(region.handleBounds.top > region.bounds.top)
        assertEquals(FlowFacetHandleAction.Drag, hitFlowFacetHandle(region.gripBounds.center, graph, view)?.action)
        assertEquals(FlowFacetHandleAction.Select, hitFlowFacetHandle(region.labelBounds.center, graph, view)?.action)
        assertEquals(FlowFacetHandleAction.ToggleCollapse, hitFlowFacetHandle(region.collapseBounds.center, graph, view)?.action)
        assertEquals(FlowFacetHandleAction.OpenMenu, hitFlowFacetHandle(region.menuBounds.center, graph, view)?.action)
        assertNull(hitFlowFacetHandle(region.lockBounds.center, graph, view))
    }

    @Test
    public fun `facet card prefers top edge and remains inside viewport`() {
        val bounds = Rect(left = 90f, top = 80f, right = 250f, bottom = 210f)

        val card = facetHandleBounds(
            facetBounds = bounds,
            width = 180f,
            height = 32f,
            gap = 6f,
            viewportSize = Size(320f, 480f),
        )

        assertTrue(card.bottom < bounds.top)
        assertTrue(card.left >= 4f)
        assertTrue(card.right <= 316f)
    }

    @Test
    public fun `facet card moves beside top-clamped region`() {
        val bounds = Rect(left = 10f, top = 8f, right = 90f, bottom = 160f)

        val card = facetHandleBounds(
            facetBounds = bounds,
            width = 150f,
            height = 32f,
            gap = 6f,
            viewportSize = Size(320f, 480f),
        )

        assertTrue(card.left > bounds.right)
        assertTrue(card.top >= 4f)
        assertTrue(card.right <= 316f)
    }

    @Test
    public fun `facet card avoids occupied nodes when another top position is free`() {
        val bounds = Rect(left = 100f, top = 100f, right = 300f, bottom = 260f)

        val card = facetHandleBounds(
            facetBounds = bounds,
            width = 100f,
            height = 30f,
            gap = 6f,
            viewportSize = Size(500f, 500f),
            occupiedBounds = listOf(Rect(left = 95f, top = 60f, right = 198f, bottom = 100f)),
        )

        assertEquals(200f, card.left, 0f)
        assertEquals(64f, card.top, 0f)
        assertEquals(0f, overlapArea(card, Rect(95f, 60f, 198f, 100f)), 0f)
    }

    @Test
    public fun `facet labels have semantic fallbacks`() {
        val facet = FlowGraphNode(
            id = FlowNodeId("facet:comment"),
            kind = FlowSemanticKind(FlowNodeKind.SYNTHETIC),
            label = " ",
            properties = mapOf("facetKind" to FlowSemanticValue.StringValue("COMMENT_MARKER")),
        )

        assertEquals("Comment marker", facetDisplayLabel(facet))
        assertEquals("Kommentarbereich", facetKindDisplayLabel(facet))
    }

    @Test
    public fun `collapsed outer facet hides nested members and nested facet handle`() {
        val first = FlowGraphNode(FlowNodeId("first"), FlowSemanticKind(FlowNodeKind.ACTION), "first")
        val second = FlowGraphNode(FlowNodeId("second"), FlowSemanticKind(FlowNodeKind.ACTION), "second")
        fun facet(id: String, label: String, members: List<FlowNodeId>) = FlowGraphNode(
            id = FlowNodeId(id),
            kind = FlowSemanticKind(FlowNodeKind.SYNTHETIC),
            label = label,
            properties = mapOf(
                "visualFacet" to FlowSemanticValue.BooleanValue(true),
                "nodeIds" to FlowSemanticValue.ListValue(
                    members.map { FlowSemanticValue.StringValue(it.value) },
                ),
            ),
        )
        val inner = facet("facet:inner", "Inner", listOf(first.id))
        val outer = facet("facet:outer", "Outer", listOf(first.id, second.id, inner.id))
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("nested"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(first, second, inner, outer),
        )

        val visibility = collapsedFacetVisibility(graph, setOf(outer.id))

        assertEquals(setOf(first.id, second.id), visibility.hiddenNodeIds)
        assertEquals(setOf(inner.id), visibility.hiddenFacetIds)
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
    public fun `dataflow layer hides only data edges`() {
        val sequence = FlowGraphEdge(FlowEdgeId("sequence"), FlowNodeId("a"), FlowNodeId("b"), FlowEdgeKind.SEQUENCE)
        val branch = FlowGraphEdge(FlowEdgeId("branch"), FlowNodeId("b"), FlowNodeId("c"), FlowEdgeKind.TRUE_BRANCH)
        val data = FlowGraphEdge(FlowEdgeId("data"), FlowNodeId("x"), FlowNodeId("b"), FlowEdgeKind.DATA_FLOW)
        val condition = FlowGraphEdge(FlowEdgeId("condition"), FlowNodeId("x"), FlowNodeId("b"), FlowEdgeKind.CONDITION)
        val loop = FlowGraphEdge(FlowEdgeId("loop"), FlowNodeId("c"), FlowNodeId("b"), FlowEdgeKind.LOOP_BACK)
        val edges = listOf(sequence, branch, data, condition, loop)

        assertEquals(edges, flowchartVisibleEdges(edges, FlowchartUiConfig(dataFlowEdgesEnabled = true)))
        assertEquals(
            listOf(sequence, branch, condition, loop),
            flowchartVisibleEdges(edges, FlowchartUiConfig(dataFlowEdgesEnabled = false)),
        )
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
        assertTrue(hits[0].bounds.contains(Offset(60f, 80f)))
        assertTrue(hits[1].bounds.contains(Offset(230f, 18f)))
    }

    @Test
    public fun `reporter node data ports are available on both horizontal sides`() {
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

        assertTrue(reporter.usesBidirectionalDataPorts())
        assertEquals(6, hits.size)
        assertEquals(6, hits.map { it.bounds.topLeft }.distinct().size)
        listOf("Input1", "Input2", "output").forEach { portName ->
            val anchors = hits.filter { it.ref.portName == portName }
            assertEquals(2, anchors.size)
            assertTrue(anchors.any { it.bounds.center.x < 160f })
            assertTrue(anchors.any { it.bounds.center.x > 160f })
        }
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
