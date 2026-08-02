/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.layout

import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.validation.FlowGraphValidator
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

public object FlowLayoutEngine {
    public const val ALGORITHM_ID: String = "sugiyama-manhattan"
    public const val ALGORITHM_VERSION: String = "2"

    public fun layout(
        graph: FlowGraphDocument,
        nodeMetrics: FlowNodeMetrics = FlowNodeMetrics(emptyMap()),
        config: FlowLayoutConfig = FlowLayoutConfig(),
        compatibleView: FlowViewDocument? = null,
    ): FlowLayoutResult {
        val validation = FlowGraphValidator.validate(graph)
        if (!validation.isValid) {
            return FlowLayoutResult(
                nodeBounds = emptyMap(),
                routes = emptyMap(),
                ranks = emptyMap(),
                backEdgeIds = emptySet(),
                diagnostics = listOf(
                    FlowLayoutDiagnostic(
                        FlowLayoutDiagnosticCode.INVALID_GRAPH,
                        validation.diagnostics.joinToString { it.code.name },
                    ),
                ),
                internalDummyPointCount = 0,
            )
        }

        val normalized = normalizeGraph(graph, config)
        if (normalized.nodes.isEmpty()) {
            val artifacts = FlowLayoutPipelineArtifacts(
                normalizedGraph = normalized,
                cycleResolution = FlowCycleResolution(emptyMap()),
                layerAssignment = FlowLayerAssignment(emptyMap()),
                dummyNodeInsertion = FlowDummyNodeInsertion(emptyList(), emptySet()),
                crossingMinimization = FlowCrossingMinimization(emptyMap()),
                nodePositioning = FlowNodePositioning(emptyMap()),
                manhattanRouting = FlowManhattanRouting(emptyMap()),
            )
            return FlowLayoutResult(emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyList(), 0, pipelineArtifacts = artifacts)
        }

        val cycleResolution = resolveCycles(normalized)
        val layerAssignment = assignLayers(normalized, cycleResolution)
        val dummyInsertion = insertDummyNodes(normalized, layerAssignment, cycleResolution)
        val crossing = minimizeCrossings(normalized, layerAssignment, cycleResolution, config)
        val positioning = positionNodes(normalized, crossing, layerAssignment, nodeMetrics, config, compatibleView)
        val routing = routeEdges(normalized, positioning, layerAssignment, cycleResolution, config, compatibleView)

        val diagnostics = mutableListOf<FlowLayoutDiagnostic>()
        if (
            positioning.nodeBounds.values.any { !finite(it) } ||
            routing.routes.values.flatMap { it.points }.any { !it.x.isFinite() || !it.y.isFinite() }
        ) {
            diagnostics += FlowLayoutDiagnostic(FlowLayoutDiagnosticCode.NON_FINITE_OUTPUT, "Layout contains non-finite geometry")
        }
        routing.routes.values.filter { it.kind == FlowRouteKind.DIRECT_FALLBACK }.forEach { route ->
            diagnostics += FlowLayoutDiagnostic(
                FlowLayoutDiagnosticCode.ROUTE_FALLBACK,
                "Collision-free orthogonal route unavailable",
                route.edgeId,
            )
        }

        val artifacts = FlowLayoutPipelineArtifacts(
            normalizedGraph = normalized,
            cycleResolution = cycleResolution,
            layerAssignment = layerAssignment,
            dummyNodeInsertion = dummyInsertion,
            crossingMinimization = crossing,
            nodePositioning = positioning,
            manhattanRouting = routing,
        )
        return FlowLayoutResult(
            nodeBounds = positioning.nodeBounds.toSortedMap(compareBy { it.value }),
            routes = routing.routes.toSortedMap(compareBy { it.value }),
            ranks = layerAssignment.ranks.toSortedMap(compareBy { it.value }),
            backEdgeIds = cycleResolution.backEdgeIds,
            diagnostics = diagnostics,
            internalDummyPointCount = dummyInsertion.dummyNodes.size,
            selfLoopEdgeIds = cycleResolution.selfLoopEdgeIds,
            pipelineArtifacts = artifacts,
        )
    }

    public fun normalizeGraph(
        graph: FlowGraphDocument,
        config: FlowLayoutConfig = FlowLayoutConfig(),
    ): FlowNormalizedGraph {
        val nodes = graph.nodes
            .filterNot {
                config.syntheticNodePolicy == FlowSyntheticNodePolicy.EXCLUDE_ANNOTATIONS &&
                    it.kind.standard == FlowNodeKind.ANNOTATION
            }
            .sortedBy { seededKey(it.id.value, config.deterministicSeed) }
        val nodeIds = nodes.map { it.id }.toSet()
        val edges = graph.edges
            .filter { it.sourceNodeId in nodeIds && it.targetNodeId in nodeIds }
            .sortedBy { seededKey(it.id.value, config.deterministicSeed) }
        return FlowNormalizedGraph(
            nodes = nodes,
            edges = edges,
            components = connectedComponents(nodes.map { it.id }, edges),
        )
    }

    public fun resolveCycles(normalized: FlowNormalizedGraph): FlowCycleResolution {
        val colors = mutableMapOf<FlowNodeId, Int>()
        val directions = linkedMapOf<FlowEdgeId, FlowLayoutEdgeDirection>()
        val outgoing = normalized.edges
            .filterNot { it.sourceNodeId == it.targetNodeId }
            .groupBy { it.sourceNodeId }

        normalized.edges
            .filter { it.sourceNodeId == it.targetNodeId }
            .sortedBy { it.id.value }
            .forEach { directions[it.id] = FlowLayoutEdgeDirection.SELF_LOOP }

        fun visit(node: FlowNodeId) {
            colors[node] = 1
            outgoing[node].orEmpty().sortedBy { it.id.value }.forEach { edge ->
                when (colors[edge.targetNodeId] ?: 0) {
                    0 -> {
                        directions[edge.id] = FlowLayoutEdgeDirection.FORWARD
                        visit(edge.targetNodeId)
                    }
                    1 -> directions[edge.id] = FlowLayoutEdgeDirection.BACK
                    else -> directions.putIfAbsent(edge.id, FlowLayoutEdgeDirection.FORWARD)
                }
            }
            colors[node] = 2
        }

        normalized.nodes.map { it.id }.sortedBy { it.value }.forEach { node ->
            if ((colors[node] ?: 0) == 0) visit(node)
        }
        normalized.edges.forEach { edge ->
            directions.putIfAbsent(edge.id, FlowLayoutEdgeDirection.FORWARD)
        }
        return FlowCycleResolution(directions)
    }

    public fun assignLayers(
        normalized: FlowNormalizedGraph,
        cycleResolution: FlowCycleResolution,
    ): FlowLayerAssignment {
        val ranks = normalized.nodes.associate { it.id to 0 }.toMutableMap()
        val forwardEdges = normalized.edges.filter {
            cycleResolution.edgeDirections[it.id] == FlowLayoutEdgeDirection.FORWARD
        }
        repeat(normalized.nodes.size) {
            forwardEdges.forEach { edge ->
                ranks[edge.targetNodeId] = max(
                    ranks.getValue(edge.targetNodeId),
                    ranks.getValue(edge.sourceNodeId) + 1,
                )
            }
        }
        return FlowLayerAssignment(ranks)
    }

    public fun insertDummyNodes(
        normalized: FlowNormalizedGraph,
        layerAssignment: FlowLayerAssignment,
        cycleResolution: FlowCycleResolution,
    ): FlowDummyNodeInsertion {
        val dummyNodes = mutableListOf<FlowDummyLayoutNode>()
        val edgeIdsUsingDummyNodes = linkedSetOf<FlowEdgeId>()
        normalized.edges.forEach { edge ->
            if (cycleResolution.edgeDirections[edge.id] != FlowLayoutEdgeDirection.FORWARD) return@forEach
            val sourceRank = layerAssignment.ranks.getValue(edge.sourceNodeId)
            val targetRank = layerAssignment.ranks.getValue(edge.targetNodeId)
            val distance = targetRank - sourceRank
            if (distance > 1) {
                edgeIdsUsingDummyNodes += edge.id
                for (rank in (sourceRank + 1) until targetRank) {
                    dummyNodes += FlowDummyLayoutNode(
                        id = "${edge.id.value}:dummy:$rank",
                        edgeId = edge.id,
                        rank = rank,
                    )
                }
            }
        }
        return FlowDummyNodeInsertion(
            dummyNodes = dummyNodes.sortedWith(compareBy<FlowDummyLayoutNode> { it.rank }.thenBy { it.id }),
            edgeIdsUsingDummyNodes = edgeIdsUsingDummyNodes,
        )
    }

    public fun minimizeCrossings(
        normalized: FlowNormalizedGraph,
        layerAssignment: FlowLayerAssignment,
        cycleResolution: FlowCycleResolution,
        config: FlowLayoutConfig = FlowLayoutConfig(),
    ): FlowCrossingMinimization {
        val outgoingForward = normalized.edges.filter {
            cycleResolution.edgeDirections[it.id] == FlowLayoutEdgeDirection.FORWARD
        }
        var ordered = layerAssignment.ranks.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
            .mapValues { (_, ids) -> ids.sortedBy { it.value } }

        repeat(config.crossingReductionSweeps) {
            ordered = ordered.mapValues { (rank, ids) ->
                val previousOrder = ordered[rank - 1].orEmpty().withIndex().associate { it.value to it.index.toDouble() }
                val nextOrder = ordered[rank + 1].orEmpty().withIndex().associate { it.value to it.index.toDouble() }
                ids.sortedWith(
                    compareBy<FlowNodeId> { id ->
                        val neighbors = outgoingForward.mapNotNull { edge ->
                            when (id) {
                                edge.targetNodeId -> previousOrder[edge.sourceNodeId]
                                edge.sourceNodeId -> nextOrder[edge.targetNodeId]
                                else -> null
                            }
                        }
                        neighbors.average().takeUnless(Double::isNaN) ?: ids.indexOf(id).toDouble()
                    }.thenBy { it.value },
                )
            }.toSortedMap()
        }

        return FlowCrossingMinimization(ordered)
    }

    public fun positionNodes(
        normalized: FlowNormalizedGraph,
        crossing: FlowCrossingMinimization,
        layerAssignment: FlowLayerAssignment,
        nodeMetrics: FlowNodeMetrics = FlowNodeMetrics(emptyMap()),
        config: FlowLayoutConfig = FlowLayoutConfig(),
        compatibleView: FlowViewDocument? = null,
    ): FlowNodePositioning {
        val bounds = linkedMapOf<FlowNodeId, FlowRect>()
        var componentOffset = 0.0
        normalized.components.forEach { component ->
            val componentRanks = component.map { layerAssignment.ranks.getValue(it) }.toSet().sorted()
            var componentExtent = 0.0
            componentRanks.forEach { rank ->
                var crossOffset = componentOffset
                crossing.orderedLayers[rank].orEmpty().filter { it in component }.forEach { id ->
                    val size = nodeMetrics.sizes[id] ?: nodeMetrics.defaultSize
                    val generated = when (config.orientation) {
                        FlowLayoutOrientation.TOP_TO_BOTTOM ->
                            FlowPoint(crossOffset, rank * (nodeMetrics.defaultSize.height + config.layerSpacing))
                        FlowLayoutOrientation.LEFT_TO_RIGHT ->
                            FlowPoint(rank * (nodeMetrics.defaultSize.width + config.layerSpacing), crossOffset)
                    }
                    val pinned = compatibleView?.nodeViews?.firstOrNull { it.nodeId == id && it.pinned }?.position
                    val position = if (config.pinnedNodePolicy == FlowPinnedNodePolicy.HONOR_VIEW) pinned ?: generated else generated
                    bounds[id] = FlowRect(position, size)
                    crossOffset += crossSize(size, config.orientation) + config.nodeSpacing
                    componentExtent = max(componentExtent, crossOffset)
                }
            }
            componentOffset = componentExtent + config.componentSpacing
        }
        return FlowNodePositioning(bounds)
    }

    public fun routeEdges(
        normalized: FlowNormalizedGraph,
        positioning: FlowNodePositioning,
        layerAssignment: FlowLayerAssignment,
        cycleResolution: FlowCycleResolution,
        config: FlowLayoutConfig = FlowLayoutConfig(),
        compatibleView: FlowViewDocument? = null,
    ): FlowManhattanRouting {
        val parallelIndices = parallelIndices(normalized.edges, cycleResolution)
        val routes = normalized.edges.associate { edge ->
            val source = positioning.nodeBounds.getValue(edge.sourceNodeId)
            val target = positioning.nodeBounds.getValue(edge.targetNodeId)
            val obstacles = positioning.nodeBounds
                .filterKeys { it != edge.sourceNodeId && it != edge.targetNodeId }
                .values
            edge.id to route(
                edge = edge,
                source = source,
                target = target,
                obstacles = obstacles,
                ranks = layerAssignment.ranks,
                direction = cycleResolution.edgeDirections.getValue(edge.id),
                parallelIndex = parallelIndices.getValue(edge.id),
                config = config,
                view = compatibleView,
            )
        }
        return FlowManhattanRouting(routes)
    }

    private data class ParallelIndex(val index: Int, val total: Int) {
        val offset: Double get() = (index - (total - 1) / 2.0) * 12.0
    }

    private fun connectedComponents(nodes: List<FlowNodeId>, edges: List<FlowGraphEdge>): List<Set<FlowNodeId>> {
        val adjacent = mutableMapOf<FlowNodeId, MutableSet<FlowNodeId>>()
        edges.forEach { edge ->
            adjacent.getOrPut(edge.sourceNodeId, ::linkedSetOf).add(edge.targetNodeId)
            adjacent.getOrPut(edge.targetNodeId, ::linkedSetOf).add(edge.sourceNodeId)
        }
        val unseen = nodes.toMutableSet()
        val result = mutableListOf<Set<FlowNodeId>>()
        while (unseen.isNotEmpty()) {
            val start = unseen.minBy { it.value }
            val queue = ArrayDeque<FlowNodeId>()
            val component = linkedSetOf<FlowNodeId>()
            queue += start
            unseen -= start
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                adjacent[current].orEmpty().sortedBy { it.value }.forEach { neighbor ->
                    if (unseen.remove(neighbor)) queue += neighbor
                }
            }
            result += component
        }
        return result
    }

    private fun route(
        edge: FlowGraphEdge,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        ranks: Map<FlowNodeId, Int>,
        direction: FlowLayoutEdgeDirection,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
        view: FlowViewDocument?,
    ): FlowRoute {
        val locked = view?.edgeViews?.firstOrNull {
            it.edgeId == edge.id && it.routeLockState == FlowRouteLockState.LOCKED
        }
        if (locked != null && locked.bendPoints.isNotEmpty()) {
            return makeRoute(
                edge.id,
                FlowRouteKind.ORTHOGONAL,
                listOf(portOut(source, config.orientation, parallelIndex.offset)) +
                    locked.bendPoints.map { FlowRoutePoint(it.x, it.y) } +
                    portIn(target, config.orientation, parallelIndex.offset),
                true,
            )
        }

        return when (direction) {
            FlowLayoutEdgeDirection.SELF_LOOP -> routeSelfLoop(edge, source, parallelIndex, config)
            FlowLayoutEdgeDirection.BACK -> routeBackEdge(edge, source, target, parallelIndex, config)
            FlowLayoutEdgeDirection.FORWARD -> routeForwardEdge(
                edge = edge,
                source = source,
                target = target,
                obstacles = obstacles,
                rankDistance = abs(ranks.getValue(edge.targetNodeId) - ranks.getValue(edge.sourceNodeId)),
                parallelIndex = parallelIndex,
                config = config,
            )
        }
    }

    private fun routeForwardEdge(
        edge: FlowGraphEdge,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        rankDistance: Int,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
    ): FlowRoute {
        if (edge.kind == FlowEdgeKind.GOTO) {
            return routeJumpEdge(edge, source, target, parallelIndex, config)
        }
        val start = portOut(source, config.orientation, parallelIndex.offset)
        val end = portIn(target, config.orientation, parallelIndex.offset)
        val directPoints = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> {
                val mid = (start.y + end.y) / 2
                listOf(start, FlowRoutePoint(start.x, mid), FlowRoutePoint(end.x, mid), end)
            }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> {
                val mid = (start.x + end.x) / 2
                listOf(start, FlowRoutePoint(mid, start.y), FlowRoutePoint(mid, end.y), end)
            }
        }
        val points = if (collides(directPoints, obstacles, config.routingClearance)) {
            detourAroundObstacles(start, end, source, target, obstacles, parallelIndex, config)
        } else {
            directPoints
        }
        val kind = if (edge.kind in branchKinds) FlowRouteKind.BRANCH else FlowRouteKind.ORTHOGONAL
        return makeRoute(edge.id, kind, collapseDuplicatePoints(points), rankDistance > 1)
    }

    private fun routeBackEdge(
        edge: FlowGraphEdge,
        source: FlowRect,
        target: FlowRect,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
    ): FlowRoute {
        val start = portOut(source, config.orientation, parallelIndex.offset)
        val end = portIn(target, config.orientation, parallelIndex.offset)
        val lane = config.routingClearance + 24.0 + abs(parallelIndex.offset)
        val points = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> {
                val x = minOf(source.left, target.left) - lane
                val y = target.top - lane
                listOf(start, FlowRoutePoint(x, start.y), FlowRoutePoint(x, y), FlowRoutePoint(end.x, y), end)
            }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> {
                val y = minOf(source.top, target.top) - lane
                val x = target.left - lane
                listOf(start, FlowRoutePoint(start.x, y), FlowRoutePoint(x, y), FlowRoutePoint(x, end.y), end)
            }
        }
        return makeRoute(edge.id, FlowRouteKind.LOOP_BACK, collapseDuplicatePoints(points), true)
    }

    private fun routeJumpEdge(
        edge: FlowGraphEdge,
        source: FlowRect,
        target: FlowRect,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
    ): FlowRoute {
        val start = portOut(source, config.orientation, parallelIndex.offset)
        val end = portIn(target, config.orientation, parallelIndex.offset)
        val lane = config.routingClearance + 24.0 + abs(parallelIndex.offset)
        val points = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> {
                val x = minOf(source.left, target.left) - lane
                val y = (start.y + end.y) / 2.0
                listOf(start, FlowRoutePoint(x, start.y), FlowRoutePoint(x, y), FlowRoutePoint(x, end.y), end)
            }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> {
                val y = minOf(source.top, target.top) - lane
                val x = (start.x + end.x) / 2.0
                listOf(start, FlowRoutePoint(start.x, y), FlowRoutePoint(x, y), FlowRoutePoint(end.x, y), end)
            }
        }
        return makeRoute(edge.id, FlowRouteKind.ORTHOGONAL, collapseDuplicatePoints(points), true)
    }

    private fun routeSelfLoop(
        edge: FlowGraphEdge,
        node: FlowRect,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
    ): FlowRoute {
        val lane = config.routingClearance + 24.0 + abs(parallelIndex.offset)
        val points = when (config.orientation) {
            FlowLayoutOrientation.TOP_TO_BOTTOM -> {
                val start = FlowRoutePoint(node.left, node.top + node.size.height * 0.35 + parallelIndex.offset)
                val end = FlowRoutePoint(node.left, node.top + node.size.height * 0.65 + parallelIndex.offset)
                val x = node.left - lane
                listOf(start, FlowRoutePoint(x, start.y), FlowRoutePoint(x, end.y), end)
            }
            FlowLayoutOrientation.LEFT_TO_RIGHT -> {
                val start = FlowRoutePoint(node.left + node.size.width * 0.35 + parallelIndex.offset, node.top)
                val end = FlowRoutePoint(node.left + node.size.width * 0.65 + parallelIndex.offset, node.top)
                val y = node.top - lane
                listOf(start, FlowRoutePoint(start.x, y), FlowRoutePoint(end.x, y), end)
            }
        }
        return makeRoute(edge.id, FlowRouteKind.SELF_LOOP, collapseDuplicatePoints(points), true)
    }

    private fun detourAroundObstacles(
        start: FlowRoutePoint,
        end: FlowRoutePoint,
        source: FlowRect,
        target: FlowRect,
        obstacles: Collection<FlowRect>,
        parallelIndex: ParallelIndex,
        config: FlowLayoutConfig,
    ): List<FlowRoutePoint> = when (config.orientation) {
        FlowLayoutOrientation.TOP_TO_BOTTOM -> {
            val lane = max(
                max(source.right, target.right),
                obstacles.maxOfOrNull { it.right } ?: Double.NEGATIVE_INFINITY,
            ) + config.routingClearance + abs(parallelIndex.offset)
            listOf(start, FlowRoutePoint(lane, start.y), FlowRoutePoint(lane, end.y), end)
        }
        FlowLayoutOrientation.LEFT_TO_RIGHT -> {
            val lane = max(
                max(source.bottom, target.bottom),
                obstacles.maxOfOrNull { it.bottom } ?: Double.NEGATIVE_INFINITY,
            ) + config.routingClearance + abs(parallelIndex.offset)
            listOf(start, FlowRoutePoint(start.x, lane), FlowRoutePoint(end.x, lane), end)
        }
    }

    private fun parallelIndices(
        edges: List<FlowGraphEdge>,
        cycleResolution: FlowCycleResolution,
    ): Map<FlowEdgeId, ParallelIndex> =
        edges.groupBy { edge ->
            listOf(
                edge.sourceNodeId.value,
                edge.targetNodeId.value,
                cycleResolution.edgeDirections.getValue(edge.id).name,
            ).joinToString("->")
        }.values.flatMap { group ->
            group.sortedBy { it.id.value }.mapIndexed { index, edge ->
                edge.id to ParallelIndex(index, group.size)
            }
        }.toMap()

    private fun crossSize(size: FlowSize, orientation: FlowLayoutOrientation): Double =
        if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) size.width else size.height

    private fun portOut(
        rect: FlowRect,
        orientation: FlowLayoutOrientation,
        offset: Double,
    ): FlowRoutePoint = if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) {
        FlowRoutePoint((rect.left + rect.right) / 2 + offset, rect.bottom)
    } else {
        FlowRoutePoint(rect.right, (rect.top + rect.bottom) / 2 + offset)
    }

    private fun portIn(
        rect: FlowRect,
        orientation: FlowLayoutOrientation,
        offset: Double,
    ): FlowRoutePoint = if (orientation == FlowLayoutOrientation.TOP_TO_BOTTOM) {
        FlowRoutePoint((rect.left + rect.right) / 2 + offset, rect.top)
    } else {
        FlowRoutePoint(rect.left, (rect.top + rect.bottom) / 2 + offset)
    }

    private fun makeRoute(
        id: FlowEdgeId,
        kind: FlowRouteKind,
        points: List<FlowRoutePoint>,
        dummy: Boolean,
    ): FlowRoute = FlowRoute(id, kind, points, points.zipWithNext(::FlowRouteSegment), dummy)

    private fun collapseDuplicatePoints(points: List<FlowRoutePoint>): List<FlowRoutePoint> =
        points.fold(emptyList()) { acc, point ->
            if (acc.lastOrNull() == point) acc else acc + point
        }

    private fun collides(
        points: List<FlowRoutePoint>,
        obstacles: Collection<FlowRect>,
        clearance: Double,
    ): Boolean = points.zipWithNext().any { (start, end) ->
        obstacles.any { rect ->
            val left = rect.left - clearance
            val right = rect.right + clearance
            val top = rect.top - clearance
            val bottom = rect.bottom + clearance
            if (start.x == end.x) {
                start.x in left..right && rangesOverlap(start.y, end.y, top, bottom)
            } else if (start.y == end.y) {
                start.y in top..bottom && rangesOverlap(start.x, end.x, left, right)
            } else {
                true
            }
        }
    }

    private fun rangesOverlap(a: Double, b: Double, low: Double, high: Double): Boolean =
        minOf(a, b) <= high && maxOf(a, b) >= low

    private fun finite(rect: FlowRect): Boolean =
        listOf(rect.left, rect.top, rect.right, rect.bottom).all(Double::isFinite) &&
            rect.size.width > 0 &&
            rect.size.height > 0

    private fun seededKey(value: String, seed: Long): String = "${value.hashCode().toLong() xor seed}:$value"

    private val branchKinds: Set<FlowEdgeKind> = setOf(
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
    )
}
