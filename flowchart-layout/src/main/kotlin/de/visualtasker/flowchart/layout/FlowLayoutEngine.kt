/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.validation.FlowGraphValidator
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt

public object FlowLayoutEngine {
    public fun layout(
        graph: FlowGraphDocument,
        nodeMetrics: FlowNodeMetrics = FlowNodeMetrics(emptyMap()),
        config: FlowLayoutConfig = FlowLayoutConfig(),
        compatibleView: FlowViewDocument? = null,
    ): FlowLayoutResult {
        val validation = FlowGraphValidator.validate(graph)
        if (!validation.isValid) return FlowLayoutResult(emptyMap(), emptyMap(), emptyMap(), emptySet(), listOf(FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.INVALID_GRAPH, validation.diagnostics.joinToString { it.code.name })), 0)
        val included = graph.nodes
            .filterNot { config.syntheticNodePolicy == FlowSyntheticNodePolicy.EXCLUDE_ANNOTATIONS && it.kind.standard == FlowNodeKind.ANNOTATION }
            .filterNot { it.isBackgroundFacetNode() }
            .sortedBy { seededKey(it.id.value, config.deterministicSeed) }
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
        applyCodeFlowOffsets(included, edges, allBounds, compatibleView, config)
        resolveOverlaps(allBounds, config)
        normalizeBounds(allBounds)
        val diagnostics = mutableListOf<FlowLayoutDiagnostic>()
        val nodesById = included.associateBy { it.id }
        val laneIndexes = laneIndexes(edges)
        val routes = edges.associate { edge ->
            val obstacles = allBounds.filterKeys { it != edge.sourceNodeId && it != edge.targetNodeId }.values
            val route = route(
                edge = edge,
                sourceNode = nodesById.getValue(edge.sourceNodeId),
                targetNode = nodesById.getValue(edge.targetNodeId),
                source = allBounds.getValue(edge.sourceNodeId),
                target = allBounds.getValue(edge.targetNodeId),
                obstacles = obstacles,
                ranks = allRanks,
                backEdges = backEdges,
                config = config,
                view = compatibleView,
                laneIndex = laneIndexes[edge.id] ?: 0,
            )
            edge.id to route
        }
        if (allBounds.values.any { !finite(it) } || routes.values.flatMap { it.points }.any { !it.x.isFinite() || !it.y.isFinite() }) diagnostics += FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.NON_FINITE_OUTPUT, "Layout contains non-finite geometry")
        routes.values.filter { it.kind == FlowRouteKind.DIRECT_FALLBACK }.forEach { diagnostics += FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.ROUTE_FALLBACK, "Collision-free orthogonal route unavailable", it.edgeId) }
        return FlowLayoutResult(allBounds.toSortedMap(compareBy { it.value }), routes.toSortedMap(compareBy { it.value }), allRanks.toSortedMap(compareBy { it.value }), backEdges, diagnostics, routes.values.sumOf { if (it.usesInternalDummyPoints) max(0, it.points.size - 2) else 0 })
    }

    private data class Classified(val ranks: Map<FlowNodeId, Int>, val backEdges: Set<FlowEdgeId>)

    private fun FlowGraphNode.isBackgroundFacetNode(): Boolean =
        properties["visualFacet"] == FlowSemanticValue.BooleanValue(true) &&
            properties["syntheticJoin"] != FlowSemanticValue.BooleanValue(true)

    private fun classifyAndRank(nodes: Set<FlowNodeId>, edges: List<FlowGraphEdge>): Classified {
        val ranks = nodes.associateWith { 0 }.toMutableMap()
        val colors = mutableMapOf<FlowNodeId, Int>()
        val back = linkedSetOf<FlowEdgeId>()
        val rankingEdges = edges
            .filter { it.sourceNodeId in nodes && it.targetNodeId in nodes }
            .filterNot { it.kind == FlowEdgeKind.DATA_FLOW || it.kind == FlowEdgeKind.CONDITION }
        val outgoing = rankingEdges.groupBy { it.sourceNodeId }
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
            rankingEdges.filterNot { it.id in back }.forEach { edge -> ranks[edge.targetNodeId] = max(ranks.getValue(edge.targetNodeId), ranks.getValue(edge.sourceNodeId) + 1) }
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
        var ordered = ids.sortedWith(compareBy<FlowNodeId> { branchOrder(it, edges) }.thenBy { it.value })
        repeat(sweeps) {
            ordered = ordered.sortedWith(
                compareBy<FlowNodeId> { branchOrder(it, edges) }
                    .thenBy { id -> edges.filter { it.targetNodeId == id }.mapNotNull { ranks[it.sourceNodeId] }.average().takeUnless(Double::isNaN) ?: -1.0 }
                    .thenBy { it.value }
            )
        }
        return ordered
    }

    private fun branchOrder(id: FlowNodeId, edges: List<FlowGraphEdge>): Int =
        edges
            .filter { it.targetNodeId == id }
            .minOfOrNull { edgeKindOrder(it.kind) }
            ?: 40

    private fun edgeKindOrder(kind: FlowEdgeKind): Int = when (kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.LOOP_BODY,
        -> 10
        FlowEdgeKind.ELSE_IF_BRANCH -> 20
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.LOOP_EXIT,
        -> 30
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
        -> 35
        FlowEdgeKind.SEQUENCE -> 40
        else -> 50
    }

    private fun route(
        edge: FlowGraphEdge,
        sourceNode: FlowGraphNode,
        targetNode: FlowGraphNode,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        ranks: Map<FlowNodeId, Int>,
        backEdges: Set<FlowEdgeId>,
        config: FlowLayoutConfig,
        view: FlowViewDocument?,
        laneIndex: Int,
    ): FlowRoute {
        val locked = view?.edgeViews?.firstOrNull { it.edgeId == edge.id && it.routeLockState == FlowRouteLockState.LOCKED }
        val start = portOut(edge, sourceNode, source, config.orientation)
        val end = portIn(edge, targetNode, target, config.orientation)
        if (locked != null && locked.bendPoints.isNotEmpty()) return makeRoute(edge.id, FlowRouteKind.ORTHOGONAL, orthogonalize(listOf(start) + locked.bendPoints.map { FlowRoutePoint(it.x, it.y) } + end), true)
        if (edge.id in backEdges && edge.kind in loopBackRouteKinds) {
            val lane = config.routingClearance + 24.0
            val points = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> listOf(start, FlowRoutePoint(source.left - lane, start.y), FlowRoutePoint(source.left - lane, target.top - lane), FlowRoutePoint(end.x, target.top - lane), end)
                FlowLayoutOrientation.LEFT_TO_RIGHT -> listOf(start, FlowRoutePoint(start.x, source.bottom + lane), FlowRoutePoint(target.left - lane, source.bottom + lane), FlowRoutePoint(target.left - lane, end.y), end)
            }
            return makeRoute(edge.id, FlowRouteKind.LOOP_BACK, points, true)
        }
        val rankDistance = kotlin.math.abs(ranks.getValue(edge.targetNodeId) - ranks.getValue(edge.sourceNodeId))
        val directPoints = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> manhattanRoute(edge, start, end, source, target, obstacles, config, laneIndex = laneIndex)
            FlowLayoutOrientation.LEFT_TO_RIGHT -> { val mid = (start.x + end.x) / 2; listOf(start, FlowRoutePoint(mid, start.y), FlowRoutePoint(mid, end.y), end) }
        }
        val points = if (collides(directPoints, obstacles, config.routingClearance)) when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> manhattanRoute(edge, start, end, source, target, obstacles, config, relaxed = true, laneIndex = laneIndex)
            FlowLayoutOrientation.LEFT_TO_RIGHT -> {
                val clearance = config.routingClearance + config.nodeSpacing * 0.5
                collisionFreeCandidate(
                    edge,
                    horizontalOuterLaneCandidates(start, end, source, target, obstacles, clearance),
                    obstacles,
                    config.routingClearance,
                )
            }
        } else directPoints
        val kind = if (edge.kind in setOf(
                FlowEdgeKind.TRUE_BRANCH,
                FlowEdgeKind.FALSE_BRANCH,
                FlowEdgeKind.ELSE_IF_BRANCH,
                FlowEdgeKind.CONDITION,
            )
        ) FlowRouteKind.BRANCH else FlowRouteKind.ORTHOGONAL
        return makeRoute(edge.id, kind, points.distinct(), rankDistance > 1 || points.size > 2)
    }

    private fun manhattanRoute(
        edge: FlowGraphEdge,
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        config: FlowLayoutConfig,
        relaxed: Boolean = false,
        laneIndex: Int = 0,
    ): List<FlowRoutePoint> {
        val clearance = config.routingClearance + if (relaxed) config.nodeSpacing * 0.5 else 0.0
        val lanePadding = laneIndex * (config.routingClearance + 12.0)
        val baseCandidates = when (edge.kind) {
            FlowEdgeKind.LOOP_BODY,
            FlowEdgeKind.LOOP_BACK,
            -> leftLaneCandidates(start, end, source, target, clearance + lanePadding)
            FlowEdgeKind.TRUE_BRANCH,
            FlowEdgeKind.ELSE_IF_BRANCH,
            FlowEdgeKind.CONDITION,
            FlowEdgeKind.DATA_FLOW,
            -> branchSideCandidates(start, end, source, target, clearance) +
                sideLaneCandidates(start, end, source, target, clearance + lanePadding)
            else -> verticalStemCandidates(start, end, source, target, clearance)
        }
        val extraCandidates = if (edge.kind in sideOutputKinds) {
            rightOuterLaneCandidates(start, end, source, target, obstacles, clearance + lanePadding)
        } else {
            outerLaneCandidates(start, end, source, target, obstacles, clearance + lanePadding)
        }
        val candidates = (baseCandidates + extraCandidates)
            .map { it.compactOrthogonalPoints() }
            .distinctBy { candidateKey(it) }
        return collisionFreeCandidate(edge, candidates, obstacles, config.routingClearance)
    }

    private fun verticalStemCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val midY = (start.y + end.y) / 2.0
        val aboveTarget = target.top - clearance
        val belowSource = source.bottom + clearance
        val rightLane = maxOf(source.right, target.right) + clearance
        val leftLane = minOf(source.left, target.left) - clearance
        return listOf(
            listOf(start, FlowRoutePoint(start.x, midY), FlowRoutePoint(end.x, midY), end),
            listOf(start, FlowRoutePoint(start.x, belowSource), FlowRoutePoint(end.x, belowSource), end),
            listOf(start, FlowRoutePoint(start.x, aboveTarget), FlowRoutePoint(end.x, aboveTarget), end),
            listOf(start, FlowRoutePoint(rightLane, start.y), FlowRoutePoint(rightLane, end.y), end),
            listOf(start, FlowRoutePoint(leftLane, start.y), FlowRoutePoint(leftLane, end.y), end),
        )
    }

    private fun sideLaneCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val nearRight = maxOf(source.right, target.right) + clearance
        val widerRight = nearRight + clearance
        val between = if (target.left > source.right) (source.right + target.left) / 2.0 else nearRight
        return listOf(between, nearRight, widerRight).distinct().map { lane ->
            listOf(start, FlowRoutePoint(lane, start.y), FlowRoutePoint(lane, end.y), end)
        }
    }

    private fun branchSideCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val stub = maxOf(16.0, clearance * 0.5)
        val closeLane = minOf(target.left - stub, source.right + stub).coerceAtLeast(source.right + stub)
        val openLane = maxOf(source.right, target.right) + clearance * 2.0
        val beforeTarget = target.top - clearance
        return listOf(
            listOf(start, FlowRoutePoint(closeLane, start.y), FlowRoutePoint(closeLane, end.y), end),
            listOf(start, FlowRoutePoint(closeLane, start.y), FlowRoutePoint(closeLane, beforeTarget), FlowRoutePoint(end.x, beforeTarget), end),
            listOf(start, FlowRoutePoint(openLane, start.y), FlowRoutePoint(openLane, end.y), end),
        )
    }

    private fun leftLaneCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val nearLeft = minOf(source.left, target.left) - clearance
        val widerLeft = nearLeft - clearance
        return listOf(nearLeft, widerLeft).map { lane ->
            listOf(start, FlowRoutePoint(lane, start.y), FlowRoutePoint(lane, end.y), end)
        }
    }

    private fun outerLaneCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val allRects = obstacles + source + target
        val outerClearance = clearance * 2.0
        val leftOuter = allRects.minOfOrNull { it.left }?.minus(outerClearance) ?: minOf(source.left, target.left) - outerClearance
        val rightOuter = allRects.maxOfOrNull { it.right }?.plus(outerClearance) ?: maxOf(source.right, target.right) + outerClearance
        val belowOuter = allRects.maxOfOrNull { it.bottom }?.plus(outerClearance) ?: maxOf(source.bottom, target.bottom) + outerClearance
        val aboveOuter = allRects.minOfOrNull { it.top }?.minus(outerClearance) ?: minOf(source.top, target.top) - outerClearance
        val belowSource = source.bottom + clearance
        val aboveSource = source.top - clearance
        val beforeTarget = target.top - clearance
        return listOf(
            listOf(start, FlowRoutePoint(leftOuter, start.y), FlowRoutePoint(leftOuter, end.y), end),
            listOf(start, FlowRoutePoint(rightOuter, start.y), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, belowSource), FlowRoutePoint(rightOuter, belowSource), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, aboveSource), FlowRoutePoint(rightOuter, aboveSource), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, beforeTarget), FlowRoutePoint(rightOuter, beforeTarget), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, aboveOuter), FlowRoutePoint(rightOuter, aboveOuter), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, belowOuter), FlowRoutePoint(rightOuter, belowOuter), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, belowOuter), FlowRoutePoint(end.x, belowOuter), end),
            listOf(start, FlowRoutePoint(start.x, aboveOuter), FlowRoutePoint(end.x, aboveOuter), end),
        )
    }

    private fun rightOuterLaneCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val allRects = obstacles + source + target
        val outerClearance = clearance * 2.0
        val rightOuter = allRects.maxOfOrNull { it.right }?.plus(outerClearance) ?: maxOf(source.right, target.right) + outerClearance
        val belowOuter = allRects.maxOfOrNull { it.bottom }?.plus(outerClearance) ?: maxOf(source.bottom, target.bottom) + outerClearance
        val aboveOuter = allRects.minOfOrNull { it.top }?.minus(outerClearance) ?: minOf(source.top, target.top) - outerClearance
        return listOf(
            listOf(start, FlowRoutePoint(start.x, aboveOuter), FlowRoutePoint(rightOuter, aboveOuter), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(start.x, belowOuter), FlowRoutePoint(rightOuter, belowOuter), FlowRoutePoint(rightOuter, end.y), end),
            listOf(start, FlowRoutePoint(rightOuter, start.y), FlowRoutePoint(rightOuter, end.y), end),
        )
    }

    private fun horizontalOuterLaneCandidates(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        clearance: Double,
    ): List<List<FlowRoutePoint>> {
        val allRects = obstacles + source + target
        val outerClearance = clearance * 2.0
        val belowOuter = allRects.maxOfOrNull { it.bottom }?.plus(outerClearance) ?: maxOf(source.bottom, target.bottom) + outerClearance
        val aboveOuter = allRects.minOfOrNull { it.top }?.minus(outerClearance) ?: minOf(source.top, target.top) - outerClearance
        return listOf(
            listOf(start, FlowRoutePoint(start.x, belowOuter), FlowRoutePoint(end.x, belowOuter), end),
            listOf(start, FlowRoutePoint(start.x, aboveOuter), FlowRoutePoint(end.x, aboveOuter), end),
        ).map { it.compactOrthogonalPoints() }
    }

    private fun collisionFreeCandidate(
        edge: FlowGraphEdge,
        candidates: List<List<FlowRoutePoint>>,
        obstacles: Collection<FlowRect>,
        clearance: Double,
    ): List<FlowRoutePoint> =
        candidates
            .filterNot { collides(it, obstacles, clearance) }
            .minWithOrNull(
                compareBy<List<FlowRoutePoint>> { directionPenalty(edge, it) }
                    .thenBy(::routeLength)
                    .thenBy { bendCount(it) }
            )
            ?: candidates.minWithOrNull(
                compareBy<List<FlowRoutePoint>> { collisionCount(it, obstacles, clearance) }
                    .thenBy { directionPenalty(edge, it) }
                    .thenBy(::routeLength)
                    .thenBy { bendCount(it) }
            )
            ?: listOf()

    private fun laneIndexes(edges: List<FlowGraphEdge>): Map<FlowEdgeId, Int> =
        edges
            .filter { it.kind in routedLaneKinds }
            .groupBy { it.sourceNodeId to it.kind }
            .flatMap { (_, group) ->
                group
                    .sortedWith(compareBy<FlowGraphEdge> { edgeKindOrder(it.kind) }.thenBy { it.label.orEmpty() }.thenBy { it.id.value })
                    .mapIndexed { index, edge -> edge.id to index }
            }
            .toMap()

    private fun applyCodeFlowOffsets(
        nodes: List<FlowGraphNode>,
        edges: List<FlowGraphEdge>,
        bounds: MutableMap<FlowNodeId, FlowRect>,
        view: FlowViewDocument?,
        config: FlowLayoutConfig,
    ) {
        val pinnedNodeIds = view?.nodeViews.orEmpty().filter { it.pinned }.map { it.nodeId }.toSet()
        if (config.orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) {
            alignEntryNodes(nodes, bounds, pinnedNodeIds, config)
            val branchTargets = edges.filter { it.kind in branchKinds || it.kind == FlowEdgeKind.CONDITION || it.kind == FlowEdgeKind.DATA_FLOW }.map { it.targetNodeId }.toSet()
            repeat(3) {
                alignStatementSequences(edges, bounds, pinnedNodeIds, branchTargets, config)
                alignValueInputs(edges, bounds, pinnedNodeIds, config)
            }
            alignJoinNodes(nodes, edges, bounds, pinnedNodeIds, config)
        }
        edges
            .filter { it.kind in branchKinds }
            .groupBy { it.sourceNodeId }
            .forEach { (_, branchEdges) ->
                branchEdges
                    .sortedWith(compareBy<FlowGraphEdge> { edgeKindOrder(it.kind) }.thenBy { it.label.orEmpty() }.thenBy { it.id.value })
                    .forEachIndexed { index, edge ->
                        if (edge.targetNodeId in pinnedNodeIds) return@forEachIndexed
                        val source = bounds[edge.sourceNodeId] ?: return@forEachIndexed
                        val target = bounds[edge.targetNodeId] ?: return@forEachIndexed
                        val adjusted = when (config.orientation) {
                            FlowLayoutOrientation.TOP_TO_BOTTOM -> {
                                val branchPosition = when (edge.kind) {
                                    FlowEdgeKind.TRUE_BRANCH -> FlowPoint(
                                        x = source.right + config.nodeSpacing,
                                        y = source.top,
                                    )
                                    FlowEdgeKind.ELSE_IF_BRANCH -> FlowPoint(
                                        x = source.right + config.nodeSpacing * 1.45 + index * config.nodeSpacing * 0.35,
                                        y = source.bottom + config.layerSpacing * 0.36 + index * (target.size.height + config.nodeSpacing * 0.35),
                                    )
                                    FlowEdgeKind.LOOP_BODY,
                                    FlowEdgeKind.LOOP_BACK,
                                    -> FlowPoint(
                                        x = source.left + (source.size.width - target.size.width) / 2.0,
                                        y = source.bottom + config.layerSpacing * 0.72 + index * (target.size.height + config.nodeSpacing * 0.35),
                                    )
                                    FlowEdgeKind.FALSE_BRANCH,
                                    FlowEdgeKind.LOOP_EXIT,
                                    -> FlowPoint(
                                        x = source.left + (source.size.width - target.size.width) / 2.0,
                                        y = source.bottom + config.layerSpacing * 0.8 + index * config.nodeSpacing * 0.25,
                                    )
                                    else -> target.origin
                                }
                                target.copy(origin = branchPosition)
                            }
                            FlowLayoutOrientation.LEFT_TO_RIGHT -> target.copy(
                                origin = FlowPoint(
                                    x = target.origin.x + index * (config.layerSpacing * 0.34),
                                    y = target.origin.y + index * (config.nodeSpacing * 0.62),
                                )
                            )
                        }
                        bounds[edge.targetNodeId] = adjusted
                    }
            }
        if (config.orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) {
            alignEntryNodes(nodes, bounds, pinnedNodeIds, config)
            val branchTargets = edges.filter { it.kind in branchKinds || it.kind == FlowEdgeKind.CONDITION || it.kind == FlowEdgeKind.DATA_FLOW }.map { it.targetNodeId }.toSet()
            repeat(3) {
                alignStatementSequences(edges, bounds, pinnedNodeIds, branchTargets, config)
                alignValueInputs(edges, bounds, pinnedNodeIds, config)
            }
            alignJoinNodes(nodes, edges, bounds, pinnedNodeIds, config)
        }
    }

    private fun alignEntryNodes(
        nodes: List<FlowGraphNode>,
        bounds: MutableMap<FlowNodeId, FlowRect>,
        pinnedNodeIds: Set<FlowNodeId>,
        config: FlowLayoutConfig,
    ) {
        nodes
            .filter { it.kind.standard == FlowNodeKind.ENTRY }
            .sortedBy { it.id.value }
            .forEachIndexed { index, node ->
                if (node.id in pinnedNodeIds) return@forEachIndexed
                val rect = bounds[node.id] ?: return@forEachIndexed
                bounds[node.id] = rect.copy(
                    origin = FlowPoint(
                        x = 0.0,
                        y = index * (rect.size.height + config.layerSpacing),
                    ),
                )
            }
    }

    private fun alignStatementSequences(
        edges: List<FlowGraphEdge>,
        bounds: MutableMap<FlowNodeId, FlowRect>,
        pinnedNodeIds: Set<FlowNodeId>,
        branchTargets: Set<FlowNodeId>,
        config: FlowLayoutConfig,
    ) {
        edges
            .filter { it.kind == FlowEdgeKind.SEQUENCE }
            .sortedBy { it.id.value }
            .forEach { edge ->
                if (edge.targetNodeId in pinnedNodeIds || edge.targetNodeId in branchTargets) return@forEach
                val source = bounds[edge.sourceNodeId] ?: return@forEach
                val target = bounds[edge.targetNodeId] ?: return@forEach
                bounds[edge.targetNodeId] = target.copy(
                    origin = FlowPoint(
                        x = source.left + (source.size.width - target.size.width) / 2.0,
                        y = source.bottom + config.layerSpacing,
                    ),
                )
            }
    }

    private fun alignValueInputs(
        edges: List<FlowGraphEdge>,
        bounds: MutableMap<FlowNodeId, FlowRect>,
        pinnedNodeIds: Set<FlowNodeId>,
        config: FlowLayoutConfig,
    ) {
        edges
            .filter { it.kind == FlowEdgeKind.CONDITION || it.kind == FlowEdgeKind.DATA_FLOW }
            .groupBy { it.targetNodeId }
            .entries
            .sortedByDescending { it.key.value }
            .forEach { (targetId, incoming) ->
                val consumer = bounds[targetId] ?: return@forEach
                val orderedIncoming = incoming
                    .sortedWith(compareBy<FlowGraphEdge> { if (it.kind == FlowEdgeKind.CONDITION) 0 else 1 }.thenBy { it.label.orEmpty() }.thenBy { it.id.value })
                val valueColumnX = consumer.right + config.nodeSpacing * 0.62
                val total = orderedIncoming.size
                orderedIncoming
                    .forEachIndexed { index, edge ->
                        if (edge.sourceNodeId in pinnedNodeIds) return@forEachIndexed
                        val value = bounds[edge.sourceNodeId] ?: return@forEachIndexed
                        val slotOffset = index - ((total - 1) / 2.0)
                        bounds[edge.sourceNodeId] = value.copy(
                            origin = FlowPoint(
                                x = valueColumnX,
                                y = consumer.top + (consumer.size.height - value.size.height) / 2.0 +
                                    slotOffset * (value.size.height + config.nodeSpacing * 0.32),
                            ),
                        )
                    }
            }
    }

    private fun alignJoinNodes(
        nodes: List<FlowGraphNode>,
        edges: List<FlowGraphEdge>,
        bounds: MutableMap<FlowNodeId, FlowRect>,
        pinnedNodeIds: Set<FlowNodeId>,
        config: FlowLayoutConfig,
    ) {
        nodes
            .filter { it.properties["syntheticJoin"] == FlowSemanticValue.BooleanValue(true) }
            .sortedBy { it.id.value }
            .forEach { join ->
                if (join.id in pinnedNodeIds) return@forEach
                val joinBounds = bounds[join.id] ?: return@forEach
                val ownerNodeId = (join.properties["ownerNodeId"] as? FlowSemanticValue.StringValue)
                    ?.value
                    ?.let(::FlowNodeId)
                val owner = ownerNodeId?.let(bounds::get)
                val incoming = edges
                    .filter { it.targetNodeId == join.id }
                    .mapNotNull { bounds[it.sourceNodeId] }
                val group = (listOfNotNull(owner) + incoming).takeIf { it.isNotEmpty() } ?: return@forEach
                val minLeft = group.minOf { it.left }
                val maxRight = group.maxOf { it.right }
                val maxBottom = group.maxOf { it.bottom }
                bounds[join.id] = joinBounds.copy(
                    origin = FlowPoint(
                        x = minLeft + (maxRight - minLeft - joinBounds.size.width) / 2.0,
                        y = maxBottom + config.layerSpacing * 0.58,
                    ),
                )
            }
    }

    private fun resolveOverlaps(
        bounds: MutableMap<FlowNodeId, FlowRect>,
        config: FlowLayoutConfig,
    ) {
        val minGap = max(config.routingClearance + 16.0, config.nodeSpacing * 0.55)
        repeat(bounds.size.coerceAtLeast(1) * 5) {
            var changed = false
            val ordered = bounds.entries.sortedWith(compareBy({ it.value.top }, { it.value.left }, { it.key.value }))
            for (i in ordered.indices) {
                val currentEntry = ordered[i]
                var current = bounds[currentEntry.key] ?: continue
                for (j in 0 until i) {
                    val previous = bounds[ordered[j].key] ?: continue
                    if (!current.overlaps(previous, minGap)) continue
                    val shiftDown = previous.bottom + minGap - current.top
                    val shiftRight = previous.right + minGap - current.left
                    current = if (shiftDown <= shiftRight || current.left < previous.right) {
                        current.copy(origin = FlowPoint(current.left, current.top + shiftDown))
                    } else {
                        current.copy(origin = FlowPoint(current.left + shiftRight, current.top))
                    }
                    bounds[currentEntry.key] = current
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    private fun normalizeBounds(bounds: MutableMap<FlowNodeId, FlowRect>) {
        val minX = bounds.values.minOfOrNull { it.left } ?: 0.0
        val minY = bounds.values.minOfOrNull { it.top } ?: 0.0
        if (minX == 0.0 && minY == 0.0) return
        bounds.replaceAll { _, rect ->
            rect.copy(origin = FlowPoint(rect.left - minX, rect.top - minY))
        }
    }

    private fun FlowRect.overlaps(other: FlowRect, gap: Double): Boolean =
        left < other.right + gap &&
            right + gap > other.left &&
            top < other.bottom + gap &&
            bottom + gap > other.top

    private fun portOut(
        edge: FlowGraphEdge,
        node: FlowGraphNode,
        rect: FlowRect,
        orientation: FlowLayoutOrientation,
    ): FlowRoutePoint {
        if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM && edge.kind in setOf(FlowEdgeKind.LOOP_BODY, FlowEdgeKind.LOOP_BACK)) {
            return FlowRoutePoint(rect.left, (rect.top + rect.bottom) / 2.0)
        }
        val portName = when (edge.kind) {
            FlowEdgeKind.TRUE_BRANCH,
            FlowEdgeKind.ELSE_IF_BRANCH,
            FlowEdgeKind.LOOP_BODY -> edge.label
            FlowEdgeKind.CONDITION,
            FlowEdgeKind.DATA_FLOW,
            -> "output"
            FlowEdgeKind.FALSE_BRANCH,
            FlowEdgeKind.SEQUENCE,
            FlowEdgeKind.LOOP_EXIT -> null
            else -> null
        }
        return sidePort(node, "outputPorts", portName, rect, inputSide = false, orientation)
            ?: when {
                orientation == FlowLayoutOrientation.TOP_TO_BOTTOM && edge.kind in sideOutputKinds -> FlowRoutePoint(rect.right, (rect.top + rect.bottom) / 2.0)
                orientation == FlowLayoutOrientation.TOP_TO_BOTTOM && edge.kind == FlowEdgeKind.FALSE_BRANCH -> FlowRoutePoint((rect.left + rect.right) / 2.0, rect.bottom)
                else -> if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) FlowRoutePoint((rect.left + rect.right) / 2, rect.bottom) else FlowRoutePoint(rect.right, (rect.top + rect.bottom) / 2)
            }
    }

    private fun portIn(
        edge: FlowGraphEdge,
        node: FlowGraphNode,
        rect: FlowRect,
        orientation: FlowLayoutOrientation,
    ): FlowRoutePoint {
        val portName = when (edge.kind) {
            FlowEdgeKind.DATA_FLOW,
            FlowEdgeKind.CONDITION -> edge.label
            FlowEdgeKind.TRUE_BRANCH,
            FlowEdgeKind.ELSE_IF_BRANCH,
            FlowEdgeKind.LOOP_BODY -> "previous"
            else -> null
        }
        return sidePort(node, "inputPorts", portName, rect, inputSide = true, orientation)
            ?: when {
                orientation == FlowLayoutOrientation.TOP_TO_BOTTOM && edge.kind in sideInputKinds -> FlowRoutePoint(rect.left, (rect.top + rect.bottom) / 2.0)
                else -> if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) FlowRoutePoint((rect.left + rect.right) / 2, rect.top) else FlowRoutePoint(rect.left, (rect.top + rect.bottom) / 2)
            }
    }

    private fun branchLaneOffset(edge: FlowGraphEdge): Double =
        when (edge.kind) {
            FlowEdgeKind.TRUE_BRANCH -> 32.0
            FlowEdgeKind.ELSE_IF_BRANCH -> 56.0 + (edge.label.orEmpty().hashCode().mod(3) * 18.0)
            FlowEdgeKind.FALSE_BRANCH -> 92.0
            FlowEdgeKind.CONDITION,
            FlowEdgeKind.DATA_FLOW,
            -> 24.0
            else -> 40.0
        }

    private fun sidePort(
        node: FlowGraphNode,
        key: String,
        name: String?,
        rect: FlowRect,
        inputSide: Boolean,
        orientation: FlowLayoutOrientation,
    ): FlowRoutePoint? {
        name ?: return null
        val ports = node.flowPorts(key)
        val index = ports.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
        if (orientation != FlowLayoutOrientation.TOP_TO_BOTTOM) {
            val gap = rect.size.height / (ports.size + 1)
            val y = rect.top + gap * (index + 1)
            val x = if (inputSide) rect.left else rect.right
            return FlowRoutePoint(x, y)
        }
        val port = ports[index]
        val side = portSide(port, inputSide)
        val portsOnSide = ports.filter { portSide(it, inputSide) == side }
        val sideIndex = portsOnSide.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0
        return when (side) {
            PortSide.Left -> {
                val gap = rect.size.height / (portsOnSide.size + 1)
                FlowRoutePoint(rect.left, rect.top + gap * (sideIndex + 1))
            }
            PortSide.Right -> {
                val gap = rect.size.height / (portsOnSide.size + 1)
                FlowRoutePoint(rect.right, rect.top + gap * (sideIndex + 1))
            }
            PortSide.Top -> {
                val gap = rect.size.width / (portsOnSide.size + 1)
                FlowRoutePoint(rect.left + gap * (sideIndex + 1), rect.top)
            }
            PortSide.Bottom -> {
                val gap = rect.size.width / (portsOnSide.size + 1)
                FlowRoutePoint(rect.left + gap * (sideIndex + 1), rect.bottom)
            }
        }
    }

    private data class LayoutPort(
        val name: String,
        val kind: FlowEdgeKind,
    )

    private enum class PortSide {
        Top,
        Bottom,
        Left,
        Right,
    }

    private fun portSide(port: LayoutPort, inputSide: Boolean): PortSide =
        if (inputSide) {
            when {
                port.name.equals("previous", ignoreCase = true) -> PortSide.Top
                port.kind == FlowEdgeKind.CONDITION || port.kind == FlowEdgeKind.DATA_FLOW -> PortSide.Left
                else -> PortSide.Top
            }
        } else {
            when {
                port.name.equals("next", ignoreCase = true) -> PortSide.Bottom
                port.kind == FlowEdgeKind.SEQUENCE || port.kind == FlowEdgeKind.LOOP_EXIT -> PortSide.Bottom
                port.kind == FlowEdgeKind.LOOP_BODY || port.kind == FlowEdgeKind.LOOP_BACK -> PortSide.Left
                else -> PortSide.Right
            }
        }

    private fun FlowGraphNode.flowPorts(key: String): List<LayoutPort> {
        val value = properties[key] as? FlowSemanticValue.ListValue ?: return emptyList()
        return value.values.mapNotNull { item ->
            val fields = (item as? FlowSemanticValue.ObjectValue)?.values ?: return@mapNotNull null
            val name = (fields["name"] as? FlowSemanticValue.StringValue)?.value ?: return@mapNotNull null
            val kindName = (fields["kind"] as? FlowSemanticValue.StringValue)?.value
            val kind = FlowEdgeKind.entries.firstOrNull { it.name == kindName } ?: FlowEdgeKind.SEQUENCE
            LayoutPort(name = name, kind = kind)
        }
    }
    private val branchKinds: Set<FlowEdgeKind> = setOf(
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.LOOP_BODY,
        FlowEdgeKind.LOOP_EXIT,
    )

    private val sideOutputKinds: Set<FlowEdgeKind> = setOf(
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
    )

    private val sideInputKinds: Set<FlowEdgeKind> = setOf(
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
        FlowEdgeKind.LOOP_BODY,
    )

    private val routedLaneKinds: Set<FlowEdgeKind> = sideOutputKinds + setOf(
        FlowEdgeKind.LOOP_BODY,
        FlowEdgeKind.LOOP_BACK,
    )

    private val loopBackRouteKinds: Set<FlowEdgeKind> = setOf(
        FlowEdgeKind.SEQUENCE,
        FlowEdgeKind.LOOP_BODY,
        FlowEdgeKind.LOOP_BACK,
    )

    private fun orthogonalize(points: List<FlowRoutePoint>): List<FlowRoutePoint> {
        if (points.size < 2) return points
        return buildList {
            add(points.first())
            points.zipWithNext().forEach { (from, to) ->
                if (from.x != to.x && from.y != to.y) add(FlowRoutePoint(to.x, from.y))
                add(to)
            }
        }.compactOrthogonalPoints()
    }

    private fun List<FlowRoutePoint>.compactOrthogonalPoints(): List<FlowRoutePoint> =
        fold(emptyList<FlowRoutePoint>()) { acc, point ->
            if (acc.lastOrNull() == point) acc else acc + point
        }.removeCollinearPoints()

    private fun List<FlowRoutePoint>.removeCollinearPoints(): List<FlowRoutePoint> {
        if (size <= 2) return this
        val result = mutableListOf(first())
        for (i in 1 until lastIndex) {
            val previous = result.last()
            val current = this[i]
            val next = this[i + 1]
            val horizontal = previous.y == current.y && current.y == next.y
            val vertical = previous.x == current.x && current.x == next.x
            if (!horizontal && !vertical) result += current
        }
        result += last()
        return result
    }

    private fun routeLength(points: List<FlowRoutePoint>): Double =
        points.zipWithNext().sumOf { (from, to) ->
            kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.y - to.y)
        }

    private fun bendCount(points: List<FlowRoutePoint>): Int =
        points.compactOrthogonalPoints().size.coerceAtLeast(2) - 2

    private fun candidateKey(points: List<FlowRoutePoint>): String =
        points.joinToString("|") { "${it.x.roundToInt()}:${it.y.roundToInt()}" }

    private fun collisionCount(points: List<FlowRoutePoint>, obstacles: Collection<FlowRect>, clearance: Double): Int =
        points.zipWithNext().sumOf { (start, end) -> obstacles.count { rect -> segmentIntersects(start, end, rect, clearance) } }

    private fun directionPenalty(edge: FlowGraphEdge, points: List<FlowRoutePoint>): Int =
        if (edge.kind in sideOutputKinds && points.any { it.x < points.first().x }) 1 else 0

    private fun makeRoute(id: FlowEdgeId, kind: FlowRouteKind, points: List<FlowRoutePoint>, dummy: Boolean): FlowRoute = FlowRoute(id, kind, points, points.zipWithNext(::FlowRouteSegment), dummy)
    private fun collides(points: List<FlowRoutePoint>, obstacles: Collection<FlowRect>, clearance: Double): Boolean = points.zipWithNext().any { (start, end) -> obstacles.any { rect ->
        segmentIntersects(start, end, rect, clearance)
    } }
    private fun segmentIntersects(start: FlowRoutePoint, end: FlowRoutePoint, rect: FlowRect, clearance: Double): Boolean {
        val left = rect.left - clearance; val right = rect.right + clearance; val top = rect.top - clearance; val bottom = rect.bottom + clearance
        return if (start.x == end.x) start.x in left..right && rangesOverlap(start.y, end.y, top, bottom)
        else if (start.y == end.y) start.y in top..bottom && rangesOverlap(start.x, end.x, left, right)
        else true
    }
    private fun rangesOverlap(a: Double, b: Double, low: Double, high: Double): Boolean = minOf(a, b) <= high && maxOf(a, b) >= low
    private fun finite(rect: FlowRect): Boolean = listOf(rect.left, rect.top, rect.right, rect.bottom).all(Double::isFinite) && rect.size.width > 0 && rect.size.height > 0
    private fun seededKey(value: String, seed: Long): String = "${value.hashCode().toLong() xor seed}:$value"
}
