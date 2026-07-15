/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.validation

import de.visualtasker.flowchart.domain.*

public enum class FlowValidationSeverity { INFO, WARNING, ERROR }

public enum class FlowValidationCode {
    UNSUPPORTED_SCHEMA, BLANK_IDENTITY, DUPLICATE_NODE_ID, DUPLICATE_EDGE_ID,
    DANGLING_EDGE_SOURCE, DANGLING_EDGE_TARGET, INVALID_ENTRY_NODE, CONFLICTING_ENTRY_NODES,
    INVALID_SOURCE_SPAN, INVALID_DIAGNOSTIC_REFERENCE, INVALID_EXTENSION_KEY, INVALID_SEMANTIC_VALUE,
    GRAPH_IDENTITY_MISMATCH, REVISION_MISMATCH, UNKNOWN_NODE_VIEW, UNKNOWN_EDGE_VIEW,
    INVALID_VIEWPORT_ZOOM, INVALID_GEOMETRY, NON_FINITE_COORDINATE, SEMANTIC_MUTATION_ATTEMPT,
    DOCUMENT_MISMATCH, RUN_MISMATCH, SOURCE_SESSION_MISMATCH, STALE_SEQUENCE,
    UNKNOWN_ACTIVE_NODE, UNKNOWN_RUNTIME_NODE, UNKNOWN_TRAVERSED_EDGE,
}

public data class FlowValidationDiagnostic(
    public val code: FlowValidationCode,
    public val message: String,
    public val severity: FlowValidationSeverity = FlowValidationSeverity.ERROR,
    public val nodeId: FlowNodeId? = null,
    public val edgeId: FlowEdgeId? = null,
)

public data class FlowValidationResult(public val diagnostics: List<FlowValidationDiagnostic>) {
    public val isValid: Boolean get() = diagnostics.none { it.severity == FlowValidationSeverity.ERROR }
    public companion object { public val VALID: FlowValidationResult = FlowValidationResult(emptyList()) }
}

internal fun schemaMajor(version: String): Int? = version.substringBefore('.').toIntOrNull()
internal fun Double.isFinitePositive(): Boolean = isFinite() && this > 0.0
internal fun FlowPoint.isFinitePoint(): Boolean = x.isFinite() && y.isFinite()

public object FlowGraphValidator {
    public fun validate(graph: FlowGraphDocument): FlowValidationResult {
        val out = mutableListOf<FlowValidationDiagnostic>()
        if (schemaMajor(graph.schemaVersion) != 1) out += diagnostic(FlowValidationCode.UNSUPPORTED_SCHEMA, "Unsupported graph schema ${graph.schemaVersion}")
        if (graph.producerId.isBlank() || graph.producerVersion.isBlank() || graph.sourceRevision.isBlank() || graph.sourceHash.isBlank()) {
            out += diagnostic(FlowValidationCode.BLANK_IDENTITY, "Graph producer and source identity must not be blank")
        }
        duplicates(graph.nodes.map { it.id }).forEach { out += diagnostic(FlowValidationCode.DUPLICATE_NODE_ID, "Duplicate node $it", nodeId = it) }
        duplicates(graph.edges.map { it.id }).forEach { out += diagnostic(FlowValidationCode.DUPLICATE_EDGE_ID, "Duplicate edge $it", edgeId = it) }
        val nodes = graph.nodes.map { it.id }.toSet()
        val edges = graph.edges.map { it.id }.toSet()
        graph.edges.forEach { edge ->
            if (edge.sourceNodeId !in nodes) out += diagnostic(FlowValidationCode.DANGLING_EDGE_SOURCE, "Unknown source ${edge.sourceNodeId.value}", edgeId = edge.id)
            if (edge.targetNodeId !in nodes) out += diagnostic(FlowValidationCode.DANGLING_EDGE_TARGET, "Unknown target ${edge.targetNodeId.value}", edgeId = edge.id)
        }
        graph.entryNodeId?.takeIf { it !in nodes }?.let { out += diagnostic(FlowValidationCode.INVALID_ENTRY_NODE, "Entry node does not exist", nodeId = it) }
        if (graph.nodes.count { it.kind.standard == FlowNodeKind.ENTRY } > 1) out += diagnostic(FlowValidationCode.CONFLICTING_ENTRY_NODES, "Multiple ENTRY nodes")
        val diagnosticIds = graph.diagnostics.map { it.id }.toSet()
        graph.nodes.forEach { node -> node.diagnosticIds.filterNot(diagnosticIds::contains).forEach { out += diagnostic(FlowValidationCode.INVALID_DIAGNOSTIC_REFERENCE, "Unknown diagnostic ${it.value}", nodeId = node.id) } }
        graph.edges.forEach { edge -> edge.diagnosticIds.filterNot(diagnosticIds::contains).forEach { out += diagnostic(FlowValidationCode.INVALID_DIAGNOSTIC_REFERENCE, "Unknown diagnostic ${it.value}", edgeId = edge.id) } }
        graph.diagnostics.forEach { value ->
            if (value.nodeId != null && value.nodeId !in nodes || value.edgeId != null && value.edgeId !in edges) out += diagnostic(FlowValidationCode.INVALID_DIAGNOSTIC_REFERENCE, "Diagnostic target does not exist")
        }
        allExtensions(graph).forEach { extension -> if (!FlowGraphExtension.EXTENSION_KEY.matches(extension.key)) out += diagnostic(FlowValidationCode.INVALID_EXTENSION_KEY, "Invalid extension key ${extension.key}") }
        return FlowValidationResult(out)
    }

    private fun allExtensions(graph: FlowGraphDocument): Sequence<FlowGraphExtension> = sequence {
        yieldAll(graph.extensions)
        graph.nodes.forEach { yieldAll(it.extensions); it.sourceReference?.let { ref -> yieldAll(ref.extensions) } }
        graph.edges.forEach { yieldAll(it.extensions); it.sourceReference?.let { ref -> yieldAll(ref.extensions) } }
        graph.diagnostics.forEach { yieldAll(it.extensions); it.sourceReference?.let { ref -> yieldAll(ref.extensions) } }
    }
}

public object FlowViewValidator {
    public fun validate(graph: FlowGraphDocument, view: FlowViewDocument): FlowValidationResult {
        val out = mutableListOf<FlowValidationDiagnostic>()
        if (schemaMajor(view.schemaVersion) != 1) out += diagnostic(FlowValidationCode.UNSUPPORTED_SCHEMA, "Unsupported view schema ${view.schemaVersion}")
        if (view.documentId != graph.documentId) out += diagnostic(FlowValidationCode.GRAPH_IDENTITY_MISMATCH, "View belongs to another graph")
        if (view.compatibleDocumentRevision != graph.documentRevision) out += diagnostic(FlowValidationCode.REVISION_MISMATCH, "View revision is stale")
        if (!view.viewport.zoom.isFinitePositive()) out += diagnostic(FlowValidationCode.INVALID_VIEWPORT_ZOOM, "Viewport zoom must be finite and positive")
        if (!view.viewport.pan.isFinitePoint()) out += diagnostic(FlowValidationCode.NON_FINITE_COORDINATE, "Viewport pan must be finite")
        val nodes = graph.nodes.map { it.id }.toSet()
        val edges = graph.edges.map { it.id }.toSet()
        view.nodeViews.forEach {
            if (it.nodeId !in nodes) out += diagnostic(FlowValidationCode.UNKNOWN_NODE_VIEW, "Unknown view node ${it.nodeId.value}", nodeId = it.nodeId, severity = FlowValidationSeverity.WARNING)
            if (!it.position.isFinitePoint() || it.size?.let { size -> !size.width.isFinitePositive() || !size.height.isFinitePositive() } == true) out += diagnostic(FlowValidationCode.INVALID_GEOMETRY, "Invalid node geometry", nodeId = it.nodeId)
        }
        view.edgeViews.forEach {
            if (it.edgeId !in edges) out += diagnostic(FlowValidationCode.UNKNOWN_EDGE_VIEW, "Unknown view edge ${it.edgeId.value}", edgeId = it.edgeId, severity = FlowValidationSeverity.WARNING)
            if (it.bendPoints.any { point -> !point.isFinitePoint() } || it.labelPosition?.let { point -> !point.isFinitePoint() } == true) out += diagnostic(FlowValidationCode.NON_FINITE_COORDINATE, "Invalid edge geometry", edgeId = it.edgeId)
        }
        return FlowValidationResult(out)
    }

    public fun quarantineUnknown(graph: FlowGraphDocument, view: FlowViewDocument): FlowViewDocument {
        val nodes = graph.nodes.map { it.id }.toSet()
        val edges = graph.edges.map { it.id }.toSet()
        return view.copy(nodeViews = view.nodeViews.filter { it.nodeId in nodes }, edgeViews = view.edgeViews.filter { it.edgeId in edges })
    }
}

public data class FlowRuntimeValidationContext(
    public val previous: FlowRuntimeSnapshot? = null,
    public val expectedRunId: FlowRunId? = null,
    public val expectedSourceSessionId: FlowSourceSessionId? = null,
)

public object FlowRuntimeSnapshotValidator {
    public fun validate(graph: FlowGraphDocument, snapshot: FlowRuntimeSnapshot, context: FlowRuntimeValidationContext = FlowRuntimeValidationContext()): FlowValidationResult {
        val out = mutableListOf<FlowValidationDiagnostic>()
        if (schemaMajor(snapshot.schemaVersion) != 1) out += diagnostic(FlowValidationCode.UNSUPPORTED_SCHEMA, "Unsupported runtime schema ${snapshot.schemaVersion}")
        if (snapshot.documentId != graph.documentId) out += diagnostic(FlowValidationCode.DOCUMENT_MISMATCH, "Runtime belongs to another graph")
        if (snapshot.documentRevision != graph.documentRevision) out += diagnostic(FlowValidationCode.REVISION_MISMATCH, "Runtime graph revision differs")
        if (context.expectedRunId != null && snapshot.runId != context.expectedRunId) out += diagnostic(FlowValidationCode.RUN_MISMATCH, "Unexpected run")
        if (context.expectedSourceSessionId != null && snapshot.sourceSessionId != context.expectedSourceSessionId) out += diagnostic(FlowValidationCode.SOURCE_SESSION_MISMATCH, "Unexpected source session")
        context.previous?.let { previous ->
            if (snapshot.runId != previous.runId) out += diagnostic(FlowValidationCode.RUN_MISMATCH, "Snapshot run changed")
            if (snapshot.sourceSessionId != previous.sourceSessionId) out += diagnostic(FlowValidationCode.SOURCE_SESSION_MISMATCH, "Source session changed")
            if (snapshot.sequence <= previous.sequence) out += diagnostic(FlowValidationCode.STALE_SEQUENCE, "Snapshot sequence is stale")
        }
        val nodes = graph.nodes.map { it.id }.toSet()
        val edges = graph.edges.map { it.id }.toSet()
        snapshot.activeNodeId?.takeIf { it !in nodes }?.let { out += diagnostic(FlowValidationCode.UNKNOWN_ACTIVE_NODE, "Unknown active node", nodeId = it) }
        snapshot.nodeStates.keys.filterNot(nodes::contains).forEach { out += diagnostic(FlowValidationCode.UNKNOWN_RUNTIME_NODE, "Unknown runtime node", nodeId = it) }
        snapshot.traversedEdgeIds.filterNot(edges::contains).forEach { out += diagnostic(FlowValidationCode.UNKNOWN_TRAVERSED_EDGE, "Unknown traversed edge", edgeId = it) }
        return FlowValidationResult(out)
    }
}

private fun <T> duplicates(values: List<T>): Set<T> = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

private fun diagnostic(
    code: FlowValidationCode,
    message: String,
    severity: FlowValidationSeverity = FlowValidationSeverity.ERROR,
    nodeId: FlowNodeId? = null,
    edgeId: FlowEdgeId? = null,
): FlowValidationDiagnostic = FlowValidationDiagnostic(code, message, severity, nodeId, edgeId)
