/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.interaction

import de.visualtasker.flowchart.domain.*
import kotlin.math.hypot

public object FlowHitTesting {
    public fun hitNode(point: FlowPoint, bounds: Map<FlowNodeId, FlowRect>): FlowNodeId? = bounds.entries.sortedBy { it.key.value }.lastOrNull { it.value.contains(point) }?.key
    public fun hitEdge(point: FlowPoint, segments: Map<FlowEdgeId, List<Pair<FlowPoint, FlowPoint>>>, tolerance: Double = 8.0): FlowEdgeId? = segments.entries.sortedBy { it.key.value }.firstOrNull { (_, values) -> values.any { (a, b) -> distanceToSegment(point, a, b) <= tolerance } }?.key
    public fun selection(point: FlowPoint, bounds: Map<FlowNodeId, FlowRect>, segments: Map<FlowEdgeId, List<Pair<FlowPoint, FlowPoint>>>): FlowHit = hitNode(point, bounds)?.let(FlowHit::Node) ?: hitEdge(point, segments)?.let(FlowHit::Edge) ?: FlowHit.None
    public fun dragThresholdExceeded(start: FlowPoint, current: FlowPoint, threshold: Double = 4.0): Boolean = hypot(current.x - start.x, current.y - start.y) >= threshold
    private fun distanceToSegment(point: FlowPoint, a: FlowPoint, b: FlowPoint): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        if (dx == 0.0 && dy == 0.0) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
    }
}

public sealed interface FlowHit { public data class Node(public val id: FlowNodeId) : FlowHit; public data class Edge(public val id: FlowEdgeId) : FlowHit; public data object None : FlowHit }

public object FlowViewportTransform {
    public fun graphToScreen(point: FlowPoint, viewport: FlowViewport): FlowPoint = FlowPoint(point.x * viewport.zoom + viewport.pan.x, point.y * viewport.zoom + viewport.pan.y)
    public fun screenToGraph(point: FlowPoint, viewport: FlowViewport): FlowPoint = FlowPoint((point.x - viewport.pan.x) / viewport.zoom, (point.y - viewport.pan.y) / viewport.zoom)
}
