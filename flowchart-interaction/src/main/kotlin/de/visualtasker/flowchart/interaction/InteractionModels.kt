/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.*

public enum class FlowActiveTool { SELECT, PAN, MARQUEE }
public enum class FlowMovementMode { SINGLE, CONNECTED_BFS }

public data class FlowNodeDragState(public val anchor: FlowPoint, public val latest: FlowPoint, public val nodeIds: Set<FlowNodeId>, public val originalPositions: Map<FlowNodeId, FlowPoint>)
public data class FlowViewportPanState(public val anchor: FlowPoint, public val latest: FlowPoint, public val originalPan: FlowPoint)
public data class FlowMarqueeState(public val start: FlowPoint, public val end: FlowPoint)
public data class FlowRoutePreview(public val edgeId: FlowEdgeId, public val points: List<FlowPoint>)

public data class FlowInteractionState(
    public val selectedNodeIds: Set<FlowNodeId> = emptySet(),
    public val selectedEdgeIds: Set<FlowEdgeId> = emptySet(),
    public val hoveredNodeId: FlowNodeId? = null,
    public val hoveredEdgeId: FlowEdgeId? = null,
    public val activeTool: FlowActiveTool = FlowActiveTool.SELECT,
    public val movementMode: FlowMovementMode = FlowMovementMode.SINGLE,
    public val dragState: FlowNodeDragState? = null,
    public val panState: FlowViewportPanState? = null,
    public val marqueeState: FlowMarqueeState? = null,
    public val temporaryRoutePreview: FlowRoutePreview? = null,
    public val undoHistory: List<FlowViewDocument> = emptyList(),
    public val redoHistory: List<FlowViewDocument> = emptyList(),
    public val focusedDiagnosticId: FlowDiagnosticId? = null,
)

public sealed interface FlowInteractionAction {
    public data class SelectNode(public val nodeId: FlowNodeId) : FlowInteractionAction
    public data class ToggleNodeSelection(public val nodeId: FlowNodeId) : FlowInteractionAction
    public data class SelectEdge(public val edgeId: FlowEdgeId) : FlowInteractionAction
    public data object ClearSelection : FlowInteractionAction
    public data class BeginNodeDrag(public val nodeId: FlowNodeId, public val at: FlowPoint) : FlowInteractionAction
    public data class UpdateNodeDrag(public val at: FlowPoint) : FlowInteractionAction
    public data object CommitNodeDrag : FlowInteractionAction
    public data object CancelNodeDrag : FlowInteractionAction
    public data class BeginViewportPan(public val at: FlowPoint) : FlowInteractionAction
    public data class UpdateViewportPan(public val at: FlowPoint) : FlowInteractionAction
    public data object CommitViewportPan : FlowInteractionAction
    public data class ZoomViewport(public val factor: Double, public val anchor: FlowPoint) : FlowInteractionAction
    public data class MarqueeSelection(public val rect: FlowRect) : FlowInteractionAction
    public data object UndoViewChange : FlowInteractionAction
    public data object RedoViewChange : FlowInteractionAction
    public data class SetMovementMode(public val mode: FlowMovementMode) : FlowInteractionAction
}

public data class FlowInteractionResult(public val state: FlowInteractionState, public val view: FlowViewDocument, public val viewChanged: Boolean)
