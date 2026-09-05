/* SPDX-License-Identifier: Apache-2.0 */
package de.visualtasker.flowchart.compose

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import de.visualtasker.flowchart.domain.*
import de.visualtasker.flowchart.interaction.*

@Composable
public fun FlowchartHost(
    graphDocument: FlowGraphDocument,
    viewDocument: FlowViewDocument?,
    runtimeSnapshot: FlowRuntimeSnapshot?,
    controller: FlowchartController,
    uiConfig: FlowchartUiConfig = FlowchartUiConfig(),
    callbacks: FlowchartHostCallbacks = FlowchartHostCallbacks(),
    nodeShapeProvider: FlowchartNodeShapeProvider? = null,
) {
    var controllerState by remember(controller) { mutableStateOf(controller.snapshot()) }
    DisposableEffect(controller, callbacks) {
        controller.setListeners(
            { callbacks.onViewDocumentChanged(it); controllerState = controller.snapshot() },
            { callbacks.onStatusMessage(it); controllerState = controller.snapshot() },
        )
        onDispose { controller.setListeners(null, null) }
    }
    LaunchedEffect(controller, graphDocument, viewDocument) { controller.attachGraph(graphDocument, viewDocument); controllerState = controller.snapshot() }
    LaunchedEffect(controller, runtimeSnapshot) { runtimeSnapshot?.let(controller::attachRuntime); controllerState = controller.snapshot() }
    val view = controllerState.view
    if (graphDocument.nodes.isEmpty() || view == null) {
        Box(Modifier.fillMaxSize().testTag("flowchart-empty").semantics { contentDescription = "Empty flowchart" }) { Text("No flowchart nodes", Modifier.padding(24.dp)) }
        return
    }
    Box(Modifier.fillMaxSize().background(uiConfig.colorTokens.background)) {
        var collapsedFacetNodeIds by remember(controller, graphDocument.documentRevision) { mutableStateOf<Set<FlowNodeId>>(emptySet()) }
        var lockedFacetNodeIds by remember(controller, graphDocument.documentRevision) { mutableStateOf<Set<FlowNodeId>>(emptySet()) }
        FlowCanvas(graphDocument, view, controllerState.runtime, controllerState.interaction, uiConfig, nodeShapeProvider, collapsedFacetNodeIds, lockedFacetNodeIds)
        FlowLabelsAndSemantics(graphDocument, view, controllerState, callbacks, collapsedFacetNodeIds)
        FlowGestureLayer(
            graph = graphDocument,
            view = view,
            controller = controller,
            config = uiConfig,
            callbacks = callbacks,
            collapsedFacetNodeIds = collapsedFacetNodeIds,
            lockedFacetNodeIds = lockedFacetNodeIds,
            onToggleFacetCollapsed = { facetId ->
                collapsedFacetNodeIds = if (facetId in collapsedFacetNodeIds) {
                    collapsedFacetNodeIds - facetId
                } else {
                    collapsedFacetNodeIds + facetId
                }
            },
            onToggleFacetLocked = { facetId ->
                lockedFacetNodeIds = if (facetId in lockedFacetNodeIds) {
                    lockedFacetNodeIds - facetId
                } else {
                    lockedFacetNodeIds + facetId
                }
            },
        ) { controllerState = controller.snapshot() }
        if (uiConfig.controlsEnabled) {
            ZoomControls(controller, uiConfig) { controllerState = controller.snapshot() }
        }
    }
}

@Composable
private fun FlowCanvas(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    runtime: FlowRuntimeSnapshot?,
    interaction: FlowInteractionState,
    config: FlowchartUiConfig,
    nodeShapeProvider: FlowchartNodeShapeProvider?,
    collapsedFacetNodeIds: Set<FlowNodeId> = emptySet(),
    lockedFacetNodeIds: Set<FlowNodeId> = emptySet(),
) {
    Canvas(Modifier.fillMaxSize().testTag("flowchart-canvas")) {
        val viewport = view.viewport
        fun screen(point: FlowPoint) = Offset((point.x * viewport.zoom + viewport.pan.x).toFloat(), (point.y * viewport.zoom + viewport.pan.y).toFloat())
        val collapsedNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
        drawBackgroundFacetRegions(graph, view, config, collapsedFacetNodeIds, lockedFacetNodeIds, ::screen)
        val edgeRoutes = graph.edges
            .sortedBy { it.id.value }
            .filterNot { edge -> edge.sourceNodeId in collapsedNodeIds || edge.targetNodeId in collapsedNodeIds }
            .mapNotNull { edge ->
                val points = edgeGraphPoints(edge, graph, view).map(::screen)
                if (points.size < 2) null else edge to points
            }
        edgeRoutes.forEachIndexed { edgeIndex, (edge, points) ->
            if (points.size < 2) return@forEachIndexed
            val traversed = edge.id in runtime?.traversedEdgeIds.orEmpty()
            val edgeColor = if (traversed) {
                config.colorTokens.traversedEdge
            } else {
                when (flowEdgeVisualCategory(edge.kind)) {
                    FlowchartEdgeVisualCategory.DEFAULT -> config.colorTokens.edge
                    FlowchartEdgeVisualCategory.BRANCH -> config.colorTokens.branchEdge
                    FlowchartEdgeVisualCategory.DATA -> config.colorTokens.dataEdge
                    FlowchartEdgeVisualCategory.LOOP -> config.colorTokens.loopEdge
                    FlowchartEdgeVisualCategory.ERROR -> config.colorTokens.errorEdge
                }
            }
            val edgeStrokeWidth = config.shapeTokens.edgeStrokeWidthDp.dp.toPx() * if (traversed) 1.55f else 1f
            val bridges = edgeBridgeIntersections(
                points = points,
                previousRoutes = edgeRoutes.take(edgeIndex).map { it.second },
                minDistanceFromEnds = edgeStrokeWidth * 4f,
            )
            points.zipWithNext().forEach { (a, b) ->
                drawLine(
                    color = edgeColor,
                    start = a,
                    end = b,
                    strokeWidth = edgeStrokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            bridges.forEach { bridge ->
                drawCircle(
                    color = config.colorTokens.background.copy(alpha = 0.96f),
                    radius = edgeStrokeWidth * 1.95f,
                    center = bridge.center,
                )
                drawArc(
                    color = edgeColor,
                    startAngle = bridge.startAngle,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(
                        bridge.center.x - edgeStrokeWidth * 2.0f,
                        bridge.center.y - edgeStrokeWidth * 2.0f,
                    ),
                    size = Size(edgeStrokeWidth * 4.0f, edgeStrokeWidth * 4.0f),
                    style = Stroke(width = edgeStrokeWidth, cap = StrokeCap.Round),
                )
            }
            val edgePresentation = flowEdgePresentation(
                points = points,
                arrowLength = config.shapeTokens.arrowLengthDp.dp.toPx().toDouble(),
                arrowWidth = config.shapeTokens.arrowWidthDp.dp.toPx().toDouble(),
            )
            edgePresentation.connector?.let { startPoint ->
                drawCircle(edgeColor, config.shapeTokens.connectorRadiusDp.dp.toPx(), startPoint)
            }
            val arrow = edgePresentation.arrowHead
            if (arrow.size == 3) {
                drawPath(
                    path = Path().apply {
                        moveTo(arrow[0].x.toFloat(), arrow[0].y.toFloat())
                        lineTo(arrow[1].x.toFloat(), arrow[1].y.toFloat())
                        lineTo(arrow[2].x.toFloat(), arrow[2].y.toFloat())
                        close()
                    },
                    color = edgeColor,
                )
            }
        }
        graph.nodes.sortedBy { it.id.value }.forEach { node ->
            if (node.isBackgroundFacetNode()) return@forEach
            if (node.id in collapsedNodeIds) return@forEach
            val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowSize(160.0, 72.0); val origin = screen(nodeView.position); val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            val runtimeState = runtime?.nodeStates?.get(node.id)
            val nodeFillColor = flowNodeFillColor(node, config.colorTokens)
            val stroke = when {
                node.id in interaction.selectedNodeIds -> config.colorTokens.selectedStroke
                runtimeState == FlowRuntimeNodeState.FAILED -> config.colorTokens.failedStroke
                runtime?.activeNodeId == node.id -> config.colorTokens.runningStroke
                runtimeState in setOf(FlowRuntimeNodeState.RUNNING, FlowRuntimeNodeState.WAITING) -> config.colorTokens.runningStroke
                runtimeState == FlowRuntimeNodeState.SUCCEEDED -> config.colorTokens.succeededStroke
                runtimeState == FlowRuntimeNodeState.SKIPPED -> config.colorTokens.skippedStroke
                else -> config.colorTokens.nodeStroke
            }
            val visualPath = resolveNodeShape(nodeShapeProvider, node, canvasSize.width, canvasSize.height)
            if (visualPath != null) {
                translate(origin.x, origin.y) {
                    drawPath(visualPath, nodeFillColor)
                    drawPath(
                        path = visualPath,
                        color = stroke,
                        style = Stroke(
                            width = config.shapeTokens.nodeStrokeWidthDp.dp.toPx(),
                            pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) {
                                PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                            } else {
                                null
                            },
                        ),
                    )
                }
            } else {
                drawRoundRect(nodeFillColor, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()))
                drawRoundRect(stroke, origin, canvasSize, CornerRadius(config.shapeTokens.nodeCornerRadiusDp.dp.toPx()), style = Stroke(config.shapeTokens.nodeStrokeWidthDp.dp.toPx(), pathEffect = if (node.kind.standard == FlowNodeKind.UNKNOWN_SOURCE || node.kind.extensionId != null) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null))
            }
            drawNodePorts(
                node = node,
                origin = origin,
                size = canvasSize,
                config = config,
            )
            if (config.diagnosticMarkersEnabled && node.diagnosticIds.isNotEmpty()) drawCircle(config.colorTokens.diagnostic, 6.dp.toPx(), Offset(origin.x + canvasSize.width - 10.dp.toPx(), origin.y + 10.dp.toPx()))
        }
    }
}

private fun DrawScope.drawBackgroundFacetRegions(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    config: FlowchartUiConfig,
    collapsedFacetNodeIds: Set<FlowNodeId>,
    lockedFacetNodeIds: Set<FlowNodeId>,
    screen: (FlowPoint) -> Offset,
) {
    flowFacetRegions(graph, view, screen)
        .forEach { region ->
            val kind = (region.facet.properties["facetKind"] as? FlowSemanticValue.StringValue)?.value.orEmpty()
            val color = when (kind) {
                "VARIABLE_BULK" -> config.colorTokens.variableNodeFill
                "COLLAPSE_GROUP" -> config.colorTokens.feedbackNodeFill
                else -> config.colorTokens.branchEdge
            }
            drawRoundRect(
                color = color.copy(alpha = if (region.facet.id in collapsedFacetNodeIds) 0.13f else 0.07f),
                topLeft = region.bounds.topLeft,
                size = region.bounds.size,
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            )
            drawRoundRect(
                color = color.copy(alpha = if (region.facet.id in collapsedFacetNodeIds) 0.52f else 0.36f),
                topLeft = region.bounds.topLeft,
                size = region.bounds.size,
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
            )
            drawFacetHandle(
                bounds = region.handleBounds,
                color = color,
                locked = region.facet.id in lockedFacetNodeIds,
                collapsed = region.facet.id in collapsedFacetNodeIds,
            )
        }
}

internal data class FlowFacetRegion(
    val facet: FlowGraphNode,
    val nodeIds: Set<FlowNodeId>,
    val bounds: androidx.compose.ui.geometry.Rect,
    val handleBounds: androidx.compose.ui.geometry.Rect,
    val gripBounds: androidx.compose.ui.geometry.Rect,
    val collapseBounds: androidx.compose.ui.geometry.Rect,
    val lockBounds: androidx.compose.ui.geometry.Rect,
)

internal enum class FlowFacetHandleAction {
    Drag,
    ToggleCollapse,
    ToggleLock,
}

internal data class FlowFacetHandleHit(
    val region: FlowFacetRegion,
    val action: FlowFacetHandleAction,
)

internal fun flowFacetRegions(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    screen: (FlowPoint) -> Offset,
): List<FlowFacetRegion> =
    graph.nodes
        .filter { it.isBackgroundFacetNode() }
        .sortedBy { it.id.value }
        .mapNotNull { facet ->
            val nodeIds = facet.facetContentNodeIds()
            val rects = view.nodeViews
                .filter { it.nodeId in nodeIds }
                .map { FlowRect(it.position, it.size ?: FlowSize(160.0, 72.0)) }
            if (rects.isEmpty()) return@mapNotNull null
            val padding = 18f
            val handleWidth = 72f
            val handleHeight = 24f
            val handleGap = 6f
            val origin = screen(FlowPoint(rects.minOf { it.left }, rects.minOf { it.top }))
            val end = screen(FlowPoint(rects.maxOf { it.right }, rects.maxOf { it.bottom }))
            val bounds = androidx.compose.ui.geometry.Rect(
                left = origin.x - padding,
                top = origin.y - padding,
                right = end.x + padding,
                bottom = end.y + padding,
            )
            val handleLeft = bounds.left + 8f
            val handleTop = bounds.top - handleHeight - handleGap
            val handleBounds = androidx.compose.ui.geometry.Rect(
                left = handleLeft,
                top = handleTop,
                right = handleLeft + handleWidth,
                bottom = handleTop + handleHeight,
            )
            FlowFacetRegion(
                facet = facet,
                nodeIds = nodeIds,
                bounds = bounds,
                handleBounds = handleBounds,
                gripBounds = androidx.compose.ui.geometry.Rect(handleBounds.left, handleBounds.top, handleBounds.left + 30f, handleBounds.bottom),
                collapseBounds = androidx.compose.ui.geometry.Rect(handleBounds.left + 30f, handleBounds.top, handleBounds.left + 50f, handleBounds.bottom),
                lockBounds = androidx.compose.ui.geometry.Rect(handleBounds.left + 50f, handleBounds.top, handleBounds.right, handleBounds.bottom),
            )
        }

internal fun hitFlowFacetHandle(
    offset: Offset,
    graph: FlowGraphDocument,
    view: FlowViewDocument,
): FlowFacetHandleHit? {
    fun screen(point: FlowPoint) = FlowViewportTransform.graphToScreen(point, view.viewport)
        .let { Offset(it.x.toFloat(), it.y.toFloat()) }
    return flowFacetRegions(graph, view, ::screen)
        .asReversed()
        .firstNotNullOfOrNull { region ->
            when {
                region.gripBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.Drag)
                region.collapseBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.ToggleCollapse)
                region.lockBounds.contains(offset) -> FlowFacetHandleHit(region, FlowFacetHandleAction.ToggleLock)
                else -> null
            }
        }
}

internal data class EdgeBridge(
    val center: Offset,
    val startAngle: Float,
)

internal fun edgeBridgeIntersections(
    points: List<Offset>,
    previousRoutes: List<List<Offset>>,
    minDistanceFromEnds: Float,
): List<EdgeBridge> =
    buildList {
        points.zipWithNext().forEach { segment ->
            val bridge = segmentBridgeAgainstRoutes(segment.first, segment.second, previousRoutes, minDistanceFromEnds)
            addAll(bridge)
        }
    }.distinctBy { "${it.center.x.toInt()}:${it.center.y.toInt()}:${it.startAngle.toInt()}" }

private fun segmentBridgeAgainstRoutes(
    a: Offset,
    b: Offset,
    routes: List<List<Offset>>,
    minDistanceFromEnds: Float,
): List<EdgeBridge> {
    val horizontal = a.y == b.y && a.x != b.x
    val vertical = a.x == b.x && a.y != b.y
    if (!horizontal && !vertical) return emptyList()
    return routes
        .flatMap { it.zipWithNext() }
        .mapNotNull { (c, d) ->
            val otherHorizontal = c.y == d.y && c.x != d.x
            val otherVertical = c.x == d.x && c.y != d.y
            when {
                horizontal && otherVertical -> crossing(a, b, c, d)?.let { EdgeBridge(it, 0f) }
                vertical && otherHorizontal -> crossing(c, d, a, b)?.let { EdgeBridge(it, 90f) }
                else -> null
            }
        }
        .filter { bridge ->
            distanceAlongSegment(a, b, bridge.center) >= minDistanceFromEnds &&
                distanceAlongSegment(a, b, bridge.center) <= segmentLength(a, b) - minDistanceFromEnds
        }
}

private fun crossing(horizontalStart: Offset, horizontalEnd: Offset, verticalStart: Offset, verticalEnd: Offset): Offset? {
    val minX = minOf(horizontalStart.x, horizontalEnd.x)
    val maxX = maxOf(horizontalStart.x, horizontalEnd.x)
    val minY = minOf(verticalStart.y, verticalEnd.y)
    val maxY = maxOf(verticalStart.y, verticalEnd.y)
    val x = verticalStart.x
    val y = horizontalStart.y
    return if (x in minX..maxX && y in minY..maxY) Offset(x, y) else null
}

private fun segmentLength(a: Offset, b: Offset): Float =
    kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)

private fun distanceAlongSegment(start: Offset, end: Offset, point: Offset): Float =
    if (start.x == end.x) kotlin.math.abs(point.y - start.y) else kotlin.math.abs(point.x - start.x)

private fun DrawScope.drawFacetHandle(
    bounds: androidx.compose.ui.geometry.Rect,
    color: Color,
    locked: Boolean,
    collapsed: Boolean,
) {
    val icon = 5.dp.toPx()
    val handleHeight = bounds.height
    drawRoundRect(
        color = color.copy(alpha = 0.28f),
        topLeft = bounds.topLeft,
        size = bounds.size,
        cornerRadius = CornerRadius(handleHeight / 2f, handleHeight / 2f),
    )
    repeat(3) { index ->
        val y = bounds.top + 7.dp.toPx() + index * 4.dp.toPx()
        drawLine(
            color = Color.White.copy(alpha = 0.82f),
            start = Offset(bounds.left + 8.dp.toPx(), y),
            end = Offset(bounds.left + 23.dp.toPx(), y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
    val chevronX = bounds.left + 39.dp.toPx()
    val chevronY = bounds.top + handleHeight / 2f
    val chevronDirection = if (collapsed) -1f else 1f
    drawLine(
        color = Color.White.copy(alpha = 0.86f),
        start = Offset(chevronX - icon, chevronY - icon * chevronDirection),
        end = Offset(chevronX, chevronY + icon * chevronDirection),
        strokeWidth = 1.7.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.86f),
        start = Offset(chevronX + icon, chevronY - icon * chevronDirection),
        end = Offset(chevronX, chevronY + icon * chevronDirection),
        strokeWidth = 1.7.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val lockX = bounds.left + 55.dp.toPx()
    val lockY = bounds.top + 8.dp.toPx()
    drawRoundRect(
        color = if (locked) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.34f),
        topLeft = Offset(lockX, lockY),
        size = Size(10.dp.toPx(), 8.dp.toPx()),
        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        style = Stroke(1.4.dp.toPx()),
    )
    drawArc(
        color = if (locked) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.34f),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(lockX + 1.5.dp.toPx(), lockY - 5.dp.toPx()),
        size = Size(7.dp.toPx(), 9.dp.toPx()),
        style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun FlowGraphNode.facetContentNodeIds(): Set<FlowNodeId> =
    (properties["nodeIds"] as? FlowSemanticValue.ListValue)
        ?.values
        .orEmpty()
        .mapNotNull { (it as? FlowSemanticValue.StringValue)?.value }
        .map(::FlowNodeId)
        .toSet()

private fun collapsedFacetContentNodeIds(
    graph: FlowGraphDocument,
    collapsedFacetNodeIds: Set<FlowNodeId>,
): Set<FlowNodeId> =
    graph.nodes
        .filter { it.id in collapsedFacetNodeIds }
        .flatMap { it.facetContentNodeIds() }
        .toSet()

internal data class FlowchartNodePort(
    val name: String,
    val label: String,
    val kind: FlowEdgeKind,
)

internal data class FlowchartNodePortHit(
    val ref: FlowchartNodePortRef,
    val bounds: androidx.compose.ui.geometry.Rect,
)

internal fun flowNodePorts(
    node: FlowGraphNode,
    key: String,
): List<FlowchartNodePort> {
    val list = node.properties[key] as? FlowSemanticValue.ListValue ?: return emptyList()
    return list.values.mapNotNull { value ->
        val fields = (value as? FlowSemanticValue.ObjectValue)?.values ?: return@mapNotNull null
        val name = (fields["name"] as? FlowSemanticValue.StringValue)?.value ?: return@mapNotNull null
        val kindName = (fields["kind"] as? FlowSemanticValue.StringValue)?.value ?: return@mapNotNull null
        val kind = FlowEdgeKind.entries.firstOrNull { it.name == kindName } ?: return@mapNotNull null
        val label = (fields["label"] as? FlowSemanticValue.StringValue)?.value ?: name
        FlowchartNodePort(name = name, label = label, kind = kind)
    }
}

private fun FlowGraphNode.isBackgroundFacetNode(): Boolean =
    properties["visualFacet"] == FlowSemanticValue.BooleanValue(true) &&
        properties["syntheticJoin"] != FlowSemanticValue.BooleanValue(true)

private fun DrawScope.drawNodePorts(
    node: FlowGraphNode,
    origin: Offset,
    size: Size,
    config: FlowchartUiConfig,
) {
    val inputPorts = flowNodePorts(node, "inputPorts")
    val outputPorts = flowNodePorts(node, "outputPorts")
    drawPortStack(
        ports = inputPorts,
        origin = origin,
        size = size,
        inputSide = true,
        config = config,
    )
    drawPortStack(
        ports = outputPorts,
        origin = origin,
        size = size,
        inputSide = false,
        config = config,
    )
}

internal fun flowNodePortHits(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    portWidthPx: Float,
    portHeightPx: Float,
): List<FlowchartNodePortHit> {
    val viewport = view.viewport
    fun screen(point: FlowPoint) = Offset(
        (point.x * viewport.zoom + viewport.pan.x).toFloat(),
        (point.y * viewport.zoom + viewport.pan.y).toFloat(),
    )
    return buildList {
        graph.nodes.forEach { node ->
            val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
            val size = nodeView.size ?: FlowSize(160.0, 72.0)
            val origin = screen(nodeView.position)
            val canvasSize = Size((size.width * viewport.zoom).toFloat(), (size.height * viewport.zoom).toFloat())
            addAll(flowNodePortHitsForSide(node, origin, canvasSize, inputSide = true, portWidthPx, portHeightPx))
            addAll(flowNodePortHitsForSide(node, origin, canvasSize, inputSide = false, portWidthPx, portHeightPx))
        }
    }
}

private fun flowNodePortHitsForSide(
    node: FlowGraphNode,
    origin: Offset,
    size: Size,
    inputSide: Boolean,
    portWidthPx: Float,
    portHeightPx: Float,
): List<FlowchartNodePortHit> {
    val ports = flowNodePorts(node, if (inputSide) "inputPorts" else "outputPorts")
    if (ports.isEmpty()) return emptyList()
    val width = portWidthPx.coerceAtMost(size.width * 0.22f).coerceAtLeast(1f)
    val height = portHeightPx.coerceAtLeast(1f)
    val gap = size.height / (ports.size + 1)
    val x = if (inputSide) origin.x - width * 0.45f else origin.x + size.width - width * 0.55f
    return ports.mapIndexed { index, port ->
        val y = origin.y + gap * (index + 1) - height / 2f
        FlowchartNodePortHit(
            ref = FlowchartNodePortRef(
                nodeId = node.id,
                portName = port.name,
                kind = port.kind,
                inputSide = inputSide,
            ),
            bounds = androidx.compose.ui.geometry.Rect(
                left = x,
                top = y,
                right = x + width,
                bottom = y + height,
            ),
        )
    }
}

private fun hitNodePort(
    offset: Offset,
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    portWidthPx: Float,
    portHeightPx: Float,
    magnetRadiusPx: Float,
): FlowchartNodePortHit? =
    flowNodePortHits(graph, view, portWidthPx, portHeightPx)
        .filter { hit -> hit.bounds.inflate(magnetRadiusPx).contains(offset) }
        .minByOrNull { hit -> (hit.bounds.center - offset).getDistance() }

private fun DrawScope.drawPortStack(
    ports: List<FlowchartNodePort>,
    origin: Offset,
    size: Size,
    inputSide: Boolean,
    config: FlowchartUiConfig,
) {
    if (ports.isEmpty()) return
    val portWidth = 34.dp.toPx().coerceAtMost(size.width * 0.38f)
    val portHeight = 16.dp.toPx()
    val gap = size.height / (ports.size + 1)
    val x = if (inputSide) origin.x - portWidth * 0.45f else origin.x + size.width - portWidth * 0.55f
    ports.forEachIndexed { index, port ->
        val y = origin.y + gap * (index + 1) - portHeight / 2f
        val color = portColor(port.kind, config.colorTokens)
        drawRoundRect(
            color = color.copy(alpha = 0.92f),
            topLeft = Offset(x, y),
            size = Size(portWidth, portHeight),
            cornerRadius = CornerRadius(portHeight / 2f, portHeight / 2f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.64f),
            topLeft = Offset(x, y),
            size = Size(portWidth, portHeight),
            cornerRadius = CornerRadius(portHeight / 2f, portHeight / 2f),
            style = Stroke(1.dp.toPx()),
        )
    }
}

private fun portColor(kind: FlowEdgeKind, tokens: FlowchartColorTokens): Color =
    when (flowEdgeVisualCategory(kind)) {
        FlowchartEdgeVisualCategory.DEFAULT -> tokens.edge
        FlowchartEdgeVisualCategory.BRANCH -> tokens.branchEdge
        FlowchartEdgeVisualCategory.DATA -> tokens.dataEdge
        FlowchartEdgeVisualCategory.LOOP -> tokens.loopEdge
        FlowchartEdgeVisualCategory.ERROR -> tokens.errorEdge
    }

internal fun resolveNodeShape(
    provider: FlowchartNodeShapeProvider?,
    node: FlowGraphNode,
    width: Float,
    height: Float,
): Path? = provider?.pathFor(node, width, height)

internal enum class FlowchartEdgeVisualCategory {
    DEFAULT,
    BRANCH,
    DATA,
    LOOP,
    ERROR,
}

internal fun flowEdgeVisualCategory(kind: FlowEdgeKind): FlowchartEdgeVisualCategory = when (kind) {
    FlowEdgeKind.TRUE_BRANCH,
    FlowEdgeKind.FALSE_BRANCH,
    FlowEdgeKind.ELSE_IF_BRANCH,
    FlowEdgeKind.CONDITION -> FlowchartEdgeVisualCategory.BRANCH

    FlowEdgeKind.DATA_FLOW -> FlowchartEdgeVisualCategory.DATA

    FlowEdgeKind.LOOP_BODY,
    FlowEdgeKind.LOOP_BACK,
    FlowEdgeKind.LOOP_EXIT -> FlowchartEdgeVisualCategory.LOOP

    FlowEdgeKind.ERROR,
    FlowEdgeKind.CATCH_BODY -> FlowchartEdgeVisualCategory.ERROR

    FlowEdgeKind.SEQUENCE,
    FlowEdgeKind.TRY_BODY,
    FlowEdgeKind.FUNCTION_CALL,
    FlowEdgeKind.FUNCTION_RETURN,
    FlowEdgeKind.EVENT,
    FlowEdgeKind.GOTO -> FlowchartEdgeVisualCategory.DEFAULT
}

internal fun flowNodeFillColor(
    node: FlowGraphNode,
    tokens: FlowchartColorTokens,
): androidx.compose.ui.graphics.Color {
    val blockType = (node.properties["blockType"] as? FlowSemanticValue.StringValue)?.value
    return when {
        blockType == null -> tokens.nodeFill
        blockType.startsWith("event.") -> tokens.eventNodeFill
        blockType.startsWith("control.") -> tokens.controlNodeFill
        blockType.startsWith("logic.") || blockType.startsWith("literal.") -> tokens.logicNodeFill
        blockType.startsWith("variable.") || blockType.startsWith("variables.") -> tokens.variableNodeFill
        blockType.startsWith("feedback.") -> tokens.feedbackNodeFill
        else -> tokens.nodeFill
    }
}

internal data class FlowchartEdgePresentation(
    val connector: Offset?,
    val arrowHead: List<FlowPoint>,
)

internal fun flowEdgePresentation(
    points: List<Offset>,
    arrowLength: Double,
    arrowWidth: Double,
): FlowchartEdgePresentation = FlowchartEdgePresentation(
    connector = points.firstOrNull(),
    arrowHead = flowArrowHead(points, arrowLength, arrowWidth),
)

internal fun flowArrowHead(
    points: List<Offset>,
    length: Double,
    width: Double,
): List<FlowPoint> {
    if (points.size < 2 || !length.isFinite() || !width.isFinite() || length <= 0.0 || width <= 0.0) {
        return emptyList()
    }
    val tip = points.last()
    var previousIndex = points.lastIndex - 1
    while (previousIndex >= 0 && points[previousIndex] == tip) previousIndex--
    if (previousIndex < 0) return emptyList()
    val previous = points[previousIndex]
    val dx = (tip.x - previous.x).toDouble()
    val dy = (tip.y - previous.y).toDouble()
    val magnitude = kotlin.math.hypot(dx, dy)
    if (!magnitude.isFinite() || magnitude <= 0.0) return emptyList()
    val unitX = dx / magnitude
    val unitY = dy / magnitude
    val effectiveLength = minOf(length, magnitude)
    val baseX = tip.x - unitX * effectiveLength
    val baseY = tip.y - unitY * effectiveLength
    val halfWidth = minOf(width / 2.0, effectiveLength / 2.0)
    val perpendicularX = -unitY * halfWidth
    val perpendicularY = unitX * halfWidth
    return listOf(
        FlowPoint(tip.x.toDouble(), tip.y.toDouble()),
        FlowPoint(baseX + perpendicularX, baseY + perpendicularY),
        FlowPoint(baseX - perpendicularX, baseY - perpendicularY),
    )
}

@Composable
private fun FlowGestureLayer(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    controller: FlowchartController,
    config: FlowchartUiConfig,
    callbacks: FlowchartHostCallbacks,
    collapsedFacetNodeIds: Set<FlowNodeId>,
    lockedFacetNodeIds: Set<FlowNodeId>,
    onToggleFacetCollapsed: (FlowNodeId) -> Unit,
    onToggleFacetLocked: (FlowNodeId) -> Unit,
    refresh: () -> Unit,
) {
    var dragNode by remember { mutableStateOf<FlowNodeId?>(null) }
    var dragNodeGroup by remember { mutableStateOf<Set<FlowNodeId>>(emptySet()) }
    var dragFacet by remember { mutableStateOf<FlowNodeId?>(null) }
    var dragPort by remember { mutableStateOf<FlowchartNodePortRef?>(null) }
    var dragPortAnchor by remember { mutableStateOf<Offset?>(null) }
    var dragPortPointer by remember { mutableStateOf<Offset?>(null) }
    var dragPortTarget by remember { mutableStateOf<FlowchartNodePortHit?>(null) }
    var panning by remember { mutableStateOf(false) }
    val currentView by rememberUpdatedState(view)
    val density = LocalDensity.current
    val portWidthPx = with(density) { 60.dp.toPx() }
    val portHeightPx = with(density) { 36.dp.toPx() }
    val portMagnetRadiusPx = with(density) { 44.dp.toPx() }
    val platformView = LocalView.current
    val hapticFeedback = LocalHapticFeedback.current
    var previousTapAt by remember { mutableLongStateOf(0L) }
    var previousTapPosition by remember { mutableStateOf<Offset?>(null) }
    val modifier = Modifier.fillMaxSize().testTag("flowchart-gestures")
        .pointerInput(graph, config.panEnabled, config.zoomEnabled) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                if (!config.panEnabled && !config.zoomEnabled) return@detectTransformGestures
                val current = controller.snapshot().view ?: return@detectTransformGestures
                val old = current.viewport
                val nextZoom = if (config.zoomEnabled) {
                    (old.zoom * zoom.toDouble()).coerceIn(0.1, 8.0)
                } else {
                    old.zoom
                }
                val anchor = FlowPoint(centroid.x.toDouble(), centroid.y.toDouble())
                val graphAnchor = FlowViewportTransform.screenToGraph(anchor, old)
                val panX = if (config.zoomEnabled) anchor.x - graphAnchor.x * nextZoom else old.pan.x
                val panY = if (config.zoomEnabled) anchor.y - graphAnchor.y * nextZoom else old.pan.y
                val nextPan = if (config.panEnabled) {
                    FlowPoint(panX + pan.x.toDouble(), panY + pan.y.toDouble())
                } else {
                    FlowPoint(panX, panY)
                }
                controller.replaceViewport(FlowViewport(nextPan, nextZoom))
                refresh()
            }
        }
        .pointerInput(graph, config) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val hiddenNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
                val facetHit = hitFlowFacetHandle(down.position, graph, currentView)
                if (facetHit != null && facetHit.action != FlowFacetHandleAction.Drag) {
                    when (facetHit.action) {
                        FlowFacetHandleAction.ToggleCollapse -> onToggleFacetCollapsed(facetHit.region.facet.id)
                        FlowFacetHandleAction.ToggleLock -> onToggleFacetLocked(facetHit.region.facet.id)
                        FlowFacetHandleAction.Drag -> Unit
                    }
                    playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Connected, config)
                    refresh()
                    return@awaitEachGesture
                }
                val startPortHit = hitNodePort(down.position, graph, currentView, portWidthPx, portHeightPx, portMagnetRadiusPx)
                        ?.takeUnless { it.ref.inputSide }
                        ?.takeUnless { it.ref.nodeId in hiddenNodeIds }
                val nodeAtDown = if (startPortHit == null && config.nodeDraggingEnabled) {
                    hitNode(down.position, currentView, hiddenNodeIds)
                } else {
                    null
                }
                val facetDragHit = facetHit?.takeIf {
                    it.action == FlowFacetHandleAction.Drag && it.region.facet.id !in lockedFacetNodeIds
                }
                val dragStart = if (nodeAtDown != null || facetDragHit != null) {
                    awaitLongPressOrCancellation(down.id)
                } else {
                    awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                }
                if (dragStart != null) {
                    dragPort = startPortHit?.ref
                    dragPortAnchor = startPortHit?.bounds?.center
                    dragPortPointer = startPortHit?.bounds?.center
                    dragNode = nodeAtDown
                    if (dragPort != null) {
                        controller.dispatch(FlowInteractionAction.SelectNode(dragPort!!.nodeId))
                        callbacks.onNodeSelected(dragPort!!.nodeId)
                        callbacks.onEdgeSelected(null)
                    } else if (dragNode != null) {
                        val movementMode = dragMovementModeForHit(down.position, currentView, dragNode!!)
                        val startPoint = FlowPoint(dragStart.position.x.toDouble(), dragStart.position.y.toDouble())
                        controller.dispatch(
                            FlowInteractionAction.BeginNodeDrag(
                                nodeId = dragNode!!,
                                at = startPoint,
                                movementMode = movementMode,
                            ),
                        )
                        dragNodeGroup = controller.snapshot().interaction.dragState?.nodeIds.orEmpty()
                        playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.DragStarted, config)
                        callbacks.onNodeDragChanged(dragNode, startPoint)
                    } else if (facetDragHit != null) {
                        dragFacet = facetDragHit.region.facet.id
                        dragNodeGroup = facetDragHit.region.nodeIds - hiddenNodeIds
                        val startPoint = FlowPoint(dragStart.position.x.toDouble(), dragStart.position.y.toDouble())
                        controller.dispatch(FlowInteractionAction.BeginNodeGroupDrag(dragNodeGroup, startPoint))
                        playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.DragStarted, config)
                    } else if (config.panEnabled) {
                        panning = true
                        controller.dispatch(FlowInteractionAction.BeginViewportPan(FlowPoint(down.position.x.toDouble(), down.position.y.toDouble())))
                    }
                    var latestDragPosition = dragStart.position
                    val completed = drag(dragStart.id) { change ->
                        latestDragPosition = change.position
                        if (change.positionChange() != Offset.Zero) change.consume()
                        val point = FlowPoint(change.position.x.toDouble(), change.position.y.toDouble())
                        if (dragPort != null) {
                            dragPortTarget = hitNodePort(change.position, graph, currentView, portWidthPx, portHeightPx, portMagnetRadiusPx)
                                ?.takeIf { hit -> hit.ref.isCompatibleTargetFor(dragPort!!) }
                            dragPortPointer = dragPortTarget?.bounds?.center ?: change.position
                        } else if (dragNode != null || dragFacet != null) {
                            controller.dispatch(FlowInteractionAction.UpdateNodeDrag(point))
                            callbacks.onNodeDragChanged(dragNode, point)
                        }
                        else if (panning) controller.dispatch(FlowInteractionAction.UpdateViewportPan(point))
                        refresh()
                    }
                    if (completed) {
                        val sourcePort = dragPort
                        if (sourcePort != null) {
                            val targetPort = hitNodePort(latestDragPosition, graph, currentView, portWidthPx, portHeightPx, portMagnetRadiusPx)
                                ?.ref
                                ?.takeIf { it.isCompatibleTargetFor(sourcePort) }
                            if (targetPort != null) {
                                playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Connected, config)
                                callbacks.onPortConnectionRequested(sourcePort, targetPort)
                            }
                        } else if (dragNode != null || dragFacet != null) {
                            val finish = FlowPoint(latestDragPosition.x.toDouble(), latestDragPosition.y.toDouble())
                            controller.dispatch(FlowInteractionAction.CommitNodeDrag)
                            playFlowchartFeedback(platformView, hapticFeedback, FlowchartFeedbackEvent.Dropped, config)
                            dragNode?.let { nodeId ->
                                callbacks.onNodeDragFinished(nodeId, dragNodeGroup.ifEmpty { setOf(nodeId) }, finish)
                            }
                        }
                        else if (panning) controller.dispatch(FlowInteractionAction.CommitViewportPan)
                    } else if (dragNode != null || dragFacet != null) {
                        controller.dispatch(FlowInteractionAction.CancelNodeDrag)
                    }
                    callbacks.onNodeDragChanged(null, null)
                    dragNode = null
                    dragNodeGroup = emptySet()
                    dragFacet = null
                    dragPort = null
                    dragPortAnchor = null
                    dragPortPointer = null
                    dragPortTarget = null
                    panning = false
                    refresh()
                } else if (config.selectionEnabled) {
                    val offset = down.position
                    val prior = previousTapPosition
                    val isDoubleTap = down.uptimeMillis - previousTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                        prior != null && (offset - prior).getDistance() <= viewConfiguration.touchSlop
                    previousTapAt = down.uptimeMillis
                    previousTapPosition = offset
                    val node = hitNode(offset, currentView, hiddenNodeIds)
                    if (isDoubleTap) {
                        node?.let(callbacks.onNodeInvoked)
                    } else {
                        val edge = if (node == null && config.edgeSelectionEnabled) hitEdge(offset, graph, currentView) else null
                        when {
                            node != null -> { controller.dispatch(FlowInteractionAction.SelectNode(node)); callbacks.onNodeSelected(node); callbacks.onEdgeSelected(null) }
                            edge != null -> { controller.dispatch(FlowInteractionAction.SelectEdge(edge)); callbacks.onNodeSelected(null); callbacks.onEdgeSelected(edge) }
                            else -> { controller.dispatch(FlowInteractionAction.ClearSelection); callbacks.onNodeSelected(null); callbacks.onEdgeSelected(null) }
                        }
                    }
                    refresh()
                }
            }
        }
    Box(modifier) {
        val anchor = dragPortAnchor
        val pointer = dragPortPointer
        val sourcePort = dragPort
        if (anchor != null && pointer != null && sourcePort != null) {
            Canvas(Modifier.fillMaxSize()) {
                val color = portColor(sourcePort.kind, config.colorTokens)
                val route = portDragPreviewRoute(anchor, dragPortTarget?.bounds?.center ?: pointer)
                route.zipWithNext().forEach { (from, to) ->
                    drawLine(
                        color = color.copy(alpha = 0.86f),
                        start = from,
                        end = to,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(color.copy(alpha = 0.96f), radius = 4.dp.toPx(), center = anchor)
                dragPortTarget?.bounds?.let { target ->
                    drawRoundRect(
                        color = color.copy(alpha = 0.24f),
                        topLeft = target.topLeft,
                        size = target.size,
                        cornerRadius = CornerRadius(target.height / 2f, target.height / 2f),
                    )
                    drawRoundRect(
                        color = color.copy(alpha = 0.92f),
                        topLeft = target.topLeft,
                        size = target.size,
                        cornerRadius = CornerRadius(target.height / 2f, target.height / 2f),
                        style = Stroke(2.dp.toPx()),
                    )
                } ?: drawCircle(color.copy(alpha = 0.72f), radius = 5.dp.toPx(), center = pointer)
            }
        }
    }
}

private enum class FlowchartFeedbackEvent {
    DragStarted,
    Dropped,
    Deleted,
    Connected,
}

private fun playFlowchartFeedback(
    platformView: android.view.View,
    haptic: HapticFeedback,
    event: FlowchartFeedbackEvent,
    config: FlowchartUiConfig,
) {
    if (config.hapticFeedbackEnabled) {
        val hapticType = when (event) {
            FlowchartFeedbackEvent.DragStarted -> HapticFeedbackType.TextHandleMove
            FlowchartFeedbackEvent.Dropped,
            FlowchartFeedbackEvent.Connected,
            -> HapticFeedbackType.LongPress
            FlowchartFeedbackEvent.Deleted -> HapticFeedbackType.LongPress
        }
        haptic.performHapticFeedback(hapticType)
        val platformHaptic = when (event) {
            FlowchartFeedbackEvent.DragStarted -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
            FlowchartFeedbackEvent.Dropped,
            FlowchartFeedbackEvent.Connected,
            -> HapticFeedbackConstants.VIRTUAL_KEY
            FlowchartFeedbackEvent.Deleted -> HapticFeedbackConstants.LONG_PRESS
        }
        platformView.performHapticFeedback(
            platformHaptic,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }
    if (!config.soundEffectsEnabled) return
    platformView.playSoundEffect(SoundEffectConstants.CLICK)
    val (tone, durationMs, volume) = when (event) {
        FlowchartFeedbackEvent.DragStarted -> Triple(ToneGenerator.TONE_PROP_BEEP, 30, 24)
        FlowchartFeedbackEvent.Dropped -> Triple(ToneGenerator.TONE_PROP_BEEP, 30, 20)
        FlowchartFeedbackEvent.Connected -> Triple(ToneGenerator.TONE_PROP_ACK, 44, 32)
        FlowchartFeedbackEvent.Deleted -> Triple(ToneGenerator.TONE_PROP_NACK, 54, 32)
    }
    runCatching {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        generator.startTone(tone, durationMs)
        platformView.postDelayed({ generator.release() }, (durationMs + 40).toLong())
    }
}

private fun dragMovementModeForHit(
    offset: Offset,
    view: FlowViewDocument,
    nodeId: FlowNodeId,
): FlowMovementMode {
    val graphPoint = FlowViewportTransform.screenToGraph(FlowPoint(offset.x.toDouble(), offset.y.toDouble()), view.viewport)
    val nodeView = view.nodeViews.firstOrNull { it.nodeId == nodeId } ?: return FlowMovementMode.NEXT_FOLLOW_FIRST
    val size = nodeView.size ?: FlowSize(160.0, 72.0)
    val centerX = nodeView.position.x + size.width / 2.0
    return if (graphPoint.x <= centerX) FlowMovementMode.NEXT_FOLLOW_FIRST else FlowMovementMode.SINGLE
}

internal fun FlowchartNodePortRef.isCompatibleTargetFor(source: FlowchartNodePortRef): Boolean =
    inputSide &&
        !source.inputSide &&
        nodeId != source.nodeId &&
        kind == source.kind

internal fun portDragPreviewRoute(
    start: Offset,
    end: Offset,
): List<Offset> {
    if (start == end) return listOf(start)
    val midX = (start.x + end.x) / 2f
    return listOf(
        start,
        Offset(midX, start.y),
        Offset(midX, end.y),
        end,
    ).compactOffsets()
}

private fun List<Offset>.compactOffsets(): List<Offset> =
    fold(emptyList<Offset>()) { acc, point ->
        if (acc.lastOrNull() == point) acc else acc + point
    }.removeCollinearOffsets()

private fun List<Offset>.removeCollinearOffsets(): List<Offset> {
    if (size <= 2) return this
    val result = mutableListOf(first())
    for (index in 1 until lastIndex) {
        val previous = result.last()
        val current = this[index]
        val next = this[index + 1]
        val horizontal = previous.y == current.y && current.y == next.y
        val vertical = previous.x == current.x && current.x == next.x
        if (!horizontal && !vertical) result += current
    }
    result += last()
    return result
}

@Composable private fun ZoomControls(controller: FlowchartController, config: FlowchartUiConfig, refresh: () -> Unit) {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (config.zoomEnabled) {
            Button({ controller.dispatch(FlowInteractionAction.ZoomViewport(1.2, FlowPoint(0.0, 0.0))); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.zoomIn }) { Text("+") }
            Button({ controller.dispatch(FlowInteractionAction.ZoomViewport(1 / 1.2, FlowPoint(0.0, 0.0))); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.zoomOut }) { Text("−") }
        }
        Button({ controller.attachGraph(controller.snapshot().graph ?: return@Button, null); refresh() }, Modifier.semantics { contentDescription = config.accessibilityLabels.centerView }) { Text("Center") }
    }
}

@Composable private fun FlowLabelsAndSemantics(
    graph: FlowGraphDocument,
    view: FlowViewDocument,
    state: FlowchartControllerState,
    callbacks: FlowchartHostCallbacks,
    collapsedFacetNodeIds: Set<FlowNodeId>,
) {
    val density = LocalDensity.current
    val hiddenNodeIds = collapsedFacetContentNodeIds(graph, collapsedFacetNodeIds)
    fun xDp(value: Double) = with(density) { value.toFloat().toDp() }
    Box(Modifier.fillMaxSize()) {
        graph.edges.forEach { edge ->
            if (edge.sourceNodeId in hiddenNodeIds || edge.targetNodeId in hiddenNodeIds) return@forEach
            val label = edge.label ?: when (edge.kind) { FlowEdgeKind.TRUE_BRANCH -> "TRUE"; FlowEdgeKind.FALSE_BRANCH -> "FALSE"; FlowEdgeKind.ELSE_IF_BRANCH -> "ELSE IF"; FlowEdgeKind.LOOP_BACK -> "LOOP"; else -> null } ?: return@forEach
            val points = edgeScreenPoints(edge, graph, view)
            val center = points.getOrNull(points.size / 2) ?: return@forEach
            Text(label, Modifier.offset(xDp(center.x), xDp(center.y)).semantics { contentDescription = "Edge $label"; selected = edge.id in state.interaction.selectedEdgeIds; onClick("Select edge") { callbacks.onEdgeSelected(edge.id); true } }, style = MaterialTheme.typography.labelSmall)
        }
        graph.nodes.forEach { node ->
        if (node.isBackgroundFacetNode()) return@forEach
        if (node.id in hiddenNodeIds) return@forEach
        val nodeView = view.nodeViews.firstOrNull { it.nodeId == node.id } ?: return@forEach
        val runtime = state.runtime?.nodeStates?.get(node.id)
        val screen = FlowViewportTransform.graphToScreen(nodeView.position, view.viewport)
        val width = (nodeView.size?.width ?: 160.0) * view.viewport.zoom
        val height = (nodeView.size?.height ?: 72.0) * view.viewport.zoom
        Box(Modifier.offset(xDp(screen.x), xDp(screen.y)).size(xDp(width), xDp(height)).padding(8.dp).semantics {
            contentDescription = buildString { append(node.label); append(", "); append(node.kind.displayName ?: node.kind.standard?.name ?: "extension node"); if (runtime != null) { append(", "); append(runtime.name) }; if (node.diagnosticIds.isNotEmpty()) append(", has diagnostics") }
            selected = node.id in state.interaction.selectedNodeIds
            onClick("Select node") { callbacks.onNodeSelected(node.id); true }
            onLongClick("Invoke node") { callbacks.onNodeInvoked(node.id); true }
        }) { Column { Text(node.label, style = MaterialTheme.typography.bodyMedium); Text(node.kind.displayName ?: node.kind.standard?.name ?: "Extension", style = MaterialTheme.typography.labelSmall); runtime?.let { Text(it.name, style = MaterialTheme.typography.labelSmall) } } }
    } }
}

private fun hitNode(
    offset: Offset,
    view: FlowViewDocument,
    hiddenNodeIds: Set<FlowNodeId> = emptySet(),
): FlowNodeId? {
    val graphPoint = FlowViewportTransform.screenToGraph(FlowPoint(offset.x.toDouble(), offset.y.toDouble()), view.viewport)
    return view.nodeViews.asReversed()
        .firstOrNull {
            it.nodeId !in hiddenNodeIds &&
                FlowRect(it.position, it.size ?: FlowSize(160.0, 72.0)).contains(graphPoint)
        }
        ?.nodeId
}

private fun hitEdge(offset: Offset, graph: FlowGraphDocument, view: FlowViewDocument): FlowEdgeId? {
    val point = FlowPoint(offset.x.toDouble(), offset.y.toDouble())
    val segments = graph.edges.associate { edge -> edge.id to edgeScreenPoints(edge, graph, view).zipWithNext() }
    return FlowHitTesting.hitEdge(point, segments, tolerance = 12.0)
}

private fun edgeScreenPoints(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): List<FlowPoint> {
    return edgeGraphPoints(edge, graph, view)
        .map { FlowViewportTransform.graphToScreen(it, view.viewport) }
}

private fun edgeGraphPoints(edge: FlowGraphEdge, graph: FlowGraphDocument, view: FlowViewDocument): List<FlowPoint> {
    val sourceNode = graph.nodes.firstOrNull { it.id == edge.sourceNodeId } ?: return emptyList()
    val targetNode = graph.nodes.firstOrNull { it.id == edge.targetNodeId } ?: return emptyList()
    val source = view.nodeViews.firstOrNull { it.nodeId == edge.sourceNodeId } ?: return emptyList()
    val target = view.nodeViews.firstOrNull { it.nodeId == edge.targetNodeId } ?: return emptyList()
    val sourceRect = FlowRect(source.position, source.size ?: FlowSize(160.0, 72.0))
    val targetRect = FlowRect(target.position, target.size ?: FlowSize(160.0, 72.0))
    val start = edgePortOut(edge, sourceNode, sourceRect)
    val end = edgePortIn(edge, targetNode, targetRect)
    val obstacles = view.nodeViews
        .filterNot { it.nodeId == edge.sourceNodeId || it.nodeId == edge.targetNodeId }
        .map { FlowRect(it.position, it.size ?: FlowSize(160.0, 72.0)) }
    val edgeView = view.edgeViews.firstOrNull { it.edgeId == edge.id }
    if (edgeView?.routeLockState == FlowRouteLockState.LOCKED && edgeView.bendPoints.isNotEmpty()) {
        return orthogonalize(listOf(start) + edgeView.bendPoints + end)
    }
    return automaticOrthogonalRoute(edge, start, end, sourceRect, targetRect, obstacles, laneIndex(edge, graph.edges))
}

private fun automaticOrthogonalRoute(
    edge: FlowGraphEdge,
    start: FlowPoint,
    end: FlowPoint,
    source: FlowRect,
    target: FlowRect,
    obstacles: Collection<FlowRect>,
    laneIndex: Int,
): List<FlowPoint> {
    val clearance = 28.0
    val lanePadding = laneIndex * 40.0
    val candidates = when (edge.kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.CONDITION,
        FlowEdgeKind.DATA_FLOW,
        -> {
            val nearRight = maxOf(source.right, target.right) + clearance + lanePadding
            val widerRight = nearRight + clearance
            val between = if (target.left > source.right) (source.right + target.left) / 2.0 else nearRight
            listOf(between, nearRight, widerRight).distinct().map { lane ->
                listOf(start, FlowPoint(lane, start.y), FlowPoint(lane, end.y), end)
            }
        }
        FlowEdgeKind.LOOP_BODY,
        FlowEdgeKind.LOOP_BACK,
        -> {
            val nearLeft = minOf(source.left, target.left) - clearance - lanePadding
            val widerLeft = nearLeft - clearance
            listOf(nearLeft, widerLeft).map { lane ->
                listOf(start, FlowPoint(lane, start.y), FlowPoint(lane, end.y), end)
            }
        }
        else -> {
            val midY = if (end.y >= start.y) (start.y + end.y) / 2.0 else source.bottom + clearance
            val belowSource = source.bottom + clearance
            val aboveTarget = target.top - clearance
            val horizontalCandidates = listOf(midY, belowSource, aboveTarget).distinct().map { lane ->
                listOf(start, FlowPoint(start.x, lane), FlowPoint(end.x, lane), end)
            }
            val rightLane = maxOf(source.right, target.right) + clearance
            val leftLane = minOf(source.left, target.left) - clearance
            horizontalCandidates + listOf(
                listOf(start, FlowPoint(rightLane, start.y), FlowPoint(rightLane, end.y), end),
                listOf(start, FlowPoint(leftLane, start.y), FlowPoint(leftLane, end.y), end),
            )
        }
    }.map { it.compactOrthogonalPoints() }
    return candidates
        .filterNot { collides(it, obstacles, clearance) }
        .minByOrNull(::routeLength)
        ?: candidates.minBy(::routeLength)
}

private fun laneIndex(edge: FlowGraphEdge, edges: List<FlowGraphEdge>): Int {
    if (edge.kind !in routedLaneKinds) return 0
    return edges
        .filter { it.sourceNodeId == edge.sourceNodeId && it.kind == edge.kind }
        .sortedWith(compareBy<FlowGraphEdge> { edgeKindOrder(it.kind) }.thenBy { it.label.orEmpty() }.thenBy { it.id.value })
        .indexOfFirst { it.id == edge.id }
        .coerceAtLeast(0)
}

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

private fun collides(points: List<FlowPoint>, obstacles: Collection<FlowRect>, clearance: Double): Boolean =
    points.zipWithNext().any { (start, end) ->
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

private fun routeLength(points: List<FlowPoint>): Double =
    points.zipWithNext().sumOf { (from, to) ->
        kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.y - to.y)
    }

private fun orthogonalize(points: List<FlowPoint>): List<FlowPoint> {
    if (points.size < 2) return points
    return buildList {
        add(points.first())
        points.zipWithNext().forEach { (from, to) ->
            if (from.x != to.x && from.y != to.y) add(FlowPoint(to.x, from.y))
            add(to)
        }
    }.compactOrthogonalPoints()
}

private fun List<FlowPoint>.compactOrthogonalPoints(): List<FlowPoint> =
    fold(emptyList<FlowPoint>()) { acc, point ->
        if (acc.lastOrNull() == point) acc else acc + point
    }.removeCollinearPoints()

private fun List<FlowPoint>.removeCollinearPoints(): List<FlowPoint> {
    if (size <= 2) return this
    val result = mutableListOf(first())
    for (i in 1 until lastIndex) {
        val prev = result.last()
        val current = this[i]
        val next = this[i + 1]
        val horizontal = prev.y == current.y && current.y == next.y
        val vertical = prev.x == current.x && current.x == next.x
        if (!horizontal && !vertical) result += current
    }
    result += last()
    return result
}

private fun edgePortOut(edge: FlowGraphEdge, node: FlowGraphNode, rect: FlowRect): FlowPoint {
    val portName = when (edge.kind) {
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.LOOP_BODY -> edge.label
        FlowEdgeKind.DATA_FLOW,
        FlowEdgeKind.CONDITION -> "output"
        FlowEdgeKind.FALSE_BRANCH,
        FlowEdgeKind.SEQUENCE,
        FlowEdgeKind.LOOP_EXIT -> null
        else -> null
    }
    if (edge.kind in setOf(FlowEdgeKind.LOOP_BODY, FlowEdgeKind.LOOP_BACK)) {
        return FlowPoint(rect.left, rect.top + rect.size.height / 2.0)
    }
    return sidePort(node, "outputPorts", portName, rect, inputSide = false)
        ?: if (edge.kind in sideOutputKinds) {
            FlowPoint(rect.right, rect.top + rect.size.height / 2.0)
        } else {
            FlowPoint(rect.left + rect.size.width / 2.0, rect.bottom)
        }
}

private fun edgePortIn(edge: FlowGraphEdge, node: FlowGraphNode, rect: FlowRect): FlowPoint {
    val portName = when (edge.kind) {
        FlowEdgeKind.DATA_FLOW,
        FlowEdgeKind.CONDITION -> edge.label
        FlowEdgeKind.TRUE_BRANCH,
        FlowEdgeKind.ELSE_IF_BRANCH,
        FlowEdgeKind.LOOP_BODY -> "previous"
        else -> null
    }
    return sidePort(node, "inputPorts", portName, rect, inputSide = true)
        ?: if (edge.kind in sideInputKinds) {
            FlowPoint(rect.left, rect.top + rect.size.height / 2.0)
        } else {
            FlowPoint(rect.left + rect.size.width / 2.0, rect.top)
        }
}

private fun sidePort(
    node: FlowGraphNode,
    key: String,
    name: String?,
    rect: FlowRect,
    inputSide: Boolean,
): FlowPoint? {
    name ?: return null
    val ports = flowNodePorts(node, key)
    val index = ports.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
    val gap = rect.size.height / (ports.size + 1)
    return FlowPoint(
        x = if (inputSide) rect.left else rect.right,
        y = rect.top + gap * (index + 1),
    )
}

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
