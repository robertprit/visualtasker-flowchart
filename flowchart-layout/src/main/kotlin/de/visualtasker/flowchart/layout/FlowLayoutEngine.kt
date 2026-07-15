/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.validation.FlowGraphValidator
import java.util.ArrayDeque
import kotlin.math.max

public object FlowLayoutEngine {
    public fun layout(
        graph: FlowGraphDocument,
        nodeMetrics: FlowNodeMetrics = FlowNodeMetrics(emptyMap()),
        config: FlowLayoutConfig = FlowLayoutConfig(),
        compatibleView: FlowViewDocument? = null,
    ): FlowLayoutResult {
        val validation = FlowGraphValidator.validate(graph)
        if (!validation.isValid) return FlowLayoutResult(emptyMap(), emptyMap(), emptyMap(), emptySet(), listOf(FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.INVALID_GRAPH, validation.diagnostics.joinToString { it.code.name })), 0)
        val included = graph.nodes.filterNot { config.syntheticNodePolicy == FlowSyntheticNodePolicy.EXCLUDE_ANNOTATIONS && it.kind.standard == FlowNodeKind.ANNOTATION }.sortedBy { seededKey(it.id.value, config.deterministicSeed) }
        if (included.isEmpty()) return FlowLayoutResult(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyList(), 0)
        val nodeIds = included.map { it.id }.toSet()
        val edges = graph.edges.filter { it.sourceNodeId in nodeIds && it.targetNodeId in nodeIds }.sortedBy { seededKey(it.id.value, config.deterministicSeed) }
        val components = connectedComponents(included.map { it.id }, edges)
        val allBounds = linkedMapOf<FlowNodeId, FlowRect>()
        val allRanks = linkedMapOf<FlowNodeId, Int>()
        val backEdges = linkedSetOf<FlowEdgeId>()
        var componentOffset = 0.0
        components.forEach { component ->
            val componentEdges = edges.filter { it.sourceNodeId in component && it.targetNodeId in component }
            val classified = classifyAndRank(component, componentEdges)
            backEdges += classified.backEdges
            val orderedLayers = classified.ranks.entries.groupBy({ it.value }, { it.key }).toSortedMap().mapValues { (_, ids) -> crossingOrder(ids, componentEdges, classified.ranks, config.crossingReductionSweeps) }
            var componentExtent = 0.0
            orderedLayers.forEach { (rank, ids) ->
                var crossOffset = componentOffset
                ids.forEach { id ->
                    val size = nodeMetrics.sizes[id] ?: nodeMetrics.defaultSize
                    val generated = when (config.orientation) {
                        FlowLayoutOrientation.TOP_TO_BOTTOM -> FlowPoint(crossOffset, rank * (nodeMetrics.defaultSize.height + config.layerSpacing))
                        FlowLayoutOrientation.LEFT_TO_RIGHT -> FlowPoint(rank * (nodeMetrics.defaultSize.width + config.layerSpacing), crossOffset)
                    }
                    val pinned = compatibleView?.nodeViews?.firstOrNull { it.nodeId == id && it.pinned }?.position
                    val position = if (config.pinnedNodePolicy == FlowPinnedNodePolicy.HONOR_VIEW) pinned ?: generated else generated
                    allBounds[id] = FlowRect(position, size)
                    allRanks[id] = rank
                    crossOffset += (if (config.orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) size.width else size.height) + config.nodeSpacing
                    componentExtent = max(componentExtent, crossOffset)
                }
            }
            componentOffset = componentExtent + config.componentSpacing
        }
        val diagnostics = mutableListOf<FlowLayoutDiagnostic>()
        val routes = edges.associate { edge ->
            val obstacles = allBounds.filterKeys { it != edge.sourceNodeId && it != edge.targetNodeId }.values
            val route = route(edge, allBounds.getValue(edge.sourceNodeId), allBounds.getValue(edge.targetNodeId), obstacles, allRanks, backEdges, config, compatibleView)
            edge.id to route
        }
        if (allBounds.values.any { !finite(it) } || routes.values.flatMap { it.points }.any { !it.x.isFinite() || !it.y.isFinite() }) diagnostics += FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.NON_FINITE_OUTPUT, "Layout contains non-finite geometry")
        routes.values.filter { it.kind == FlowRouteKind.DIRECT_FALLBACK }.forEach { diagnostics += FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.ROUTE_FALLBACK, "Collision-free orthogonal route unavailable", it.edgeId) }
        return FlowLayoutResult(allBounds.toSortedMap(compareBy { it.value }), routes.toSortedMap(compareBy { it.value }), allRanks.toSortedMap(compareBy { it.value }), backEdges, diagnostics, routes.values.sumOf { if (it.usesInternalDummyPoints) max(0, it.points.size - 2) else 0 })
    }

    private data class Classified(val ranks: Map<FlowNodeId, Int>, val backEdges: Set<FlowEdgeId>)

    private fun classifyAndRank(nodes: Set<FlowNodeId>, edges: List<FlowGraphEdge>): Classified {
        val ranks = nodes.associateWith { 0 }.toMutableMap()
        val colors = mutableMapOf<FlowNodeId, Int>()
        val back = linkedSetOf<FlowEdgeId>()
        val outgoing = edges.filter { it.sourceNodeId in nodes && it.targetNodeId in nodes }.groupBy { it.sourceNodeId }
        fun visit(node: FlowNodeId) {
            colors[node] = 1
            outgoing[node].orEmpty().sortedBy { it.id.value }.forEach { edge ->
                when (colors[edge.targetNodeId] ?: 0) {
                    0 -> visit(edge.targetNodeId)
                    1 -> back += edge.id
                }
            }
            colors[node] = 2
        }
        nodes.sortedBy { it.value }.forEach { if ((colors[it] ?: 0) == 0) visit(it) }
        repeat(nodes.size) {
            edges.filterNot { it.id in back }.forEach { edge -> ranks[edge.targetNodeId] = max(ranks.getValue(edge.targetNodeId), ranks.getValue(edge.sourceNodeId) + 1) }
        }
        return Classified(ranks, back)
    }

    private fun connectedComponents(nodes: List<FlowNodeId>, edges: List<FlowGraphEdge>): List<Set<FlowNodeId>> {
        val adjacent = mutableMapOf<FlowNodeId, MutableSet<FlowNodeId>>()
        edges.forEach { adjacent.getOrPut(it.sourceNodeId, ::linkedSetOf).add(it.targetNodeId); adjacent.getOrPut(it.targetNodeId, ::linkedSetOf).add(it.sourceNodeId) }
        val unseen = nodes.toMutableSet(); val result = mutableListOf<Set<FlowNodeId>>()
        while (unseen.isNotEmpty()) {
            val start = unseen.minBy { it.value }; val queue = ArrayDeque<FlowNodeId>(); val component = linkedSetOf<FlowNodeId>(); queue += start; unseen -= start
            while (queue.isNotEmpty()) { val current = queue.removeFirst(); component += current; adjacent[current].orEmpty().sortedBy { it.value }.forEach { if (unseen.remove(it)) queue += it } }
            result += component
        }
        return result
    }

    private fun crossingOrder(ids: List<FlowNodeId>, edges: List<FlowGraphEdge>, ranks: Map<FlowNodeId, Int>, sweeps: Int): List<FlowNodeId> {
        var ordered = ids.sortedBy { it.value }
        repeat(sweeps) {
            ordered = ordered.sortedWith(compareBy<FlowNodeId> { id -> edges.filter { it.targetNodeId == id }.mapNotNull { ranks[it.sourceNodeId] }.average().takeUnless(Double::isNaN) ?: -1.0 }.thenBy { it.value })
        }
        return ordered
    }

    private fun route(edge: FlowGraphEdge, source: FlowRect, target: FlowRect, obstacles: Collection<FlowRect>, ranks: Map<FlowNodeId, Int>, backEdges: Set<FlowEdgeId>, config: FlowLayoutConfig, view: FlowViewDocument?): FlowRoute {
        val locked = view?.edgeViews?.firstOrNull { it.edgeId == edge.id && it.routeLockState == FlowRouteLockState.LOCKED }
        if (locked != null && locked.bendPoints.isNotEmpty()) return makeRoute(edge.id, FlowRouteKind.ORTHOGONAL, listOf(portOut(source, config.orientation)) + locked.bendPoints.map { FlowRoutePoint(it.x, it.y) } + portIn(target, config.orientation), true)
        val start = portOut(source, config.orientation); val end = portIn(target, config.orientation)
        if (edge.id in backEdges) {
            val lane = config.routingClearance + 24.0
            val points = when (config.orientation) {
                FlowLayoutOrientation.TOP_TO_BOTTOM -> listOf(start, FlowRoutePoint(source.right + lane, start.y), FlowRoutePoint(source.right + lane, target.top - lane), FlowRoutePoint(end.x, target.top - lane), end)
                FlowLayoutOrientation.LEFT_TO_RIGHT -> listOf(start, FlowRoutePoint(start.x, source.bottom + lane), FlowRoutePoint(target.left - lane, source.bottom + lane), FlowRoutePoint(target.left - lane, end.y), end)
            }
            return makeRoute(edge.id, FlowRouteKind.LOOP_BACK, points, true)
        }
        val rankDistance = kotlin.math.abs(ranks.getValue(edge.targetNodeId) - ranks.getValue(edge.sourceNodeId))
        val directPoints = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> { val mid = (start.y + end.y) / 2; listOf(start, FlowRoutePoint(start.x, mid), FlowRoutePoint(end.x, mid), end) }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> { val mid = (start.x + end.x) / 2; listOf(start, FlowRoutePoint(mid, start.y), FlowRoutePoint(mid, end.y), end) }
        }
        val points = if (collides(directPoints, obstacles, config.routingClearance)) when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> { val lane = obstacles.maxOfOrNull { it.right }?.plus(config.routingClearance) ?: max(source.right, target.right) + config.routingClearance; listOf(start, FlowRoutePoint(lane, start.y), FlowRoutePoint(lane, end.y), end) }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> { val lane = obstacles.maxOfOrNull { it.bottom }?.plus(config.routingClearance) ?: max(source.bottom, target.bottom) + config.routingClearance; listOf(start, FlowRoutePoint(start.x, lane), FlowRoutePoint(end.x, lane), end) }
        } else directPoints
        val kind = if (edge.kind in setOf(FlowEdgeKind.TRUE_BRANCH, FlowEdgeKind.FALSE_BRANCH, FlowEdgeKind.ELSE_IF_BRANCH)) FlowRouteKind.BRANCH else FlowRouteKind.ORTHOGONAL
        return makeRoute(edge.id, kind, points.distinct(), rankDistance > 1)
    }

    private fun portOut(rect: FlowRect, orientation: FlowLayoutOrientation): FlowRoutePoint = if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) FlowRoutePoint((rect.left + rect.right) / 2, rect.bottom) else FlowRoutePoint(rect.right, (rect.top + rect.bottom) / 2)
    private fun portIn(rect: FlowRect, orientation: FlowLayoutOrientation): FlowRoutePoint = if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) FlowRoutePoint((rect.left + rect.right) / 2, rect.top) else FlowRoutePoint(rect.left, (rect.top + rect.bottom) / 2)
    private fun makeRoute(id: FlowEdgeId, kind: FlowRouteKind, points: List<FlowRoutePoint>, dummy: Boolean): FlowRoute = FlowRoute(id, kind, points, points.zipWithNext(::FlowRouteSegment), dummy)
    private fun collides(points: List<FlowRoutePoint>, obstacles: Collection<FlowRect>, clearance: Double): Boolean = points.zipWithNext().any { (start, end) -> obstacles.any { rect ->
        val left = rect.left - clearance; val right = rect.right + clearance; val top = rect.top - clearance; val bottom = rect.bottom + clearance
        if (start.x == end.x) start.x in left..right && rangesOverlap(start.y, end.y, top, bottom)
        else if (start.y == end.y) start.y in top..bottom && rangesOverlap(start.x, end.x, left, right)
        else true
    } }
    private fun rangesOverlap(a: Double, b: Double, low: Double, high: Double): Boolean = minOf(a, b) <= high && maxOf(a, b) >= low
    private fun finite(rect: FlowRect): Boolean = listOf(rect.left, rect.top, rect.right, rect.bottom).all(Double::isFinite) && rect.size.width > 0 && rect.size.height > 0
    private fun seededKey(value: String, seed: Long): String = "${value.hashCode().toLong() xor seed}:$value"
}
