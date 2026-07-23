/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.FlowDocumentId
import de.visualtasker.flowchart.domain.FlowDocumentRevision
import de.visualtasker.flowchart.domain.FlowGraphDocument
import de.visualtasker.flowchart.domain.FlowGraphNode
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

    private fun graph(): FlowGraphDocument {
        val nodeId = FlowNodeId("start")
        return FlowGraphDocument(
            documentId = FlowDocumentId("doc"),
            documentRevision = FlowDocumentRevision("1"),
            producerId = "test",
            producerVersion = "1",
            sourceRevision = "1",
            sourceHash = "hash",
            entryNodeId = nodeId,
            nodes = listOf(FlowGraphNode(nodeId, FlowSemanticKind(FlowNodeKind.ENTRY), "Start")),
        )
    }

    private fun view(): FlowViewDocument =
        FlowViewDocument(
            documentId = FlowDocumentId("doc"),
            compatibleDocumentRevision = FlowDocumentRevision("1"),
            surfaceId = FlowSurfaceId("surface"),
            nodeViews = listOf(
                FlowNodeView(FlowNodeId("start"), FlowPoint(0.0, 0.0), FlowSize(160.0, 72.0)),
            ),
        )
}
