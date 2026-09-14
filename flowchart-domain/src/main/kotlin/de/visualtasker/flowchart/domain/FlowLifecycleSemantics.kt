/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import kotlinx.serialization.Serializable

@Serializable
public enum class FlowExecutionKind(public val wireValue: String) {
    WORKFLOW("workflow"),
    RECORDING("recording"),
    DRY_RUN("dry-run"),
}

@Serializable
public enum class FlowTerminatorRole(public val wireValue: String) {
    START("start"),
    END("end"),
}

public object FlowLifecycleSemantics {
    public const val EXECUTION_KIND_KEY: String = "visualtasker.execution-kind"
    public const val TERMINATOR_ROLE_PROPERTY: String = "executionTerminatorRole"
    public const val EXECUTION_KIND_PROPERTY: String = "executionKind"

    public fun graphExtension(kind: FlowExecutionKind): FlowGraphExtension =
        FlowGraphExtension(EXECUTION_KIND_KEY, FlowSemanticValue.StringValue(kind.wireValue))

    public fun nodeProperties(
        kind: FlowExecutionKind,
        role: FlowTerminatorRole,
    ): Map<String, FlowSemanticValue> = mapOf(
        EXECUTION_KIND_PROPERTY to FlowSemanticValue.StringValue(kind.wireValue),
        TERMINATOR_ROLE_PROPERTY to FlowSemanticValue.StringValue(role.wireValue),
    )
}

public fun FlowGraphDocument.executionKind(): FlowExecutionKind =
    extensions.executionKindOrNull() ?: FlowExecutionKind.WORKFLOW

public fun FlowRuntimeSnapshot.executionKindOrNull(): FlowExecutionKind? =
    extensions.executionKindOrNull()

public fun FlowGraphNode.terminatorRole(): FlowTerminatorRole? =
    (properties[FlowLifecycleSemantics.TERMINATOR_ROLE_PROPERTY] as? FlowSemanticValue.StringValue)
        ?.value
        ?.let { value -> FlowTerminatorRole.entries.firstOrNull { it.wireValue == value } }

private fun List<FlowGraphExtension>.executionKindOrNull(): FlowExecutionKind? =
    firstOrNull { it.key == FlowLifecycleSemantics.EXECUTION_KIND_KEY }
        ?.value
        ?.let { it as? FlowSemanticValue.StringValue }
        ?.value
        ?.let { value -> FlowExecutionKind.entries.firstOrNull { it.wireValue == value } }
