/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowEdgeId
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphEdge
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowLayoutMetadata
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowNodeView
import de.visualtasker.flowchart.domain.FlowPoint
import de.visualtasker.flowchart.domain.FlowSemanticKind
import de.visualtasker.flowchart.domain.FlowSize
import de.visualtasker.flowchart.domain.FlowSurfaceId
import de.visualtasker.flowchart.domain.FlowViewDocument
import de.visualtasker.flowchart.domain.FlowViewport
import de.visualtasker.flowchart.layout.FlowLayoutEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRestoredViewCharacterizationTest {
    @Test
    fun metadataLessRestoredViewDoesNotOverrideFreshBranchAutoLayout() {
        val graph = branchingGraph()
        val controller = FlowchartController(FlowSurfaceId("surface"))
        val viewport = FlowViewport(FlowPoint(48.0, -32.0), 0.85)
        val overlappingRestoredView = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            viewport = viewport,
            nodeViews = graph.nodes.map { node ->
                FlowNodeView(
                    nodeId = node.id,
                    position = FlowPoint(0.0, 0.0),
                    size = FlowSize(160.0, 72.0),
                )
            },
        )

        val status = controller.attachGraph(graph, overlappingRestoredView)
        val installedView = controller.snapshot().view!!

        assertEquals(FlowchartStatusCode.ATTACHED, status.code)
        assertTrue(installedView.nodeViews.size > 8)
        assertTrue(
            "A restored view without layout metadata must be refreshed instead of preserving overlapping node positions.",
            installedView.nodeViews.map { it.position }.toSet().size > 1,
        )
        assertEquals(viewport, installedView.viewport)
        assertEquals(FlowLayoutEngine.ALGORITHM_ID, installedView.layoutMetadata?.algorithmId)
        assertEquals(FlowLayoutEngine.ALGORITHM_VERSION, installedView.layoutMetadata?.algorithmVersion)
    }

    @Test
    fun restoredViewWithCompatibleLayoutMetadataRemainsGeometryAuthority() {
        val graph = branchingGraph()
        val seedController = FlowchartController(FlowSurfaceId("surface"))
        seedController.attachGraph(graph)
        val seedView = seedController.snapshot().view!!
        val restoredViewport = FlowViewport(FlowPoint(-24.0, 96.0), 1.25)
        val shiftedNodeViews = seedView.nodeViews.map { view ->
            view.copy(position = FlowPoint(view.position.x + 24.0, view.position.y + 36.0))
        }
        val restoredView = seedView.copy(
            viewport = restoredViewport,
            nodeViews = shiftedNodeViews,
        )
        val controller = FlowchartController(FlowSurfaceId("surface"))

        val status = controller.attachGraph(graph, restoredView)
        val installedView = controller.snapshot().view!!

        assertEquals(FlowchartStatusCode.ATTACHED, status.code)
        assertEquals(restoredViewport, installedView.viewport)
        assertEquals(shiftedNodeViews.map { it.position }, installedView.nodeViews.map { it.position })
    }

    @Test
    fun restoredViewWithIncompatibleLayoutMetadataRefreshesBranchGeometry() {
        val graph = branchingGraph()
        val controller = FlowchartController(FlowSurfaceId("surface"))
        val viewport = FlowViewport(FlowPoint(12.0, 16.0), 0.9)
        val overlappingRestoredView = FlowViewDocument(
            documentId = graph.documentId,
            compatibleDocumentRevision = graph.documentRevision,
            surfaceId = FlowSurfaceId("surface"),
            viewport = viewport,
            nodeViews = graph.nodes.map { node ->
                FlowNodeView(
                    nodeId = node.id,
                    position = FlowPoint(0.0, 0.0),
                    size = FlowSize(160.0, 72.0),
                )
            },
            layoutMetadata = FlowLayoutMetadata(
                algorithmId = FlowLayoutEngine.ALGORITHM_ID,
                algorithmVersion = "legacy",
                deterministicSeed = 0L,
            ),
        )

        val status = controller.attachGraph(graph, overlappingRestoredView)
        val installedView = controller.snapshot().view!!

        assertEquals(FlowchartStatusCode.ATTACHED, status.code)
        assertNotEquals(1, installedView.nodeViews.map { it.position }.toSet().size)
        assertEquals(viewport, installedView.viewport)
        assertEquals(FlowLayoutEngine.ALGORITHM_VERSION, installedView.layoutMetadata?.algorithmVersion)
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
