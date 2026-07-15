/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import kotlinx.serialization.Serializable

@Serializable public enum class FlowRuntimeNodeState { NOT_STARTED, QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, SKIPPED, CANCELLED }

@Serializable
public data class FlowRuntimeDiagnostic(
    public val id: FlowDiagnosticId,
    public val severity: FlowDiagnosticSeverity,
    public val code: String,
    public val message: String,
    public val nodeId: FlowNodeId? = null,
    public val edgeId: FlowEdgeId? = null,
) { init { require(code.isNotBlank()); require(message.isNotBlank()) } }

@Serializable
public data class FlowRuntimeSnapshot(
    public val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    public val runId: FlowRunId,
    public val sourceSessionId: FlowSourceSessionId,
    public val documentId: FlowDocumentId,
    public val documentRevision: FlowDocumentRevision,
    public val sequence: Long,
    public val capturedAtEpochMs: Long,
    public val activeNodeId: FlowNodeId? = null,
    public val nodeStates: Map<FlowNodeId, FlowRuntimeNodeState> = emptyMap(),
    public val traversedEdgeIds: List<FlowEdgeId> = emptyList(),
    public val loopIterationLabels: Map<FlowNodeId, String> = emptyMap(),
    public val diagnostics: List<FlowRuntimeDiagnostic> = emptyList(),
    public val extensions: List<FlowGraphExtension> = emptyList(),
) {
    init { require(sequence >= 0); require(capturedAtEpochMs >= 0) }
    public companion object { public const val CURRENT_SCHEMA_VERSION: String = "1.0" }
}
