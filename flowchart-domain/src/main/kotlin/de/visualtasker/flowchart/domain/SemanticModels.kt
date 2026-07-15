/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class FlowNodeKind {
    ENTRY, EXIT, ACTION, INPUT, OUTPUT, ASSIGNMENT, PROPERTY_ACCESS, DECISION,
    ELSE_IF, ELSE, LOOP_START, LOOP_END, TRY_START, CATCH, TRY_END,
    FUNCTION_START, FUNCTION_END, FUNCTION_CALL, ANNOTATION, SYNTHETIC, UNKNOWN_SOURCE,
}

@Serializable
public data class FlowSemanticKind(
    public val standard: FlowNodeKind? = null,
    public val extensionId: String? = null,
    public val displayName: String? = null,
) {
    init {
        require((standard == null) != (extensionId == null)) { "Exactly one standard or extension kind is required" }
        extensionId?.let { require(it.isNotBlank()) }
    }
}

@Serializable
public enum class FlowEdgeKind {
    SEQUENCE, TRUE_BRANCH, FALSE_BRANCH, ELSE_IF_BRANCH, LOOP_BODY, LOOP_BACK, LOOP_EXIT,
    TRY_BODY, CATCH_BODY, ERROR, FUNCTION_CALL, FUNCTION_RETURN, EVENT, GOTO,
}

@Serializable
public sealed interface FlowSemanticValue {
    @Serializable @SerialName("null") public data object NullValue : FlowSemanticValue
    @Serializable @SerialName("boolean") public data class BooleanValue(public val value: Boolean) : FlowSemanticValue
    @Serializable @SerialName("number") public data class NumberValue(public val canonicalValue: String) : FlowSemanticValue {
        init { require(canonicalValue.toBigDecimalOrNull() != null) { "Number must be canonicalizable" } }
    }
    @Serializable @SerialName("string") public data class StringValue(public val value: String) : FlowSemanticValue
    @Serializable @SerialName("list") public data class ListValue(public val values: List<FlowSemanticValue>) : FlowSemanticValue
    @Serializable @SerialName("object") public data class ObjectValue(public val values: Map<String, FlowSemanticValue>) : FlowSemanticValue
}

@Serializable
public data class FlowGraphExtension(
    public val key: String,
    public val value: FlowSemanticValue,
) {
    init { require(EXTENSION_KEY.matches(key)) { "Invalid extension key: $key" } }
    public companion object { public val EXTENSION_KEY: Regex = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*") }
}

@Serializable
public data class FlowSourceSpan(
    public val startOffset: Int,
    public val endOffsetExclusive: Int,
    public val startLine: Int,
    public val startColumn: Int,
    public val endLine: Int,
    public val endColumnExclusive: Int,
) {
    init {
        require(startOffset >= 0 && endOffsetExclusive >= startOffset)
        require(startLine >= 1 && startColumn >= 1 && endLine >= 1 && endColumnExclusive >= 1)
        require(endLine > startLine || endLine == startLine && endColumnExclusive >= startColumn)
    }
}

@Serializable
public data class FlowGraphSourceReference(
    public val producerDocumentId: String,
    public val sourceRevision: String,
    public val span: FlowSourceSpan? = null,
    public val canonicalText: String? = null,
    public val sourceKind: String? = null,
    public val extensions: List<FlowGraphExtension> = emptyList(),
) {
    init { require(producerDocumentId.isNotBlank()); require(sourceRevision.isNotBlank()) }
}

@Serializable public enum class FlowDiagnosticSeverity { INFO, WARNING, ERROR }

@Serializable
public data class FlowGraphDiagnostic(
    public val id: FlowDiagnosticId,
    public val severity: FlowDiagnosticSeverity,
    public val code: String,
    public val message: String,
    public val nodeId: FlowNodeId? = null,
    public val edgeId: FlowEdgeId? = null,
    public val sourceReference: FlowGraphSourceReference? = null,
    public val extensions: List<FlowGraphExtension> = emptyList(),
) {
    init { require(code.isNotBlank()); require(message.isNotBlank()) }
}

@Serializable
public data class FlowGraphNode(
    public val id: FlowNodeId,
    public val kind: FlowSemanticKind,
    public val label: String,
    public val sourceReference: FlowGraphSourceReference? = null,
    public val properties: Map<String, FlowSemanticValue> = emptyMap(),
    public val diagnosticIds: List<FlowDiagnosticId> = emptyList(),
    public val extensions: List<FlowGraphExtension> = emptyList(),
)

@Serializable
public data class FlowGraphEdge(
    public val id: FlowEdgeId,
    public val sourceNodeId: FlowNodeId,
    public val targetNodeId: FlowNodeId,
    public val kind: FlowEdgeKind,
    public val extensionKind: String? = null,
    public val label: String? = null,
    public val sourceReference: FlowGraphSourceReference? = null,
    public val diagnosticIds: List<FlowDiagnosticId> = emptyList(),
    public val extensions: List<FlowGraphExtension> = emptyList(),
)

@Serializable
public data class FlowGraphDocument(
    public val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    public val documentId: FlowDocumentId,
    public val documentRevision: FlowDocumentRevision,
    public val producerId: String,
    public val producerVersion: String,
    public val sourceRevision: String,
    public val sourceHash: String,
    public val entryNodeId: FlowNodeId? = null,
    public val nodes: List<FlowGraphNode> = emptyList(),
    public val edges: List<FlowGraphEdge> = emptyList(),
    public val diagnostics: List<FlowGraphDiagnostic> = emptyList(),
    public val extensions: List<FlowGraphExtension> = emptyList(),
) {
    init {
        require(producerId.isNotBlank()); require(producerVersion.isNotBlank())
        require(sourceRevision.isNotBlank()); require(sourceHash.isNotBlank())
    }
    public companion object { public const val CURRENT_SCHEMA_VERSION: String = "1.0" }
}
