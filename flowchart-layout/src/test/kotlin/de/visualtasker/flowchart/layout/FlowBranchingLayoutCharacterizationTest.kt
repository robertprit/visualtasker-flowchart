/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowEdgeId
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphEdge
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowRect
import de.visualtasker.flowchart.domain.FlowSemanticKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowBranchingLayoutCharacterizationTest {
    @Test
    fun multiBranchIfElseIfElseAutoLayoutKeepsRoutesOrthogonalAndOutsideNodes() {
        val graph = branchingGraph()

        val result = FlowLayoutEngine.layout(graph)

        assertTrue(result.isValid)
        assertEquals(FlowRouteKind.BRANCH, result.routes.getValue(FlowEdgeId("e-if-then")).kind)
        assertEquals(FlowRouteKind.BRANCH, result.routes.getValue(FlowEdgeId("e-if-elseif-retry")).kind)
        assertEquals(FlowRouteKind.BRANCH, result.routes.getValue(FlowEdgeId("e-retry-elseif-image")).kind)
        assertEquals(FlowRouteKind.BRANCH, result.routes.getValue(FlowEdgeId("e-timeout-else")).kind)
        assertTrue(result.ranks.getValue(FlowNodeId("join")) > result.ranks.getValue(FlowNodeId("elseif-timeout")))
        assertTrue(result.pipelineArtifacts!!.dummyNodeInsertion.edgeIdsUsingDummyNodes.contains(FlowEdgeId("e-scan-login-join")))
        assertTrue(result.pipelineArtifacts!!.dummyNodeInsertion.edgeIdsUsingDummyNodes.contains(FlowEdgeId("e-click-image-join")))

        result.routes.values.forEach { route ->
            assertTrue(
                "Route ${route.edgeId.value} must stay orthogonal: ${route.points}",
                route.segments.all { it.start.x == it.end.x || it.start.y == it.end.y },
            )
            result.nodeBounds
                .filterKeys { nodeId -> nodeId != routeSource(graph, route.edgeId) && nodeId != routeTarget(graph, route.edgeId) }
                .values
                .forEach { obstacle -> assertRouteAvoidsNodeInterior(route, obstacle) }
        }
    }

    private fun routeSource(graph: FlowGraphDocument, edgeId: FlowEdgeId): FlowNodeId =
        graph.edges.single { it.id == edgeId }.sourceNodeId

    private fun routeTarget(graph: FlowGraphDocument, edgeId: FlowEdgeId): FlowNodeId =
        graph.edges.single { it.id == edgeId }.targetNodeId

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
            assertFalse("Route ${route.edgeId.value} crosses $obstacle via ${route.points}", crossesVertical || crossesHorizontal)
        }
    }

    private fun branchingGraph(): FlowGraphDocument =
        FlowGraphDocument(
            documentId = FlowDocumentId("branching"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            entryNodeId = FlowNodeId("entry"),
            nodes = listOf(
                node("entry", FlowNodeKind.ENTRY),
                node("if-login", FlowNodeKind.DECISION),
                node("click-login", FlowNodeKind.ACTION),
                node("scan-after-login", FlowNodeKind.ACTION),
                node("elseif-retry", FlowNodeKind.ELSE_IF),
                node("scan-retry", FlowNodeKind.ACTION),
                node("click-retry", FlowNodeKind.ACTION),
                node("elseif-image", FlowNodeKind.ELSE_IF),
                node("click-image", FlowNodeKind.ACTION),
                node("elseif-enabled", FlowNodeKind.ELSE_IF),
                node("click-enabled", FlowNodeKind.ACTION),
                node("scan-enabled", FlowNodeKind.ACTION),
                node("elseif-timeout", FlowNodeKind.ELSE_IF),
                node("screenshot-timeout", FlowNodeKind.ACTION),
                node("else", FlowNodeKind.ELSE),
                node("screenshot-missing", FlowNodeKind.ACTION),
                node("join", FlowNodeKind.ACTION),
            ),
            edges = listOf(
                edge("e-entry-if", "entry", "if-login", FlowEdgeKind.SEQUENCE),
                edge("e-if-then", "if-login", "click-login", FlowEdgeKind.TRUE_BRANCH),
                edge("e-click-login-scan", "click-login", "scan-after-login", FlowEdgeKind.SEQUENCE),
                edge("e-scan-login-join", "scan-after-login", "join", FlowEdgeKind.SEQUENCE),
                edge("e-if-elseif-retry", "if-login", "elseif-retry", FlowEdgeKind.FALSE_BRANCH),
                edge("e-retry-then", "elseif-retry", "scan-retry", FlowEdgeKind.TRUE_BRANCH),
                edge("e-scan-retry-click", "scan-retry", "click-retry", FlowEdgeKind.SEQUENCE),
                edge("e-click-retry-join", "click-retry", "join", FlowEdgeKind.SEQUENCE),
                edge("e-retry-elseif-image", "elseif-retry", "elseif-image", FlowEdgeKind.ELSE_IF_BRANCH),
                edge("e-image-then", "elseif-image", "click-image", FlowEdgeKind.TRUE_BRANCH),
                edge("e-click-image-join", "click-image", "join", FlowEdgeKind.SEQUENCE),
                edge("e-image-elseif-enabled", "elseif-image", "elseif-enabled", FlowEdgeKind.ELSE_IF_BRANCH),
                edge("e-enabled-then", "elseif-enabled", "click-enabled", FlowEdgeKind.TRUE_BRANCH),
                edge("e-click-enabled-scan", "click-enabled", "scan-enabled", FlowEdgeKind.SEQUENCE),
                edge("e-scan-enabled-join", "scan-enabled", "join", FlowEdgeKind.SEQUENCE),
                edge("e-enabled-elseif-timeout", "elseif-enabled", "elseif-timeout", FlowEdgeKind.ELSE_IF_BRANCH),
                edge("e-timeout-then", "elseif-timeout", "screenshot-timeout", FlowEdgeKind.TRUE_BRANCH),
                edge("e-timeout-shot-join", "screenshot-timeout", "join", FlowEdgeKind.SEQUENCE),
                edge("e-timeout-else", "elseif-timeout", "else", FlowEdgeKind.FALSE_BRANCH),
                edge("e-else-body", "else", "screenshot-missing", FlowEdgeKind.SEQUENCE),
                edge("e-else-join", "screenshot-missing", "join", FlowEdgeKind.SEQUENCE),
            ),
        )

    private fun node(id: String, kind: FlowNodeKind): FlowGraphNode =
        FlowGraphNode(FlowNodeId(id), FlowSemanticKind(kind), id)

    private fun edge(id: String, from: String, to: String, kind: FlowEdgeKind): FlowGraphEdge =
        FlowGraphEdge(FlowEdgeId(id), FlowNodeId(from), FlowNodeId(to), kind)
}
