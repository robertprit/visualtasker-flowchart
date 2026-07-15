/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.*
import java.util.ArrayDeque

public object FlowInteractionReducer {
    public fun reduce(state: FlowInteractionState, action: FlowInteractionAction, graph: FlowGraphDocument, view: FlowViewDocument): FlowInteractionResult = when (action) {
        is FlowInteractionAction.SelectNode -> result(state.copy(selectedNodeIds = setOf(action.nodeId), selectedEdgeIds = emptySet()), view)
        is FlowInteractionAction.ToggleNodeSelection -> result(state.copy(selectedNodeIds = state.selectedNodeIds.toMutableSet().apply { if (!add(action.nodeId)) remove(action.nodeId) }, selectedEdgeIds = emptySet()), view)
        is FlowInteractionAction.SelectEdge -> result(state.copy(selectedNodeIds = emptySet(), selectedEdgeIds = setOf(action.edgeId)), view)
        FlowInteractionAction.ClearSelection -> result(state.copy(selectedNodeIds = emptySet(), selectedEdgeIds = emptySet()), view)
        is FlowInteractionAction.SetMovementMode -> result(state.copy(movementMode = action.mode), view)
        is FlowInteractionAction.BeginNodeDrag -> beginDrag(state, action, graph, view)
        is FlowInteractionAction.UpdateNodeDrag -> updateDrag(state, action, view)
        FlowInteractionAction.CommitNodeDrag -> commitDrag(state, view)
        FlowInteractionAction.CancelNodeDrag -> cancelDrag(state, view)
        is FlowInteractionAction.BeginViewportPan -> result(state.copy(panState = FlowViewportPanState(action.at, action.at, view.viewport.pan)), view)
        is FlowInteractionAction.UpdateViewportPan -> updatePan(state, action, view)
        FlowInteractionAction.CommitViewportPan -> commitPan(state, view)
        is FlowInteractionAction.ZoomViewport -> zoom(state, action, view)
        is FlowInteractionAction.MarqueeSelection -> marquee(state, action, view)
        FlowInteractionAction.UndoViewChange -> undo(state, view)
        FlowInteractionAction.RedoViewChange -> redo(state, view)
    }

    private fun beginDrag(state: FlowInteractionState, action: FlowInteractionAction.BeginNodeDrag, graph: FlowGraphDocument, view: FlowViewDocument): FlowInteractionResult {
        val ids = if (state.movementMode == FlowMovementMode.CONNECTED_BFS) connected(graph, action.nodeId) else setOf(action.nodeId)
        val positions = view.nodeViews.filter { it.nodeId in ids }.associate { it.nodeId to it.position }
        return result(state.copy(dragState = FlowNodeDragState(action.at, action.at, ids, positions)), view)
    }

    private fun updateDrag(state: FlowInteractionState, action: FlowInteractionAction.UpdateNodeDrag, view: FlowViewDocument): FlowInteractionResult {
        val drag = state.dragState ?: return result(state, view)
        val dx = action.at.x - drag.anchor.x; val dy = action.at.y - drag.anchor.y
        val changed = view.copy(nodeViews = view.nodeViews.map { node -> drag.originalPositions[node.nodeId]?.let { node.copy(position = FlowPoint(it.x + dx, it.y + dy)) } ?: node })
        return FlowInteractionResult(state.copy(dragState = drag.copy(latest = action.at)), changed, false)
    }

    private fun commitDrag(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult {
        val drag = state.dragState ?: return result(state, view)
        val original = view.copy(nodeViews = view.nodeViews.map { node -> drag.originalPositions[node.nodeId]?.let { node.copy(position = it) } ?: node })
        return FlowInteractionResult(state.copy(dragState = null, undoHistory = state.undoHistory + original, redoHistory = emptyList()), view, true)
    }

    private fun cancelDrag(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult {
        val drag = state.dragState ?: return result(state, view)
        val restored = view.copy(nodeViews = view.nodeViews.map { node -> drag.originalPositions[node.nodeId]?.let { node.copy(position = it) } ?: node })
        return result(state.copy(dragState = null), restored)
    }

    private fun updatePan(state: FlowInteractionState, action: FlowInteractionAction.UpdateViewportPan, view: FlowViewDocument): FlowInteractionResult {
        val pan = state.panState ?: return result(state, view)
        val moved = FlowPoint(pan.originalPan.x + action.at.x - pan.anchor.x, pan.originalPan.y + action.at.y - pan.anchor.y)
        return FlowInteractionResult(state.copy(panState = pan.copy(latest = action.at)), view.copy(viewport = view.viewport.copy(pan = moved)), false)
    }

    private fun commitPan(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult {
        val pan = state.panState ?: return result(state, view)
        val original = view.copy(viewport = view.viewport.copy(pan = pan.originalPan))
        return FlowInteractionResult(state.copy(panState = null, undoHistory = state.undoHistory + original, redoHistory = emptyList()), view, true)
    }

    private fun zoom(state: FlowInteractionState, action: FlowInteractionAction.ZoomViewport, view: FlowViewDocument): FlowInteractionResult {
        require(action.factor.isFinite() && action.factor > 0)
        val old = view.viewport; val zoom = (old.zoom * action.factor).coerceIn(0.1, 8.0)
        val graphAnchor = FlowViewportTransform.screenToGraph(action.anchor, old)
        val pan = FlowPoint(action.anchor.x - graphAnchor.x * zoom, action.anchor.y - graphAnchor.y * zoom)
        return FlowInteractionResult(state.copy(undoHistory = state.undoHistory + view, redoHistory = emptyList()), view.copy(viewport = FlowViewport(pan, zoom)), true)
    }

    private fun marquee(state: FlowInteractionState, action: FlowInteractionAction.MarqueeSelection, view: FlowViewDocument): FlowInteractionResult {
        val selected = view.nodeViews.filter { node -> val size = node.size ?: FlowSize(1.0, 1.0); intersects(action.rect, FlowRect(node.position, size)) }.map { it.nodeId }.toSet()
        return result(state.copy(selectedNodeIds = selected, selectedEdgeIds = emptySet(), marqueeState = null), view)
    }

    private fun undo(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult {
        val previous = state.undoHistory.lastOrNull() ?: return result(state, view)
        return FlowInteractionResult(state.copy(undoHistory = state.undoHistory.dropLast(1), redoHistory = state.redoHistory + view), previous, true)
    }

    private fun redo(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult {
        val next = state.redoHistory.lastOrNull() ?: return result(state, view)
        return FlowInteractionResult(state.copy(redoHistory = state.redoHistory.dropLast(1), undoHistory = state.undoHistory + view), next, true)
    }

    private fun connected(graph: FlowGraphDocument, start: FlowNodeId): Set<FlowNodeId> {
        val adjacent = graph.edges.flatMap { listOf(it.sourceNodeId to it.targetNodeId, it.targetNodeId to it.sourceNodeId) }.groupBy({ it.first }, { it.second })
        val seen = linkedSetOf(start); val queue = ArrayDeque<FlowNodeId>(); queue += start
        while (queue.isNotEmpty()) adjacent[queue.removeFirst()].orEmpty().sortedBy { it.value }.forEach { if (seen.add(it)) queue += it }
        return seen
    }

    private fun intersects(a: FlowRect, b: FlowRect): Boolean = a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top
    private fun result(state: FlowInteractionState, view: FlowViewDocument): FlowInteractionResult = FlowInteractionResult(state, view, false)
}
