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

    @Test public fun `loop back and goto routes use left lanes in top to bottom layout`() {
        val graph = graphWithKinds(
            nodes = listOf("a", "b", "c"),
            edges = listOf(
                edge("a", "b"),
                edge("b", "a", FlowEdgeKind.LOOP_BACK),
                edge("b", "c", FlowEdgeKind.GOTO),
            ),
        )
        val result = FlowLayoutEngine.layout(graph)
        val loopRoute = result.routes.getValue(FlowEdgeId("e1"))
        val gotoRoute = result.routes.getValue(FlowEdgeId("e2"))
        val loopLeft = minOf(
            result.nodeBounds.getValue(FlowNodeId("a")).left,
            result.nodeBounds.getValue(FlowNodeId("b")).left,
        )
        val gotoLeft = minOf(
            result.nodeBounds.getValue(FlowNodeId("b")).left,
            result.nodeBounds.getValue(FlowNodeId("c")).left,
        )

        assertTrue(loopRoute.points.any { it.x < loopLeft })
        assertTrue(gotoRoute.points.any { it.x < gotoLeft })
        assertOrthogonal(loopRoute)
        assertOrthogonal(gotoRoute)
    }

    @Test public fun `long edge uses only internal dummy route points`() {
        val graph = graph(listOf("a", "b", "c"), listOf("a" to "b", "b" to "c", "a" to "c"))
        val result = FlowLayoutEngine.layout(graph)
        assertTrue(result.internalDummyPointCount > 0)
        assertEquals(3, graph.nodes.size)
        assertEquals(setOf(FlowEdgeId("e2")), result.pipelineArtifacts!!.dummyNodeInsertion.edgeIdsUsingDummyNodes)
        assertTrue(result.pipelineArtifacts!!.dummyNodeInsertion.dummyNodes.all { it.edgeId == FlowEdgeId("e2") })
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

    @Test public fun `layout result exposes explicit pipeline artifacts`() {
        val graph = graph(listOf("a", "b", "c", "d"), listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d"))
        val result = FlowLayoutEngine.layout(graph)
        val artifacts = result.pipelineArtifacts!!

        assertEquals(graph.nodes.map { it.id }.toSet(), artifacts.normalizedGraph.nodes.map { it.id }.toSet())
        assertEquals(result.backEdgeIds, artifacts.cycleResolution.backEdgeIds)
        assertEquals(result.ranks, artifacts.layerAssignment.ranks)
        assertEquals(result.nodeBounds, artifacts.nodePositioning.nodeBounds)
        assertEquals(result.routes, artifacts.manhattanRouting.routes)
        assertEquals(listOf(FlowNodeId("a")), artifacts.crossingMinimization.orderedLayers.getValue(0))
        assertEquals(listOf(FlowNodeId("d")), artifacts.crossingMinimization.orderedLayers.getValue(2))
    }

    @Test public fun `self loop is classified and routed separately`() {
        val graph = graph(listOf("a"), listOf("a" to "a"))
        val result = FlowLayoutEngine.layout(graph)

        assertEquals(setOf(FlowEdgeId("e0")), result.selfLoopEdgeIds)
        assertEquals(FlowRouteKind.SELF_LOOP, result.routes.getValue(FlowEdgeId("e0")).kind)
        assertEquals(FlowLayoutEdgeDirection.SELF_LOOP, result.pipelineArtifacts!!.cycleResolution.edgeDirections.getValue(FlowEdgeId("e0")))
        assertTrue(result.routes.getValue(FlowEdgeId("e0")).points.any { it.x < result.nodeBounds.getValue(FlowNodeId("a")).left })
    }

    @Test public fun `parallel edges receive distinguishable manhattan lanes`() {
        val graph = graph(
            nodes = listOf("a", "b"),
            edges = listOf("a" to "b", "a" to "b"),
        )
        val result = FlowLayoutEngine.layout(graph)
        val first = result.routes.getValue(FlowEdgeId("e0"))
        val second = result.routes.getValue(FlowEdgeId("e1"))

        assertNotEquals(first.points, second.points)
        assertTrue(first.segments.all { it.start.x == it.end.x || it.start.y == it.end.y })
        assertTrue(second.segments.all { it.start.x == it.end.x || it.start.y == it.end.y })
    }

    @Test public fun `crossing minimization uses deterministic tie breakers`() {
        val graph = graph(
            nodes = listOf("a", "b", "c", "d"),
            edges = listOf("a" to "c", "b" to "d"),
        )
        val first = FlowLayoutEngine.layout(graph, config = FlowLayoutConfig(crossingReductionSweeps = 8))
        val second = FlowLayoutEngine.layout(graph, config = FlowLayoutConfig(crossingReductionSweeps = 8))

        assertEquals(
            first.pipelineArtifacts!!.crossingMinimization.orderedLayers,
            second.pipelineArtifacts!!.crossingMinimization.orderedLayers,
        )
    }

    @Test public fun `branch shaped graph keeps branch routes orthogonal and layered`() {
        val graph = graphWithKinds(
            nodes = listOf("entry", "if", "then", "else", "join"),
            edges = listOf(
                edge("entry", "if"),
                edge("if", "then", FlowEdgeKind.TRUE_BRANCH, "TRUE"),
                edge("if", "else", FlowEdgeKind.FALSE_BRANCH, "FALSE"),
                edge("then", "join"),
                edge("else", "join"),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)

        assertTrue(result.isValid)
        assertEquals(0, result.ranks.getValue(FlowNodeId("entry")))
        assertEquals(1, result.ranks.getValue(FlowNodeId("if")))
        assertEquals(2, result.ranks.getValue(FlowNodeId("then")))
        assertEquals(2, result.ranks.getValue(FlowNodeId("else")))
        assertEquals(3, result.ranks.getValue(FlowNodeId("join")))
        result.routes.values.forEach(::assertOrthogonal)
    }

    @Test public fun `sprint six routing golden covers back self parallel and long forward edges`() {
        val graph = graphWithKinds(
            nodes = listOf("a", "b", "c", "d"),
            edges = listOf(
                edge("a", "b"),
                edge("b", "c"),
                edge("c", "b", FlowEdgeKind.LOOP_BACK),
                edge("c", "c", FlowEdgeKind.LOOP_BACK),
                edge("a", "d"),
                edge("a", "d"),
                edge("a", "c"),
            ),
        )

        val result = FlowLayoutEngine.layout(graph)

        assertTrue(result.isValid)
        assertEquals(FlowRouteKind.LOOP_BACK, result.routes.getValue(FlowEdgeId("e2")).kind)
        assertEquals(FlowRouteKind.SELF_LOOP, result.routes.getValue(FlowEdgeId("e3")).kind)
        assertNotEquals(
            result.routes.getValue(FlowEdgeId("e4")).points,
            result.routes.getValue(FlowEdgeId("e5")).points,
        )
        assertTrue(result.pipelineArtifacts!!.dummyNodeInsertion.edgeIdsUsingDummyNodes.contains(FlowEdgeId("e6")))
        assertTrue(result.routes.getValue(FlowEdgeId("e6")).usesInternalDummyPoints)
        result.routes.values.forEach(::assertOrthogonal)
        assertRouteAvoidsNodeInterior(result.routes.getValue(FlowEdgeId("e6")), result.nodeBounds.getValue(FlowNodeId("b")))
        assertSelfLoopLeavesNodeExterior(result.routes.getValue(FlowEdgeId("e3")), result.nodeBounds.getValue(FlowNodeId("c")))
    }

    private fun graph(nodes: List<String>, edges: List<Pair<String, String>>): FlowGraphDocument {
        val graphNodes = nodes.map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graphEdges = edges.mapIndexed { index, (source, target) -> FlowGraphEdge(FlowEdgeId("e$index"), FlowNodeId(source), FlowNodeId(target), FlowEdgeKind.SEQUENCE) }
        return FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "fixture", producerVersion = "1", sourceRevision = "1", sourceHash = "hash", nodes = graphNodes, edges = graphEdges)
    }

    private data class EdgeFixture(
        val source: String,
        val target: String,
        val kind: FlowEdgeKind = FlowEdgeKind.SEQUENCE,
        val label: String? = null,
    )

    private fun edge(
        source: String,
        target: String,
        kind: FlowEdgeKind = FlowEdgeKind.SEQUENCE,
        label: String? = null,
    ): EdgeFixture = EdgeFixture(source, target, kind, label)

    private fun graphWithKinds(nodes: List<String>, edges: List<EdgeFixture>): FlowGraphDocument {
        val graphNodes = nodes.map { id ->
            val kind = if (id == "entry") FlowNodeKind.ENTRY else if (id == "if") FlowNodeKind.DECISION else FlowNodeKind.ACTION
            FlowGraphNode(FlowNodeId(id), FlowSemanticKind(kind), id)
        }
        val graphEdges = edges.mapIndexed { index, edge ->
            FlowGraphEdge(
                id = FlowEdgeId("e$index"),
                sourceNodeId = FlowNodeId(edge.source),
                targetNodeId = FlowNodeId(edge.target),
                kind = edge.kind,
                label = edge.label,
            )
        }
        return FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "fixture", producerVersion = "1", sourceRevision = "1", sourceHash = "hash", nodes = graphNodes, edges = graphEdges)
    }

    private fun assertOrthogonal(route: FlowRoute) {
        assertTrue(route.segments.isNotEmpty())
        assertTrue(route.segments.all { segment ->
            segment.start.x == segment.end.x || segment.start.y == segment.end.y
        })
    }

    private fun assertRouteAvoidsNodeInterior(route: FlowRoute, obstacle: FlowRect) {
        val inset = 1.0
        route.segments.forEach { segment ->
            val minX = minOf(segment.start.x, segment.end.x)
            val maxX = maxOf(segment.start.x, segment.end.x)
            val minY = minOf(segment.start.y, segment.end.y)
            val maxY = maxOf(segment.start.y, segment.end.y)
            val crossesVertical = segment.start.x == segment.end.x &&
                segment.start.x > obstacle.left + inset &&
                segment.start.x < obstacle.right - inset &&
                maxY > obstacle.top + inset &&
                minY < obstacle.bottom - inset
            val crossesHorizontal = segment.start.y == segment.end.y &&
                segment.start.y > obstacle.top + inset &&
                segment.start.y < obstacle.bottom - inset &&
                maxX > obstacle.left + inset &&
                minX < obstacle.right - inset
            assertFalse("Route ${route.edgeId.value} crosses ${obstacle}", crossesVertical || crossesHorizontal)
        }
    }

    private fun assertSelfLoopLeavesNodeExterior(route: FlowRoute, bounds: FlowRect) {
        assertTrue(route.points.any { point -> point.x < bounds.left || point.y < bounds.top })
    }
}
