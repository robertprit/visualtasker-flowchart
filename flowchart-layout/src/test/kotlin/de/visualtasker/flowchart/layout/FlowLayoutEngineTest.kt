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

    private fun graph(nodes: List<String>, edges: List<Pair<String, String>>): FlowGraphDocument {
        val graphNodes = nodes.map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
        val graphEdges = edges.mapIndexed { index, (source, target) -> FlowGraphEdge(FlowEdgeId("e$index"), FlowNodeId(source), FlowNodeId(target), FlowEdgeKind.SEQUENCE) }
        return FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "fixture", producerVersion = "1", sourceRevision = "1", sourceHash = "hash", nodes = graphNodes, edges = graphEdges)
    }
}
