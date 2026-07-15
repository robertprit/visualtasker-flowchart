/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.validation

import de.visualtasker.flowchart.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class ValidationTest {
    private val node = FlowGraphNode(FlowNodeId("n1"), FlowSemanticKind(FlowNodeKind.ENTRY), "Start")
    private val graph = FlowGraphDocument(documentId = FlowDocumentId("g"), documentRevision = FlowDocumentRevision("1"), producerId = "test", producerVersion = "1", sourceRevision = "1", sourceHash = "abc", entryNodeId = node.id, nodes = listOf(node))

    @Test public fun `duplicate and dangling elements are typed`() {
        val edge = FlowGraphEdge(FlowEdgeId("e"), node.id, FlowNodeId("missing"), FlowEdgeKind.SEQUENCE)
        val result = FlowGraphValidator.validate(graph.copy(nodes = listOf(node, node), edges = listOf(edge)))
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.code == FlowValidationCode.DUPLICATE_NODE_ID })
        assertTrue(result.diagnostics.any { it.code == FlowValidationCode.DANGLING_EDGE_TARGET })
    }

    @Test public fun `unknown view references are quarantined without semantic mutation`() {
        val view = FlowViewDocument(documentId = graph.documentId, compatibleDocumentRevision = graph.documentRevision, surfaceId = FlowSurfaceId("s"), nodeViews = listOf(FlowNodeView(FlowNodeId("unknown"), FlowPoint(0.0, 0.0))))
        assertEquals(emptyList<FlowNodeView>(), FlowViewValidator.quarantineUnknown(graph, view).nodeViews)
        assertEquals(1, graph.nodes.size)
    }

    @Test public fun `stale runtime is rejected`() {
        val old = snapshot(2)
        val result = FlowRuntimeSnapshotValidator.validate(graph, snapshot(1), FlowRuntimeValidationContext(previous = old))
        assertTrue(result.diagnostics.any { it.code == FlowValidationCode.STALE_SEQUENCE })
    }

    private fun snapshot(sequence: Long) = FlowRuntimeSnapshot(runId = FlowRunId("r"), sourceSessionId = FlowSourceSessionId("s"), documentId = graph.documentId, documentRevision = graph.documentRevision, sequence = sequence, capturedAtEpochMs = 1)
}
