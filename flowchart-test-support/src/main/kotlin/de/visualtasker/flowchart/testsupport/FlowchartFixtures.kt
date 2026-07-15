/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.testsupport

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.layout.*
import de.visualtasker.flowchart.serialization.FlowGraphJsonCodec

public object DeterministicFlowIds {
    public fun node(value: String): FlowNodeId = FlowNodeId("node-$value")
    public fun edge(index: Int): FlowEdgeId = FlowEdgeId("edge-${index.toString().padStart(3, '0')}")
}

public object FlowchartFixtures {
    public fun empty(): FlowGraphDocument = graph("empty", emptyList(), emptyList())
    public fun oneNode(): FlowGraphDocument = graph("one", listOf("start"), emptyList())
    public fun linear(): FlowGraphDocument = graph("linear", listOf("entry", "action", "exit"), listOf("entry" to "action", "action" to "exit"), kinds = mapOf("entry" to FlowNodeKind.ENTRY, "exit" to FlowNodeKind.EXIT))
    public fun branch(): FlowGraphDocument = graph("branch", listOf("decision", "true", "false", "join"), listOf("decision" to "true", "decision" to "false", "true" to "join", "false" to "join"), edgeKinds = listOf(FlowEdgeKind.TRUE_BRANCH, FlowEdgeKind.FALSE_BRANCH, FlowEdgeKind.SEQUENCE, FlowEdgeKind.SEQUENCE), kinds = mapOf("decision" to FlowNodeKind.DECISION))
    public fun nestedBranches(): FlowGraphDocument = graph("nested-branches", listOf("d1", "a", "d2", "b", "c"), listOf("d1" to "a", "d1" to "d2", "d2" to "b", "d2" to "c"), kinds = mapOf("d1" to FlowNodeKind.DECISION, "d2" to FlowNodeKind.DECISION))
    public fun simpleLoop(): FlowGraphDocument = graph("loop", listOf("start", "body", "end"), listOf("start" to "body", "body" to "body", "body" to "end"), edgeKinds = listOf(FlowEdgeKind.LOOP_BODY, FlowEdgeKind.LOOP_BACK, FlowEdgeKind.LOOP_EXIT), kinds = mapOf("start" to FlowNodeKind.LOOP_START, "end" to FlowNodeKind.LOOP_END))
    public fun nestedLoop(): FlowGraphDocument = graph("nested-loop", listOf("outer", "inner", "body", "end"), listOf("outer" to "inner", "inner" to "body", "body" to "inner", "inner" to "outer", "outer" to "end"), kinds = mapOf("outer" to FlowNodeKind.LOOP_START, "inner" to FlowNodeKind.LOOP_START, "end" to FlowNodeKind.LOOP_END))
    public fun loopWithBranch(): FlowGraphDocument = graph("loop-branch", listOf("loop", "decision", "yes", "no", "end"), listOf("loop" to "decision", "decision" to "yes", "decision" to "no", "yes" to "loop", "no" to "end"), kinds = mapOf("loop" to FlowNodeKind.LOOP_START, "decision" to FlowNodeKind.DECISION, "end" to FlowNodeKind.LOOP_END))
    public fun tryCatch(): FlowGraphDocument = graph("try-catch", listOf("try", "body", "catch", "end"), listOf("try" to "body", "body" to "end", "try" to "catch", "catch" to "end"), edgeKinds = listOf(FlowEdgeKind.TRY_BODY, FlowEdgeKind.SEQUENCE, FlowEdgeKind.CATCH_BODY, FlowEdgeKind.SEQUENCE), kinds = mapOf("try" to FlowNodeKind.TRY_START, "catch" to FlowNodeKind.CATCH, "end" to FlowNodeKind.TRY_END))
    public fun disconnected(): FlowGraphDocument = graph("disconnected", listOf("a", "b", "c", "d"), listOf("a" to "b", "c" to "d"))
    public fun longCrossingEdges(): FlowGraphDocument = graph("crossing", listOf("a", "b", "c", "d", "e"), listOf("a" to "b", "b" to "c", "c" to "d", "d" to "e", "a" to "e", "b" to "d"))
    public fun backEdge(): FlowGraphDocument = graph("back-edge", listOf("a", "b", "c"), listOf("a" to "b", "b" to "c", "c" to "a"))
    public fun syntheticAnnotation(): FlowGraphDocument = graph("annotation", listOf("a", "note"), emptyList(), kinds = mapOf("note" to FlowNodeKind.ANNOTATION))
    public fun unknownExtension(): FlowGraphDocument = graph("extension", listOf("vendor"), emptyList(), extensionKinds = mapOf("vendor" to "browser.capability"))
    public fun browserCapabilityChain(): FlowGraphDocument = graph("browser", listOf("open", "navigate", "inspect", "close"), listOf("open" to "navigate", "navigate" to "inspect", "inspect" to "close"), extensionKinds = mapOf("open" to "browser.open", "navigate" to "browser.navigate", "inspect" to "browser.inspect", "close" to "browser.close"))
    public fun largeDeterministic(size: Int = 100): FlowGraphDocument { require(size >= 1); val nodes = (0 until size).map { "n$it" }; return graph("large-$size", nodes, nodes.zipWithNext()) }

    public val all: Map<String, () -> FlowGraphDocument> = linkedMapOf(
        "Empty" to ::empty, "One node" to ::oneNode, "Linear" to ::linear, "Branch" to ::branch,
        "Nested branches" to ::nestedBranches, "Simple loop" to ::simpleLoop, "Nested loop" to ::nestedLoop,
        "Loop with branch" to ::loopWithBranch, "Try/catch" to ::tryCatch, "Disconnected" to ::disconnected,
        "Long crossing edges" to ::longCrossingEdges, "Back edge" to ::backEdge, "Synthetic annotation" to ::syntheticAnnotation,
        "Unknown extension" to ::unknownExtension, "Browser capability chain" to ::browserCapabilityChain, "Large deterministic" to { largeDeterministic() },
    )

    private fun graph(name: String, nodeNames: List<String>, connections: List<Pair<String, String>>, edgeKinds: List<FlowEdgeKind> = emptyList(), kinds: Map<String, FlowNodeKind> = emptyMap(), extensionKinds: Map<String, String> = emptyMap()): FlowGraphDocument {
        val nodes = nodeNames.map { value -> FlowGraphNode(DeterministicFlowIds.node(value), extensionKinds[value]?.let { FlowSemanticKind(extensionId = it, displayName = it.substringAfterLast('.')) } ?: FlowSemanticKind(kinds[value] ?: FlowNodeKind.ACTION), value.replaceFirstChar(Char::uppercase)) }
        val edges = connections.mapIndexed { index, (source, target) -> FlowGraphEdge(DeterministicFlowIds.edge(index), DeterministicFlowIds.node(source), DeterministicFlowIds.node(target), edgeKinds.getOrElse(index) { FlowEdgeKind.SEQUENCE }) }
        return FlowGraphDocument(documentId = FlowDocumentId("fixture-$name"), documentRevision = FlowDocumentRevision("1"), producerId = "visualtasker-flowchart-fixtures", producerVersion = "1", sourceRevision = "1", sourceHash = "fixture-$name", entryNodeId = nodes.firstOrNull()?.id, nodes = nodes, edges = edges)
    }
}

public object FlowchartAssertions {
    public fun requireDeterministicLayout(graph: FlowGraphDocument, config: FlowLayoutConfig = FlowLayoutConfig()): FlowLayoutResult {
        val first = FlowLayoutEngine.layout(graph, config = config); val second = FlowLayoutEngine.layout(graph, config = config)
        require(first == second) { "Layout is not deterministic" }; require(first.isValid) { "Layout is invalid" }; return first
    }
    public fun requireOrthogonalRoutes(result: FlowLayoutResult) { result.routes.values.flatMap { it.segments }.forEach { require(it.start.x == it.end.x || it.start.y == it.end.y) { "Route is not orthogonal" } } }
    public fun requireCanonicalRoundTrip(graph: FlowGraphDocument): String { val codec = FlowGraphJsonCodec(); val json = codec.encodeCanonical(graph); require(codec.encodeCanonical((codec.decode(json) as de.visualtasker.flowchart.serialization.FlowDecodeResult.Success).value) == json); return json }
}

public object FlowRuntimeSnapshotBuilder {
    public fun forGraph(graph: FlowGraphDocument, sequence: Long = 1, states: Map<FlowNodeId, FlowRuntimeNodeState> = emptyMap()): FlowRuntimeSnapshot = FlowRuntimeSnapshot(runId = FlowRunId("fixture-run"), sourceSessionId = FlowSourceSessionId("fixture-session"), documentId = graph.documentId, documentRevision = graph.documentRevision, sequence = sequence, capturedAtEpochMs = sequence, nodeStates = states)
}
