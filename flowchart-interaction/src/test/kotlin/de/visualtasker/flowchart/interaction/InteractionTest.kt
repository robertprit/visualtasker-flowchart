/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.*
import org.junit.Assert.*
import org.junit.Test

public class InteractionTest {
    private val nodes = listOf("a", "b").map { FlowGraphNode(FlowNodeId(it), FlowSemanticKind(FlowNodeKind.ACTION), it) }
    private val graph = FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "test", producerVersion = "1", sourceRevision = "1", sourceHash = "h", nodes = nodes, edges = listOf(FlowGraphEdge(FlowEdgeId("e"), nodes[0].id, nodes[1].id, FlowEdgeKind.SEQUENCE)))
    private val view = FlowViewDocument(documentId = graph.documentId, compatibleDocumentRevision = graph.documentRevision, surfaceId = FlowSurfaceId("s"), nodeViews = listOf(FlowNodeView(nodes[0].id, FlowPoint(0.0, 0.0), FlowSize(10.0, 10.0)), FlowNodeView(nodes[1].id, FlowPoint(20.0, 0.0), FlowSize(10.0, 10.0))))

    @Test public fun `single drag commits then undo and redo`() {
        var result = FlowInteractionReducer.reduce(FlowInteractionState(), FlowInteractionAction.BeginNodeDrag(nodes[0].id, FlowPoint(0.0, 0.0)), graph, view)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.UpdateNodeDrag(FlowPoint(10.0, 5.0)), graph, result.view)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.CommitNodeDrag, graph, result.view)
        assertTrue(result.viewChanged); assertEquals(FlowPoint(10.0, 5.0), result.view.nodeViews[0].position)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.UndoViewChange, graph, result.view)
        assertEquals(FlowPoint(0.0, 0.0), result.view.nodeViews[0].position)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.RedoViewChange, graph, result.view)
        assertEquals(FlowPoint(10.0, 5.0), result.view.nodeViews[0].position)
    }

    @Test public fun `BFS movement moves connected group and cancel restores`() {
        var state = FlowInteractionState(movementMode = FlowMovementMode.CONNECTED_BFS)
        var result = FlowInteractionReducer.reduce(state, FlowInteractionAction.BeginNodeDrag(nodes[0].id, FlowPoint(0.0, 0.0)), graph, view)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.UpdateNodeDrag(FlowPoint(5.0, 0.0)), graph, result.view)
        assertEquals(FlowPoint(25.0, 0.0), result.view.nodeViews[1].position)
        result = FlowInteractionReducer.reduce(result.state, FlowInteractionAction.CancelNodeDrag, graph, result.view)
        assertEquals(view, result.view)
    }

    @Test public fun `next follow first moves only downstream nodes`() {
        val upstream = FlowGraphNode(FlowNodeId("upstream"), FlowSemanticKind(FlowNodeKind.ACTION), "upstream")
        val current = FlowGraphNode(FlowNodeId("current"), FlowSemanticKind(FlowNodeKind.ACTION), "current")
        val next = FlowGraphNode(FlowNodeId("next"), FlowSemanticKind(FlowNodeKind.ACTION), "next")
        val sibling = FlowGraphNode(FlowNodeId("sibling"), FlowSemanticKind(FlowNodeKind.ACTION), "sibling")
        val downstreamGraph = graph.copy(
            nodes = listOf(upstream, current, next, sibling),
            edges = listOf(
                FlowGraphEdge(FlowEdgeId("up-current"), upstream.id, current.id, FlowEdgeKind.SEQUENCE),
                FlowGraphEdge(FlowEdgeId("current-next"), current.id, next.id, FlowEdgeKind.SEQUENCE),
                FlowGraphEdge(FlowEdgeId("sibling-current"), sibling.id, current.id, FlowEdgeKind.SEQUENCE),
            ),
        )
        val downstreamView = view.copy(
            nodeViews = listOf(
                FlowNodeView(upstream.id, FlowPoint(0.0, 0.0), FlowSize(10.0, 10.0)),
                FlowNodeView(current.id, FlowPoint(20.0, 0.0), FlowSize(10.0, 10.0)),
                FlowNodeView(next.id, FlowPoint(40.0, 0.0), FlowSize(10.0, 10.0)),
                FlowNodeView(sibling.id, FlowPoint(60.0, 0.0), FlowSize(10.0, 10.0)),
            ),
        )

        var result = FlowInteractionReducer.reduce(
            FlowInteractionState(movementMode = FlowMovementMode.NEXT_FOLLOW_FIRST),
            FlowInteractionAction.BeginNodeDrag(current.id, FlowPoint(0.0, 0.0)),
            downstreamGraph,
            downstreamView,
        )
        result = FlowInteractionReducer.reduce(
            result.state,
            FlowInteractionAction.UpdateNodeDrag(FlowPoint(8.0, 4.0)),
            downstreamGraph,
            result.view,
        )

        assertEquals(FlowPoint(0.0, 0.0), result.view.nodeViews[0].position)
        assertEquals(FlowPoint(28.0, 4.0), result.view.nodeViews[1].position)
        assertEquals(FlowPoint(48.0, 4.0), result.view.nodeViews[2].position)
        assertEquals(FlowPoint(60.0, 0.0), result.view.nodeViews[3].position)
    }

    @Test public fun `screen drag delta is converted through viewport zoom and pan`() {
        listOf(0.5, 1.0, 2.0).forEach { zoom ->
            val transformedView = view.copy(viewport = FlowViewport(pan = FlowPoint(75.0, -30.0), zoom = zoom))
            var result = FlowInteractionReducer.reduce(
                FlowInteractionState(),
                FlowInteractionAction.BeginNodeDrag(nodes[0].id, FlowPoint(100.0, 50.0)),
                graph,
                transformedView,
            )
            result = FlowInteractionReducer.reduce(
                result.state,
                FlowInteractionAction.UpdateNodeDrag(FlowPoint(120.0, 60.0)),
                graph,
                result.view,
            )

            assertEquals(FlowPoint(20.0 / zoom, 10.0 / zoom), result.view.nodeViews[0].position)
        }
    }

    @Test public fun `controller is callback silent on attach and emits once after commit`() {
        val controller = FlowchartController(FlowSurfaceId("s")); var calls = 0; var committed: FlowViewDocument? = null
        controller.setListeners({ calls++; committed = controller.snapshot().view }, null)
        controller.attachGraph(graph, view); assertEquals(0, calls)
        controller.dispatch(FlowInteractionAction.ZoomViewport(2.0, FlowPoint(0.0, 0.0)))
        assertEquals(1, calls); assertEquals(controller.snapshot().view, committed)
        controller.close(); controller.dispatch(FlowInteractionAction.ClearSelection); assertEquals(1, calls)
    }

    @Test public fun `controller rebases stale view across same graph identity revision`() {
        val controller = FlowchartController(FlowSurfaceId("s"))
        val staleView = view.copy(
            viewport = FlowViewport(pan = FlowPoint(42.0, -17.0), zoom = 1.75),
            nodeViews = view.nodeViews.map {
                if (it.nodeId == nodes[0].id) it.copy(position = FlowPoint(123.0, 456.0)) else it
            },
        )
        val revisedGraph = graph.copy(documentRevision = FlowDocumentRevision("2"))

        val status = controller.attachGraph(revisedGraph, staleView)
        val installed = controller.snapshot().view!!

        assertEquals(FlowchartStatusCode.ATTACHED, status.code)
        assertEquals(revisedGraph.documentRevision, installed.compatibleDocumentRevision)
        assertEquals(staleView.viewport, installed.viewport)
        assertEquals(FlowPoint(123.0, 456.0), installed.nodeViews.single { it.nodeId == nodes[0].id }.position)

        controller.close()
    }

    @Test public fun `controller cancels transient drag and pan state`() {
        val controller = FlowchartController(FlowSurfaceId("s"))
        controller.attachGraph(graph, view)

        controller.dispatch(FlowInteractionAction.BeginNodeDrag(nodes[0].id, FlowPoint(0.0, 0.0)))
        controller.dispatch(FlowInteractionAction.UpdateNodeDrag(FlowPoint(12.0, 6.0)))
        controller.cancelTransientInteraction()

        assertNull(controller.snapshot().interaction.dragState)
        assertEquals(view.nodeViews[0].position, controller.snapshot().view!!.nodeViews[0].position)

        controller.dispatch(FlowInteractionAction.BeginViewportPan(FlowPoint(0.0, 0.0)))
        controller.dispatch(FlowInteractionAction.UpdateViewportPan(FlowPoint(20.0, 10.0)))
        controller.cancelTransientInteraction()

        assertNull(controller.snapshot().interaction.panState)
        assertEquals(view.viewport, controller.snapshot().view!!.viewport)

        controller.close()
    }

    @Test public fun `stale runtime snapshot is rejected`() {
        val controller = FlowchartController(FlowSurfaceId("s")); controller.attachGraph(graph, view)
        fun runtime(sequence: Long) = FlowRuntimeSnapshot(runId = FlowRunId("r"), sourceSessionId = FlowSourceSessionId("s"), documentId = graph.documentId, documentRevision = graph.documentRevision, sequence = sequence, capturedAtEpochMs = 1)
        assertEquals(FlowchartStatusCode.RUNTIME_ATTACHED, controller.attachRuntime(runtime(2)).code)
        assertEquals(FlowchartStatusCode.RUNTIME_REJECTED, controller.attachRuntime(runtime(1)).code)
    }
}
