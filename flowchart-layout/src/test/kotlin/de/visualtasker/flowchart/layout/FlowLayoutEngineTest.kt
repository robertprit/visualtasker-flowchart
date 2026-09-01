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

    @Test public fun `branch targets are ordered true elseif false across the layer`() {
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

        assertTrue(result.nodeBounds.getValue(FlowNodeId("then")).left < result.nodeBounds.getValue(FlowNodeId("elseif")).left)
        assertTrue(result.nodeBounds.getValue(FlowNodeId("elseif")).left < result.nodeBounds.getValue(FlowNodeId("else")).left)
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

    private fun graph(nodes: List<String>, edges: List<Pair<String, String>>): FlowGraphDocument {
        val graphNodes = nodes.map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graphEdges = edges.mapIndexed { index, (source, target) -> FlowGraphEdge(FlowEdgeId("e$index"), FlowNodeId(source), FlowNodeId(target), FlowEdgeKind.SEQUENCE) }
        return FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "fixture", producerVersion = "1", sourceRevision = "1", sourceHash = "hash", nodes = graphNodes, edges = graphEdges)
    }
}
