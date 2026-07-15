/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.domain

import kotlinx.serialization.Serializable

@Serializable public data class FlowPoint(public val x: Double, public val y: Double)
@Serializable public data class FlowSize(public val width: Double, public val height: Double)
@Serializable public data class FlowRect(public val origin: FlowPoint, public val size: FlowSize) {
    public val left: Double get() = origin.x
    public val top: Double get() = origin.y
    public val right: Double get() = origin.x + size.width
    public val bottom: Double get() = origin.y + size.height
    public fun contains(point: FlowPoint): Boolean = point.x in left..right && point.y in top..bottom
}

@Serializable
public data class FlowViewport(
    public val pan: FlowPoint = FlowPoint(0.0, 0.0),
    public val zoom: Double = 1.0,
)

@Serializable
public data class FlowNodeView(
    public val nodeId: FlowNodeId,
    public val position: FlowPoint,
    public val size: FlowSize? = null,
    public val collapsed: Boolean = false,
    public val visualToken: String? = null,
    public val pinned: Boolean = false,
    public val extensions: List<FlowGraphExtension> = emptyList(),
)

@Serializable public enum class FlowRouteLockState { AUTOMATIC, LOCKED }

@Serializable
public data class FlowEdgeView(
    public val edgeId: FlowEdgeId,
    public val bendPoints: List<FlowPoint> = emptyList(),
    public val labelPosition: FlowPoint? = null,
    public val routeLockState: FlowRouteLockState = FlowRouteLockState.AUTOMATIC,
    public val extensions: List<FlowGraphExtension> = emptyList(),
)

@Serializable
public data class FlowLayoutMetadata(
    public val algorithmId: String,
    public val algorithmVersion: String,
    public val deterministicSeed: Long,
    public val extensions: List<FlowGraphExtension> = emptyList(),
)

@Serializable
public data class FlowViewAnnotation(
    public val id: String,
    public val label: String,
    public val position: FlowPoint,
    public val extensions: List<FlowGraphExtension> = emptyList(),
) { init { require(id.isNotBlank()) } }

@Serializable
public data class FlowViewDocument(
    public val schemaVersion: String = CURRENT_SCHEMA_VERSION,
    public val documentId: FlowDocumentId,
    public val compatibleDocumentRevision: FlowDocumentRevision,
    public val surfaceId: FlowSurfaceId,
    public val viewport: FlowViewport = FlowViewport(),
    public val nodeViews: List<FlowNodeView> = emptyList(),
    public val edgeViews: List<FlowEdgeView> = emptyList(),
    public val annotations: List<FlowViewAnnotation> = emptyList(),
    public val layoutMetadata: FlowLayoutMetadata? = null,
    public val extensions: List<FlowGraphExtension> = emptyList(),
) { public companion object { public const val CURRENT_SCHEMA_VERSION: String = "1.0" } }
