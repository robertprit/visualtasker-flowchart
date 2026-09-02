/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.*
import org.junit.Assert.*
import org.junit.Test

public class FlowLayoutEngineTest {
    @Test public fun `identical input produces identical positions and routes`() {
        val graph = graph(listOf("a", "b", "c"), listOf("a" to "b", "a" to "c"))
        val first = FlowLayoutEngine.layout(graph, config = FlowLayoutConfig(deterministicSeed = 42))
        assertEquals(first, FlowLayoutEngine.layout(graph, config = FlowLayoutConfig(deterministicSeed = 42)))
        assertTrue(first.isValid)
    }

    @Test public fun `cycle is classified and loop back route remains visible`() {
        val graph = graph(listOf("a", "b"), listOf("a" to "b", "b" to "a"))
        val result = FlowLayoutEngine.layout(graph)
        assertEquals(1, result.backEdgeIds.size)
        assertTrue(result.routes.values.any { it.kind == FlowRouteKind.LOOP_BACK })
    }

    @Test public fun `long edge uses only internal dummy route points`() {
        val graph = graph(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c", "a" to "c"))
        val result = FlowLayoutEngine.layout(graph)
        assertTrue(result.internalDummyPointCount > 0)
        assertEquals(3, graph.nodes.size)
    }

    @Test public fun `disconnected components and both orientations are finite`() {
        val graph = graph(listOf("a", "b", "c"), listOf("a" to "b"))
        FlowLayoutOrientation.values().forEach { orientation -> assertTrue(FlowLayoutEngine.layout(graph, config = FlowLayoutConfig(orientation = orientation)).isValid) }
    }

    @Test public fun `long route detours around intervening node rectangle`() {
        val graph = graph(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c", "a" to "c"))
        val result = FlowLayoutEngine.layout(graph)
        val longRoute = result.routes.getValue(FlowEdgeId("e2"))
        val obstacle = result.nodeBounds.getValue(FlowNodeId("b"))
        assertFalse(longRoute.segments.any { segment -> segment.start.x == segment.end.x && segment.start.x in obstacle.left..obstacle.right && minOf(segment.start.y, segment.end.y) <= obstacle.bottom && maxOf(segment.start.y, segment.end.y) >= obstacle.top })
    }

    @Test public fun `branch targets form code flow stair with false below the decision`() {
        val nodes = listOf("if", "then", "elseif", "else")
        val graphNodes = nodes.map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("branches"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = graphNodes,
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("true"), FlowNodeId("if"), FlowNodeId("then"), FlowEdgeKind.TRUE_BRANCH),
                FlowGraphEdge(FlowEdgeId("elseif"), FlowNodeId("if"), FlowNodeId("elseif"), FlowEdgeKind.ELSE_IF_BRANCH),
                FlowGraphEdge(FlowEdgeId("false"), FlowNodeId("if"), FlowNodeId("else"), FlowEdgeKind.FALSE_BRANCH),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)
        val ifBounds = result.nodeBounds.getValue(FlowNodeId("if"))
        val thenBounds = result.nodeBounds.getValue(FlowNodeId("then"))
        val elseifBounds = result.nodeBounds.getValue(FlowNodeId("elseif"))
        val elseBounds = result.nodeBounds.getValue(FlowNodeId("else"))

        assertTrue(thenBounds.left > ifBounds.right)
        assertTrue(elseifBounds.left > thenBounds.left)
        assertTrue(elseifBounds.top > thenBounds.top)
        assertEquals(ifBounds.left, elseBounds.left, 0.001)
        assertTrue(elseBounds.top > ifBounds.bottom)
    }

    @Test public fun `data and condition edges do not push consumers into deeper ranks`() {
        val graphNodes = listOf("start", "if", "compare", "literal").map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("data-ranks"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = graphNodes,
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("seq"), FlowNodeId("start"), FlowNodeId("if"), FlowEdgeKind.SEQUENCE),
                FlowGraphEdge(FlowEdgeId("condition"), FlowNodeId("compare"), FlowNodeId("if"), FlowEdgeKind.CONDITION),
                FlowGraphEdge(FlowEdgeId("data"), FlowNodeId("literal"), FlowNodeId("compare"), FlowEdgeKind.DATA_FLOW),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)

        assertEquals(1, result.ranks.getValue(FlowNodeId("if")))
        assertEquals(0, result.ranks.getValue(FlowNodeId("compare")))
        assertEquals(0, result.ranks.getValue(FlowNodeId("literal")))
    }

    @Test public fun `routes attach to declared side ports when present`() {
        val source = FlowGraphNode(
            id = FlowNodeId("if"),
            kind = FlowSemanticKind(FlowNodeKind.DECISION),
            label = "if",
            properties = mapOf("outputPorts" to ports("THEN", "ELSE", "next")),
        )
        val target = FlowGraphNode(
            id = FlowNodeId("then"),
            kind = FlowSemanticKind(FlowNodeKind.ACTION),
            label = "then",
            properties = mapOf("inputPorts" to ports("previous")),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("ports"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(source, target),
            edges = listOf(
                FlowGraphEdge(
                    id = FlowEdgeId("then-edge"),
                    sourceNodeId = source.id,
                    targetNodeId = target.id,
                    kind = FlowEdgeKind.TRUE_BRANCH,
                    label = "THEN",
                )
            ),
        )

        val result = FlowLayoutEngine.layout(
            graph,
            nodeMetrics = FlowNodeMetrics(
                sizes = mapOf(
                    source.id to FlowSize(160.0, 80.0),
                    target.id to FlowSize(120.0, 60.0),
                ),
            ),
            config = FlowLayoutConfig(orientation = FlowLayoutOrientation.LEFT_TO_RIGHT),
        )
        val sourceBounds = result.nodeBounds.getValue(source.id)
        val targetBounds = result.nodeBounds.getValue(target.id)
        val route = result.routes.getValue(FlowEdgeId("then-edge"))

        assertEquals(sourceBounds.right, route.points.first().x, 0.001)
        assertEquals(sourceBounds.top + 20.0, route.points.first().y, 0.001)
        assertEquals(targetBounds.left, route.points.last().x, 0.001)
        assertEquals(targetBounds.top + 30.0, route.points.last().y, 0.001)
    }

    @Test public fun `sequence uses vertical main stem anchors`() {
        val graph = graph(listOf("start", "wait"), listOf("start" to "wait"))

        val result = FlowLayoutEngine.layout(graph)
        val source = result.nodeBounds.getValue(FlowNodeId("start"))
        val target = result.nodeBounds.getValue(FlowNodeId("wait"))
        val route = result.routes.getValue(FlowEdgeId("e0"))

        assertEquals(source.left, target.left, 0.001)
        assertEquals(source.left + source.size.width / 2.0, route.points.first().x, 0.001)
        assertEquals(source.bottom, route.points.first().y, 0.001)
        assertEquals(target.left + target.size.width / 2.0, route.points.last().x, 0.001)
        assertEquals(target.top, route.points.last().y, 0.001)
    }

    @Test public fun `value compare chain is placed horizontally near consumer`() {
        val graphNodes = listOf(
            FlowGraphNode(FlowNodeId("if"), FlowSemanticKind(FlowNodeKind.DECISION), "if"),
            FlowGraphNode(FlowNodeId("compare"), FlowSemanticKind(FlowNodeKind.DECISION), "compare"),
            FlowGraphNode(FlowNodeId("left"), FlowSemanticKind(FlowNodeKind.INPUT), "left"),
            FlowGraphNode(FlowNodeId("right"), FlowSemanticKind(FlowNodeKind.INPUT), "right"),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("value-row"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = graphNodes,
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("condition"), FlowNodeId("compare"), FlowNodeId("if"), FlowEdgeKind.CONDITION),
                FlowGraphEdge(FlowEdgeId("left-input"), FlowNodeId("left"), FlowNodeId("compare"), FlowEdgeKind.DATA_FLOW, label = "Input1"),
                FlowGraphEdge(FlowEdgeId("right-input"), FlowNodeId("right"), FlowNodeId("compare"), FlowEdgeKind.DATA_FLOW, label = "Input2"),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)
        val ifBounds = result.nodeBounds.getValue(FlowNodeId("if"))
        val compareBounds = result.nodeBounds.getValue(FlowNodeId("compare"))
        val leftBounds = result.nodeBounds.getValue(FlowNodeId("left"))
        val rightBounds = result.nodeBounds.getValue(FlowNodeId("right"))

        assertTrue(compareBounds.left > ifBounds.right)
        assertTrue(leftBounds.left > compareBounds.right)
        assertTrue(rightBounds.left > leftBounds.right)
        assertEquals(compareBounds.top, leftBounds.top, 0.001)
        assertEquals(compareBounds.top, rightBounds.top, 0.001)
    }

    @Test public fun `layout keeps minimum spacing between node rectangles`() {
        val nodes = listOf("start", "a", "b", "c", "d", "e")
        val graph = graph(
            nodes,
            listOf("start" to "a", "start" to "b", "start" to "c", "a" to "d", "b" to "d", "c" to "e"),
        )

        val result = FlowLayoutEngine.layout(graph)
        val bounds = result.nodeBounds.values.toList()

        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second ->
                assertFalse(first.overlaps(second, gap = 1.0))
            }
        }
    }

    @Test public fun `entry node stays at left layout edge for top down code flow`() {
        val graphNodes = listOf(
            FlowGraphNode(FlowNodeId("start"), FlowSemanticKind(FlowNodeKind.ENTRY), "start"),
            FlowGraphNode(FlowNodeId("loop"), FlowSemanticKind(FlowNodeKind.LOOP_START), "loop"),
            FlowGraphNode(FlowNodeId("body"), FlowSemanticKind(FlowNodeKind.ACTION), "body"),
            FlowGraphNode(FlowNodeId("end"), FlowSemanticKind(FlowNodeKind.ACTION), "end"),
            FlowGraphNode(FlowNodeId("compare"), FlowSemanticKind(FlowNodeKind.DECISION), "compare"),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("entry-left"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = graphNodes,
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("start-loop"), FlowNodeId("start"), FlowNodeId("loop"), FlowEdgeKind.SEQUENCE),
                FlowGraphEdge(FlowEdgeId("body"), FlowNodeId("loop"), FlowNodeId("body"), FlowEdgeKind.LOOP_BODY),
                FlowGraphEdge(FlowEdgeId("exit"), FlowNodeId("loop"), FlowNodeId("end"), FlowEdgeKind.LOOP_EXIT),
                FlowGraphEdge(FlowEdgeId("condition"), FlowNodeId("compare"), FlowNodeId("loop"), FlowEdgeKind.CONDITION),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)
        val minLeft = result.nodeBounds.values.minOf { it.left }

        assertEquals(minLeft, result.nodeBounds.getValue(FlowNodeId("start")).left, 0.001)
    }

    @Test public fun `automatic routes avoid unrelated node rectangles`() {
        val graph = graph(listOf("a", "b", "c", "d"), listOf("a" to "b", "b" to "c", "a" to "d", "d" to "c"))

        val result = FlowLayoutEngine.layout(graph)

        result.routes.values.forEach { route ->
            val edge = graph.edges.first { it.id == route.edgeId }
            result.nodeBounds
                .filterKeys { it != edge.sourceNodeId && it != edge.targetNodeId }
                .values
                .forEach { obstacle ->
                    assertFalse(
                        route.segments.any { segment -> segment.intersects(obstacle, gap = 1.0) }
                    )
                }
        }
    }

    @Test public fun `locked bend routes are normalized to orthogonal segments`() {
        val graph = graph(listOf("a", "b"), listOf("a" to "b"))
        val view = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = listOf(
                FlowNodeView(FlowNodeId("a"), FlowPoint(0.0, 0.0), FlowSize(120.0, 60.0)),
                FlowNodeView(FlowNodeId("b"), FlowPoint(260.0, 160.0), FlowSize(120.0, 60.0)),
            ),
            edgeViews = listOf(
                FlowEdgeView(
                    edgeId = FlowEdgeId("e0"),
                    bendPoints = listOf(FlowPoint(80.0, 130.0)),
                    routeLockState = FlowRouteLockState.LOCKED,
                )
            ),
        )

        val route = FlowLayoutEngine.layout(graph, compatibleView = view).routes.getValue(FlowEdgeId("e0"))

        assertTrue(route.points.zipWithNext().all { (from, to) -> from.x == to.x || from.y == to.y })
    }

    @Test public fun `synthetic join is centered below branch group`() {
        val decision = FlowGraphNode(FlowNodeId("if"), FlowSemanticKind(FlowNodeKind.DECISION), "if")
        val thenNode = FlowGraphNode(FlowNodeId("then"), FlowSemanticKind(FlowNodeKind.ACTION), "then")
        val elseNode = FlowGraphNode(FlowNodeId("else"), FlowSemanticKind(FlowNodeKind.ACTION), "else")
        val join = FlowGraphNode(
            FlowNodeId("join:if"),
            FlowSemanticKind(FlowNodeKind.SYNTHETIC),
            "JOIN",
            properties = mapOf(
                "syntheticJoin" to FlowSemanticValue.BooleanValue(true),
                "ownerNodeId" to FlowSemanticValue.StringValue("if"),
            ),
        )
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("join-layout"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(decision, thenNode, elseNode, join),
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("then"), decision.id, thenNode.id, FlowEdgeKind.TRUE_BRANCH),
                FlowGraphEdge(FlowEdgeId("else"), decision.id, elseNode.id, FlowEdgeKind.FALSE_BRANCH),
                FlowGraphEdge(FlowEdgeId("then-join"), thenNode.id, join.id, FlowEdgeKind.SEQUENCE),
                FlowGraphEdge(FlowEdgeId("else-join"), elseNode.id, join.id, FlowEdgeKind.SEQUENCE),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)
        val thenBounds = result.nodeBounds.getValue(thenNode.id)
        val elseBounds = result.nodeBounds.getValue(elseNode.id)
        val joinBounds = result.nodeBounds.getValue(join.id)

        assertTrue(joinBounds.top > maxOf(thenBounds.bottom, elseBounds.bottom))
        assertTrue(joinBounds.left > minOf(thenBounds.left, elseBounds.left))
        assertTrue(joinBounds.right < maxOf(thenBounds.right, elseBounds.right) + joinBounds.size.width)
    }

    @Test public fun `parallel side branches reserve separate routing lanes`() {
        val decision = FlowGraphNode(FlowNodeId("if"), FlowSemanticKind(FlowNodeKind.DECISION), "if")
        val first = FlowGraphNode(FlowNodeId("first"), FlowSemanticKind(FlowNodeKind.ACTION), "first")
        val second = FlowGraphNode(FlowNodeId("second"), FlowSemanticKind(FlowNodeKind.ACTION), "second")
        val graph = FlowGraphDocument(
            documentId = FlowDocumentId("lanes"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "fixture",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            nodes = listOf(decision, first, second),
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("first"), decision.id, first.id, FlowEdgeKind.TRUE_BRANCH, label = "A"),
                FlowGraphEdge(FlowEdgeId("second"), decision.id, second.id, FlowEdgeKind.TRUE_BRANCH, label = "B"),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)
        val firstLane = result.routes.getValue(FlowEdgeId("first")).points[1].x
        val secondLane = result.routes.getValue(FlowEdgeId("second")).points[1].x

        assertTrue(firstLane != secondLane)
    }

    private fun graph(nodes: List<String>, edges: List<Pair<String, String>>): FlowGraphDocument {
        val graphNodes = nodes.map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graphEdges = edges.mapIndexed { index, (source, target) -> FlowGraphEdge(FlowEdgeId("e$index"), FlowNodeId(source), FlowNodeId(target), FlowEdgeKind.SEQUENCE) }
        return FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "fixture", producerVersion = "1", sourceRevision = "1", sourceHash = "hash", nodes = graphNodes, edges = graphEdges)
    }

    private fun ports(vararg names: String): FlowSemanticValue =
        FlowSemanticValue.ListValue(
            names.map { name ->
                FlowSemanticValue.ObjectValue(
                    mapOf(
                        "name" to FlowSemanticValue.StringValue(name),
                        "label" to FlowSemanticValue.StringValue(name),
                        "kind" to FlowSemanticValue.StringValue(FlowEdgeKind.SEQUENCE.name),
                    )
                )
            }
        )

    private fun FlowRect.overlaps(other: FlowRect, gap: Double): Boolean =
        left < other.right + gap &&
            right + gap > other.left &&
            top < other.bottom + gap &&
            bottom + gap > other.top

    private fun FlowRouteSegment.intersects(rect: FlowRect, gap: Double): Boolean {
        val left = rect.left - gap
        val right = rect.right + gap
        val top = rect.top - gap
        val bottom = rect.bottom + gap
        return if (start.x == end.x) {
            start.x in left..right && minOf(start.y, end.y) <= bottom && maxOf(start.y, end.y) >= top
        } else if (start.y == end.y) {
            start.y in top..bottom && minOf(start.x, end.x) <= right && maxOf(start.x, end.x) >= left
        } else {
            true
        }
    }
}
