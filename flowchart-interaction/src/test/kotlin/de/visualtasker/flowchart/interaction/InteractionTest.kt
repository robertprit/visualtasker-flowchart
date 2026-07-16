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

    @Test public fun `stale runtime snapshot is rejected`() {
        val controller = FlowchartController(FlowSurfaceId("s")); controller.attachGraph(graph, view)
        fun runtime(sequence: Long) = FlowRuntimeSnapshot(runId = FlowRunId("r"), sourceSessionId = FlowSourceSessionId("s"), documentId = graph.documentId, documentRevision = graph.documentRevision, sequence = sequence, capturedAtEpochMs = 1)
        assertEquals(FlowchartStatusCode.RUNTIME_ATTACHED, controller.attachRuntime(runtime(2)).code)
        assertEquals(FlowchartStatusCode.RUNTIME_REJECTED, controller.attachRuntime(runtime(1)).code)
    }
}
