/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.serialization

import de.visualtasker.flowchart.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

public class CanonicalCodecsTest {
    private val graph = FlowGraphDocument(
        documentId = FlowDocumentId("graph"), documentRevision = FlowDocumentRevision("1"),
        producerId = "fixture", producerVersion = "1", sourceRevision = "source-1", sourceHash = "sha256:test",
        nodes = listOf(FlowGraphNode(FlowNodeId("n1"), FlowSemanticKind(FlowNodeKind.ACTION), "Action", properties = linkedMapOf("z" to FlowSemanticValue.StringValue("last"), "a" to FlowSemanticValue.NumberValue("1.50")))),
        extensions = listOf(FlowGraphExtension("vendor.value", FlowSemanticValue.StringValue("preserved"))),
    )

    @Test public fun `graph encoding is canonical round trippable and locale independent`() {
        val codec = FlowGraphJsonCodec()
        val originalLocale = Locale.getDefault()
        val first = codec.encodeCanonical(graph)
        try { Locale.setDefault(Locale.GERMANY); assertEquals(first, codec.encodeCanonical(graph)) } finally { Locale.setDefault(originalLocale) }
        val decoded = codec.decode(first) as FlowDecodeResult.Success
        assertEquals(graph, decoded.value)
        assertTrue(first.indexOf("\"a\"") < first.indexOf("\"z\""))
    }

    @Test public fun `unsupported major schema is typed`() {
        val encoded = FlowGraphJsonCodec().encodeCanonical(graph).replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
        assertTrue(FlowGraphJsonCodec().decode(encoded) is FlowDecodeResult.UnsupportedSchema)
    }

    @Test public fun `view and runtime codecs preserve deterministic collections`() {
        val view = FlowViewDocument(documentId = graph.documentId, compatibleDocumentRevision = graph.documentRevision, surfaceId = FlowSurfaceId("main"))
        assertEquals(FlowViewJsonCodec(graph).encodeCanonical(view), FlowViewJsonCodec(graph).encodeCanonical(view))
        val runtime = FlowRuntimeSnapshot(runId = FlowRunId("run"), sourceSessionId = FlowSourceSessionId("session"), documentId = graph.documentId, documentRevision = graph.documentRevision, sequence = 1, capturedAtEpochMs = 1)
        assertEquals(FlowRuntimeJsonCodec(graph).encodeCanonical(runtime), FlowRuntimeJsonCodec(graph).encodeCanonical(runtime))
    }

    @Test public fun `canonical graph matches checked in golden fixture`() {
        val goldenGraph = FlowGraphDocument(documentId = FlowDocumentId("golden"), documentRevision = FlowDocumentRevision("1"), producerId = "golden", producerVersion = "1", sourceRevision = "1", sourceHash = "hash")
        val expected = checkNotNull(javaClass.getResource("/golden/graph-empty-v1.json")).readText().trim()
        assertEquals(expected, FlowGraphJsonCodec().encodeCanonical(goldenGraph))
    }
}
