/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphEdge
import de.visualtasker.flowchart.domain.FlowGraphNode
import de.visualtasker.flowchart.domain.FlowEdgeId
import de.visualtasker.flowchart.domain.FlowEdgeKind
import de.visualtasker.flowchart.domain.FlowNodeId
import de.visualtasker.flowchart.domain.FlowNodeKind
import de.visualtasker.flowchart.domain.FlowNodeView
import de.visualtasker.flowchart.domain.FlowPoint
import de.visualtasker.flowchart.domain.FlowSemanticKind
import de.visualtasker.flowchart.domain.FlowSize
import de.visualtasker.flowchart.domain.FlowSurfaceId
import de.visualtasker.flowchart.domain.FlowViewDocument
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowInteractionReducerTest {
    @Test
    fun viewportPanCannotMoveEveryNodeOutOfReach() {
        val graph = graph()
        val view = view()
        val begun = FlowInteractionReducer.reduce(
            state = FlowInteractionState(),
            action = FlowInteractionAction.BeginViewportPan(FlowPoint(0.0, 0.0)),
            graph = graph,
            view = view,
        )

        val panned = FlowInteractionReducer.reduce(
            state = begun.state,
            action = FlowInteractionAction.UpdateViewportPan(FlowPoint(-5000.0, -5000.0)),
            graph = graph,
            view = view,
        ).view

        assertTrue(panned.viewport.pan.x > -5000.0)
        assertTrue(panned.viewport.pan.y > -5000.0)
    }

    @Test
    fun defaultNodeDragMovesDownstreamNodesOnly() {
        val graph = graph(
            nodes = listOf("a", "b", "c", "side"),
            edges = listOf("a" to "b", "b" to "c"),
        )
        val view = view(
            "a" to FlowPoint(0.0, 0.0),
            "b" to FlowPoint(0.0, 100.0),
            "c" to FlowPoint(0.0, 200.0),
            "side" to FlowPoint(300.0, 0.0),
        )

        val begun = FlowInteractionReducer.reduce(
            state = FlowInteractionState(),
            action = FlowInteractionAction.BeginNodeDrag(FlowNodeId("b"), FlowPoint(0.0, 100.0)),
            graph = graph,
            view = view,
        )
        val moved = FlowInteractionReducer.reduce(
            state = begun.state,
            action = FlowInteractionAction.UpdateNodeDrag(FlowPoint(20.0, 130.0)),
            graph = graph,
            view = begun.view,
        ).view

        assertPosition(moved, "a", 0.0, 0.0)
        assertPosition(moved, "b", 20.0, 130.0)
        assertPosition(moved, "c", 20.0, 230.0)
        assertPosition(moved, "side", 300.0, 0.0)
    }

    private fun graph(
        nodes: List<String> = listOf("start"),
        edges: List<Pair<String, String>> = emptyList(),
    ): FlowGraphDocument {
        val nodeId = FlowNodeId(nodes.first())
        return FlowGraphDocument(
            documentId = FlowDocumentId("doc"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            entryNodeId = nodeId,
            nodes = nodes.map { id ->
                FlowGraphNode(FlowNodeId(id), FlowSemanticKind(FlowNodeKind.ACTION), id)
            },
            edges = edges.mapIndexed { index, edge ->
                FlowGraphEdge(
                    FlowEdgeId("e$index"),
                    FlowNodeId(edge.first),
                    FlowNodeId(edge.second),
                    FlowEdgeKind.SEQUENCE,
                )
            },
        )
    }

    private fun view(
        vararg positions: Pair<String, FlowPoint> = arrayOf("start" to FlowPoint(0.0, 0.0)),
    ): FlowViewDocument =
        FlowViewDocument(
            documentId = FlowDocumentId("doc"),
            compatibleDocumentRevision = FlowDocumentRevision("1"),
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = positions.map { (id, point) ->
                FlowNodeView(FlowNodeId(id), point, FlowSize(160.0, 72.0))
            },
        )

    private fun assertPosition(view: FlowViewDocument, id: String, x: Double, y: Double) {
        val position = view.nodeViews.first { it.nodeId == FlowNodeId(id) }.position
        org.junit.Assert.assertEquals(x, position.x, 0.001)
        org.junit.Assert.assertEquals(y, position.y, 0.001)
    }
}
